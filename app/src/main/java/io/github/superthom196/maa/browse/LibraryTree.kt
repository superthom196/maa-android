package io.github.superthom196.maa.browse

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession

/**
 * The Android Auto browse tree (ids documented in docs/PROTOCOL.md, "Browse ids").
 * Implemented by [LibraryTreeImpl]; the playback service's session callback only delegates here.
 *
 * Playable track items carry a `maa://` URI ([io.github.superthom196.maa.playback.StreamUris])
 * and a custom cache key in their localConfiguration. Items that come back from a controller
 * (Android Auto, the phone UI) have that stripped, so [expandForPlayback] rebuilds them from ids.
 *
 * Methods throw [io.github.superthom196.maa.data.NotLoggedInException] when signed out and
 * [io.github.superthom196.maa.data.OfflineException] when neither the server nor the disk
 * cache can answer.
 */
interface LibraryTree {
    fun root(): MediaItem
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>
    suspend fun item(mediaId: String): MediaItem?
    suspend fun search(query: String): List<MediaItem>
    /**
     * Turns what a controller asked to play into a queue: a single track id expands to its
     * album/playlist context starting at that track; an album or playlist id expands to its
     * tracks. Returned items are fully resolved (URI + cache key + metadata).
     */
    suspend fun expandForPlayback(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition
    /** Rebuilds a fully resolved playable item from its media id (queue restore); null if unknown. */
    suspend fun resolve(mediaId: String): MediaItem?
}
