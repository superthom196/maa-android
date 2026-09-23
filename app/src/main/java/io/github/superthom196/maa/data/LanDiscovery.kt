package io.github.superthom196.maa.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

private const val TAG = "LanDiscovery"
const val MA_DEFAULT_PORT = 8095

/**
 * Finds Music Assistant servers on the LAN three ways at once (port of MATV's MaDiscovery):
 *  - the addresses we already know, re-probed every 2 s until they answer (the network is
 *    often still coming up),
 *  - mDNS `_mass._tcp` (fast when multicast works, which on phones and some routers it doesn't),
 *  - a sweep of the phone's own /24 hitting `GET /info` on :8095 (slow but reliable).
 * Each server is emitted once (by `server_id`); the flow completes after [WINDOW_MS].
 */
class LanDiscovery(private val context: Context, private val api: MaApi) : ServerDiscovery {

    companion object {
        const val WINDOW_MS = 15_000L
    }

    override fun discover(knownHosts: List<String>): Flow<DiscoveredServer> = callbackFlow {
        // Hit from the known-host loop, the mDNS callbacks and 32 sweep coroutines at once.
        val seen = Collections.synchronizedSet(HashSet<String>())
        fun offer(s: DiscoveredServer) {
            if (seen.add(s.info.serverId)) trySend(s)
        }

        val known = launch(Dispatchers.IO) {
            val pending = knownHosts.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct().toMutableList()
            while (isActive && pending.isNotEmpty()) {
                for (base in pending.toList()) {
                    runCatching { api.fetchInfo(base, timeoutMs = 2500) }
                        .onSuccess { offer(DiscoveredServer(base, it, "saved")); pending.remove(base) }
                        .onFailure { Log.i(TAG, "known host $base not answering: ${it.message}") }
                }
                delay(2000)
            }
        }

        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "mdns start failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = si.host?.hostAddress ?: return
                        val port = if (si.port > 0) si.port else MA_DEFAULT_PORT
                        val base = "http://${if (host.contains(':')) "[$host]" else host}:$port"
                        launch(Dispatchers.IO) {
                            runCatching { api.fetchInfo(base) }.onSuccess { offer(DiscoveredServer(base, it, "mDNS")) }
                        }
                    }
                })
            }
        }
        runCatching { nsd?.discoverServices("_mass._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "mdns unavailable: ${it.message}") }

        val sweep = launch(Dispatchers.IO) {
            // No IPv4 address yet means the link is still coming up: wait for it rather than sweeping nothing.
            var prefixes = localIpv4Prefixes()
            var waited = 0L
            while (prefixes.isEmpty() && waited < WINDOW_MS - 4000 && isActive) {
                delay(1500); waited += 1500
                prefixes = localIpv4Prefixes()
            }
            Log.i(TAG, "sweeping ${prefixes.joinToString()}")
            val gate = Semaphore(32)
            for (prefix in prefixes) {
                for (i in 1..254) {
                    launch {
                        gate.withPermit {
                            val base = "http://$prefix.$i:$MA_DEFAULT_PORT"
                            val info = withTimeoutOrNull(2500) { runCatching { api.fetchInfo(base, timeoutMs = 1000) }.getOrNull() }
                            if (info != null) offer(DiscoveredServer(base, info, "scan"))
                        }
                    }
                }
            }
        }

        val window = launch { delay(WINDOW_MS); channel.close() }

        awaitClose {
            runCatching { nsd?.stopServiceDiscovery(listener) }
            known.cancel()
            sweep.cancel()
            window.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** The /24 prefixes ("192.168.1") of every IPv4 address on the active network. */
    private fun localIpv4Prefixes(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val fromProps = props?.linkAddresses.orEmpty().mapNotNull { la ->
            (la.address as? Inet4Address)?.hostAddress?.substringBeforeLast('.')
        }
        if (fromProps.isNotEmpty()) return fromProps.distinct()
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
