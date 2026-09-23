package io.github.superthom196.maa.playback

import androidx.media3.datasource.cache.CacheSpan
import io.github.superthom196.maa.playback.PinningLruEvictor.Companion.victims
import org.junit.Assert.assertEquals
import org.junit.Test

class PinningLruEvictorTest {
    private fun span(key: String, length: Long, touched: Long) = CacheSpan(key, 0, length, touched, null)

    // Oldest first, as the evictor's TreeSet orders them.
    private val old = span("old", 30, 1)
    private val current = span("current", 40, 2)
    private val mid = span("mid", 20, 3)
    private val recent = span("recent", 10, 4)
    private val all = listOf(old, current, mid, recent)
    private val size = all.sumOf { it.length } // 100

    @Test fun nothingWhenItFits() {
        assertEquals(emptyList<CacheSpan>(), victims(all, size, 0, 100, emptySet()))
    }

    @Test fun evictsOldestFirst() {
        assertEquals(listOf(old), victims(all, size, 0, 80, emptySet()))
        assertEquals(listOf(old, current), victims(all, size, 0, 50, emptySet()))
    }

    @Test fun makesRoomForANewFile() {
        assertEquals(listOf(old), victims(all, size, 25, 100, emptySet()))
    }

    @Test fun neverEvictsPinnedKeys() {
        assertEquals(listOf(old, mid), victims(all, size, 0, 50, setOf("current")))
    }

    @Test fun runsOverWhenOnlyPinnedRemain() {
        assertEquals(listOf(old, recent), victims(all, size, 0, 10, setOf("current", "mid")))
    }
}
