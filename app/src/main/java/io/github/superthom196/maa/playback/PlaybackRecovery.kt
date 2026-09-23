package io.github.superthom196.maa.playback

import android.app.PendingIntent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import io.github.superthom196.maa.data.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What to do when the player gives up on an item. Network trouble never gets here (the
 * [RetryingLoadErrorPolicy] waits it out in BUFFERING); what does is an answer that retrying
 * cannot fix, a damaged file, or something unexpected.
 */
enum class Recovery { SKIP, PAUSE, SIGN_IN, DROP_CACHE_AND_RETRY, RETRY_WHEN_ONLINE }

object PlayerErrors {
    const val MAX_CONSECUTIVE_SKIPS = 3
    private const val MAX_RETRIES_PER_ITEM = 3

    /**
     * @param status HTTP status found in the error's cause chain, if any.
     * @param errorCode the [PlaybackException] error code.
     * @param attemptsOnItem earlier recoveries of the current item (0 on the first error).
     * @param consecutiveSkips skips in a row without anything reaching READY in between.
     */
    fun decide(status: Int?, errorCode: Int, attemptsOnItem: Int, consecutiveSkips: Int): Recovery {
        val skip = if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) Recovery.PAUSE else Recovery.SKIP
        return when {
            status == 401 || status == 403 -> Recovery.SIGN_IN
            // 416: a partial file in the cache no longer matches the server's (re-transcoded) one.
            status == 416 -> if (attemptsOnItem == 0) Recovery.DROP_CACHE_AND_RETRY else skip
            status != null && status in 400..499 -> skip
            // 3xxx parsing, 4xxx decoding: most likely a damaged cached file; fetch it again once.
            errorCode in 3000..4999 -> if (attemptsOnItem == 0) Recovery.DROP_CACHE_AND_RETRY else skip
            attemptsOnItem >= MAX_RETRIES_PER_ITEM -> skip
            else -> Recovery.RETRY_WHEN_ONLINE
        }
    }

    fun httpStatus(error: Throwable?): Int? {
        var e = error
        while (e != null) {
            if (e is HttpDataSource.InvalidResponseCodeException) return e.responseCode
            e = e.cause
        }
        return null
    }
}

/**
 * Applies [PlayerErrors.decide] to the player. Main thread only, like the player.
 *
 * A 401/403 pauses and puts a "sign in" error with a resolution intent on the session, which
 * Android Auto shows with a button that opens the phone app. Media3 strips the transport commands
 * while such a custom error is set, so it is cleared as soon as the token changes (signed in
 * again) or something plays. Every other error leaves the player in its own IDLE state, where
 * Auto keeps play/skip available to retry.
 */
@OptIn(UnstableApi::class)
class PlaybackRecovery(
    private val player: Player,
    private val network: NetworkMonitor,
    private val audioCache: AudioCache,
    private val signIn: PendingIntent,
    /** Emits when the stored token changes. */
    private val tokenChanges: Flow<Unit>,
    private val scope: CoroutineScope,
) : Player.Listener {
    var session: MediaSession? = null

    private var consecutiveSkips = 0
    private var attemptsOnItem = 0
    private var waiting: Job? = null
    private var authErrorShown = false

    fun start() {
        player.addListener(this)
        scope.launch {
            tokenChanges.collect {
                if (authErrorShown) {
                    Log.i(TAG, "token changed: clearing the sign-in error")
                    clearAuthError()
                    if (player.playerError != null) player.prepare()
                }
            }
        }
    }

    fun release() {
        player.removeListener(this)
        waiting?.cancel()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        attemptsOnItem = 0
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            consecutiveSkips = 0
            clearAuthError()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val status = PlayerErrors.httpStatus(error)
        val action = PlayerErrors.decide(status, error.errorCode, attemptsOnItem, consecutiveSkips)
        Log.w(TAG, "player error ${error.errorCodeName} (http ${status ?: "-"}), attempt $attemptsOnItem: $action", error)
        attemptsOnItem++
        waiting?.cancel()
        when (action) {
            Recovery.SKIP -> {
                consecutiveSkips++
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                } else {
                    player.pause()
                }
            }
            Recovery.PAUSE -> {
                player.pause()
                status("Paused: tracks unavailable")
            }
            Recovery.SIGN_IN -> {
                player.pause()
                status("Sign in again on the phone")
                val extras = Bundle().apply {
                    putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, "Sign in on phone")
                    putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, signIn)
                }
                authErrorShown = true
                session?.setPlaybackException(
                    PlaybackException("Sign in again on the phone", null, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED, extras),
                )
            }
            Recovery.DROP_CACHE_AND_RETRY -> {
                val key = player.currentMediaItem?.localConfiguration?.customCacheKey
                waiting = scope.launch {
                    if (key != null) withContext(Dispatchers.IO) { audioCache.remove(key) }
                    player.prepare()
                }
            }
            Recovery.RETRY_WHEN_ONLINE -> {
                waiting = scope.launch {
                    network.online.first { it }
                    delay(1_000)
                    if (player.playerError != null) player.prepare()
                }
            }
        }
    }

    private fun clearAuthError() {
        if (!authErrorShown) return
        authErrorShown = false
        session?.setPlaybackException(null)
    }

    private fun status(message: String) {
        audioCache.prefetchState.value = audioCache.prefetchState.value.copy(message = message)
    }

    private companion object {
        const val TAG = "MAA/Playback"
    }
}
