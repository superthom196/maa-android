package io.github.superthom196.maa.playback

import android.content.Context
import io.github.superthom196.maa.data.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** WS-C: owns the on-phone SimpleCache and the prefetch status. Stub. */
class AudioCache(context: Context, config: ConfigStore, scope: CoroutineScope) : CacheControl {
    override val usedBytes: StateFlow<Long> get() = TODO()
    override val prefetch: StateFlow<PrefetchStatus> get() = TODO()
    override suspend fun clear(): Unit = TODO()
}
