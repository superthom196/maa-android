package io.github.superthom196.maa.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.superthom196.maa.art.ArtUris
import io.github.superthom196.maa.browse.LibraryTree
import io.github.superthom196.maa.data.NotLoggedInException
import io.github.superthom196.maa.data.OfflineException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext

/**
 * The Android Auto (and phone UI) side of the session: browsing and search are delegated to the
 * [LibraryTree], play requests are expanded by it into full queues, and "resume" comes from the
 * [QueueStore]. Runs on the main thread ([scope] is the service's); the tree does its own IO.
 *
 * Errors: signed out → AUTHENTICATION_EXPIRED with Auto's resolution extras (a "Sign in on phone"
 * button that opens the app); offline → an IO error saying so. The root itself is always granted:
 * refusing it leaves Auto on a loading screen forever.
 */
@OptIn(UnstableApi::class)
class SessionCallback(
    private val context: Context,
    private val library: LibraryTree,
    private val queueStore: QueueStore,
    private val signIn: PendingIntent,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    /** Last few searches, so Auto's paged result requests do not search again. Main thread only. */
    private val searches = object : LinkedHashMap<String, List<MediaItem>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) = size > 8
    }

    /**
     * Media3 1.11 gives untrusted controllers read-only access by default. Android Auto and System
     * UI are trusted (system / notification listener); the car packages are listed as well so a
     * device where that check fails still gets transport controls. Others stay read-only.
     */
    override fun onConnectAsync(session: MediaSession, controller: ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        if (isTrusted(controller)) {
            grantArt(controller)
            builder
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(SHUFFLE).add(REPEAT).build(),
                )
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setMediaButtonPreferences(buttons(session.player))
        }
        return Futures.immediateFuture(builder.build())
    }

    override fun onCustomCommand(session: MediaSession, controller: ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        val player = session.player
        when (customCommand.customAction) {
            SHUFFLE.customAction -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            REPEAT.customAction -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        if (isTrusted(browser)) grantArt(browser)
        val root = runCatching { library.root() }.getOrElse {
            Log.w(TAG, "library root failed, using a bare one", it)
            MediaItem.Builder().setMediaId("root")
                .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
                .build()
        }
        val extras = Bundle().apply {
            // Media3 sets this from the available commands too; stated here so it is not forgotten.
            putBoolean(SEARCH_SUPPORTED, true)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = guarded("children of $parentId") {
        LibraryResult.ofItemList(library.children(parentId, page, pageSize), params)
    }

    override fun onGetItem(session: MediaLibrarySession, browser: ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        guarded("item $mediaId") {
            library.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "Not found"))
        }

    override fun onSearch(session: MediaLibrarySession, browser: ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
        guarded("search") {
            val results = library.search(query)
            searches[query] = results
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = guarded("search results") {
        val results = searches[query] ?: library.search(query).also { searches[query] = it }
        LibraryResult.ofItemList(results.drop(page * pageSize).take(pageSize), params)
    }

    override fun onAddMediaItems(mediaSession: MediaSession, controller: ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
        scope.future { library.expandForPlayback(mediaItems, 0, C.TIME_UNSET).mediaItems }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future { library.expandForPlayback(mediaItems, startIndex, startPositionMs) }

    override fun onPlaybackResumption(mediaSession: MediaSession, controller: ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future {
            val restored = withContext(Dispatchers.IO) { queueStore.restore() }
                ?: throw UnsupportedOperationException("No stored queue to resume")
            if (isForPlayback) {
                mediaSession.player.shuffleModeEnabled = restored.shuffle
                mediaSession.player.repeatMode = restored.repeat
            }
            Log.i(TAG, "resuming ${restored.items.size} items at ${restored.startIndex} (${restored.positionMs / 1000} s)")
            MediaItemsWithStartPosition(restored.items, restored.startIndex, restored.positionMs)
        }

    /** Shuffle and repeat toggles: the two custom buttons Auto and the notification have room for. */
    fun buttons(player: Player): ImmutableList<CommandButton> {
        val shuffle = CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (player.shuffleModeEnabled) "Shuffle off" else "Shuffle")
            .setSessionCommand(SHUFFLE)
            .build()
        val repeatIcon = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            else -> CommandButton.ICON_REPEAT_OFF
        }
        val repeat = CommandButton.Builder(repeatIcon)
            .setDisplayName("Repeat")
            .setSessionCommand(REPEAT)
            .build()
        return ImmutableList.of(shuffle, repeat)
    }

    /**
     * Artwork is served by our ContentProvider (Auto cannot send the Bearer token), which is not
     * exported: read access is granted per controller, and only to trusted ones.
     */
    private fun grantArt(controller: ControllerInfo) {
        val pkg = controller.packageName
        runCatching {
            context.grantUriPermission(pkg, ART_ROOT, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "could not grant artwork access to $pkg: ${it.message}") }
    }

    private fun isTrusted(controller: ControllerInfo): Boolean =
        controller.isTrusted || controller.packageName in CAR_PACKAGES || controller.packageName == context.packageName

    private fun <T : Any> guarded(what: String, block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = scope.future {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(what, e)
        }
    }

    private fun <T : Any> errorResult(what: String, e: Exception): LibraryResult<T> {
        val error = when (e) {
            is NotLoggedInException -> {
                Log.i(TAG, "$what: not signed in")
                signInError()
            }
            is OfflineException -> {
                Log.i(TAG, "$what: offline")
                SessionError(SessionError.ERROR_IO, "Can't reach Music Assistant")
            }
            else -> {
                Log.w(TAG, "$what failed", e)
                SessionError(SessionError.ERROR_UNKNOWN, e.message ?: "Something went wrong")
            }
        }
        return LibraryResult.ofError<T>(error)
    }

    private fun signInError(): SessionError {
        val extras = Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, "Sign in on phone")
            putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, signIn)
        }
        return SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, "Sign in to Music Assistant on your phone", extras)
    }

    companion object {
        private const val TAG = "MAA/Session"

        /** `MediaBrowserService` root extra; the Media3 constant for it is internal (legacy package). */
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"

        val SHUFFLE = SessionCommand("io.github.superthom196.maa.SHUFFLE", Bundle.EMPTY)
        val REPEAT = SessionCommand("io.github.superthom196.maa.REPEAT", Bundle.EMPTY)

        val ART_ROOT: Uri = Uri.parse("content://${ArtUris.AUTHORITY}/")

        /** Android Auto, its car services, and System UI (media controls / resumption card). */
        private val CAR_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.gms.car",
            "com.android.systemui",
        )
    }
}
