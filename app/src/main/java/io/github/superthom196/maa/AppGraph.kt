package io.github.superthom196.maa

import android.content.Context
import io.github.superthom196.maa.browse.LibraryTree
import io.github.superthom196.maa.browse.LibraryTreeImpl
import io.github.superthom196.maa.data.AndroidNetworkMonitor
import io.github.superthom196.maa.data.AuthInterceptor
import io.github.superthom196.maa.data.BaseUrlProvider
import io.github.superthom196.maa.data.ConfigStore
import io.github.superthom196.maa.data.HttpMaApi
import io.github.superthom196.maa.data.LanDiscovery
import io.github.superthom196.maa.data.MaApi
import io.github.superthom196.maa.data.NetworkMonitor
import io.github.superthom196.maa.data.Prefs
import io.github.superthom196.maa.data.Reachability
import io.github.superthom196.maa.data.ServerDiscovery
import io.github.superthom196.maa.playback.AudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency graph: one instance of each service for the whole process, shared by
 * the playback service (Android Auto), the artwork provider and the phone UI. Everything is lazy
 * so a cold start from Android Auto only builds what browsing needs.
 */
object AppGraph {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    val context: Context get() = app

    /** Process-wide background scope; never cancelled. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val config: ConfigStore by lazy { Prefs(app) }

    val network: NetworkMonitor by lazy { AndroidNetworkMonitor(app, scope) }

    /** Shared client: Bearer token added only for our server's hosts (LAN and remote). */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(config))
            .build()
    }

    val baseUrl: BaseUrlProvider by lazy { Reachability(http, config, network, scope) }

    val api: MaApi by lazy { HttpMaApi(http, baseUrl, config) }

    val discovery: ServerDiscovery by lazy { LanDiscovery(app, api) }

    val audioCache: AudioCache by lazy { AudioCache(app, config, scope) }

    val library: LibraryTree by lazy { LibraryTreeImpl(app, api, config, scope) }
}
