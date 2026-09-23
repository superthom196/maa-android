package io.github.superthom196.maa.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Least-recently-used eviction like Media3's `LeastRecentlyUsedCacheEvictor`, with two changes that
 * one needs: the size limit follows the Settings screen at runtime (the stock evictor's is final),
 * and the prefetch targets are pinned, so writing the next track can never evict the current one.
 * If only pinned spans are left the cache is allowed to run over; [PrefetchBudget] keeps that rare.
 *
 * Threading: SimpleCache calls the listener methods from its own synchronized methods, and the two
 * setters synchronize on the same cache instance, so all state is only touched under that monitor.
 */
@OptIn(UnstableApi::class)
class PinningLruEvictor(maxBytes: Long) : CacheEvictor {
    @Volatile var maxBytes: Long = maxBytes
        private set

    private var pinned: Set<String> = emptySet()
    private val lru = TreeSet<CacheSpan>(::compare)
    private var size = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        lru.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        lru.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun setMaxBytes(cache: Cache, bytes: Long) = synchronized(cache) {
        maxBytes = bytes
        evict(cache, 0)
    }

    fun setPinned(cache: Cache, keys: Set<String>) = synchronized(cache) {
        if (keys == pinned) return@synchronized
        pinned = keys
        evict(cache, 0)
    }

    private fun evict(cache: Cache, required: Long) {
        // Collected first: removeSpan calls back into onSpanRemoved, which mutates the set.
        victims(lru, size, required, maxBytes, pinned).forEach(cache::removeSpan)
    }

    companion object {
        /** The spans to drop, oldest first, skipping pinned keys, until [required] more bytes fit. */
        fun victims(oldestFirst: Iterable<CacheSpan>, size: Long, required: Long, max: Long, pinned: Set<String>): List<CacheSpan> {
            if (size + required <= max) return emptyList()
            val out = ArrayList<CacheSpan>()
            var projected = size
            for (span in oldestFirst) {
                if (projected + required <= max) break
                if (span.key in pinned) continue
                out += span
                projected -= span.length
            }
            return out
        }

        private fun compare(a: CacheSpan, b: CacheSpan): Int =
            if (a.lastTouchTimestamp == b.lastTouchTimestamp) a.compareTo(b)
            else a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
    }
}
