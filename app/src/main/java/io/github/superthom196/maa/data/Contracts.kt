package io.github.superthom196.maa.data

import io.github.superthom196.maa.playback.TrackRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Shared contracts between the workstreams. The lead owns this file: signatures change only
 * through the lead. Implementations live next to it in data/ (Prefs, HttpMaApi, Reachability,
 * AndroidNetworkMonitor, LanDiscovery, AuthInterceptor).
 */

/** The one server this app is signed in to. Both URLs point at the same `serverId`. */
@Serializable
data class ServerConfig(
    val serverId: String,
    val name: String,
    /** e.g. http://192.168.0.150:8095, from discovery or typed. */
    val lanUrl: String?,
    /** e.g. http://100.97.96.36:8095 (Tailscale), used when the LAN URL does not answer. */
    val remoteUrl: String?,
    /** Long-lived Music Assistant token. */
    val token: String,
    val username: String? = null,
    /** Format name the app requests from the MAA plugin, e.g. "opus-192" or "flac-16-44". */
    val format: String = DEFAULT_FORMAT,
    /** Whether the server's MAA plugin answered /maa/info last time we asked. */
    val pluginSeen: Boolean = false,
) {
    val urls: List<String> get() = listOfNotNull(lanUrl, remoteUrl).distinct()

    companion object {
        const val DEFAULT_FORMAT = "opus-192"
    }
}

/** Phone-side playback settings (Settings screen). */
@Serializable
data class PlaybackSettings(
    /** Tracks after the current one to download in full. The current track is always fetched too. */
    val lookahead: Int = 3,
    /** Upper bound for the on-phone audio cache. */
    val cacheMb: Int = 256,
)

/** Persistent configuration. StateFlows are populated before the constructor returns. */
interface ConfigStore {
    val server: StateFlow<ServerConfig?>
    val settings: StateFlow<PlaybackSettings>
    suspend fun saveServer(cfg: ServerConfig)
    suspend fun updateServer(transform: (ServerConfig) -> ServerConfig)
    suspend fun saveSettings(s: PlaybackSettings)
    /** Forget the token (keeps nothing about the server). */
    suspend fun signOut()
}

/** Which of [ServerConfig.urls] currently reaches the server. */
interface BaseUrlProvider {
    /** The reachable base URL without trailing slash, or null when none is (offline). */
    val current: StateFlow<String?>
    /** Returns [current] if set, else probes and waits up to [timeoutMs]. */
    suspend fun awaitBase(timeoutMs: Long = 5_000): String?
    /** A request against [current] failed at the network level: re-probe soon. */
    fun reportFailure()
}

interface NetworkMonitor {
    /** True when the device has a validated internet-capable network. */
    val online: StateFlow<Boolean>
    /** Emits whenever the default network changes (Wi-Fi <-> mobile, lost, regained). */
    val changes: Flow<Unit>
}

data class DiscoveredServer(val baseUrl: String, val info: ServerInfo, val via: String)

interface ServerDiscovery {
    /** Finds servers on the LAN (mDNS `_mass._tcp`, subnet sweep, [knownHosts]) for ~15 s. */
    fun discover(knownHosts: List<String> = emptyList()): Flow<DiscoveredServer>
}

@Serializable
data class MaaCacheStats(val files: Int = 0, val bytes: Long = 0, @SerialName("max_bytes") val maxBytes: Long = 0)

/** GET /maa/info, see docs/PROTOCOL.md. */
@Serializable
data class MaaInfo(
    val plugin: String,
    val version: String,
    val api: Int,
    @SerialName("server_id") val serverId: String? = null,
    val format: String,
    val formats: List<String> = emptyList(),
    val cache: MaaCacheStats? = null,
)

data class SearchResults(
    val artists: List<MediaItem> = emptyList(),
    val albums: List<MediaItem> = emptyList(),
    val tracks: List<MediaItem> = emptyList(),
    val playlists: List<MediaItem> = emptyList(),
)

/**
 * Music Assistant over its stateless HTTP API (`POST {base}/api`, Bearer token), always against
 * [BaseUrlProvider.current]. Throws [OfflineException] when no base URL answers,
 * [NotLoggedInException] without a token or on 401, [MaApiException] for server-side errors.
 */
interface MaApi {
    suspend fun call(command: String, args: Map<String, Any?> = emptyMap(), timeoutMs: Long = 15_000): JsonElement

    suspend fun libraryItems(kind: String, offset: Int = 0, limit: Int = 500, orderBy: String? = null, favorite: Boolean? = null): List<MediaItem>
    suspend fun artistAlbums(provider: String, itemId: String): List<MediaItem>
    suspend fun albumTracks(provider: String, itemId: String): List<MediaItem>
    suspend fun playlistTracks(provider: String, itemId: String): List<MediaItem>
    suspend fun recentlyPlayed(limit: Int = 30): List<MediaItem>
    suspend fun search(query: String, limit: Int = 20): SearchResults

    /** GET /maa/info; throws [MaApiException] with code 404 when the plugin is not installed/enabled. */
    suspend fun maaInfo(): MaaInfo
    /** POST /maa/prepare: ask the server to transcode these ahead. Best effort, never throws. */
    suspend fun prepare(tracks: List<TrackRef>, format: String)

    // Unauthenticated / explicit-base calls used by the sign-in flow.
    suspend fun fetchInfo(base: String, timeoutMs: Long = 2_500): ServerInfo
    /** POST {base}/auth/login -> session token. */
    suspend fun login(base: String, username: String, password: String): String
    /** auth/token/create with [sessionToken] -> long-lived token. */
    suspend fun createLongLivedToken(base: String, sessionToken: String, name: String): String
}

class OfflineException(message: String = "Server not reachable") : Exception(message)
class NotLoggedInException(message: String = "Not signed in") : Exception(message)
class MaApiException(val code: Int, val error: String?, message: String) : Exception(message)
