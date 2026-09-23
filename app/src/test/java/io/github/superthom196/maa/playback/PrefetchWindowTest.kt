package io.github.superthom196.maa.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchWindowTest {
    private val unset = PrefetchWindow.INDEX_UNSET

    /** Timeline.getNextWindowIndex for a plain queue of [size] items. */
    private fun linear(size: Int, repeatAll: Boolean = false): (Int) -> Int = { i ->
        when {
            i + 1 < size -> i + 1
            repeatAll -> 0
            else -> unset
        }
    }

    @Test fun linearQueue() {
        assertEquals(listOf(2, 3, 4, 5), PrefetchWindow.targets(2, 3, linear(10)))
    }

    @Test fun stopsAtEndOfQueue() {
        assertEquals(listOf(8, 9), PrefetchWindow.targets(8, 3, linear(10)))
        assertEquals(listOf(9), PrefetchWindow.targets(9, 3, linear(10)))
    }

    @Test fun repeatAllWrapsAround() {
        assertEquals(listOf(8, 9, 0, 1), PrefetchWindow.targets(8, 3, linear(10, repeatAll = true)))
    }

    @Test fun repeatAllOnShortQueueDoesNotLoop() {
        assertEquals(listOf(1, 0), PrefetchWindow.targets(1, 3, linear(2, repeatAll = true)))
        assertEquals(listOf(0), PrefetchWindow.targets(0, 3, linear(1, repeatAll = true)))
    }

    @Test fun repeatOneIsTreatedAsOffByTheCaller() {
        // With REPEAT_MODE_ONE the caller asks the timeline for REPEAT_MODE_OFF, i.e. linear order.
        assertEquals(listOf(4, 5, 6, 7), PrefetchWindow.targets(4, 3, linear(10)))
        // Had it passed REPEAT_ONE, next(i) == i: still only the current one, no loop.
        assertEquals(listOf(4), PrefetchWindow.targets(4, 3) { it })
    }

    @Test fun followsShuffledOrder() {
        val order = listOf(3, 0, 4, 1, 2)
        val next: (Int) -> Int = { i -> order.indexOf(i).let { if (it + 1 < order.size) order[it + 1] else unset } }
        assertEquals(listOf(0, 4, 1, 2), PrefetchWindow.targets(0, 3, next))
        assertEquals(listOf(1, 2), PrefetchWindow.targets(1, 3, next))
    }

    @Test fun smallWindows() {
        assertEquals(listOf(5), PrefetchWindow.targets(5, 0, linear(10)))
        assertEquals(listOf(5, 6), PrefetchWindow.targets(5, 1, linear(10)))
        assertEquals(listOf(5), PrefetchWindow.targets(5, -2, linear(10)))
    }

    @Test fun emptyQueue() {
        assertEquals(emptyList<Int>(), PrefetchWindow.targets(unset, 3, linear(0)))
    }
}
