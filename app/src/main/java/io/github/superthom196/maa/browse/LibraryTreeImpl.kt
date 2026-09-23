package io.github.superthom196.maa.browse

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import io.github.superthom196.maa.data.ConfigStore
import io.github.superthom196.maa.data.MaApi
import io.github.superthom196.maa.data.NotLoggedInException
import io.github.superthom196.maa.data.ServerConfig
import io.github.superthom196.maa.data.maJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import io.github.superthom196.maa.data.MediaItem as MaItem

private const val TAG = "LibraryTree"

/**
 * The Android Auto browse tree over the Music Assistant library.
 *
 * Auto gives up on slow loads and re-requests every open list on each reconnect, so nothing here
 * waits on the server when a local answer exists: the Artists/Albums tabs come from
 * [LibraryIndex] (disk, refreshed in the background), every other list from [BrowseCache]
 * (memory for 5 min, then server for ≤ 4 s, then disk).
 */
class LibraryTreeImpl(
    context: Context,
    private val api: MaApi,
    private val config: ConfigStore,
    private val scope: CoroutineScope,
) : LibraryTree {
    private val dir = File(context.filesDir, "browse")
    private val index = LibraryIndex(dir, api, scope)
    private val cache = BrowseCache(File(dir, "lists"), scope)
    private val map = MediaItemMapper(context.applicationContext, config)

    init {
        // Another server or account: lists in memory are someone else's (disk copies are keyed by server).
        scope.launch {
            config.server.map { it?.serverId to it?.token }.distinctUntilChanged().drop(1).collect { cache.clearMemory() }
        }
    }

    // ---------------------------------------------------------------- tree

    /** Exists even when signed out: the service turns the first children() failure into a sign-in prompt. */
    override fun root(): MediaItem = map.folder(BrowseId.Root, "Music Assistant", Holds.FOLDERS)

    override suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val server = requireServer()
        val id = BrowseId.parse(parentId) ?: return emptyList()
        return BrowseText.page(childrenOf(server.serverId, id), page, pageSize)
    }

    private suspend fun childrenOf(sid: String, id: BrowseId): List<MediaItem> = when (id) {
        BrowseId.Root -> {
            // Only to pick the tabs' layouts; a disk read normally, never a long wait.
            indexOrNull(sid)
            listOf(BrowseId.Home, BrowseId.Artists, BrowseId.Albums, BrowseId.Playlists).map { fixedItem(sid, it) }
        }
        BrowseId.Home -> listOf(BrowseId.HomeRecent, BrowseId.HomeAdded, BrowseId.HomeFavAlbums, BrowseId.HomeFavTracks).map { fixedItem(sid, it) }
        BrowseId.HomeRecent -> recent(sid).map { if (it.mediaType == "track") map.track(it, TrackCtx.SINGLE) else map.container(it) }
        BrowseId.HomeAdded -> added(sid).map { map.container(it) }
        BrowseId.HomeFavAlbums -> favAlbums(sid).map { map.container(it) }
        BrowseId.HomeFavTracks -> favTracks(sid).map { map.track(it, TrackCtx.FAVTRACKS) }
        BrowseId.Artists -> index.get(sid).let { s ->
            if (s.artists.size > BUCKET_THRESHOLD) s.artistBuckets.map { letterItem(BrowseId.ArtistLetter(it.letter), it.count) }
            else s.artists.map { map.container(it) }
        }
        BrowseId.Albums -> index.get(sid).let { s ->
            if (s.albums.size > BUCKET_THRESHOLD) s.albumBuckets.map { letterItem(BrowseId.AlbumLetter(it.letter), it.count) }
            else s.albums.map { map.container(it) }
        }
        BrowseId.Playlists -> playlists(sid).map { map.container(it) }
        is BrowseId.ArtistLetter -> index.get(sid).let { itemsInBucket(it.artists, it.artistBuckets, id.letter) }.map { map.container(it) }
        is BrowseId.AlbumLetter -> index.get(sid).let { itemsInBucket(it.albums, it.albumBuckets, id.letter) }.map { map.container(it) }
        is BrowseId.Artist -> artistAlbums(sid, id.provider, id.itemId).map { map.container(it) }
        is BrowseId.Album -> {
            val tracks = albumTracks(sid, id.provider, id.itemId)
            val album = index.peek(sid)?.album(id.provider, id.itemId)
            val groups = BrowseText.discGroups(tracks)
            tracks.mapIndexed { i, t -> map.track(t, TrackCtx.album(id.provider, id.itemId), album, groups[i]) }
        }
        is BrowseId.Playlist -> playlistTracks(sid, id.provider, id.itemId).map { map.track(it, TrackCtx.playlist(id.provider, id.itemId)) }
        is BrowseId.Track -> emptyList()
    }

    override suspend fun item(mediaId: String): MediaItem? {
        val server = requireServer()
        val sid = server.serverId
        return when (val id = BrowseId.parse(mediaId) ?: return null) {
            BrowseId.Root -> root()
            is BrowseId.Fixed -> fixedItem(sid, id)
            is BrowseId.ArtistLetter -> letterItem(id, index.peek(sid)?.let { itemsInBucket(it.artists, it.artistBuckets, id.letter).size })
            is BrowseId.AlbumLetter -> letterItem(id, index.peek(sid)?.let { itemsInBucket(it.albums, it.albumBuckets, id.letter).size })
            is BrowseId.Artist -> map.container(lookup(sid, "artist", id.provider, id.itemId))
            is BrowseId.Album -> map.container(lookup(sid, "album", id.provider, id.itemId))
            is BrowseId.Playlist -> map.container(lookup(sid, "playlist", id.provider, id.itemId))
            is BrowseId.Track -> resolveTrack(sid, id)
        }
    }

    override suspend fun search(query: String): List<MediaItem> {
        requireServer()
        if (query.isBlank()) return emptyList()
        val r = api.search(query, SEARCH_LIMIT)
        // Tracks first: Auto shows search results as one flat list, and a track is what people
        // most often search for while driving. Group titles split it into sections.
        return r.tracks.take(SEARCH_LIMIT).map { map.track(it.slim("track"), TrackCtx.SEARCH, groupTitle = "Tracks") } +
            r.albums.take(SEARCH_LIMIT).map { map.container(it.slim("album"), "Albums") } +
            r.artists.take(SEARCH_LIMIT).map { map.container(it.slim("artist"), "Artists") } +
            r.playlists.take(SEARCH_LIMIT).map { map.container(it.slim("playlist"), "Playlists") }
    }

    // ---------------------------------------------------------------- playback

    override suspend fun expandForPlayback(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        val sid = requireServer().serverId
        if (items.size == 1) {
            val only = items[0]
            val query = only.requestMetadata.searchQuery
            if (query != null && BrowseId.parse(only.mediaId) == null) {
                return voice(sid, query, only.requestMetadata.extras, startPositionMs)
            }
            val (list, start) = expandOne(sid, only)
            if (list.isEmpty()) throw IllegalArgumentException("Nothing to play for ${only.mediaId}")
            return MediaSession.MediaItemsWithStartPosition(list, start, startPositionMs)
        }
        // Several items (e.g. a queue handed over by the phone UI): expand each, keep the start item.
        val out = ArrayList<MediaItem>()
        var start = 0
        items.forEachIndexed { i, m ->
            if (i == startIndex) start = out.size
            out += runCatching { expandFlat(sid, m) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
        }
        if (out.isEmpty()) throw IllegalArgumentException("Nothing to play")
        return MediaSession.MediaItemsWithStartPosition(out, start.coerceIn(0, out.lastIndex), startPositionMs)
    }

    override suspend fun resolve(mediaId: String): MediaItem? {
        val sid = requireServer().serverId
        val id = BrowseId.parse(mediaId) as? BrowseId.Track ?: return null
        return resolveTrack(sid, id)
    }

    /** One tapped item → queue and start index: a track plays within its context. */
    private suspend fun expandOne(sid: String, m: MediaItem): Pair<List<MediaItem>, Int> {
        if (m.localConfiguration != null) return listOf(m) to 0 // already resolved (phone UI)
        return when (val id = BrowseId.parse(m.mediaId)) {
            is BrowseId.Track -> {
                val ctxList = runCatching { contextTracks(sid, id.ctx) }
                    .onFailure { if (it is CancellationException) throw it; logW(TAG, "context of ${m.mediaId}: ${it.message}") }
                    .getOrNull()
                val at = ctxList?.let { BrowseText.indexOfTrack(it, id.provider, id.itemId) }
                if (ctxList != null && at != null) mapTracks(sid, ctxList, id.ctx) to at
                else listOf(resolveTrack(sid, id)) to 0
            }
            null -> emptyList<MediaItem>() to 0
            else -> expandFlat(sid, m) to 0
        }
    }

    /** Any id → the tracks it stands for, without context expansion for single tracks. */
    private suspend fun expandFlat(sid: String, m: MediaItem): List<MediaItem> {
        if (m.localConfiguration != null) return listOf(m)
        return when (val id = BrowseId.parse(m.mediaId)) {
            is BrowseId.Track -> listOf(resolveTrack(sid, id))
            is BrowseId.Album -> mapTracks(sid, albumTracks(sid, id.provider, id.itemId), TrackCtx.album(id.provider, id.itemId))
            is BrowseId.Playlist -> mapTracks(sid, playlistTracks(sid, id.provider, id.itemId), TrackCtx.playlist(id.provider, id.itemId))
            is BrowseId.Artist -> artistTracks(sid, id.provider, id.itemId)
            BrowseId.HomeFavTracks -> mapTracks(sid, favTracks(sid), TrackCtx.FAVTRACKS)
            else -> emptyList()
        }
    }

    /** "Hey Google, play X" arrives as an item with no id and a search query. */
    private suspend fun voice(sid: String, query: String, extras: Bundle?, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        val (list, start) = if (query.isBlank()) {
            // "Play music": favourites, shuffled, else the newest album.
            val fav = favTracks(sid).shuffled().take(VOICE_SHUFFLE_MAX)
            if (fav.isNotEmpty()) mapTracks(sid, fav, TrackCtx.FAVTRACKS) to 0
            else added(sid).firstOrNull()?.let { mapTracks(sid, albumTracks(sid, it.provider, it.itemId), TrackCtx.album(it.provider, it.itemId)) to 0 }
                ?: (emptyList<MediaItem>() to 0)
        } else {
            val focus = runCatching { extras?.getString(VoiceSearch.EXTRA_MEDIA_FOCUS) }.getOrNull()
            when (val pick = VoiceSearch.pick(query, focus, api.search(query, SEARCH_LIMIT))) {
                is VoicePick.Track -> {
                    val t = pick.track
                    val album = t.album
                    val tracks = album?.let { a -> runCatching { albumTracks(sid, a.provider, a.itemId) }.getOrNull() }
                    val at = tracks?.let { BrowseText.indexOfTrack(it, t.provider, t.itemId) }
                    if (album != null && tracks != null && at != null) mapTracks(sid, tracks, TrackCtx.album(album.provider, album.itemId)) to at
                    else listOf(map.track(t.slim("track"), TrackCtx.SINGLE)) to 0
                }
                is VoicePick.Album -> mapTracks(sid, albumTracks(sid, pick.album.provider, pick.album.itemId), TrackCtx.album(pick.album.provider, pick.album.itemId)) to 0
                is VoicePick.Artist -> artistTracks(sid, pick.artist.provider, pick.artist.itemId) to 0
                is VoicePick.Playlist -> mapTracks(sid, playlistTracks(sid, pick.playlist.provider, pick.playlist.itemId), TrackCtx.playlist(pick.playlist.provider, pick.playlist.itemId)) to 0
                VoicePick.Nothing -> emptyList<MediaItem>() to 0
            }
        }
        if (list.isEmpty()) throw IllegalStateException("Nothing found for “$query”")
        return MediaSession.MediaItemsWithStartPosition(list, start, startPositionMs)
    }

    /** A track's context as a list, or null for contexts that are just the track. */
    private suspend fun contextTracks(sid: String, ctx: TrackCtx): List<MaItem>? = when (ctx.kind) {
        CtxKind.ALBUM -> albumTracks(sid, ctx.provider, ctx.itemId)
        CtxKind.PLAYLIST -> playlistTracks(sid, ctx.provider, ctx.itemId)
        CtxKind.FAVTRACKS -> favTracks(sid)
        CtxKind.SEARCH, CtxKind.SINGLE -> null
    }

    /** Every track of an artist's albums, newest album first (capped: this backs "play artist"). */
    private suspend fun artistTracks(sid: String, provider: String, itemId: String): List<MediaItem> = coroutineScope {
        artistAlbums(sid, provider, itemId).take(ARTIST_ALBUMS_MAX).map { a ->
            async {
                runCatching { mapTracks(sid, albumTracks(sid, a.provider, a.itemId), TrackCtx.album(a.provider, a.itemId), a) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    private fun mapTracks(sid: String, tracks: List<MaItem>, ctx: TrackCtx, album: MaItem? = null): List<MediaItem> {
        val ctxAlbum = album ?: if (ctx.kind == CtxKind.ALBUM) index.peek(sid)?.album(ctx.provider, ctx.itemId) else null
        return tracks.map { map.track(it, ctx, ctxAlbum) }
    }

    /**
     * Full playable item for a track id. Metadata comes from its context list (memory/disk first,
     * then the server within the cache's timeout), else `music/tracks/get`; failing both, a bare
     * item from the ids alone still plays, which beats dropping it from a restored queue.
     */
    private suspend fun resolveTrack(sid: String, id: BrowseId.Track): MediaItem {
        val album = if (id.ctx.kind == CtxKind.ALBUM) index.peek(sid)?.album(id.ctx.provider, id.ctx.itemId) else null
        val key = contextKey(sid, id.ctx)
        val fromCtx = key?.let { (cmd, args) -> cache.peek(cmd, args) }?.firstOrNull { it.provider == id.provider && it.itemId == id.itemId }
            ?: runCatching { contextTracks(sid, id.ctx) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                ?.firstOrNull { it.provider == id.provider && it.itemId == id.itemId }
        if (fromCtx != null) return map.track(fromCtx, id.ctx, album)
        val fetched = fetchItem("track", id.provider, id.itemId)
        return map.track(fetched ?: MaItem(itemId = id.itemId, provider = id.provider, name = "Track", mediaType = "track"), id.ctx, album)
    }

    // ---------------------------------------------------------------- nodes without an MA item

    private fun fixedItem(sid: String, id: BrowseId.Fixed): MediaItem = when (id) {
        BrowseId.Root -> root()
        BrowseId.Home -> map.folder(id, "Home", Holds.FOLDERS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, artwork = map.homeIcon)
        BrowseId.Artists -> map.folder(id, "Artists", Holds.ARTISTS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, artwork = map.artistsIcon)
        BrowseId.Albums -> {
            // Bucketed, the tab lists letters; unbucketed (or unknown yet), album covers.
            val bucketed = (index.peek(sid)?.albums?.size ?: 0) > BUCKET_THRESHOLD
            map.folder(id, "Albums", if (bucketed) Holds.FOLDERS else Holds.ALBUMS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, artwork = map.albumsIcon)
        }
        BrowseId.Playlists -> map.folder(id, "Playlists", Holds.PLAYLISTS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, artwork = map.playlistsIcon)
        BrowseId.HomeRecent -> map.folder(id, "Recently played", Holds.ALBUMS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        BrowseId.HomeAdded -> map.folder(id, "Recently added", Holds.ALBUMS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        BrowseId.HomeFavAlbums -> map.folder(id, "Favourite albums", Holds.ALBUMS, mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        BrowseId.HomeFavTracks -> map.folder(id, "Favourite tracks", Holds.TRACKS, mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = true)
    }

    private fun letterItem(id: BrowseId, count: Int?): MediaItem = when (id) {
        is BrowseId.ArtistLetter -> map.folder(
            id, id.letter, Holds.ARTISTS, count?.let { BrowseText.countLabel(it, "artist", "artists") },
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
        )
        is BrowseId.AlbumLetter -> map.folder(
            id, id.letter, Holds.ALBUMS, count?.let { BrowseText.countLabel(it, "album", "albums") },
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        )
        else -> error("not a letter: $id")
    }

    // ---------------------------------------------------------------- data

    private suspend fun recent(sid: String): List<MaItem> {
        val raw = cache.get("recent", listOf(sid)) { api.recentlyPlayed(RECENT_LIMIT) }
        val seen = HashSet<String>()
        val containers = raw.filter { it.mediaType in CONTAINER_TYPES && seen.add("${it.mediaType}/${it.provider}/${it.itemId}") }
        val snap = index.peek(sid)
        // Playlog rows carry name and picture only; the index knows album artists and years.
        val enriched = containers.map { if (it.mediaType == "album") snap?.album(it.provider, it.itemId) ?: it else it }
        // Older servers log only tracks: better those than an empty shelf.
        return enriched.ifEmpty { raw.filter { it.mediaType == "track" && seen.add("track/${it.provider}/${it.itemId}") } }
    }

    private suspend fun added(sid: String) =
        cache.get("added", listOf(sid)) { api.libraryItems("albums", 0, ADDED_LIMIT, "timestamp_added_desc") }.map { it.asType("album") }

    private suspend fun favAlbums(sid: String) =
        cache.get("favalbums", listOf(sid)) { allPages { o, l -> api.libraryItems("albums", o, l, "sort_name", true) } }.map { it.asType("album") }

    private suspend fun favTracks(sid: String) =
        cache.get("favtracks", listOf(sid)) { allPages { o, l -> api.libraryItems("tracks", o, l, "sort_name", true) } }.map { it.asType("track") }

    private suspend fun playlists(sid: String) =
        cache.get("playlists", listOf(sid)) { allPages { o, l -> api.libraryItems("playlists", o, l, "sort_name") } }.map { it.asType("playlist") }

    private suspend fun albumTracks(sid: String, provider: String, itemId: String) =
        BrowseText.sortAlbumTracks(cache.get("album_tracks", listOf(sid, provider, itemId)) { api.albumTracks(provider, itemId) }.map { it.asType("track") })

    /** Playlists may hold radio stations or episodes; the tree plays tracks only. */
    private suspend fun playlistTracks(sid: String, provider: String, itemId: String) =
        cache.get("playlist_tracks", listOf(sid, provider, itemId)) { api.playlistTracks(provider, itemId) }
            .filter { it.mediaType == "track" || it.mediaType == "unknown" }.map { it.asType("track") }

    private suspend fun artistAlbums(sid: String, provider: String, itemId: String) =
        BrowseText.sortArtistAlbums(cache.get("artist_albums", listOf(sid, provider, itemId)) { api.artistAlbums(provider, itemId) }.map { it.asType("album") })

    private fun contextKey(sid: String, ctx: TrackCtx): Pair<String, List<String>>? = when (ctx.kind) {
        CtxKind.ALBUM -> "album_tracks" to listOf(sid, ctx.provider, ctx.itemId)
        CtxKind.PLAYLIST -> "playlist_tracks" to listOf(sid, ctx.provider, ctx.itemId)
        CtxKind.FAVTRACKS -> "favtracks" to listOf(sid)
        else -> null
    }

    /** An artist/album/playlist for [item]: index, then server (briefly), then a stub named by type. */
    private suspend fun lookup(sid: String, type: String, provider: String, itemId: String): MaItem {
        if (type == "playlist") {
            cache.peek("playlists", listOf(sid))?.firstOrNull { it.provider == provider && it.itemId == itemId }?.let { return it.asType(type) }
        } else {
            val snap = indexOrNull(sid)
            (if (type == "artist") snap?.artist(provider, itemId) else snap?.album(provider, itemId))?.let { return it }
        }
        return fetchItem(type, provider, itemId) ?: MaItem(itemId = itemId, provider = provider, name = type.replaceFirstChar { it.uppercase() }, mediaType = type)
    }

    /** `music/{type}s/get`, bounded so a single-item rebuild never stalls Auto. */
    private suspend fun fetchItem(type: String, provider: String, itemId: String): MaItem? = try {
        withTimeoutOrNull(ITEM_TIMEOUT_MS) {
            val el = api.call("music/${type}s/get", mapOf("item_id" to itemId, "provider_instance_id_or_domain" to provider), ITEM_TIMEOUT_MS)
            maJson.decodeFromJsonElement(MaItem.serializer(), el).slim(type)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logW(TAG, "$type $provider/$itemId: ${e.message}")
        null
    }

    private suspend fun allPages(fetch: suspend (offset: Int, limit: Int) -> List<MaItem>): List<MaItem> {
        val all = ArrayList<MaItem>()
        while (all.size < PAGED_MAX) {
            val batch = fetch(all.size, PAGE)
            all += batch
            if (batch.size < PAGE) break
        }
        return all
    }

    /** Some list commands (and older caches) leave media_type out; the context says what it is. */
    private fun MaItem.asType(type: String): MaItem = if (mediaType == "unknown") copy(mediaType = type) else this

    /** The index if it can be had quickly (normally a disk read); null offline on first use. */
    private suspend fun indexOrNull(sid: String): LibraryIndex.Snapshot? = try {
        withTimeoutOrNull(1_500) { index.get(sid) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun requireServer(): ServerConfig = config.server.value ?: throw NotLoggedInException()

    private companion object {
        const val SEARCH_LIMIT = 5
        const val RECENT_LIMIT = 50
        const val ADDED_LIMIT = 100
        const val PAGE = 500
        const val PAGED_MAX = 5_000
        const val ARTIST_ALBUMS_MAX = 20
        const val VOICE_SHUFFLE_MAX = 200
        const val ITEM_TIMEOUT_MS = 4_000L
        val CONTAINER_TYPES = setOf("album", "playlist", "artist")
    }
}
