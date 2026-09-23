package io.github.superthom196.maa.playback

/**
 * Keeps the prefetch window inside the phone cache. The cache can be as small as 64 MB, and a
 * handful of FLAC tracks is more than that; downloading them all would make the LRU evictor eat
 * the very tracks that are about to play. So only the leading targets whose sizes add up to
 * [FRACTION] of the cache are downloaded (and pinned against eviction), the rest wait their turn.
 *
 * Pure so it is unit-tested; sizes are the real content length once known, [estimateBytes] before.
 */
object PrefetchBudget {
    /** Share of the cache the window may occupy; the rest absorbs estimate errors and old tracks. */
    const val FRACTION = 0.8

    private const val DEFAULT_DURATION_MS = 5 * 60_000L

    fun budget(maxBytes: Long): Long = (maxBytes * FRACTION).toLong()

    /**
     * A deliberately high guess at a file's size before the server has told us: FLAC 16/44.1
     * averages 700–1000 kbit/s, Opus is its nominal bitrate plus a little Ogg overhead.
     */
    fun estimateBytes(format: String, durationMs: Long?): Long {
        val kbps = when {
            format.startsWith("flac") -> 1_000L
            format.startsWith("opus-") -> format.removePrefix("opus-").toLongOrNull() ?: 192L
            else -> 320L
        }
        val ms = durationMs?.takeIf { it > 0 } ?: DEFAULT_DURATION_MS
        return kbps * 1000 / 8 * ms / 1000 * 105 / 100
    }

    /**
     * How many leading entries of [sizes] to download. The first (the current track) always counts,
     * even when it alone is bigger than the budget: playing it matters more than the cache limit.
     */
    fun fit(sizes: List<Long>, budget: Long): Int {
        if (sizes.isEmpty()) return 0
        var total = sizes[0]
        var n = 1
        while (n < sizes.size && total + sizes[n] <= budget) {
            total += sizes[n]
            n++
        }
        return n
    }
}
