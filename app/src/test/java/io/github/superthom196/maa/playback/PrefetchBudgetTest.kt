package io.github.superthom196.maa.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchBudgetTest {
    private val mb = 1024L * 1024

    @Test fun currentTrackAlwaysCounts() {
        assertEquals(1, PrefetchBudget.fit(listOf(500 * mb, 1 * mb), budget = 100 * mb))
    }

    @Test fun takesLeadingTargetsThatFit() {
        assertEquals(3, PrefetchBudget.fit(listOf(10 * mb, 10 * mb, 10 * mb, 10 * mb), budget = 35 * mb))
        assertEquals(4, PrefetchBudget.fit(listOf(10 * mb, 10 * mb, 10 * mb, 10 * mb), budget = 40 * mb))
    }

    @Test fun stopsAtFirstThatDoesNotFitEvenIfALaterOneWould() {
        // In order: skipping ahead would download a track that plays after one we do not have.
        assertEquals(1, PrefetchBudget.fit(listOf(10 * mb, 50 * mb, 1 * mb), budget = 40 * mb))
    }

    @Test fun empty() {
        assertEquals(0, PrefetchBudget.fit(emptyList(), budget = 100 * mb))
    }

    @Test fun opusWindowFitsSmallestCache() {
        // 64 MB cache, four 5-minute Opus 192 tracks: the whole window (current + 3) fits.
        val sizes = List(4) { PrefetchBudget.estimateBytes("opus-192", 5 * 60_000L) }
        assertEquals(4, PrefetchBudget.fit(sizes, PrefetchBudget.budget(64 * mb)))
    }

    @Test fun flacWindowIsCappedInSmallCache() {
        val sizes = List(4) { PrefetchBudget.estimateBytes("flac-16-44", 4 * 60_000L) }
        val fit = PrefetchBudget.fit(sizes, PrefetchBudget.budget(64 * mb))
        assertTrue("fit=$fit", fit in 1..2)
        assertEquals(4, PrefetchBudget.fit(sizes, PrefetchBudget.budget(256 * mb)))
    }

    @Test fun estimates() {
        // 192 kbit/s for 60 s = 1.44 MB, plus 5 % container overhead.
        assertEquals(1_512_000L, PrefetchBudget.estimateBytes("opus-192", 60_000L))
        assertTrue(PrefetchBudget.estimateBytes("flac-16-44", 60_000L) > PrefetchBudget.estimateBytes("opus-320", 60_000L))
        // Unknown duration: assume five minutes.
        assertEquals(PrefetchBudget.estimateBytes("opus-128", 300_000L), PrefetchBudget.estimateBytes("opus-128", null))
    }
}
