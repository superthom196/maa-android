package io.github.superthom196.maa.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import io.github.superthom196.maa.data.ConfigStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The on-phone audio cache: whole tracks, keyed by [StreamUris.cacheKey] (no host, so a track
 * fetched on Wi-Fi at home plays from here in the car). Lives in filesDir rather than cacheDir
 * because the system clears cacheDir when storage runs low, which is exactly the tracks we need.
 *
 * One instance per process (SimpleCache locks its folder), owned by AppGraph. The player only reads
 * it; the [Prefetcher] is the only writer, and [clear] coordinates with it through [writeLock].
 */
@OptIn(UnstableApi::class)
class AudioCache(context: Context, config: ConfigStore, scope: CoroutineScope) : CacheControl {
    val evictor = PinningLruEvictor(bytesFor(config.settings.value.cacheMb))
    val cache: SimpleCache = SimpleCache(File(context.filesDir, "audio"), evictor, StandaloneDatabaseProvider(context))

    private val used = MutableStateFlow(cache.cacheSpace)
    override val usedBytes: StateFlow<Long> = used.asStateFlow()

    /** Written by the [Prefetcher]. */
    internal val prefetchState = MutableStateFlow(PrefetchStatus())
    override val prefetch: StateFlow<PrefetchStatus> = prefetchState.asStateFlow()

    /** Held by the prefetcher around each download, so [clear] never races a live writer. */
    internal val writeLock = Mutex()
    private val paused = MutableStateFlow(false)

    /** Set by the prefetcher: aborts the download in flight (see [clear]). */
    @Volatile internal var cancelWrites: () -> Unit = {}

    init {
        scope.launch {
            config.settings.map { bytesFor(it.cacheMb) }.distinctUntilChanged().collect { bytes ->
                evictor.setMaxBytes(cache, bytes)
                refreshUsage()
            }
        }
    }

    val maxBytes: Long get() = evictor.maxBytes

    /** Total length of [key] if a download has started at some point, else `C.LENGTH_UNSET`. */
    fun contentLength(key: String): Long = ContentMetadata.getContentLength(cache.getContentMetadata(key))

    fun isFullyCached(key: String): Boolean {
        val length = contentLength(key)
        return length != C.LENGTH_UNSET.toLong() && cache.isCached(key, 0, length)
    }

    fun cachedBytes(key: String, length: Long): Long = cache.getCachedBytes(key, 0, length)

    /** Keys the evictor must leave alone: the prefetch targets. */
    fun pin(keys: Set<String>) = evictor.setPinned(cache, keys)

    fun remove(key: String) = cache.removeResource(key)

    fun refreshUsage() {
        used.value = cache.cacheSpace
    }

    /** Suspends while [clear] runs. */
    internal suspend fun awaitWritable() {
        paused.first { !it }
    }

    internal val writable: Boolean get() = !paused.value

    override suspend fun clear() = withContext(Dispatchers.IO) {
        paused.value = true
        try {
            cancelWrites()
            writeLock.withLock { cache.keys.forEach(cache::removeResource) }
        } finally {
            paused.value = false
            refreshUsage()
        }
    }

    private companion object {
        /** Floor well under the Settings minimum (64 MB), so a bad value never disables caching. */
        fun bytesFor(mb: Int): Long = mb.coerceAtLeast(32).toLong() * 1024 * 1024
    }
}
