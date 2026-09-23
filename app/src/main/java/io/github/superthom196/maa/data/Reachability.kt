package io.github.superthom196.maa.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "Reachability"

/** One probe of `{url}/info`: [serverId] is null when the URL did not answer as Music Assistant. */
data class ProbeResult(val url: String, val serverId: String?)

object EndpointSelector {
    /**
     * The first URL in [preferenceOrder] (LAN before remote) whose probe answered with
     * [expectedServerId]. Another server at the same address (a new DHCP lease, a different
     * Tailscale node) is as good as no answer: its library ids would mean different tracks.
     */
    fun choose(results: List<ProbeResult>, expectedServerId: String, preferenceOrder: List<String>): String? {
        val ok = results.filter { it.serverId == expectedServerId }.map { it.url.trimEnd('/') }.toSet()
        return preferenceOrder.map { it.trimEnd('/') }.firstOrNull { it in ok }
    }
}

/**
 * Picks which of [ServerConfig.urls] reaches the server right now: the LAN URL at home, the
 * Tailscale one in the car. Probes are unauthenticated `GET /info` calls checked against the
 * saved `server_id`, all URLs in parallel, 2.5 s each.
 *
 * Re-probes when the saved URLs change, when the default network changes (1 s debounce), when a
 * caller reports a failed request (at most every 3 s), and every 60 s while nothing answers but
 * the device is online. While offline [current] is null without probing. Probes run one at a
 * time on a single worker, so an old answer can never overwrite a newer one.
 *
 * @param now, probe, log test seams; [probe] defaults to an HTTP `GET {url}/info`.
 */
@OptIn(FlowPreview::class)
class Reachability(
    http: OkHttpClient,
    private val config: ConfigStore,
    private val network: NetworkMonitor,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    probe: (suspend (String) -> ServerInfo?)? = null,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : BaseUrlProvider {
    private val probeHttp by lazy { http.newBuilder().callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).build() }
    private val probeFn: suspend (String) -> ServerInfo? = probe ?: ::httpProbe

    private val _current = MutableStateFlow<String?>(null)
    override val current: StateFlow<String?> = _current.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    @Volatile private var lastProbeAt = 0L

    init {
        scope.launch { for (r in requests) runProbe() }
        scope.launch {
            config.server.map { it?.let { c -> c.serverId to c.urls } }.distinctUntilChanged().collect {
                _current.value = null // the old choice may belong to the old server
                request()
            }
        }
        scope.launch { network.changes.debounce(NETWORK_DEBOUNCE_MS).collect { request() } }
        scope.launch {
            network.online.drop(1).collect { online -> if (online) request() else _current.value = null }
        }
        scope.launch {
            failures.conflate().collect {
                val wait = lastProbeAt + FAILURE_MIN_INTERVAL_MS - now()
                if (wait > 0) delay(wait)
                request()
            }
        }
        scope.launch {
            while (true) {
                delay(RETRY_INTERVAL_MS)
                if (_current.value == null && network.online.value && config.server.value != null) request()
            }
        }
    }

    override suspend fun awaitBase(timeoutMs: Long): String? {
        _current.value?.let { return it }
        if (config.server.value == null) return null
        request()
        return withTimeoutOrNull(timeoutMs) { _current.filterNotNull().first() }
    }

    override fun reportFailure() {
        failures.tryEmit(Unit)
    }

    private fun request() {
        requests.trySend(Unit)
    }

    private suspend fun runProbe() {
        val cfg = config.server.value
        if (cfg == null || !network.online.value) {
            _current.value = null
            return
        }
        val urls = cfg.urls.map { it.trimEnd('/') }
        val results = coroutineScope {
            urls.map { u -> async { ProbeResult(u, safeProbe(u)?.serverId) } }.awaitAll()
        }
        lastProbeAt = now()
        // A sign-out, a new server or losing the network while the probes ran: this answer is stale.
        val still = config.server.value
        if (still == null || still.serverId != cfg.serverId || still.urls != cfg.urls || !network.online.value) {
            if (still != null && network.online.value) request()
            return
        }
        val chosen = EndpointSelector.choose(results, cfg.serverId, urls)
        if (chosen != _current.value) log("base URL ${_current.value} -> $chosen (${results.joinToString { "${it.url}=${it.serverId?.take(8)}" }})")
        _current.value = chosen
    }

    private suspend fun safeProbe(url: String): ServerInfo? = try {
        probeFn(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun httpProbe(url: String): ServerInfo? {
        val req = Request.Builder().url("$url/info").get().build()
        return probeHttp.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = withContext(Dispatchers.IO) { resp.body.string() }
            runCatching { maJson.decodeFromString(ServerInfo.serializer(), body) }.getOrNull()
        }
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 2_500L
        const val NETWORK_DEBOUNCE_MS = 1_000L
        const val FAILURE_MIN_INTERVAL_MS = 3_000L
        const val RETRY_INTERVAL_MS = 60_000L
    }
}
