package io.github.superthom196.maa.playback

import io.github.superthom196.maa.playback.PlayerErrors.decide
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerErrorsTest {
    private val ioBadHttpStatus = 2004
    private val parsingContainerMalformed = 3001
    private val decodingFailed = 4001
    private val unspecified = 1000

    @Test fun missingTrackSkipsUntilThreeInARow() {
        assertEquals(Recovery.SKIP, decide(404, ioBadHttpStatus, 0, 0))
        assertEquals(Recovery.SKIP, decide(400, ioBadHttpStatus, 0, 2))
        assertEquals(Recovery.PAUSE, decide(404, ioBadHttpStatus, 0, 3))
    }

    @Test fun authErrorsAskForSignIn() {
        assertEquals(Recovery.SIGN_IN, decide(401, ioBadHttpStatus, 0, 0))
        assertEquals(Recovery.SIGN_IN, decide(403, ioBadHttpStatus, 5, 5))
    }

    @Test fun damagedFileIsFetchedAgainOnceThenSkipped() {
        assertEquals(Recovery.DROP_CACHE_AND_RETRY, decide(null, parsingContainerMalformed, 0, 0))
        assertEquals(Recovery.SKIP, decide(null, parsingContainerMalformed, 1, 0))
        assertEquals(Recovery.DROP_CACHE_AND_RETRY, decide(null, decodingFailed, 0, 0))
        assertEquals(Recovery.DROP_CACHE_AND_RETRY, decide(416, ioBadHttpStatus, 0, 0))
        assertEquals(Recovery.SKIP, decide(416, ioBadHttpStatus, 1, 0))
    }

    @Test fun anythingElseWaitsForNetworkThenGivesUpOnTheItem() {
        assertEquals(Recovery.RETRY_WHEN_ONLINE, decide(null, unspecified, 0, 0))
        assertEquals(Recovery.RETRY_WHEN_ONLINE, decide(null, unspecified, 2, 0))
        assertEquals(Recovery.SKIP, decide(null, unspecified, 3, 0))
        assertEquals(Recovery.PAUSE, decide(null, unspecified, 3, 3))
    }
}
