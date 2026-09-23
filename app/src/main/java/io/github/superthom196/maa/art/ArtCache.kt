package io.github.superthom196.maa.art

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * What an [ArtProvider] URI asks for, parsed without android.net.Uri so it is unit-testable.
 * [canonical] is the cache identity: the same image at the same size, whichever base URL
 * (LAN or Tailscale) fetched it.
 */
sealed class ArtRequest {
    abstract val canonical: String

    data class Proxy(val proxyId: String, val size: Int) : ArtRequest() {
        override val canonical get() = "proxy/$proxyId?size=$size"
    }

    data class Remote(val url: String) : ArtRequest() {
        override val canonical get() = "url?u=$url"
    }

    companion object {
        /** imageproxy accepts exactly these (MA 2.10 rejects anything else with a 400). */
        val SIZES = intArrayOf(80, 160, 256, 512, 1024)
        private const val DEFAULT_SIZE = 512

        /**
         * @param segments the URI's decoded path segments
         * @param param the URI's decoded query parameter by name
         * @return null for anything but the two shapes [ArtUris] builds
         */
        fun parse(segments: List<String>, param: (String) -> String?): ArtRequest? = when {
            segments.size == 2 && segments[0] == "proxy" && segments[1].isNotBlank() -> {
                val asked = param("size")?.toIntOrNull() ?: DEFAULT_SIZE
                Proxy(segments[1], SIZES.firstOrNull { it >= asked } ?: SIZES.last())
            }
            segments.size == 1 && segments[0] == "url" -> {
                val u = param("u")?.trim().orEmpty()
                val scheme = u.substringBefore("://", "").lowercase()
                if ((scheme == "http" || scheme == "https") && u.length > scheme.length + 3) Remote(u) else null
            }
            else -> null
        }
    }
}

/**
 * Artwork on disk, so the car still shows covers in a dead zone. LRU by file mtime (touched on
 * every hit), evicted after each write down to [maxBytes]. Files are written to a temp name and
 * renamed, so a reader never sees half an image and two writers of the same key both succeed.
 */
class ArtCache(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES, private val now: () -> Long = System::currentTimeMillis) {
    private val tmpSeq = AtomicLong()

    fun key(canonical: String): String =
        MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }

    fun file(key: String): File = File(dir, key)

    /** The cached file for [key], marked as just used; null on a miss. */
    fun get(key: String): File? {
        val f = file(key)
        if (!f.isFile || f.length() == 0L) return null
        f.setLastModified(now())
        return f
    }

    /**
     * Copies [input] into the cache under [key], refusing more than [limit] bytes.
     * @throws IOException when the image is too large or the write fails; nothing is left behind.
     */
    fun put(key: String, input: InputStream, limit: Long = MAX_IMAGE_BYTES): File {
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp${tmpSeq.incrementAndGet()}")
        try {
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw IOException("image larger than $limit bytes")
                    out.write(buf, 0, n)
                }
            }
            if (total == 0L) throw IOException("empty image")
            val dest = file(key)
            if (!tmp.renameTo(dest)) throw IOException("rename failed for $dest")
            dest.setLastModified(now())
            evict()
            return dest
        } finally {
            tmp.delete()
        }
    }

    /** Oldest first until the total fits; also sweeps temp files a crash left behind. */
    @Synchronized
    fun evict() {
        val files = dir.listFiles() ?: return
        val stale = now() - STALE_TMP_MS
        val entries = files.filter { f ->
            if (f.name.contains(".tmp")) {
                if (f.lastModified() < stale) f.delete()
                false
            } else f.isFile
        }
        var total = entries.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in entries.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    companion object {
        const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        private const val STALE_TMP_MS = 60 * 60 * 1000L

        /** Image type from the first bytes, for getType on a cached file. */
        fun sniffMime(head: ByteArray): String? = when {
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "image/jpeg"
            head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> "image/png"
            head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            head.size >= 6 && String(head, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
            else -> null
        }
    }
}
