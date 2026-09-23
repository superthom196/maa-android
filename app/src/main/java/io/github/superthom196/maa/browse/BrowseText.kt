package io.github.superthom196.maa.browse

import io.github.superthom196.maa.data.MediaItem

/** How Android Auto should lay out a node's children. */
enum class Style { LIST, GRID }

/** What a browsable node holds; decides its children's [Style]. */
enum class Holds { ALBUMS, ARTISTS, PLAYLISTS, TRACKS, FOLDERS, MIXED }

/**
 * The pure decisions behind [MediaItemMapper]: display text and layout. Kept free of android.*
 * so they run in plain JVM tests.
 */
object BrowseText {
    /** "Song (Live)" unless the name already carries its version. */
    fun title(item: MediaItem): String {
        val v = item.version?.trim().orEmpty()
        return if (v.isEmpty() || item.name.contains(v, ignoreCase = true)) item.name else "${item.name} ($v)"
    }

    /** Album card: "Artist · 1999", either part alone when the other is unknown. */
    fun albumSubtitle(item: MediaItem): String? =
        listOfNotNull(item.artistLine.takeIf { it.isNotBlank() }, item.year?.takeIf { it > 0 }?.toString())
            .joinToString(" · ").ifEmpty { null }

    fun playlistSubtitle(item: MediaItem): String? = item.owner?.takeIf { it.isNotBlank() }

    fun trackSubtitle(item: MediaItem): String? = item.artistLine.takeIf { it.isNotBlank() }

    fun subtitle(item: MediaItem): String? = when (item.mediaType) {
        "album" -> albumSubtitle(item)
        "playlist" -> playlistSubtitle(item)
        "track" -> trackSubtitle(item)
        else -> null
    }

    fun countLabel(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"

    /**
     * Albums are covers, so a grid; everything else is text first. Artist pictures are patchy in
     * most libraries and a grid of blank tiles reads worse than a list.
     */
    fun childStyle(holds: Holds): Style = when (holds) {
        Holds.ALBUMS -> Style.GRID
        else -> Style.LIST
    }

    /** Track order within an album: disc, then track number, then the server's order. */
    fun sortAlbumTracks(tracks: List<MediaItem>): List<MediaItem> =
        tracks.withIndex().sortedWith(
            compareBy<IndexedValue<MediaItem>>({ it.value.discNumber ?: 0 }, { it.value.trackNumber ?: Int.MAX_VALUE }, { it.index }),
        ).map { it.value }

    /** "Disc 1", "Disc 2" group headers, only when the album spans more than one disc. */
    fun discGroups(tracks: List<MediaItem>): List<String?> {
        val discs = tracks.mapNotNull { it.discNumber?.takeIf { d -> d > 0 } }.toSet()
        if (discs.size < 2) return tracks.map { null }
        return tracks.map { t -> t.discNumber?.takeIf { it > 0 }?.let { "Disc $it" } }
    }

    /** An artist's albums newest first; undated ones after, by name. */
    fun sortArtistAlbums(albums: List<MediaItem>): List<MediaItem> =
        albums.sortedWith(compareByDescending<MediaItem> { it.year ?: Int.MIN_VALUE }.thenBy { foldKey(it.sortName ?: it.name) })

    /** The page [page] of size [pageSize]; a non-positive or huge page size means everything. */
    fun <T> page(all: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return if (page <= 0) all else emptyList()
        val from = page.toLong().coerceAtLeast(0) * pageSize
        if (from >= all.size) return emptyList()
        val to = minOf(all.size.toLong(), from + pageSize)
        return all.subList(from.toInt(), to.toInt())
    }

    /** Where a tapped track sits in its context; null when it is not there (list changed). */
    fun indexOfTrack(tracks: List<MediaItem>, provider: String, itemId: String): Int? =
        tracks.indexOfFirst { it.provider == provider && it.itemId == itemId }.takeIf { it >= 0 }
}
