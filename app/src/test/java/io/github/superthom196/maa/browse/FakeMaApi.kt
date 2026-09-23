package io.github.superthom196.maa.browse

import io.github.superthom196.maa.data.ItemMapping
import io.github.superthom196.maa.data.MaApi
import io.github.superthom196.maa.data.MaaInfo
import io.github.superthom196.maa.data.MediaItem
import io.github.superthom196.maa.data.SearchResults
import io.github.superthom196.maa.data.ServerInfo
import io.github.superthom196.maa.data.maJson
import io.github.superthom196.maa.playback.TrackRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

fun item(id: String, name: String, type: String = "album", sortName: String? = null, artist: String? = null, year: Int? = null) =
    MediaItem(
        itemId = id, provider = "library", name = name, sortName = sortName, mediaType = type, year = year,
        artists = artist?.let { listOf(ItemMapping(itemId = "a-$it", provider = "library", name = it)) },
    )

/** Records calls; lists come from mutable fields so tests can change the "server" between calls. */
class FakeMaApi : MaApi {
    var artists: List<MediaItem> = emptyList()
    var albums: List<MediaItem> = emptyList()
    var failWith: Exception? = null
    val calls = mutableListOf<String>()

    private fun <T> page(all: List<T>, offset: Int, limit: Int) = all.drop(offset).take(limit)

    override suspend fun call(command: String, args: Map<String, Any?>, timeoutMs: Long): JsonElement {
        calls += command
        failWith?.let { throw it }
        require(command == "music/artists/library_items") { "unexpected $command" }
        require(args["album_artists_only"] == true)
        val list = page(artists, args["offset"] as Int, args["limit"] as Int)
        return JsonArray(list.map { maJson.encodeToJsonElement(MediaItem.serializer(), it) })
    }

    override suspend fun libraryItems(kind: String, offset: Int, limit: Int, orderBy: String?, favorite: Boolean?): List<MediaItem> {
        calls += "libraryItems:$kind"
        failWith?.let { throw it }
        return page(if (kind == "albums") albums else emptyList(), offset, limit)
    }

    override suspend fun artistAlbums(provider: String, itemId: String) = error("unused")
    override suspend fun albumTracks(provider: String, itemId: String) = error("unused")
    override suspend fun playlistTracks(provider: String, itemId: String) = error("unused")
    override suspend fun recentlyPlayed(limit: Int) = error("unused")
    override suspend fun search(query: String, limit: Int): SearchResults = error("unused")
    override suspend fun maaInfo(): MaaInfo = error("unused")
    override suspend fun prepare(tracks: List<TrackRef>, format: String) = Unit
    override suspend fun fetchInfo(base: String, timeoutMs: Long): ServerInfo = error("unused")
    override suspend fun login(base: String, username: String, password: String): String = error("unused")
    override suspend fun createLongLivedToken(base: String, sessionToken: String, name: String): String = error("unused")
}
