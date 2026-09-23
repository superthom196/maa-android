package io.github.superthom196.maa.browse

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import io.github.superthom196.maa.data.ConfigStore
import io.github.superthom196.maa.data.MaApi
import kotlinx.coroutines.CoroutineScope

/** WS-D: the browse tree. Stub. */
class LibraryTreeImpl(context: Context, api: MaApi, config: ConfigStore, scope: CoroutineScope) : LibraryTree {
    override fun root(): MediaItem = TODO()
    override suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> = TODO()
    override suspend fun item(mediaId: String): MediaItem? = TODO()
    override suspend fun search(query: String): List<MediaItem> = TODO()
    override suspend fun expandForPlayback(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition = TODO()
    override suspend fun resolve(mediaId: String): MediaItem? = TODO()
}
