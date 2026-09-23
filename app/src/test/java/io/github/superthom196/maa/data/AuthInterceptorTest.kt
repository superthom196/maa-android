package io.github.superthom196.maa.data

import io.github.superthom196.maa.data.AuthInterceptor.Companion.isOurs
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthInterceptorTest {
    private val homes = listOf("http://192.168.0.150:8095", "http://100.97.96.36:8095")

    @Test fun theServerOnEitherAddress() {
        assertTrue(isOurs("http://192.168.0.150:8095/imageproxy/abc123?size=256".toHttpUrl(), homes))
        assertTrue(isOurs("http://100.97.96.36:8095/api".toHttpUrl(), homes))
    }

    @Test fun notAThirdPartyImageHost() {
        assertFalse(isOurs("https://assets.fanart.tv/fanart/music/x/artistthumb/y.jpg".toHttpUrl(), homes))
        assertFalse(isOurs("https://r2.theaudiodb.com/images/media/artist/thumb/z.jpg".toHttpUrl(), homes))
    }

    @Test fun sameHostOtherPortIsNotOurs() {
        assertFalse(isOurs("http://192.168.0.150:8097/flow/x".toHttpUrl(), homes))
    }

    @Test fun defaultPortMatchesTheExplicitOne() {
        val home = listOf("http://serverpi")
        assertTrue(isOurs("http://serverpi:80/imageproxy/abc".toHttpUrl(), home))
        assertFalse(isOurs("https://serverpi/imageproxy/abc".toHttpUrl(), home))
    }

    @Test fun hostIsCaseInsensitive() {
        assertTrue(isOurs("http://ServerPi.local:8095/api".toHttpUrl(), listOf("http://serverpi.local:8095/")))
    }

    @Test fun nothingWhileNoServerIsKnown() {
        assertFalse(isOurs("http://192.168.0.150:8095/imageproxy/abc".toHttpUrl(), emptyList()))
        assertFalse(isOurs("http://192.168.0.150:8095/imageproxy/abc".toHttpUrl(), listOf("not a url")))
    }
}
