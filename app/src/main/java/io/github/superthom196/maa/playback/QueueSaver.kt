package io.github.superthom196.maa.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Writes the player's queue to the [QueueStore]: on track change, pause, queue edits, shuffle and
 * repeat, and every 15 s while playing (so a killed process loses at most that much position).
 * Snapshots are taken on the main thread (the player lives there), written on IO, debounced.
 *
 * An empty queue is never written, so clearing the player (e.g. dismissing the notification)
 * still leaves the last queue for "resume".
 */
@OptIn(FlowPreview::class)
class QueueSaver(private val player: Player, private val store: QueueStore, private val scope: CoroutineScope) : Player.Listener {
    private val pending = MutableStateFlow<StoredQueue?>(null)
    private var items: List<StoredItem>? = null
    private var ticker: Job? = null

    fun start() {
        player.addListener(this)
        scope.launch(Dispatchers.IO) {
            pending.filterNotNull().debounce(DEBOUNCE_MS).collect { store.write(it) }
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_TIMELINE_CHANGED)) items = null
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
            )
        ) save()
        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
            ticker?.cancel()
            if (player.isPlaying) {
                ticker = scope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        save()
                    }
                }
            }
        }
    }

    /** Main thread. */
    fun save() {
        snapshot()?.let { pending.value = it }
    }

    /** Main thread, synchronous: for onDestroy, when the debounced writer is about to be cancelled. */
    fun flush() {
        snapshot()?.let(store::write)
    }

    fun release() {
        ticker?.cancel()
        player.removeListener(this)
    }

    private fun snapshot(): StoredQueue? {
        if (player.mediaItemCount == 0) return null
        val list = items ?: (0 until player.mediaItemCount)
            .mapNotNull { QueueStore.fromMediaItem(player.getMediaItemAt(it)) }
            .also { items = it }
        if (list.isEmpty()) return null
        return StoredQueue(
            items = list,
            // Items without a URI are dropped above; then re-find the current one by id.
            currentIndex = if (list.size == player.mediaItemCount) player.currentMediaItemIndex
            else list.indexOfFirst { it.mediaId == player.currentMediaItem?.mediaId }.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
        )
    }

    private companion object {
        const val TICK_MS = 15_000L
        const val DEBOUNCE_MS = 500L
    }
}
