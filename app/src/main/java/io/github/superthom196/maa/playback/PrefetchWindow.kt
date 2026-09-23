package io.github.superthom196.maa.playback

/**
 * Which queue positions the prefetcher keeps on the phone: the current one first (it is the one
 * that stops the music if it goes missing), then up to `count` that will play after it.
 *
 * Pure so the edge cases are unit-tested; the caller supplies the player's idea of "next", i.e.
 * `timeline.getNextWindowIndex(i, if (repeatMode == REPEAT_MODE_ONE) REPEAT_MODE_OFF else repeatMode,
 * shuffleModeEnabled)`. REPEAT_ONE is mapped to OFF because what matters is where the user ends up
 * after pressing next, not the track that repeats (that one is already the current target).
 */
object PrefetchWindow {
    /** Same value as `C.INDEX_UNSET`, kept local so this stays free of Android classes. */
    const val INDEX_UNSET = -1

    fun targets(current: Int, count: Int, next: (Int) -> Int): List<Int> {
        if (current < 0) return emptyList()
        val out = LinkedHashSet<Int>()
        out += current
        var i = current
        repeat(count.coerceAtLeast(0)) {
            i = next(i)
            // A repeat that wraps back onto something already listed means the queue is shorter
            // than the window: everything is in, stop instead of going round again.
            if (i == INDEX_UNSET || !out.add(i)) return out.toList()
        }
        return out.toList()
    }
}
