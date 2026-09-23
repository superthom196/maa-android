package io.github.superthom196.maa.data

import io.github.superthom196.maa.playback.TrackRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaWireTest {
    private enum class Kind { album }

    @Test fun argsConvertEveryType() {
        val obj = MaWire.argsToJson(
            mapOf(
                "s" to "x", "i" to 3, "l" to 4L, "d" to 1.5, "b" to true, "n" to null,
                "list" to listOf("a", 1), "map" to mapOf("k" to false), "enum" to Kind.album,
                "json" to JsonPrimitive("raw"), "set" to setOf("only"),
            ),
        )
        assertEquals(JsonPrimitive("x"), obj["s"])
        assertEquals(3, obj["i"]!!.jsonPrimitive.int)
        assertEquals("4", obj["l"]!!.jsonPrimitive.content)
        assertEquals("1.5", obj["d"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), obj["b"])
        assertEquals(JsonNull, obj["n"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(1))), obj["list"])
        assertEquals(JsonObject(mapOf("k" to JsonPrimitive(false))), obj["map"])
        assertEquals(JsonPrimitive("album"), obj["enum"])
        assertEquals(JsonPrimitive("raw"), obj["json"])
        assertEquals(JsonArray(listOf(JsonPrimitive("only"))), obj["set"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedArgTypeFailsLoudly() {
        MaWire.toJson(Any())
    }

    @Test fun commandBodyShape() {
        val el = maJson.parseToJsonElement(MaWire.commandBody("music/albums/library_items", mapOf("limit" to 5), "maa-1")).jsonObject
        assertEquals("music/albums/library_items", el["command"]!!.jsonPrimitive.content)
        assertEquals("maa-1", el["message_id"]!!.jsonPrimitive.content)
        assertEquals(5, el["args"]!!.jsonObject["limit"]!!.jsonPrimitive.int)
    }

    @Test fun loginBodyShape() {
        val el = maJson.parseToJsonElement(MaWire.loginBody("thom", "p\"w")).jsonObject
        val creds = el["credentials"]!!.jsonObject
        assertEquals("thom", creds["username"]!!.jsonPrimitive.content)
        assertEquals("p\"w", creds["password"]!!.jsonPrimitive.content)
    }

    @Test fun prepareChunksByTenAndDedupes() {
        val refs = (1..23).map { TrackRef("library", "$it") } + TrackRef("library", "1")
        val bodies = MaWire.prepareBodies(refs, "opus-192").map { maJson.parseToJsonElement(it).jsonObject }
        assertEquals(listOf(10, 10, 3), bodies.map { it["tracks"]!!.jsonArray.size })
        assertEquals("opus-192", bodies[0]["format"]!!.jsonPrimitive.content)
        val first = bodies[0]["tracks"]!!.jsonArray[0].jsonObject
        assertEquals("library", first["provider"]!!.jsonPrimitive.content)
        assertEquals("1", first["item_id"]!!.jsonPrimitive.content)
        assertTrue(MaWire.prepareBodies(emptyList(), "opus-192").isEmpty())
    }

    @Test fun unauthorizedIsNotLoggedIn() {
        assertTrue(MaWire.httpError(401, "Authentication required", "x") is NotLoggedInException)
    }

    @Test fun plainTextErrorKeepsCodeAndText() {
        val e = MaWire.httpError(400, "Invalid Command: music/nope", "music/nope") as MaApiException
        assertEquals(400, e.code)
        assertNull(e.error)
        assertTrue(e.message!!.contains("Invalid Command: music/nope"))
    }

    @Test fun pluginJsonErrorKeepsErrorCode() {
        val e = MaWire.httpError(503, """{"error":"provider_unavailable","message":"offline"}""", "maa/track") as MaApiException
        assertEquals(503, e.code)
        assertEquals("provider_unavailable", e.error)
        assertTrue(e.message!!.contains("offline"))
    }

    @Test fun missingPluginIs404WithEmptyBody() {
        val e = MaWire.httpError(404, "", "maa/info") as MaApiException
        assertEquals(404, e.code)
        assertEquals("maa/info: HTTP 404", e.message)
    }

    @Test fun emptyResultIsNull() {
        assertEquals(JsonNull, MaWire.parseResult(""))
    }

    @Test fun itemsFromArrayOrItemsObjectSkippingBadOnes() {
        val arr = maJson.parseToJsonElement(
            """[
              {"item_id":"12","provider":"library","name":"Kind of Blue","media_type":"album","year":1959,
               "artists":[{"item_id":"3","provider":"library","name":"Miles Davis","media_type":"artist"}],
               "metadata":{"images":[{"type":"thumb","path":"/x.jpg","provider":"filesystem","remotely_accessible":false,"proxy_id":"abc"}]},
               "favorite":true,"provider_mappings":[{"item_id":"a","provider_domain":"filesystem_local","provider_instance":"fs1","available":true,
               "audio_format":{"content_type":"flac","sample_rate":96000,"bit_depth":24}}]},
              {"name":"no id"},
              {"item_id":"7","provider":"library","name":"So What","media_type":"track","duration":545,"unknown_field":{"x":1}}
            ]""",
        )
        var bad = 0
        val items = MaWire.parseItems(arr) { bad++ }
        assertEquals(1, bad)
        assertEquals(listOf("12", "7"), items.map { it.itemId })
        assertEquals("Miles Davis", items[0].artistLine)
        assertEquals("abc", items[0].thumb?.proxyId)
        assertEquals(545.0, items[1].duration!!, 0.0)

        val wrapped = maJson.parseToJsonElement("""{"items":[{"item_id":"1","provider":"library","name":"A"}]}""")
        assertEquals(1, MaWire.parseItems(wrapped).size)
        assertTrue(MaWire.parseItems(JsonNull).isEmpty())
    }

    @Test fun recentlyPlayedItemMappingsParse() {
        val el = maJson.parseToJsonElement(
            """[{"item_id":"5","provider":"library","name":"Blue Train","media_type":"album","available":true,
                 "image":{"type":"thumb","path":"/c.jpg","provider":"fs","proxy_id":"p5"}}]""",
        )
        val item = MaWire.parseItems(el).single()
        assertEquals("album", item.mediaType)
        assertEquals("p5", item.thumb?.proxyId)
    }

    @Test fun searchSections() {
        val el = maJson.parseToJsonElement(
            """{"artists":[{"item_id":"1","provider":"library","name":"A","media_type":"artist"}],
                "albums":[],"tracks":[{"item_id":"2","provider":"library","name":"T","media_type":"track"},{"bogus":1}],
                "radio":[{"item_id":"9","provider":"tunein","name":"R","media_type":"radio"}]}""",
        )
        val r = MaWire.parseSearch(el)
        assertEquals(listOf("1"), r.artists.map { it.itemId })
        assertTrue(r.albums.isEmpty())
        assertEquals(listOf("2"), r.tracks.map { it.itemId })
        assertTrue(r.playlists.isEmpty())
        assertEquals(SearchResults(), MaWire.parseSearch(JsonNull))
    }

    @Test fun tokenFromBareStringOrObject() {
        assertEquals("eyJ.long", MaWire.parseToken(MaWire.parseResult("\"eyJ.long\"")))
        assertEquals("tok", MaWire.parseToken(MaWire.parseResult("""{"token":"tok"}""")))
        assertNull(MaWire.parseToken(MaWire.parseResult("\"\"")))
        assertNull(MaWire.parseToken(JsonNull))
    }

    @Test fun loginSuccess() {
        val r = MaWire.parseLogin(200, """{"success":true,"token":"sess","user":{"user_id":"u","username":"thom"}}""")
        assertEquals("sess", r.getOrThrow())
    }

    @Test fun loginWrongPassword() {
        val e = MaWire.parseLogin(401, """{"success":false,"error":"Invalid username or password"}""").exceptionOrNull() as MaApiException
        assertEquals(401, e.code)
        assertEquals("Wrong username or password", e.message)
    }

    @Test fun loginBeforeOnboardingSaysSo() {
        val e = MaWire.parseLogin(403, """{"success":false,"error":"Setup required"}""").exceptionOrNull() as MaApiException
        assertEquals(403, e.code)
        assertEquals("Login failed: Setup required", e.message)
    }

    @Test fun loginOkButNoTokenIsAFailure() {
        assertTrue(MaWire.parseLogin(200, "<html>proxy</html>").isFailure)
    }
}
