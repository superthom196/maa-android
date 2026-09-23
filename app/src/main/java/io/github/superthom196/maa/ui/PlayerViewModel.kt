package io.github.superthom196.maa.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.superthom196.maa.R
import io.github.superthom196.maa.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NowPlaying(
    val connected: Boolean = false,
    /** Why the player service could not be reached, or the player's last error. */
    val error: String? = null,
    val hasItem: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val artworkUri: Uri? = null,
    /** Playing or about to (buffering with play requested): the button shows Pause. */
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
)

data class Progress(val positionMs: Long = 0, val durationMs: Long = 0)

/** One level of the phone-side browser. [id] is null for the root until getLibraryRoot answers. */
data class BrowseLevel(
    val key: Long,
    val id: String?,
    val title: String,
    /** The container itself (album/playlist) when it is playable, for the header "Play" button. */
    val item: MediaItem? = null,
    val items: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val nextPage: Int = 0,
    val endReached: Boolean = false,
)

/**
 * The phone UI's client of [PlaybackService], through the same MediaLibraryService interface
 * Android Auto uses: what works here works in the car. One [MediaBrowser] serves both the
 * now-playing card and the library browser; it is released with the ViewModel.
 *
 * MediaBrowser must be used on the thread it was built on; viewModelScope runs on Main.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _browse = MutableStateFlow<List<BrowseLevel>>(emptyList())
    val browse: StateFlow<List<BrowseLevel>> = _browse.asStateFlow()

    /** One-shot message for a snackbar (e.g. a play request the service refused). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Polled while someone is looking: Player has no position callback. */
    val progress: StateFlow<Progress> = flow {
        while (true) {
            val b = browser?.takeIf { it.isConnected }
            emit(if (b == null) Progress() else Progress(b.currentPosition, b.duration.takeIf { it != C.TIME_UNSET } ?: 0))
            delay(500)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), Progress())

    private var browser: MediaBrowser? = null
    private val connectLock = Mutex()
    private var nextKey = 0L

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        reconnect()
    }

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    /** The connected browser, (re)connecting when needed; throws when the service refuses. */
    private suspend fun connected(): MediaBrowser = connectLock.withLock {
        browser?.let { if (it.isConnected) return it else it.release() }
        browser = null
        val app = getApplication<Application>()
        val future = MediaBrowser.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
            .setListener(object : MediaBrowser.Listener {
                override fun onDisconnected(controller: MediaController) {
                    _nowPlaying.update { it.copy(connected = false) }
                }
            })
            .buildAsync()
        val b = try {
            future.await()
        } catch (e: CancellationException) {
            MediaController.releaseFuture(future)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cannot connect to the playback service", e)
            _nowPlaying.update { NowPlaying(error = str(R.string.player_unavailable, e.message ?: e.javaClass.simpleName)) }
            throw e
        }
        b.addListener(playerListener)
        browser = b
        refresh()
        b
    }

    fun reconnect() {
        viewModelScope.launch {
            try {
                connected()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // connected() already put the reason in nowPlaying.error.
            }
        }
    }

    private fun refresh() {
        val b = browser ?: return
        val md = b.mediaMetadata
        _nowPlaying.value = NowPlaying(
            connected = b.isConnected,
            error = b.playerError?.let { str(R.string.player_error, it.message ?: it.errorCodeName) },
            hasItem = b.currentMediaItem != null,
            title = md.title?.toString() ?: md.displayTitle?.toString(),
            artist = (md.artist ?: md.albumArtist ?: md.subtitle)?.toString(),
            artworkUri = md.artworkUri,
            playing = b.wantsToPlay(),
            buffering = b.playbackState == Player.STATE_BUFFERING,
            canPrevious = b.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            canNext = b.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT),
        )
    }

    // ------------------------------------------------------------------ transport

    fun playPause() = withBrowser { b ->
        if (b.wantsToPlay()) {
            b.pause()
        } else {
            if (b.playbackState == Player.STATE_IDLE) b.prepare()
            if (b.playbackState == Player.STATE_ENDED) b.seekToDefaultPosition()
            b.play()
        }
    }

    private fun Player.wantsToPlay() = playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE

    fun previous() = withBrowser { it.seekToPrevious() }
    fun next() = withBrowser { it.seekToNext() }

    /** Hands the item to the service as Android Auto would; the service expands a track to its album context. */
    fun play(item: MediaItem) = withBrowser { b ->
        b.setMediaItem(item)
        b.prepare()
        b.play()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun withBrowser(action: (MediaBrowser) -> Unit) {
        viewModelScope.launch {
            try {
                action(connected())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = str(R.string.player_unavailable, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ------------------------------------------------------------------ browse

    /** Starts at the library root (fresh each time the Browse screen is opened). */
    fun startBrowse() {
        _browse.value = listOf(BrowseLevel(key = nextKey++, id = null, title = str(R.string.browse_title)))
        loadMore()
    }

    /** Browsable → drill in; playable leaf (a track) → play it. */
    fun open(item: MediaItem) {
        val md = item.mediaMetadata
        if (md.isBrowsable == true) {
            val title = md.title?.toString() ?: str(R.string.browse_title)
            _browse.update { it + BrowseLevel(key = nextKey++, id = item.mediaId, title = title, item = item.takeIf { md.isPlayable == true }) }
            loadMore()
        } else if (md.isPlayable == true) {
            play(item)
        }
    }

    /** Pops one level; false at the root so the screen itself closes. */
    fun browseBack(): Boolean {
        if (_browse.value.size <= 1) return false
        _browse.update { it.dropLast(1) }
        return true
    }

    /** Loads the next page of the top level (also the Retry action). */
    fun loadMore() {
        val level = _browse.value.lastOrNull() ?: return
        if (level.loading || level.endReached) return
        updateLevel(level.key) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val b = connected()
                val id = level.id ?: run {
                    val root = b.getLibraryRoot(null).await()
                    root.value?.mediaId?.takeIf { root.resultCode == LibraryResult.RESULT_SUCCESS } ?: throw BrowseException(errorText(root))
                }
                val r = b.getChildren(id, level.nextPage, PAGE_SIZE, null).await()
                if (r.resultCode != LibraryResult.RESULT_SUCCESS) throw BrowseException(errorText(r))
                val page = r.value.orEmpty()
                updateLevel(level.key) { l ->
                    val known = l.items.mapTo(HashSet()) { it.mediaId }
                    val fresh = page.filter { known.add(it.mediaId) }
                    // A short page, or one that only repeats what we have (a tree that ignores paging), ends the list.
                    l.copy(id = id, items = l.items + fresh, loading = false, nextPage = l.nextPage + 1, endReached = page.size < PAGE_SIZE || fresh.isEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BrowseException) {
                updateLevel(level.key) { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                Log.w(TAG, "browse ${level.id} failed", e)
                updateLevel(level.key) { it.copy(loading = false, error = str(R.string.player_unavailable, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** Updates the level with [key] if it is still on the stack (the user may have gone back meanwhile). */
    private fun updateLevel(key: Long, transform: (BrowseLevel) -> BrowseLevel) =
        _browse.update { levels -> levels.map { if (it.key == key) transform(it) else it } }

    /** The service's own words when it gave any (e.g. "Sign in on phone"), else a generic text per code. */
    @OptIn(UnstableApi::class)
    private fun errorText(r: LibraryResult<*>): String {
        r.sessionError?.message?.takeIf { it.isNotBlank() }?.let { return it }
        return when (r.resultCode) {
            LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
            LibraryResult.RESULT_ERROR_SESSION_SETUP_REQUIRED -> str(R.string.browse_error_sign_in)
            LibraryResult.RESULT_ERROR_IO -> str(R.string.browse_error_offline)
            LibraryResult.RESULT_ERROR_SESSION_DISCONNECTED -> str(R.string.browse_error_disconnected)
            else -> str(R.string.browse_error_generic, r.resultCode)
        }
    }

    override fun onCleared() {
        browser?.run {
            removeListener(playerListener)
            release()
        }
        browser = null
    }

    private class BrowseException(message: String) : Exception(message)

    private companion object {
        const val TAG = "MaaPlayerUi"
        const val PAGE_SIZE = 100
    }
}
