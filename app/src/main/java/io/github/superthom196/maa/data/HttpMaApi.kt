package io.github.superthom196.maa.data

import io.github.superthom196.maa.playback.TrackRef
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/** WS-B: [MaApi] over `POST {base}/api`. Stub. */
class HttpMaApi(http: OkHttpClient, base: BaseUrlProvider, config: ConfigStore) : MaApi {
    override suspend fun call(command: String, args: Map<String, Any?>, timeoutMs: Long): JsonElement = TODO()
    override suspend fun libraryItems(kind: String, offset: Int, limit: Int, orderBy: String?, favorite: Boolean?): List<MediaItem> = TODO()
    override suspend fun artistAlbums(provider: String, itemId: String): List<MediaItem> = TODO()
    override suspend fun albumTracks(provider: String, itemId: String): List<MediaItem> = TODO()
    override suspend fun playlistTracks(provider: String, itemId: String): List<MediaItem> = TODO()
    override suspend fun recentlyPlayed(limit: Int): List<MediaItem> = TODO()
    override suspend fun search(query: String, limit: Int): SearchResults = TODO()
    override suspend fun maaInfo(): MaaInfo = TODO()
    override suspend fun prepare(tracks: List<TrackRef>, format: String): Unit = TODO()
    override suspend fun fetchInfo(base: String, timeoutMs: Long): ServerInfo = TODO()
    override suspend fun login(base: String, username: String, password: String): String = TODO()
    override suspend fun createLongLivedToken(base: String, sessionToken: String, name: String): String = TODO()
}
