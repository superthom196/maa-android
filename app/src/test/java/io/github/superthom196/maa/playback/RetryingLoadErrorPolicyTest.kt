package io.github.superthom196.maa.playback

import io.github.superthom196.maa.playback.RetryingLoadErrorPolicy.Companion.FATAL
import io.github.superthom196.maa.playback.RetryingLoadErrorPolicy.Companion.retryAfter
import io.github.superthom196.maa.playback.RetryingLoadErrorPolicy.Companion.retryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryingLoadErrorPolicyTest {
    @Test fun networkErrorsRetryWithGrowingDelayCappedAt5s() {
        assertEquals(1_000L, retryDelayMs(null, null, false, 1))
        assertEquals(2_000L, retryDelayMs(null, null, false, 2))
        assertEquals(5_000L, retryDelayMs(null, null, false, 5))
        assertEquals(5_000L, retryDelayMs(null, null, false, 1_000))
    }

    @Test fun serverErrorsRetry() {
        assertEquals(3_000L, retryDelayMs(500, null, false, 3))
        assertEquals(1_000L, retryDelayMs(502, null, false, 1))
        assertEquals(2_000L, retryDelayMs(408, null, false, 2))
        assertEquals(2_000L, retryDelayMs(429, null, false, 2))
    }

    @Test fun transcodePendingHonoursRetryAfter() {
        assertEquals(5_000L, retryDelayMs(503, 5, false, 1))
        assertEquals(5_000L, retryDelayMs(503, null, false, 7))
        assertEquals(10_000L, retryDelayMs(503, 30, false, 1))
        assertEquals(1_000L, retryDelayMs(503, 0, false, 1))
    }

    @Test fun clientErrorsAreFatal() {
        for (status in listOf(400, 401, 403, 404, 410, 416)) assertEquals("$status", FATAL, retryDelayMs(status, null, false, 1))
    }

    @Test fun nonRetriableCausesAreFatal() {
        assertEquals(FATAL, retryDelayMs(null, null, true, 1))
        assertEquals(FATAL, retryDelayMs(503, 5, true, 1))
    }

    @Test fun parsesRetryAfterCaseInsensitively() {
        assertEquals(5L, retryAfter(mapOf("retry-after" to listOf(" 5 "))))
        assertEquals(30L, retryAfter(mapOf("Retry-After" to listOf("30"))))
        assertNull(retryAfter(mapOf("Retry-After" to listOf("Wed, 21 Oct 2015 07:28:00 GMT"))))
        assertNull(retryAfter(emptyMap()))
    }
}
