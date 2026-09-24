package io.github.superthom196.maa.browse

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import io.github.superthom196.maa.R
import io.github.superthom196.maa.art.ArtUris
import io.github.superthom196.maa.data.ConfigStore
import io.github.superthom196.maa.data.ServerConfig
import io.github.superthom196.maa.playback.StreamUris
import io.github.superthom196.maa.playback.TrackRef
import io.github.superthom196.maa.data.MediaItem as MaItem

/**
 * Music Assistant items → Media3 items for Android Auto. Text and layout decisions live in
 * [BrowseText]; this class only assembles builders (android.* types, so device-tested only).
 *
 * Content-style hints ride on each browsable item's own extras and describe that item's
 * children: Auto reads them when the user opens it.
 */
@OptIn(UnstableApi::class)
class MediaItemMapper(private val context: Context, private val config: ConfigStore) {

    /** A tab or list node that has no Music Assistant item behind it. */
    fun folder(
        id: BrowseId,
        title: String,
        holds: Holds,
        subtitle: String? = null,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        artwork: Uri? = null,
        playable: Boolean = false,
    ): MediaItem = browsable(id, title, subtitle, mediaType, holds, artwork, playable, groupTitle = null)

    /** Artist, album or playlist: browsable; albums and playlists are playable as a whole too. */
    fun container(item: MaItem, groupTitle: String? = null): MediaItem = when (item.mediaType) {
        "artist" -> browsable(
            BrowseId.Artist(item.provider, item.itemId), item.name, null,
            MediaMetadata.MEDIA_TYPE_ARTIST, Holds.ALBUMS, ArtUris.forItem(item), playable = false, groupTitle,
        )
        "playlist" -> browsable(
            BrowseId.Playlist(item.provider, item.itemId), item.name, BrowseText.playlistSubtitle(item),
            MediaMetadata.MEDIA_TYPE_PLAYLIST, Holds.TRACKS, ArtUris.forItem(item), playable = true, groupTitle,
        )
        else -> browsable(
            BrowseId.Album(item.provider, item.itemId), BrowseText.title(item), BrowseText.albumSubtitle(item),
            MediaMetadata.MEDIA_TYPE_ALBUM, Holds.TRACKS, ArtUris.forItem(item), playable = true, groupTitle,
            artist = item.artistLine, year = item.year,
        )
    }

    /**
     * A fully resolved playable track: host-independent `maa://` URI and cache key in the format the
     * server is set to, so the same track always lands on the same cache entry.
     * [album] (the context's album, when known) fills album artist and fallback artwork.
     */
    fun track(item: MaItem, ctx: TrackCtx, album: MaItem? = null, groupTitle: String? = null): MediaItem {
        val server = config.server.value
        val format = server?.format ?: ServerConfig.DEFAULT_FORMAT
        val ref = TrackRef(item.provider, item.itemId)
        val md = MediaMetadata.Builder()
            .setTitle(BrowseText.title(item))
            .setArtist(item.artistLine.ifBlank { null })
            .setAlbumTitle(item.album?.name?.ifBlank { null } ?: album?.name)
            .setAlbumArtist(album?.artistLine?.ifBlank { null })
            .setTrackNumber(item.trackNumber?.takeIf { it > 0 })
            .setDiscNumber(item.discNumber?.takeIf { it > 0 })
            .setDurationMs(item.duration?.takeIf { it > 0 }?.let { (it * 1000).toLong() })
            .setArtworkUri(ArtUris.forItem(item) ?: ArtUris.forItem(album))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        groupTitle?.let { md.setExtras(Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }) }
        return MediaItem.Builder()
            .setMediaId(BrowseId.Track(ctx, item.provider, item.itemId).encode())
            .setUri(Uri.parse(StreamUris.trackUri(ref, format, server?.variant.orEmpty())))
            .setCustomCacheKey(StreamUris.cacheKey(server?.serverId.orEmpty(), ref, format, server?.variant.orEmpty()))
            .setMimeType(StreamUris.mimeType(format))
            .setMediaMetadata(md.build())
            .build()
    }

    /** Tab icon as an android.resource URI (Auto draws root children's artwork as tab icons). */
    fun resourceUri(@DrawableRes id: Int): Uri {
        val res = context.resources
        return Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(res.getResourcePackageName(id))
            .appendPath(res.getResourceTypeName(id))
            .appendPath(res.getResourceEntryName(id))
            .build()
    }

    // Referenced through R so resource shrinking keeps them.
    val homeIcon: Uri get() = resourceUri(R.drawable.ic_tab_home)
    val artistsIcon: Uri get() = resourceUri(R.drawable.ic_tab_artists)
    val albumsIcon: Uri get() = resourceUri(R.drawable.ic_tab_albums)
    val playlistsIcon: Uri get() = resourceUri(R.drawable.ic_tab_playlists)

    companion object {
        /**
         * Extras for the `LibraryParams` the service returns from `onGetLibraryRoot`: the tree-wide
         * default layout (lists). Each node's own hints override it for its children.
         */
        fun rootExtras(): Bundle = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
    }

    private fun browsable(
        id: BrowseId,
        title: String,
        subtitle: String?,
        mediaType: Int,
        holds: Holds,
        artwork: Uri?,
        playable: Boolean,
        groupTitle: String?,
        artist: String? = null,
        year: Int? = null,
    ): MediaItem {
        val style = when (BrowseText.childStyle(holds)) {
            Style.GRID -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            Style.LIST -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        }
        val extras = Bundle().apply {
            // Both keys: an album is browsable *and* playable, and hosts differ in which they consult.
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
            groupTitle?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        }
        val md = MediaMetadata.Builder()
            .setTitle(title)
            // displayTitle makes the legacy (MediaBrowserCompat) description use our subtitle
            // instead of guessing one from artist/album fields.
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtist(artist?.ifBlank { null })
            .setReleaseYear(year?.takeIf { it > 0 })
            .setArtworkUri(artwork)
            .setIsBrowsable(true)
            .setIsPlayable(playable)
            .setMediaType(mediaType)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(id.encode()).setMediaMetadata(md).build()
    }
}
