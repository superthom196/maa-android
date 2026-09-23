package io.github.superthom196.maa.data

import io.github.superthom196.maa.playback.TrackRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The pure half of [HttpMaApi]: request bodies, error mapping and result parsing, kept free of
 * I/O and android.* so the unit tests cover exactly what goes over the wire.
 *
 * Wire facts (MA 2.10.3, webserver/controller.py `_handle_jsonrpc_api_command`): `POST /api`
 * answers 200 with the command's result as bare JSON. Every failure is a *plain-text* body:
 * 400 (bad JSON, unknown command, InvalidDataError), 401 (no/bad token), 403 (missing scope,
 * InsufficientPermissions), 500 ("Internal server error"), 503 ("Setup required"). The MAA
 * plugin's own routes answer errors as `{"error","message"}` JSON instead.
 */
object MaWire {
    /** Upper bound per `/maa/prepare` request, see docs/PROTOCOL.md. */
    const val PREPARE_BATCH = 10

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is Iterable<*> -> JsonArray(value.map { toJson(it) })
        is Array<*> -> JsonArray(value.map { toJson(it) })
        else -> throw IllegalArgumentException("Unsupported argument type ${value::class.java.name}")
    }

    /** Nulls are kept: the server treats a JSON null as "use the default", like a missing key. */
    fun argsToJson(args: Map<String, Any?>): JsonObject = JsonObject(args.mapValues { toJson(it.value) })

    fun commandBody(command: String, args: Map<String, Any?>, messageId: String): String = buildJsonObject {
        put("command", command)
        put("args", argsToJson(args))
        put("message_id", messageId)
    }.toString()

    fun loginBody(username: String, password: String): String = buildJsonObject {
        put("credentials", buildJsonObject {
            put("username", username)
            put("password", password)
        })
    }.toString()

    /** `/maa/prepare` bodies, [PREPARE_BATCH] tracks each. */
    fun prepareBodies(tracks: List<TrackRef>, format: String): List<String> =
        tracks.distinct().chunked(PREPARE_BATCH).map { chunk ->
            buildJsonObject {
                put("format", format)
                put("tracks", buildJsonArray {
                    for (t in chunk) add(buildJsonObject { put("provider", t.provider); put("item_id", t.itemId) })
                })
            }.toString()
        }

    /**
     * An HTTP error as the app's exception types. 401 means the token is gone or revoked; the
     * rest keeps the server's code and text so a caller (and a bug report) can tell a missing
     * plugin (404) from a scope problem (403) or a server crash (500).
     */
    fun httpError(code: Int, body: String, what: String): Exception {
        val obj = runCatching { maJson.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val error = obj?.stringField("error")
        val text = obj?.stringField("message") ?: error ?: body.trim().take(200)
        if (code == 401) return NotLoggedInException(if (text.isBlank()) "Not signed in" else "Not signed in ($text)")
        return MaApiException(code, error, if (text.isBlank()) "$what: HTTP $code" else "$what: HTTP $code: $text")
    }

    /** The body of a 2xx `/api` answer; an empty body (a command returning None) reads as null. */
    fun parseResult(body: String): JsonElement =
        if (body.isBlank()) JsonNull else maJson.parseToJsonElement(body)

    /** A list result as media items; entries that don't decode are skipped, not fatal. */
    fun parseItems(el: JsonElement, onBad: (Throwable) -> Unit = {}): List<MediaItem> =
        el.asItemArray().mapNotNull { item ->
            runCatching { maJson.decodeFromJsonElement(MediaItem.serializer(), item) }
                .onFailure(onBad).getOrNull()
        }

    /** `music/search` → [SearchResults]; each section may hold full items or ItemMappings. */
    fun parseSearch(el: JsonElement, onBad: (Throwable) -> Unit = {}): SearchResults {
        val obj = el as? JsonObject ?: return SearchResults()
        fun section(key: String) = obj[key]?.let { parseItems(it, onBad) }.orEmpty()
        return SearchResults(
            artists = section("artists"),
            albums = section("albums"),
            tracks = section("tracks"),
            playlists = section("playlists"),
        )
    }

    /** `auth/token/create` returns the token as a bare JSON string; tolerate `{token: ...}` too. */
    fun parseToken(el: JsonElement): String? {
        val direct = (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return (direct ?: el.findStringDeep("token"))?.takeIf { it.isNotBlank() }
    }

    /** `POST /auth/login` → the session token, or the error the sign-in screen should show. */
    fun parseLogin(code: Int, body: String): Result<String> {
        val el = runCatching { maJson.parseToJsonElement(body) }.getOrNull()
        val token = el?.findStringDeep("token")
        if (code in 200..299 && !token.isNullOrBlank()) return Result.success(token)
        val msg = el?.findStringDeep("error") ?: el?.findStringDeep("message") ?: body.trim().take(120)
        return Result.failure(
            when (code) {
                401 -> MaApiException(code, "unauthorized", "Wrong username or password")
                else -> MaApiException(code, el?.findStringDeep("error"), "Login failed: ${msg.ifBlank { "HTTP $code" }}")
            },
        )
    }
}
