package io.github.superthom196.maa.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrisTest {
    private val fsRef = TrackRef("filesystem_local--abc", "Artist/Album (2020)/01 - Söng & Dance +1.flac")

    @Test fun roundTripsAwkwardIds() {
        val uri = StreamUris.trackUri(fsRef, "opus-192")
        assertEquals(fsRef to "opus-192", StreamUris.parse(uri))
    }

    @Test fun libraryTrack() {
        val uri = StreamUris.trackUri(TrackRef("library", "123"), "flac-16-44")
        assertEquals("maa://track?provider=library&item_id=123&format=flac-16-44", uri)
    }

    @Test fun rejectsOtherUris() {
        assertNull(StreamUris.parse("http://example.com/x"))
        assertNull(StreamUris.parse("maa://track?provider=library&format=opus-192"))
    }

    @Test fun httpUrlUsesBaseAndKeepsQuery() {
        val uri = StreamUris.trackUri(TrackRef("library", "7"), "opus-192")
        assertEquals(
            "http://100.97.96.36:8095/maa/track?provider=library&item_id=7&format=opus-192",
            StreamUris.httpUrl("http://100.97.96.36:8095/", uri),
        )
    }

    @Test fun cacheKeyIsHostFreeAndSlashSafe() {
        val key = StreamUris.cacheKey("srv", fsRef, "opus-192")
        assertEquals(5, key.split('/').size)
        assertEquals("v1/srv/library/9/opus-192", StreamUris.cacheKey("srv", TrackRef("library", "9"), "opus-192"))
    }

    @Test fun variantRidesInUriAndKeyButNotInPluginUrl() {
        val ref = TrackRef("library", "7")
        val uri = StreamUris.trackUri(ref, "flac-16-44", "n-17")
        assertEquals("n-17", StreamUris.variant(uri))
        assertEquals(ref to "flac-16-44", StreamUris.parse(uri))
        assertEquals("v1/srv/library/7/flac-16-44_n-17", StreamUris.cacheKey("srv", ref, "flac-16-44", "n-17"))
        assertEquals(
            "http://h:8095/maa/track?provider=library&item_id=7&format=flac-16-44",
            StreamUris.httpUrl("http://h:8095", uri),
        )
    }

    @Test fun noVariantKeepsOldKeys() {
        val ref = TrackRef("library", "7")
        assertEquals("", StreamUris.variant(StreamUris.trackUri(ref, "opus-192")))
        assertEquals(StreamUris.cacheKey("srv", ref, "opus-192"), StreamUris.cacheKey("srv", ref, "opus-192", ""))
    }

    @Test fun mimeTypes() {
        assertEquals("audio/flac", StreamUris.mimeType("flac-16-44"))
        assertEquals("audio/ogg", StreamUris.mimeType("opus-192"))
    }
}
