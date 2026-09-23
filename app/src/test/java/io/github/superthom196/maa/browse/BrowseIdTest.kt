package io.github.superthom196.maa.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseIdTest {
    private val awkward = listOf(
        "123",
        "Artist/Album (2020)/01 - Söng & Dance +1.flac",
        "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
        "100% pure",
        "a+b c",
        "日本語/トラック",
        "#hash?query&x=y",
        "",
        "-",
        "%2F",
    )

    private fun roundTrip(id: BrowseId) {
        val s = id.encode()
        assertEquals(s, id, BrowseId.parse(s))
        // Some Auto components treat ids as URIs: never a raw fragment or query marker, and each
        // item id stays within its own segment.
        assertFalse(s, s.contains('#') || s.contains('?') || s.contains('+') || s.contains(' '))
    }

    @Test fun fixedIds() {
        val expected = mapOf(
            BrowseId.Root to "root", BrowseId.Home to "home", BrowseId.HomeRecent to "home/recent",
            BrowseId.HomeAdded to "home/added", BrowseId.HomeFavAlbums to "home/favalbums",
            BrowseId.HomeFavTracks to "home/favtracks", BrowseId.Artists to "artists",
            BrowseId.Albums to "albums", BrowseId.Playlists to "playlists",
        )
        for ((id, s) in expected) {
            assertEquals(s, id.encode())
            assertEquals(id, BrowseId.parse(s))
        }
    }

    @Test fun containersRoundTrip() {
        for (prov in listOf("library", "filesystem_local--abc", "")) for (id in awkward) {
            roundTrip(BrowseId.Artist(prov, id))
            roundTrip(BrowseId.Album(prov, id))
            roundTrip(BrowseId.Playlist(prov, id))
        }
    }

    @Test fun tracksRoundTrip() {
        for (kind in CtxKind.entries) for (id in awkward) {
            val ctx = if (kind.hasContainer) TrackCtx(kind, "library", id) else TrackCtx(kind)
            roundTrip(BrowseId.Track(ctx, "filesystem_local--x", id))
        }
    }

    @Test fun letters() {
        for (l in ('A'..'Z').map { it.toString() } + "#") {
            roundTrip(BrowseId.ArtistLetter(l))
            roundTrip(BrowseId.AlbumLetter(l))
        }
        assertEquals("albums/L/%23", BrowseId.AlbumLetter("#").encode())
    }

    @Test fun protocolExamples() {
        assertEquals("album/library/12", BrowseId.Album("library", "12").encode())
        assertEquals("track/album/library/12/library/345", BrowseId.Track(TrackCtx.album("library", "12"), "library", "345").encode())
        assertEquals("track/search/-/-/library/9", BrowseId.Track(TrackCtx.SEARCH, "library", "9").encode())
        assertEquals("artist/fs/a%2Fb%3Ac%25d%2Be%20f", BrowseId.Artist("fs", "a/b:c%d+e f").encode())
    }

    @Test fun unusedContextSegmentsAreIgnored() {
        assertEquals(
            BrowseId.Track(TrackCtx.FAVTRACKS, "library", "1"),
            BrowseId.parse("track/favtracks/whatever/else/library/1"),
        )
    }

    @Test fun malformedIsNull() {
        val bad = listOf(
            null, "", "nope", "root/x", "home/other", "album", "album/library", "album/library/1/2",
            "artists/L", "artists/L/AB", "artists/L/a", "artists/X/A", "albums/L/%zz",
            "album/library/%zz", "album/library/%", "track/album/library/1/library", "track/bogus/-/-/library/1",
            "track/album/library/1/library/2/3", "album%2Flibrary/1", "Album/library/1", "playlists/L/A",
        )
        for (s in bad) assertNull(s, BrowseId.parse(s))
    }

    @Test fun letterValidation() {
        assertTrue(BrowseId.isLetter("Q"))
        assertTrue(BrowseId.isLetter("#"))
        assertFalse(BrowseId.isLetter("q"))
        assertFalse(BrowseId.isLetter("É"))
    }
}
