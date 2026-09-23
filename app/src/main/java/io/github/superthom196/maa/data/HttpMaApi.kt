package io.github.superthom196.maa.data

import android.util.Log
import io.github.superthom196.maa.playback.TrackRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "HttpMaApi"
private val JSON = "application/json".toMediaType()

/**
 * [MaApi] over Music Assistant's stateless `POST {base}/api`. No websocket: the car needs
 * request/response only, and a socket that has to be re-established after every dead zone buys
 * nothing here. Wire details and error shapes are in [MaWire].
 *
 * Error contract: no saved server/token → [NotLoggedInException]; no base URL answers →
 * [OfflineException]; a network failure mid-call → [BaseUrlProvider.reportFailure] then
 * [OfflineException] (cause kept); HTTP 401 → [NotLoggedInException]; any other non-2xx →
 * [MaApiException] with the HTTP status as `code`.
 */
class HttpMaApi(
    private val http: OkHttpClient,
    private val base: BaseUrlProvider,
    private val config: ConfigStore,
) : MaApi {
    private val nextId = AtomicLong(1)

    override suspend fun call(command: String, args: Map<String, Any?>, timeoutMs: Long): JsonElement {
        val body = MaWire.commandBody(command, args, "maa-${nextId.getAndIncrement()}")
        val (code, text) = authed(timeoutMs) { b -> Request.Builder().url("$b/api").post(body.toRequestBody(JSON)) }
        if (code !in 200..299) throw MaWire.httpError(code, text, command)
        return withContext(Dispatchers.Default) { MaWire.parseResult(text) }
    }

    override suspend fun libraryItems(kind: String, offset: Int, limit: Int, orderBy: String?, favorite: Boolean?): List<MediaItem> {
        val args = buildMap<String, Any?> {
            put("offset", offset)
            put("limit", limit)
            if (orderBy != null) put("order_by", orderBy)
            if (favorite != null) put("favorite", favorite)
            // Without it the list includes every track artist of every compilation.
            if (kind == "artists") put("album_artists_only", true)
        }
        return items(call("music/$kind/library_items", args), kind)
    }

    /** A library artist's answer is already limited to in-library albums (the command has no in_library_only). */
    override suspend fun artistAlbums(provider: String, itemId: String): List<MediaItem> =
        items(call("music/artists/artist_albums", mapOf("item_id" to itemId, "provider_instance_id_or_domain" to provider)), "album")

    override suspend fun albumTracks(provider: String, itemId: String): List<MediaItem> =
        items(
            call(
                "music/albums/album_tracks",
                mapOf("item_id" to itemId, "provider_instance_id_or_domain" to provider, "in_library_only" to true),
            ),
            "track",
        )

    /** Not paged: the command is an async generator the HTTP handler drains into one array. */
    override suspend fun playlistTracks(provider: String, itemId: String): List<MediaItem> =
        items(
            call(
                "music/playlists/playlist_tracks",
                mapOf("item_id" to itemId, "provider_instance_id_or_domain" to provider),
                timeoutMs = 30_000,
            ),
            "track",
        )

    /** The same filter MA's own "Recently played" home row uses, narrowed to what the car can open. */
    override suspend fun recentlyPlayed(limit: Int): List<MediaItem> {
        val el = call(
            "music/recently_played_items",
            mapOf("limit" to limit, "media_types" to listOf("album", "playlist"), "user_initiated_only" to true),
        )
        return items(el, "recent item").filter { it.mediaType == "album" || it.mediaType == "playlist" }
    }

    override suspend fun search(query: String, limit: Int): SearchResults {
        val el = call(
            "music/search",
            mapOf(
                "search_query" to query,
                "media_types" to listOf("artist", "album", "track", "playlist"),
                "limit" to limit,
                // The library only: other providers are slow to search and not what the car browses.
                "providers" to listOf("library"),
            ),
        )
        return withContext(Dispatchers.Default) { MaWire.parseSearch(el) { Log.w(TAG, "bad search item: ${it.message}") } }
    }

    override suspend fun maaInfo(): MaaInfo {
        val (code, text) = authed(10_000) { b -> Request.Builder().url("$b/maa/info").get() }
        if (code !in 200..299) throw MaWire.httpError(code, text, "maa/info")
        return runCatching { maJson.decodeFromString(MaaInfo.serializer(), text) }
            .getOrElse { throw MaApiException(code, "bad_response", "maa/info: unexpected answer (${it.message})") }
    }

    override suspend fun prepare(tracks: List<TrackRef>, format: String) {
        for (body in MaWire.prepareBodies(tracks, format)) {
            try {
                val (code, text) = authed(10_000) { b -> Request.Builder().url("$b/maa/prepare").post(body.toRequestBody(JSON)) }
                if (code !in 200..299) Log.w(TAG, "prepare: HTTP $code ${text.take(120)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "prepare failed: ${e.message}")
            }
        }
    }

    override suspend fun fetchInfo(base: String, timeoutMs: Long): ServerInfo {
        val req = Request.Builder().url("${base.trimEnd('/')}/info").get().build()
        val (code, text) = exchange(req, timeoutMs, base)
        if (code !in 200..299) throw MaApiException(code, null, "HTTP $code from $base/info")
        return runCatching { maJson.decodeFromString(ServerInfo.serializer(), text) }
            .getOrElse { throw MaApiException(code, "not_music_assistant", "No Music Assistant server at $base") }
    }

    override suspend fun login(base: String, username: String, password: String): String {
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/auth/login")
            .post(MaWire.loginBody(username, password).toRequestBody(JSON))
            .build()
        val (code, text) = exchange(req, 15_000, base)
        return MaWire.parseLogin(code, text).getOrThrow()
    }

    override suspend fun createLongLivedToken(base: String, sessionToken: String, name: String): String {
        val body = MaWire.commandBody("auth/token/create", mapOf("name" to name), "maa-${nextId.getAndIncrement()}")
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/api")
            // Explicit: this base may not be in the saved config yet, and the interceptor never overrides it.
            .header("Authorization", "Bearer $sessionToken")
            .post(body.toRequestBody(JSON))
            .build()
        val (code, text) = exchange(req, 15_000, base)
        if (code !in 200..299) throw MaWire.httpError(code, text, "auth/token/create")
        return MaWire.parseToken(MaWire.parseResult(text))
            ?: throw MaApiException(code, "no_token", "auth/token/create returned no token")
    }

    /**
     * One request against the current base URL with the saved token. The header is set here as
     * well as by [AuthInterceptor], so a call never depends on the interceptor's host matching.
     */
    private suspend fun authed(timeoutMs: Long, build: (String) -> Request.Builder): Pair<Int, String> {
        val cfg = config.server.value ?: throw NotLoggedInException()
        if (cfg.token.isBlank()) throw NotLoggedInException()
        val b = base.awaitBase() ?: throw OfflineException()
        val req = build(b).header("Authorization", "Bearer ${cfg.token}").build()
        return try {
            exchange(req, timeoutMs, b)
        } catch (e: OfflineException) {
            base.reportFailure()
            throw e
        }
    }

    /** Executes [req] with a whole-call timeout; any I/O failure (timeout included) becomes [OfflineException]. */
    private suspend fun exchange(req: Request, timeoutMs: Long, where: String): Pair<Int, String> {
        val call = http.newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            call.await().use { resp -> resp.code to withContext(Dispatchers.IO) { resp.body.string() } }
        } catch (e: IOException) {
            throw OfflineException("No answer from $where (${e.message ?: e.javaClass.simpleName})").apply { initCause(e) }
        }
    }

    private suspend fun items(el: JsonElement, what: String): List<MediaItem> =
        withContext(Dispatchers.Default) { MaWire.parseItems(el) { Log.w(TAG, "bad $what: ${it.message}") } }
}
