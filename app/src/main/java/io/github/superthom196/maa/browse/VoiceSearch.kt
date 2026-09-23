package io.github.superthom196.maa.browse

import io.github.superthom196.maa.data.MediaItem
import io.github.superthom196.maa.data.SearchResults

/** What "Hey Google, play X" should start. */
sealed class VoicePick {
    /** The track, inside its album (or alone when the album is unknown). */
    data class Track(val track: MediaItem) : VoicePick()
    data class Album(val album: MediaItem) : VoicePick()
    data class Artist(val artist: MediaItem) : VoicePick()
    data class Playlist(val playlist: MediaItem) : VoicePick()
    data object Nothing : VoicePick()
}

/**
 * Chooses from search results for a voice request. The Assistant says what kind of thing it heard
 * through `MediaStore.EXTRA_MEDIA_FOCUS` ("vnd.android.cursor.item/album", …/artist, …/audio,
 * …/playlist); that wins when present and matched. Without it: a result whose name is exactly
 * the query ("play Abbey Road" → the album, not a track that merely mentions it), else Track →
 * Artist → Album → Playlist, like the official MA app.
 */
object VoiceSearch {
    const val EXTRA_MEDIA_FOCUS = "android.intent.extra.focus" // MediaStore.EXTRA_MEDIA_FOCUS

    fun pick(query: String, focus: String?, r: SearchResults): VoicePick {
        when (focus?.substringAfterLast('/')) {
            "artist" -> r.artists.firstOrNull()?.let { return VoicePick.Artist(it) }
            "album" -> r.albums.firstOrNull()?.let { return VoicePick.Album(it) }
            "audio" -> r.tracks.firstOrNull()?.let { return VoicePick.Track(it) }
            "playlist" -> r.playlists.firstOrNull()?.let { return VoicePick.Playlist(it) }
        }
        val q = foldKey(query)
        if (q.isNotEmpty()) {
            val exactTrack = r.tracks.firstOrNull { foldKey(it.name) == q }
            if (exactTrack == null) {
                r.artists.firstOrNull { foldKey(it.name) == q }?.let { return VoicePick.Artist(it) }
                r.albums.firstOrNull { foldKey(it.name) == q }?.let { return VoicePick.Album(it) }
                r.playlists.firstOrNull { foldKey(it.name) == q }?.let { return VoicePick.Playlist(it) }
            } else {
                return VoicePick.Track(exactTrack)
            }
        }
        r.tracks.firstOrNull()?.let { return VoicePick.Track(it) }
        r.artists.firstOrNull()?.let { return VoicePick.Artist(it) }
        r.albums.firstOrNull()?.let { return VoicePick.Album(it) }
        r.playlists.firstOrNull()?.let { return VoicePick.Playlist(it) }
        return VoicePick.Nothing
    }
}
