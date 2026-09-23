package io.github.superthom196.maa.ui

import io.github.superthom196.maa.data.MaApiException
import io.github.superthom196.maa.data.NotLoggedInException
import io.github.superthom196.maa.data.OfflineException
import io.github.superthom196.maa.playback.PrefetchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FormattersTest {
    @Test fun bytes() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("0 B", Formatters.bytes(-5))
        assertEquals("1023 B", Formatters.bytes(1023))
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.5 KB", Formatters.bytes(1536))
        assertEquals("45 MB", Formatters.bytes(45L * 1024 * 1024))
        assertEquals("2.0 GB", Formatters.bytes(2L * 1024 * 1024 * 1024))
        assertEquals("1.5 TB", Formatters.bytes(1536L * 1024 * 1024 * 1024))
    }

    @Test fun cacheSize() {
        assertEquals(listOf("128 MB", "256 MB", "512 MB", "1 GB", "2 GB"), Formatters.CACHE_CHOICES_MB.map(Formatters::cacheSize))
        assertEquals("1536 MB", Formatters.cacheSize(1536))
    }

    @Test fun lookaheadRangeHonoursTwoAhead() {
        assertEquals(2, Formatters.LOOKAHEAD_RANGE.first)
        assertEquals(10, Formatters.LOOKAHEAD_RANGE.last)
    }

    @Test fun duration() {
        assertEquals("0:00", Formatters.duration(-1))
        assertEquals("0:59", Formatters.duration(59_999))
        assertEquals("3:07", Formatters.duration(187_000))
        assertEquals("1:02:03", Formatters.duration(3_723_000))
    }

    private val onPhone = { c: Int, t: Int -> "$c/$t on phone" }

    @Test fun prefetchLine() {
        assertEquals("3/4 on phone · Downloading 45%", Formatters.prefetchLine(PrefetchStatus(4, 3, "Downloading 45%"), onPhone))
        assertEquals("4/4 on phone", Formatters.prefetchLine(PrefetchStatus(4, 4, "  "), onPhone))
        assertEquals("Waiting for network", Formatters.prefetchLine(PrefetchStatus(0, 0, "Waiting for network"), onPhone))
        assertNull(Formatters.prefetchLine(PrefetchStatus(), onPhone))
    }

    @Test fun loginFailure() {
        assertEquals(LoginFailure.BAD_CREDENTIALS, Formatters.loginFailure(MaApiException(401, "unauthorized", "Invalid credentials")))
        assertEquals(LoginFailure.BAD_CREDENTIALS, Formatters.loginFailure(NotLoggedInException()))
        assertEquals(LoginFailure.UNREACHABLE, Formatters.loginFailure(OfflineException()))
        assertEquals(LoginFailure.UNREACHABLE, Formatters.loginFailure(SocketTimeoutException()))
        assertEquals(LoginFailure.UNREACHABLE, Formatters.loginFailure(RuntimeException("wrapped", UnknownHostException("x"))))
        assertEquals(LoginFailure.OTHER, Formatters.loginFailure(MaApiException(500, null, "boom")))
        assertEquals(LoginFailure.OTHER, Formatters.loginFailure(IllegalStateException("bad json")))
    }
}
