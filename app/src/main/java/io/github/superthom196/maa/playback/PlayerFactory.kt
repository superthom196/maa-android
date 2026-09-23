package io.github.superthom196.maa.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** The one ExoPlayer the service owns. */
@OptIn(UnstableApi::class)
object PlayerFactory {
    fun create(context: Context, sources: MaaDataSources, policy: LoadErrorHandlingPolicy): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources.player).setLoadErrorHandlingPolicy(policy))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps Wi-Fi (and the CPU) awake while streaming with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // ExoPlayer 1.11 fails playback after 10 min of BUFFERING without progress. Waiting
            // out a long dead zone is the whole point here, so never call that "stuck".
            .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE)
            .build()
}
