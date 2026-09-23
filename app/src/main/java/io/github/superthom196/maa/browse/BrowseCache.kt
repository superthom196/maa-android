package io.github.superthom196.maa.browse

import io.github.superthom196.maa.data.MediaItem
import io.github.superthom196.maa.data.NotLoggedInException
import io.github.superthom196.maa.data.maJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

private const val TAG = "BrowseCache"

/**
 * Lists behind the tree (album tracks, playlist tracks, artist albums, home lists, playlists),
 * three layers deep so Android Auto never waits long:
 *  1. memory, for [memoryTtlMs]: Auto hosts re-request every open parent on each reconnect and page
 *     through lists one request at a time, so identical calls in quick succession stay local;
 *  2. the server, for at most [networkTimeoutMs] when a disk copy exists (unbounded when none does,
 *     since then there is nothing better to show). A late answer still lands in the cache;
 *  3. the disk copy (`files/browse/lists/{sha1}.json`), written through on every success and kept
 *     under [maxBytes] by evicting the least recently used files.
 * When both server and disk fail, the server's exception propagates (typically OfflineException).
 */
class BrowseCache(
    private val dir: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val networkTimeoutMs: Long = 4_000,
    private val memoryTtlMs: Long = 5 * 60_000L,
    private val maxBytes: Long = 20L * 1024 * 1024,
    private val memoryEntries: Int = 64,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    private class Stored(val key: String, val items: List<MediaItem>)

    private class Mem(val items: List<MediaItem>, val at: Long)

    private val memory = object : LinkedHashMap<String, Mem>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mem>?) = size > memoryEntries
    }

    /** In-flight fetches per key, so concurrent pages of one list share one request. */
    private val inFlight = HashMap<String, Deferred<Result<List<MediaItem>>>>()

    /**
     * The list for [command]+[args] (which also form the cache key; include the server id).
     * [fresh] skips the memory layer, for callers that must see the server's current answer.
     */
    suspend fun get(command: String, args: List<String>, fresh: Boolean = false, fetch: suspend () -> List<MediaItem>): List<MediaItem> {
        val key = keyOf(command, args)
        if (!fresh) memGet(key)?.let { return it }
        val job = synchronized(inFlight) {
            inFlight[key]?.takeIf { it.isActive } ?: scope.async {
                // Result-wrapped: failures must not cancel the shared scope, and nobody may await.
                val r = try {
                    Result.success(fetch().map { it.slim() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                // The disk write runs on its own so the caller gets the answer without waiting for it.
                r.onSuccess { items -> memPut(key, items); scope.launch { writeDisk(key, items) } }
                synchronized(inFlight) { inFlight.remove(key) }
                r
            }.also { inFlight[key] = it }
        }
        val quick = withTimeoutOrNull(networkTimeoutMs) { job.await() }
        quick?.onSuccess { return it }
        val error = quick?.exceptionOrNull()
        if (error is NotLoggedInException) throw error // signed out: stale lists would only mislead
        readDisk(key)?.let { items ->
            logI(TAG, "$command: ${if (error == null) "server slow" else "server failed (${error.message})"}, ${items.size} from disk")
            memPut(key, items, stale = true)
            return items
        }
        if (error != null) throw error
        return job.await().getOrThrow() // no copy anywhere: waiting is the only way to show something
    }

    /** Memory or disk only, never the network: for rebuilding single items cheaply. */
    suspend fun peek(command: String, args: List<String>): List<MediaItem>? {
        val key = keyOf(command, args)
        synchronized(memory) { memory[key] }?.let { return it.items }
        return readDisk(key)?.also { memPut(key, it, stale = true) }
    }

    /** Forget the memory layer (server changed or signed out); the disk copies are keyed by server. */
    fun clearMemory() {
        synchronized(memory) { memory.clear() }
    }

    private fun memGet(key: String): List<MediaItem>? = synchronized(memory) {
        memory[key]?.takeIf { clock() - it.at < memoryTtlMs }?.items
    }

    /** A disk fallback is remembered as already expired, so the next call tries the server again. */
    private fun memPut(key: String, items: List<MediaItem>, stale: Boolean = false) = synchronized(memory) {
        memory[key] = Mem(items, if (stale) Long.MIN_VALUE / 2 else clock())
    }

    private fun file(key: String) = File(dir, "$key.json")

    private suspend fun readDisk(key: String): List<MediaItem>? = withContext(io) {
        val f = file(key)
        if (!f.isFile) return@withContext null
        runCatching {
            val s = maJson.decodeFromString(Stored.serializer(), f.readText())
            f.setLastModified(clock()) // LRU by mtime: a read counts as a use
            s.items
        }.onFailure { logW(TAG, "unreadable ${f.name}: ${it.message}") }.getOrNull()
    }

    private suspend fun writeDisk(key: String, items: List<MediaItem>) = withContext(io) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(maJson.encodeToString(Stored.serializer(), Stored(key, items)))
            if (!tmp.renameTo(file(key))) error("rename failed")
            file(key).setLastModified(clock())
            trim()
        }.onFailure { logW(TAG, "write failed: ${it.message}") }
        Unit
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    internal companion object {
        fun keyOf(command: String, args: List<String>): String {
            val md = MessageDigest.getInstance("SHA-1")
            // Length-prefixed so ("a/b","c") and ("a","b/c") can never collide.
            (listOf(command) + args).forEach { md.update("${it.length}:$it;".toByteArray(Charsets.UTF_8)) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
