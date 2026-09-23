package io.github.superthom196.maa.art

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.superthom196.maa.AppGraph
import io.github.superthom196.maa.data.AuthInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "ArtProvider"

/**
 * Serves [ArtUris] content URIs to Android Auto and the phone UI. The car's image loader cannot
 * send our Bearer token, so this fetches with it and hands back a file descriptor of the copy in
 * [ArtCache] — cached covers keep showing when the network doesn't.
 *
 * Callers are not checked: access is controlled by the manifest (not exported, read grants on
 * the authority prefix to trusted controllers). openFile runs on binder threads, where blocking
 * is expected; network fetches are capped at [MAX_PARALLEL] and extra requests wait their turn
 * rather than fail, because a host whose image request failed rarely asks again.
 *
 * Nothing touches [AppGraph] before openFile: a ContentProvider is created before
 * Application.onCreate, which is where the graph gets its context.
 */
class ArtProvider : ContentProvider() {
    private val cache by lazy { ArtCache(File(requireNotNull(context).filesDir, "art")) }
    private val gate = Semaphore(MAX_PARALLEL, true)
    /** One download per image at a time; a second request for it waits and then reads the cache. */
    private val inFlight = ConcurrentHashMap<String, Any>()

    /** Foreign image hosts get no token, even for a URL that happens to point at our server. */
    private val anonymousHttp: OkHttpClient by lazy {
        AppGraph.http.newBuilder().apply { interceptors().removeAll { it is AuthInterceptor } }.build()
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val req = parse(uri) ?: throw FileNotFoundException("Not an artwork URI: $uri")
        val file = fetch(req)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val req = parse(uri) ?: return "image/*"
        val f = cache.file(cache.key(req.canonical))
        val head = runCatching { f.inputStream().use { s -> ByteArray(12).let { it.copyOf(s.read(it).coerceAtLeast(0)) } } }.getOrNull()
        return head?.let { ArtCache.sniffMime(it) } ?: "image/*"
    }

    private fun parse(uri: Uri): ArtRequest? {
        if (uri.authority != ArtUris.AUTHORITY) return null
        return ArtRequest.parse(uri.pathSegments.orEmpty()) { uri.getQueryParameter(it) }
    }

    private fun fetch(req: ArtRequest): File {
        val key = cache.key(req.canonical)
        cache.get(key)?.let { return it }
        val lock = inFlight.computeIfAbsent(key) { Any() }
        try {
            synchronized(lock) {
                cache.get(key)?.let { return it } // the request we waited behind fetched it
                if (!gate.tryAcquire(QUEUE_WAIT_S, TimeUnit.SECONDS)) throw FileNotFoundException("Artwork queue full")
                try {
                    return download(req, key)
                } finally {
                    gate.release()
                }
            }
        } finally {
            inFlight.remove(key, lock)
        }
    }

    private fun download(req: ArtRequest, key: String): File {
        val (client, url) = when (req) {
            is ArtRequest.Proxy -> {
                val base = runBlocking { AppGraph.baseUrl.awaitBase(BASE_WAIT_MS) }
                    ?: throw FileNotFoundException("Server not reachable")
                AppGraph.http to proxyUrl(base, req)
            }
            is ArtRequest.Remote -> anonymousHttp to (req.url.toHttpUrlOrNull() ?: throw FileNotFoundException("Bad URL"))
        }
        val call = client.newCall(Request.Builder().url(url).get().build())
        call.timeout().timeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw FileNotFoundException("HTTP ${resp.code} for ${req.canonical}")
                val type = resp.header("Content-Type").orEmpty()
                if (!type.startsWith("image/")) throw FileNotFoundException("Not an image ($type) for ${req.canonical}")
                val declared = resp.body.contentLength()
                if (declared > ArtCache.MAX_IMAGE_BYTES) throw FileNotFoundException("Image too large ($declared bytes)")
                return resp.body.byteStream().use { cache.put(key, it) }
            }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, e.message.orEmpty())
            throw e
        } catch (e: IOException) {
            if (req is ArtRequest.Proxy) AppGraph.baseUrl.reportFailure()
            Log.w(TAG, "fetch failed for ${req.canonical}: ${e.message}")
            throw FileNotFoundException("Artwork unavailable: ${e.message}")
        }
    }

    private fun proxyUrl(base: String, req: ArtRequest.Proxy): HttpUrl =
        (base.toHttpUrlOrNull() ?: throw FileNotFoundException("Bad base URL"))
            .newBuilder()
            .addPathSegment("imageproxy")
            .addPathSegment(req.proxyId)
            .addQueryParameter("size", req.size.toString())
            .build()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    private companion object {
        const val MAX_PARALLEL = 4
        const val QUEUE_WAIT_S = 30L
        const val BASE_WAIT_MS = 5_000L
        const val FETCH_TIMEOUT_S = 20L
    }
}
