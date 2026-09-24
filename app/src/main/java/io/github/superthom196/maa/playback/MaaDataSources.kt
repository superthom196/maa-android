package io.github.superthom196.maa.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.superthom196.maa.data.BaseUrlProvider
import io.github.superthom196.maa.data.ConfigStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient

/** No base URL answers right now. An IOException so the load retry policy just waits it out. */
class NoServerException : IOException("Music Assistant is not reachable")

/**
 * The data source stacks for the player and the prefetcher, both over the one [AudioCache]:
 *
 * `CacheDataSource → ResolvingDataSource (maa:// → current base URL) → OkHttp`
 *
 * The cache sits outside the resolver, so a fully cached track never needs a base URL (or any
 * network) at all, and the cache key is the host-free `DataSpec.key` (ProgressiveMediaPeriod fills
 * it from `MediaItem.localConfiguration.customCacheKey`).
 *
 * The player's stack is read-only (no write sink): in CacheDataSource.openNextSource a read-only
 * instance releases the hole span straight away and reads upstream unlocked, and without
 * FLAG_BLOCK_ON_CACHE it never waits for the prefetcher's lock either. It still switches to cached
 * spans as the prefetcher writes them (it re-checks the cache every MIN_READ_BEFORE_CHECKING_CACHE
 * bytes). The prefetcher's stack writes and blocks on the cache lock: it is the only writer.
 */
@OptIn(UnstableApi::class)
class MaaDataSources(
    http: OkHttpClient,
    private val baseUrl: BaseUrlProvider,
    private val config: ConfigStore,
    private val audioCache: AudioCache,
) {
    /** A cache miss on the server holds the response for up to 180 s while it transcodes. */
    val client: OkHttpClient = http.newBuilder().readTimeout(200, TimeUnit.SECONDS).build()

    /** The item's own key; derived from the maa:// URI for an item that came without one. */
    val cacheKeyFactory = CacheKeyFactory { spec -> spec.key ?: keyFor(spec.uri.toString()) }

    private val resolver = object : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri.toString()
            if (dataSpec.uri.scheme != StreamUris.SCHEME) return dataSpec
            // Called on a loader thread, so waiting for a probe here is fine.
            val base = baseUrl.current.value ?: runBlocking { baseUrl.awaitBase(5_000) } ?: throw NoServerException()
            return dataSpec.withUri(Uri.parse(StreamUris.httpUrl(base, uri)))
        }
    }

    val player: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(audioCache.cache)
        .setCacheKeyFactory(cacheKeyFactory)
        .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(OkHttpDataSource.Factory(client), resolver))
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * For one prefetch download. [calls] is the prefetcher's own call factory so it can cancel a
     * request the server is still holding (CacheWriter.cancel only takes effect between reads).
     * Use `createDataSourceForDownloading()`, which adds FLAG_BLOCK_ON_CACHE: without it a
     * momentary lock would make CacheWriter read upstream without writing anything.
     */
    fun prefetch(calls: Call.Factory): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(audioCache.cache)
        .setCacheKeyFactory(cacheKeyFactory)
        .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(OkHttpDataSource.Factory(calls), resolver))

    fun keyFor(maaUri: String): String {
        val serverId = config.server.value?.serverId
        val (ref, format) = StreamUris.parse(maaUri) ?: return maaUri
        return if (serverId == null) maaUri else StreamUris.cacheKey(serverId, ref, format, StreamUris.variant(maaUri))
    }
}
