package io.github.superthom196.maa.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "Prefs"
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "maa")

/**
 * [ConfigStore] on a DataStore file, each value one JSON string: the models are @Serializable
 * already, and a field added later decodes with its default instead of needing a new key.
 *
 * The first read blocks: AppGraph is lazy and may first be touched from Android Auto's binder
 * thread, which must see the saved server immediately rather than a signed-out placeholder.
 */
class Prefs(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ConfigStore {
    private object K {
        val server = stringPreferencesKey("server")
        val settings = stringPreferencesKey("settings")
    }

    private val store = context.applicationContext.dataStore
    private val data = store.data.catch { e ->
        // A corrupt file must not take the app down; the user signs in again instead.
        Log.w(TAG, "preferences unreadable: ${e.message}")
        emit(emptyPreferences())
    }
    /** Serialises read-modify-write so two quick edits never lose one another. */
    private val writes = Mutex()

    private val _server: MutableStateFlow<ServerConfig?>
    private val _settings: MutableStateFlow<PlaybackSettings>
    override val server: StateFlow<ServerConfig?>
    override val settings: StateFlow<PlaybackSettings>

    init {
        val initial = runBlocking(Dispatchers.IO) { data.first() }
        _server = MutableStateFlow(decodeServer(initial[K.server]))
        _settings = MutableStateFlow(decodeSettings(initial[K.settings]))
        server = _server.asStateFlow()
        settings = _settings.asStateFlow()
        scope.launch {
            data.collect { p ->
                _server.value = decodeServer(p[K.server])
                _settings.value = decodeSettings(p[K.settings])
            }
        }
    }

    override suspend fun saveServer(cfg: ServerConfig) = writes.withLock { writeServer(cfg) }

    override suspend fun updateServer(transform: (ServerConfig) -> ServerConfig) = writes.withLock {
        val cur = _server.value ?: return@withLock
        writeServer(transform(cur))
    }

    override suspend fun saveSettings(s: PlaybackSettings) = writes.withLock {
        val clamped = clamp(s)
        _settings.value = clamped
        store.edit { it[K.settings] = maJson.encodeToString(PlaybackSettings.serializer(), clamped) }
        Unit
    }

    override suspend fun signOut() = writes.withLock {
        _server.value = null
        store.edit { it.remove(K.server) }
        Unit
    }

    private suspend fun writeServer(cfg: ServerConfig) {
        _server.value = cfg
        store.edit { it[K.server] = maJson.encodeToString(ServerConfig.serializer(), cfg) }
    }

    companion object {
        const val MIN_CACHE_MB = 64
        const val MAX_CACHE_MB = 4096

        fun clamp(s: PlaybackSettings): PlaybackSettings =
            s.copy(lookahead = s.lookahead.coerceIn(1, 10), cacheMb = s.cacheMb.coerceIn(MIN_CACHE_MB, MAX_CACHE_MB))

        internal fun decodeServer(raw: String?): ServerConfig? = raw?.let {
            runCatching { maJson.decodeFromString(ServerConfig.serializer(), it) }.getOrNull()
        }

        internal fun decodeSettings(raw: String?): PlaybackSettings = clamp(
            raw?.let { runCatching { maJson.decodeFromString(PlaybackSettings.serializer(), it) }.getOrNull() }
                ?: PlaybackSettings(),
        )
    }
}
