package io.github.superthom196.maa.browse

import io.github.superthom196.maa.data.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseTextTest {
    private fun track(id: String, disc: Int?, no: Int?) = item(id, "T$id", "track").copy(discNumber = disc, trackNumber = no)

    @Test fun titleAddsVersionOnce() {
        assertEquals("Song (Live)", BrowseText.title(item("1", "Song").copy(version = "Live")))
        assertEquals("Song (Live)", BrowseText.title(item("1", "Song (Live)").copy(version = "live")))
        assertEquals("Song", BrowseText.title(item("1", "Song").copy(version = " ")))
    }

    @Test fun subtitles() {
        assertEquals("Radiohead · 2000", BrowseText.albumSubtitle(item("1", "Kid A", artist = "Radiohead", year = 2000)))
        assertEquals("Radiohead", BrowseText.albumSubtitle(item("1", "Kid A", artist = "Radiohead", year = 0)))
        assertEquals("1999", BrowseText.albumSubtitle(item("1", "X", year = 1999)))
        assertNull(BrowseText.albumSubtitle(item("1", "X")))
        assertEquals("Radiohead", BrowseText.subtitle(item("1", "Idioteque", "track", artist = "Radiohead")))
        assertNull(BrowseText.subtitle(item("1", "Radiohead", "artist")))
        assertEquals("1 album", BrowseText.countLabel(1, "album", "albums"))
        assertEquals("3 albums", BrowseText.countLabel(3, "album", "albums"))
    }

    @Test fun layoutChoice() {
        assertEquals(Style.GRID, BrowseText.childStyle(Holds.ALBUMS))
        for (h in listOf(Holds.TRACKS, Holds.ARTISTS, Holds.PLAYLISTS, Holds.FOLDERS, Holds.MIXED)) assertEquals(Style.LIST, BrowseText.childStyle(h))
    }

    @Test fun paging() {
        val all = (0 until 10).toList()
        assertEquals(listOf(0, 1, 2, 3), BrowseText.page(all, 0, 4))
        assertEquals(listOf(8, 9), BrowseText.page(all, 2, 4))
        assertEquals(emptyList<Int>(), BrowseText.page(all, 3, 4))
        assertEquals(all, BrowseText.page(all, 0, Int.MAX_VALUE))
        assertEquals(all, BrowseText.page(all, 0, 0))
        assertEquals(emptyList<Int>(), BrowseText.page(all, 1, Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), BrowseText.page(all, Int.MAX_VALUE, 1_000)) // no overflow
    }

    @Test fun albumTrackOrderAndDiscGroups() {
        val sorted = BrowseText.sortAlbumTracks(listOf(track("c", 2, 1), track("b", 1, 2), track("a", 1, 1), track("x", null, null)))
        assertEquals(listOf("x", "a", "b", "c"), sorted.map { it.itemId })
        assertEquals(listOf(null, "Disc 1", "Disc 1", "Disc 2"), BrowseText.discGroups(sorted))
        assertEquals(listOf(null, null), BrowseText.discGroups(listOf(track("a", 1, 1), track("b", 1, 2))))
    }

    @Test fun artistAlbumsNewestFirst() {
        val albums = listOf(item("1", "Old", year = 1990), item("2", "Undated"), item("3", "New", year = 2020), item("4", "Also 2020", year = 2020))
        assertEquals(listOf("Also 2020", "New", "Old", "Undated"), BrowseText.sortArtistAlbums(albums).map { it.name })
    }

    @Test fun findsTappedTrack() {
        val list = listOf(track("1", 1, 1), track("2", 1, 2))
        assertEquals(1, BrowseText.indexOfTrack(list, "library", "2"))
        assertNull(BrowseText.indexOfTrack(list, "library", "3"))
        assertNull(BrowseText.indexOfTrack(list, "spotify", "2"))
    }
}

class VoiceSearchTest {
    private val results = SearchResults(
        artists = listOf(item("ar", "The Beatles", "artist")),
        albums = listOf(item("al", "Abbey Road", "album")),
        tracks = listOf(item("tr", "Something", "track")),
        playlists = listOf(item("pl", "Road Trip", "playlist")),
    )

    @Test fun focusWins() {
        assertEquals(VoicePick.Album(results.albums[0]), VoiceSearch.pick("something", "vnd.android.cursor.item/album", results))
        assertEquals(VoicePick.Artist(results.artists[0]), VoiceSearch.pick("x", "vnd.android.cursor.item/artist", results))
        assertEquals(VoicePick.Playlist(results.playlists[0]), VoiceSearch.pick("x", "vnd.android.cursor.item/playlist", results))
        assertEquals(VoicePick.Track(results.tracks[0]), VoiceSearch.pick("x", "vnd.android.cursor.item/audio", results))
    }

    @Test fun focusWithoutMatchFallsThrough() {
        val noAlbums = results.copy(albums = emptyList())
        assertEquals(VoicePick.Track(results.tracks[0]), VoiceSearch.pick("x", "vnd.android.cursor.item/album", noAlbums))
    }

    @Test fun exactNameBeatsTopTrack() {
        assertEquals(VoicePick.Album(results.albums[0]), VoiceSearch.pick("abbey road", null, results))
        assertEquals(VoicePick.Artist(results.artists[0]), VoiceSearch.pick("the beatles", null, results))
        assertEquals(VoicePick.Track(results.tracks[0]), VoiceSearch.pick("Something", null, results))
    }

    @Test fun defaultOrderTrackArtistAlbumPlaylist() {
        assertEquals(VoicePick.Track(results.tracks[0]), VoiceSearch.pick("zzz", null, results))
        assertEquals(VoicePick.Artist(results.artists[0]), VoiceSearch.pick("zzz", null, results.copy(tracks = emptyList())))
        assertEquals(VoicePick.Album(results.albums[0]), VoiceSearch.pick("zzz", null, results.copy(tracks = emptyList(), artists = emptyList())))
        assertEquals(
            VoicePick.Playlist(results.playlists[0]),
            VoiceSearch.pick("zzz", null, results.copy(tracks = emptyList(), artists = emptyList(), albums = emptyList())),
        )
        assertEquals(VoicePick.Nothing, VoiceSearch.pick("zzz", null, SearchResults()))
    }
}
