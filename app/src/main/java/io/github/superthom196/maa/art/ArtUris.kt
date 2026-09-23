package io.github.superthom196.maa.art

import android.net.Uri
import io.github.superthom196.maa.data.MediaItem
import io.github.superthom196.maa.data.MediaItemImage

/**
 * Artwork URIs for Media3 metadata. Android Auto loads artwork itself and cannot send our Bearer
 * token, so artwork goes through [ArtProvider] (content://io.github.superthom196.maa.art/...),
 * which fetches with auth and keeps a disk copy for offline use:
 *  - `content://…/proxy/{proxyId}?size=N` → `{base}/imageproxy/{proxyId}?size=N`
 *  - `content://…/url?u={encoded}` → a remote http(s) image fetched as-is (no token)
 * Provider-local images without a proxy id cannot be fetched (MA 2.10 rejects the old form).
 */
object ArtUris {
    // android.net.Uri builders: this file is exercised on-device, StreamUris carries the unit tests.
    const val AUTHORITY = "io.github.superthom196.maa.art"
    private val sizes = intArrayOf(80, 160, 256, 512, 1024)

    fun normalize(size: Int): Int = sizes.firstOrNull { it >= size } ?: 1024

    fun forImage(img: MediaItemImage?, size: Int = 512): Uri? {
        if (img == null) return null
        val s = normalize(size)
        img.proxyId?.takeIf { it.isNotBlank() }?.let {
            return Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("proxy").appendPath(it).appendQueryParameter("size", s.toString()).build()
        }
        val path = img.path ?: return null
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("url").appendQueryParameter("u", path).build()
        }
        return null
    }

    fun forItem(item: MediaItem?, size: Int = 512): Uri? {
        if (item == null) return null
        forImage(item.thumb, size)?.let { return it }
        forImage(item.album?.image, size)?.let { return it }
        return item.artists?.firstNotNullOfOrNull { forImage(it.image, size) }
    }
}
