package io.github.superthom196.maa.playback

import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/** WS-C: the MediaLibraryService Android Auto binds to. Stub. */
class PlaybackService : MediaLibraryService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = null
}
