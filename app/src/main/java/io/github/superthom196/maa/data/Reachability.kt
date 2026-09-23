package io.github.superthom196.maa.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/** WS-B: picks the reachable URL among [ServerConfig.urls]. Stub. */
class Reachability(http: OkHttpClient, config: ConfigStore, network: NetworkMonitor, scope: CoroutineScope) : BaseUrlProvider {
    override val current: StateFlow<String?> get() = TODO()
    override suspend fun awaitBase(timeoutMs: Long): String? = TODO()
    override fun reportFailure(): Unit = TODO()
}
