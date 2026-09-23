package io.github.superthom196.maa.playback

import java.net.URLDecoder
import java.net.URLEncoder

/** A track as the MAA plugin addresses it: `provider` may be "library". */
data class TrackRef(val provider: String, val itemId: String)

/**
 * Queue items carry a host-independent URI, `maa://track?provider=P&item_id=I&format=F`, which
 * the data source resolves against whichever base URL (LAN or Tailscale) currently answers.
 * The cache key has no host either, so a track fetched at home still plays from cache in the car.
 *
 * Pure string handling (no android.net.Uri) so it is unit-testable; wrap with `Uri.parse`.
 */
object StreamUris {
    const val SCHEME = "maa"
    const val HOST = "track"
    private const val PREFIX = "$SCHEME://$HOST?"

    fun trackUri(ref: TrackRef, format: String): String =
        PREFIX + query(ref, format)

    /** Parses a [trackUri]; null for anything else. */
    fun parse(uri: String): Pair<TrackRef, String>? {
        if (!uri.startsWith(PREFIX)) return null
        val params = uri.removePrefix(PREFIX).split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to decode(part.substring(eq + 1))
        }.toMap()
        val provider = params["provider"] ?: return null
        val itemId = params["item_id"] ?: return null
        val format = params["format"] ?: return null
        return TrackRef(provider, itemId) to format
    }

    fun cacheKey(serverId: String, ref: TrackRef, format: String): String =
        listOf("v1", serverId, ref.provider, ref.itemId, format).joinToString("/") { encode(it) }

    /** The plugin URL for a [trackUri] on [base]. */
    fun httpUrl(base: String, maaUri: String): String {
        val (ref, format) = parse(maaUri) ?: throw IllegalArgumentException("Not a maa:// track uri: $maaUri")
        return base.trimEnd('/') + "/maa/track?" + query(ref, format)
    }

    fun mimeType(format: String): String = if (format.startsWith("flac")) "audio/flac" else "audio/ogg"

    private fun query(ref: TrackRef, format: String) =
        "provider=${encode(ref.provider)}&item_id=${encode(ref.itemId)}&format=${encode(format)}"

    internal fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    internal fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8)
}
