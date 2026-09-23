package io.github.superthom196.maa.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** WS-B: DataStore-backed [ConfigStore]. Stub. */
class Prefs(context: Context) : ConfigStore {
    override val server: StateFlow<ServerConfig?> get() = TODO()
    override val settings: StateFlow<PlaybackSettings> get() = TODO()
    override suspend fun saveServer(cfg: ServerConfig): Unit = TODO()
    override suspend fun updateServer(transform: (ServerConfig) -> ServerConfig): Unit = TODO()
    override suspend fun saveSettings(s: PlaybackSettings): Unit = TODO()
    override suspend fun signOut(): Unit = TODO()
}
