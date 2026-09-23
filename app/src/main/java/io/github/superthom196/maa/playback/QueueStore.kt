package io.github.superthom196.maa.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One queue entry, with everything needed to play it again without asking the server. */
@Serializable
data class StoredItem(
    val mediaId: String,
    val uri: String,
    val cacheKey: String? = null,
    val mimeType: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: String? = null,
    val durationMs: Long? = null,
)

@Serializable
data class StoredQueue(
    val items: List<StoredItem>,
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
)

/** A stored queue turned back into player items. */
class RestoredQueue(val items: List<MediaItem>, val startIndex: Int, val positionMs: Long, val shuffle: Boolean, val repeat: Int)

/**
 * The play queue on disk, so a restarted service (or Android Auto's "resume") picks up where it
 * left off, and does so without a network: items are rebuilt from the stored URI and cache key,
 * so a queue whose tracks are cached plays in a tunnel. A plain JSON file, written atomically.
 */
class QueueStore(private val file: File) {

    fun write(queue: StoredQueue) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(queue))
            if (!tmp.renameTo(file)) error("rename failed")
        }.onFailure { Log.w(TAG, "saving the queue failed: ${it.message}") }
    }

    fun read(): StoredQueue? = runCatching { if (file.exists()) decode(file.readText()) else null }.getOrNull()

    fun restore(): RestoredQueue? {
        val q = read() ?: return null
        val items = q.items.map(::toMediaItem)
        return RestoredQueue(items, q.currentIndex, q.positionMs, q.shuffle, q.repeat)
    }

    companion object {
        private const val TAG = "MAA/Queue"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun encode(queue: StoredQueue): String = json.encodeToString(StoredQueue.serializer(), queue)

        /** Null for anything unusable, including an empty queue; the index is clamped into range. */
        fun decode(text: String): StoredQueue? {
            val q = runCatching { json.decodeFromString(StoredQueue.serializer(), text) }.getOrNull() ?: return null
            if (q.items.isEmpty()) return null
            return q.copy(currentIndex = q.currentIndex.coerceIn(0, q.items.lastIndex), positionMs = q.positionMs.coerceAtLeast(0))
        }

        fun toMediaItem(s: StoredItem): MediaItem = MediaItem.Builder()
            .setMediaId(s.mediaId)
            .setUri(s.uri)
            .setCustomCacheKey(s.cacheKey)
            .setMimeType(s.mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist(s.artist)
                    .setAlbumTitle(s.album)
                    .setArtworkUri(s.artworkUri?.let(Uri::parse))
                    .setDurationMs(s.durationMs)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

        /** Null for items that cannot be replayed (no URI). */
        fun fromMediaItem(item: MediaItem): StoredItem? {
            val local = item.localConfiguration ?: return null
            val m = item.mediaMetadata
            return StoredItem(
                mediaId = item.mediaId,
                uri = local.uri.toString(),
                cacheKey = local.customCacheKey,
                mimeType = local.mimeType,
                title = m.title?.toString(),
                artist = m.artist?.toString(),
                album = m.albumTitle?.toString(),
                artworkUri = m.artworkUri?.toString(),
                durationMs = m.durationMs,
            )
        }
    }
}
