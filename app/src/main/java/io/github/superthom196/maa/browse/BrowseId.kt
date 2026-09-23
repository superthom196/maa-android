package io.github.superthom196.maa.browse

import java.net.URLDecoder
import java.net.URLEncoder

/** Where a track was tapped: playing it queues that whole context, starting at the track. */
enum class CtxKind(val segment: String) {
    ALBUM("album"), PLAYLIST("playlist"), FAVTRACKS("favtracks"), SEARCH("search"), SINGLE("single");

    /** Album and playlist contexts name a container; the others stand alone. */
    val hasContainer: Boolean get() = this == ALBUM || this == PLAYLIST

    companion object {
        fun of(segment: String): CtxKind? = entries.firstOrNull { it.segment == segment }
    }
}

/** A track's context. [provider]/[itemId] are only meaningful when [kind] has a container. */
data class TrackCtx(val kind: CtxKind, val provider: String = "", val itemId: String = "") {
    companion object {
        val FAVTRACKS = TrackCtx(CtxKind.FAVTRACKS)
        val SEARCH = TrackCtx(CtxKind.SEARCH)
        val SINGLE = TrackCtx(CtxKind.SINGLE)
        fun album(provider: String, itemId: String) = TrackCtx(CtxKind.ALBUM, provider, itemId)
        fun playlist(provider: String, itemId: String) = TrackCtx(CtxKind.PLAYLIST, provider, itemId)
    }
}

/**
 * Every node of the Android Auto tree as a typed value (docs/PROTOCOL.md, "Browse ids").
 *
 * Media ids come back from controllers verbatim, possibly from a queue persisted long ago, so the
 * string form is the stable one: segments joined with `/`, each percent-encoded (`URLEncoder`, with
 * `+` as `%20`) so provider item ids full of `/`, `:` and `%` (file paths!) survive. [parse] is
 * strict and returns null for anything it did not produce, never throws.
 */
sealed class BrowseId {
    abstract fun encode(): String
    final override fun toString(): String = encode()

    /** The ids without parameters: tabs and home lists. */
    sealed class Fixed(private val id: String) : BrowseId() {
        override fun encode() = id
    }

    data object Root : Fixed("root")
    data object Home : Fixed("home")
    data object HomeRecent : Fixed("home/recent")
    data object HomeAdded : Fixed("home/added")
    data object HomeFavAlbums : Fixed("home/favalbums")
    data object HomeFavTracks : Fixed("home/favtracks")
    data object Artists : Fixed("artists")
    data object Albums : Fixed("albums")
    data object Playlists : Fixed("playlists")

    /** A letter bucket: "A".."Z" or "#". */
    data class ArtistLetter(val letter: String) : BrowseId() {
        override fun encode() = join("artists", "L", letter)
    }

    data class AlbumLetter(val letter: String) : BrowseId() {
        override fun encode() = join("albums", "L", letter)
    }

    data class Artist(val provider: String, val itemId: String) : BrowseId() {
        override fun encode() = join("artist", provider, itemId)
    }

    data class Album(val provider: String, val itemId: String) : BrowseId() {
        override fun encode() = join("album", provider, itemId)
    }

    data class Playlist(val provider: String, val itemId: String) : BrowseId() {
        override fun encode() = join("playlist", provider, itemId)
    }

    data class Track(val ctx: TrackCtx, val provider: String, val itemId: String) : BrowseId() {
        override fun encode(): String {
            val c = if (ctx.kind.hasContainer) ctx else TrackCtx(ctx.kind, UNUSED, UNUSED)
            return join("track", ctx.kind.segment, c.provider, c.itemId, provider, itemId)
        }
    }

    companion object {
        private const val UNUSED = "-"
        private val fixed: Map<String, BrowseId> by lazy {
            listOf(Root, Home, HomeRecent, HomeAdded, HomeFavAlbums, HomeFavTracks, Artists, Albums, Playlists)
                .associateBy { it.encode() }
        }

        fun isLetter(s: String): Boolean = s == "#" || (s.length == 1 && s[0] in 'A'..'Z')

        fun parse(id: String?): BrowseId? {
            if (id.isNullOrEmpty()) return null
            fixed[id]?.let { return it }
            val raw = id.split('/')
            val s = raw.map { decode(it) ?: return null }
            // The first segment of every id is a plain word, never encoded; compare the raw form so
            // "album%2Fx" can't pose as a kind.
            return when (raw[0]) {
                "artists", "albums" -> if (s.size == 3 && raw[1] == "L" && isLetter(s[2])) {
                    if (raw[0] == "artists") ArtistLetter(s[2]) else AlbumLetter(s[2])
                } else null
                "artist" -> if (s.size == 3) Artist(s[1], s[2]) else null
                "album" -> if (s.size == 3) Album(s[1], s[2]) else null
                "playlist" -> if (s.size == 3) Playlist(s[1], s[2]) else null
                "track" -> {
                    if (s.size != 6) return null
                    val kind = CtxKind.of(raw[1]) ?: return null
                    val ctx = if (kind.hasContainer) TrackCtx(kind, s[2], s[3]) else TrackCtx(kind)
                    Track(ctx, s[4], s[5])
                }
                else -> null
            }
        }

        internal fun encodeSegment(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

        private fun decode(s: String): String? =
            try { URLDecoder.decode(s, Charsets.UTF_8) } catch (_: IllegalArgumentException) { null }

        private fun join(vararg segments: String) = segments.joinToString("/") { encodeSegment(it) }
    }
}
