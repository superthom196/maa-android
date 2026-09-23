package io.github.superthom196.maa.browse

import io.github.superthom196.maa.data.MaApi
import io.github.superthom196.maa.data.MediaItem
import io.github.superthom196.maa.data.asItemArray
import io.github.superthom196.maa.data.maJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer

private const val TAG = "LibraryIndex"

/** Artists and albums tabs list items directly up to this many, letter buckets beyond. */
internal const val BUCKET_THRESHOLD = 150

/**
 * Every library (album) artist and album, sorted and bucketed by letter, for the Artists and
 * Albums tabs. Those tabs are the ones Android Auto opens straight away and the lists are the
 * largest, so they are served from a copy on disk (`files/browse/index-{serverId}.json`) and only
 * refreshed in the background: when older than [maxAgeMs], or once per process when older than
 * [processRefreshAgeMs] so a fresh Auto session picks up new albums. Only the very first use,
 * with nothing on disk, waits for the server.
 */
class LibraryIndex(
    private val dir: File,
    private val api: MaApi,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 12 * 3600_000L,
    private val processRefreshAgeMs: Long = 3600_000L,
    private val pageSize: Int = 500,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    @Serializable
    private class Stored(val serverId: String, val fetchedAt: Long, val artists: List<MediaItem>, val albums: List<MediaItem>)

    /** One consistent view of the index; lists are sorted, maps are keyed by "provider/itemId". */
    class Snapshot(val serverId: String, val fetchedAt: Long, val artists: List<MediaItem>, val albums: List<MediaItem>) {
        private val artistsByKey = artists.associateBy { key(it.provider, it.itemId) }
        private val albumsByKey = albums.associateBy { key(it.provider, it.itemId) }
        val artistBuckets: List<Bucket> = bucketsOf(artists)
        val albumBuckets: List<Bucket> = bucketsOf(albums)

        fun artist(provider: String, itemId: String): MediaItem? = artistsByKey[key(provider, itemId)]
        fun album(provider: String, itemId: String): MediaItem? = albumsByKey[key(provider, itemId)]

        private fun key(p: String, i: String) = "$p/$i"
    }

    private val mutex = Mutex()
    private var current: Snapshot? = null
    private var refreshing: Deferred<Result<Snapshot>>? = null
    private val refreshedThisProcess = HashSet<String>()
    private var lastAttemptAt = Long.MIN_VALUE / 2

    /** The index for [serverId]: memory, else disk, else (first use) the server. Throws only then. */
    suspend fun get(serverId: String): Snapshot {
        val pending: Deferred<Result<Snapshot>> = mutex.withLock {
            val snap = current?.takeIf { it.serverId == serverId } ?: readDisk(serverId)?.also { current = it }
            if (snap != null) {
                // Stale but usable: refresh behind it, at most once a minute while the server is away.
                if (isStale(snap) && clock() - lastAttemptAt > RETRY_MS) startRefresh(serverId)
                return snap
            }
            startRefresh(serverId)
        }
        return pending.await().getOrThrow()
    }

    /** Cheap lookups for [LibraryTree.item]: never touches the network. */
    fun peek(serverId: String): Snapshot? = current?.takeIf { it.serverId == serverId }

    private fun isStale(snap: Snapshot): Boolean {
        val age = clock() - snap.fetchedAt
        return age > maxAgeMs || (snap.serverId !in refreshedThisProcess && age > processRefreshAgeMs)
    }

    /** Single flight; call with [mutex] held. */
    private fun startRefresh(serverId: String): Deferred<Result<Snapshot>> {
        refreshing?.takeIf { it.isActive }?.let { return it }
        refreshedThisProcess.add(serverId)
        lastAttemptAt = clock()
        // Result-wrapped: a failure must not cancel the shared scope, and nobody may be awaiting.
        return scope.async {
            try {
                Result.success(fetchAndStore(serverId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "refresh failed: ${e.message}")
                Result.failure(e)
            }
        }.also { refreshing = it }
    }

    private suspend fun fetchAndStore(serverId: String): Snapshot {
        val t0 = clock()
        val artists = fetchAll { offset ->
            // album_artists_only: the Artists tab is for browsing albums; track-only guest artists
            // would bury them. libraryItems() has no flag for it, hence the raw command.
            api.call(
                "music/artists/library_items",
                mapOf("offset" to offset, "limit" to pageSize, "order_by" to "sort_name", "album_artists_only" to true),
            ).asItemArray().mapNotNull { el ->
                runCatching { maJson.decodeFromJsonElement(MediaItem.serializer(), el) }.getOrNull()
            }
        }.map { it.slim("artist") }
        val albums = fetchAll { offset -> api.libraryItems("albums", offset, pageSize, "sort_name") }.map { it.slim("album") }
        val snap = withContext(cpu) {
            Snapshot(serverId, clock(), sortForIndex(artists), sortForIndex(albums))
        }
        mutex.withLock { current = snap }
        writeDisk(snap)
        logI(TAG, "${artists.size} artists, ${albums.size} albums from server in ${clock() - t0} ms")
        return snap
    }

    private suspend fun fetchAll(page: suspend (offset: Int) -> List<MediaItem>): List<MediaItem> {
        val seen = HashSet<String>()
        val all = ArrayList<MediaItem>()
        var offset = 0
        while (offset < HARD_CAP) {
            val batch = page(offset)
            for (item in batch) if (seen.add("${item.provider}/${item.itemId}")) all.add(item)
            offset += batch.size
            if (batch.size < pageSize) break
        }
        return all
    }

    private fun file(serverId: String) = File(dir, "index-${BrowseId.encodeSegment(serverId)}.json")

    private suspend fun readDisk(serverId: String): Snapshot? = withContext(io) {
        val f = file(serverId)
        if (!f.isFile) return@withContext null
        runCatching {
            val s = maJson.decodeFromString(Stored.serializer(), f.readText())
            // Stored sorted, but re-sorting is cheap and survives a change to the sort rules.
            if (s.serverId != serverId) null else Snapshot(s.serverId, s.fetchedAt, sortForIndex(s.artists), sortForIndex(s.albums))
        }.onFailure { logW(TAG, "index unreadable: ${it.message}") }.getOrNull()
    }

    private suspend fun writeDisk(snap: Snapshot) = withContext(io) {
        runCatching {
            dir.mkdirs()
            val f = file(snap.serverId)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(maJson.encodeToString(Stored.serializer(), Stored(snap.serverId, snap.fetchedAt, snap.artists, snap.albums)))
            if (!tmp.renameTo(f)) error("rename failed")
        }.onFailure { logW(TAG, "index write failed: ${it.message}") }
        Unit
    }

    private companion object {
        const val HARD_CAP = 50_000
        const val RETRY_MS = 60_000L
    }
}

/** A letter's run in a sorted index list. */
data class Bucket(val letter: String, val start: Int, val count: Int)

private val whitespace = Regex("\\s+")
private val nonWord = Regex("[^\\p{L}\\p{N} ]")
/** Letters NFD leaves alone but a reader files under a Latin letter anyway. */
private val latinFolds = mapOf('ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'đ' to "d", 'ð' to "d", 'ł' to "l", 'þ' to "th", 'ı' to "i")

/** Lowercase, accents and punctuation off, whitespace collapsed: "Björk" files next to "Bjork". */
internal fun foldKey(raw: String): String {
    val nfd = Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFD)
    val sb = StringBuilder(nfd.length)
    for (c in nfd) {
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
        latinFolds[c]?.let { sb.append(it) } ?: sb.append(c)
    }
    return nonWord.replace(whitespace.replace(sb, " "), "").trim()
}

/** MA's sort_name already drops leading articles ("Beatles, The"); fall back to the name. */
internal fun indexKey(item: MediaItem): String = foldKey(item.sortName?.takeIf { it.isNotBlank() } ?: item.name)

/** "A".."Z", or "#" for digits, symbols, other scripts and blanks. */
internal fun bucketOf(key: String): String {
    val c = key.firstOrNull() ?: return "#"
    return if (c in 'a'..'z') c.uppercaseChar().toString() else "#"
}

/** "#" goes last: real names first, metadata oddities at the end. */
private fun bucketRank(letter: String): Int = if (letter == "#") 26 else letter[0] - 'A'

internal fun sortForIndex(items: List<MediaItem>): List<MediaItem> =
    items.map { Triple(it, indexKey(it), bucketOf(indexKey(it))) }
        .sortedWith(
            compareBy<Triple<MediaItem, String, String>>({ bucketRank(it.third) }, { it.second })
                .thenBy { it.first.name.lowercase() }
                .thenBy { it.first.year ?: Int.MAX_VALUE }
                .thenBy { it.first.provider }
                .thenBy { it.first.itemId },
        )
        .map { it.first }

/** Letter runs of a list already in [sortForIndex] order. */
internal fun bucketsOf(sorted: List<MediaItem>): List<Bucket> {
    val out = ArrayList<Bucket>()
    var i = 0
    while (i < sorted.size) {
        val letter = bucketOf(indexKey(sorted[i]))
        var j = i + 1
        while (j < sorted.size && bucketOf(indexKey(sorted[j])) == letter) j++
        out.add(Bucket(letter, i, j - i))
        i = j
    }
    return out
}

/** The items of one letter; empty when the letter has none. */
internal fun itemsInBucket(sorted: List<MediaItem>, buckets: List<Bucket>, letter: String): List<MediaItem> =
    buckets.firstOrNull { it.letter == letter }?.let { sorted.subList(it.start, it.start + it.count) }.orEmpty()
