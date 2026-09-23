package io.github.superthom196.maa.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import io.github.superthom196.maa.AppGraph
import io.github.superthom196.maa.ui.MainActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * The MediaLibraryService Android Auto binds to (and the phone UI's MediaController), playing
 * locally on the phone with ExoPlayer. Media3's default notification provider runs the media
 * notification and the foreground state.
 *
 * Owns the player and everything tied to its lifetime: the [Prefetcher] (the offline guarantee),
 * [PlaybackRecovery] (player errors) and [QueueSaver] (resume). All of them are main-thread.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var prefetcher: Prefetcher
    private lateinit var recovery: PlaybackRecovery
    private lateinit var saver: QueueSaver

    override fun onCreate() {
        super.onCreate()
        val cache = AppGraph.audioCache
        val sources = MaaDataSources(AppGraph.http, AppGraph.baseUrl, AppGraph.config, cache)
        player = PlayerFactory.create(this, sources, RetryingLoadErrorPolicy { AppGraph.baseUrl.reportFailure() })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val queueStore = QueueStore(File(filesDir, "queue.json"))
        val callback = SessionCallback(this, AppGraph.library, queueStore, openApp, scope)

        prefetcher = Prefetcher(player, cache, sources, AppGraph.config, AppGraph.network, AppGraph.api, scope).also { it.start() }
        saver = QueueSaver(player, queueStore, scope).also { it.start() }
        val tokenChanges = AppGraph.config.server.map { it?.token }.distinctUntilChanged().drop(1).map { }
        recovery = PlaybackRecovery(player, AppGraph.network, cache, openApp, tokenChanges, scope)

        val built = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(openApp)
            .build()
        session = built
        recovery.session = built
        recovery.start()

        // Keep the shuffle / repeat buttons showing the current state.
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                    built.setMediaButtonPreferences(callback.buttons(player))
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while music plays in the car must not stop it, and "plays" includes
        // BUFFERING through a dead zone (the stock check, isPlaying, would stop there).
        if (!isPlaybackOngoing || !player.playWhenReady || player.mediaItemCount == 0) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        saver.flush()
        saver.release()
        recovery.release()
        prefetcher.release()
        session?.release()
        session = null
        player.release()
        scope.cancel()
        runCatching { revokeUriPermission(SessionCallback.ART_ROOT, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        super.onDestroy()
    }
}
