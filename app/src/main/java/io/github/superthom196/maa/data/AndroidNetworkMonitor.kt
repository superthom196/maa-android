package io.github.superthom196.maa.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "NetworkMonitor"

/**
 * The default network, as [ConnectivityManager] sees it. "Online" deliberately means only that a
 * default network with the INTERNET capability exists, not that Android validated it: a home Wi-Fi
 * whose uplink is down, or a Tailscale VPN, still reaches the server, and Reachability's probes
 * are the real test.
 */
class AndroidNetworkMonitor(
    context: Context,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) : NetworkMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(initialOnline())
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: Flow<Unit> = _changes.asSharedFlow()

    // Callbacks arrive on ConnectivityManager's own thread, one at a time.
    @Volatile private var current: Network? = null
    @Volatile private var transports: Int = -1

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            current = network
            transports = -1
            _online.value = cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
            _changes.tryEmit(Unit)
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            transports = -1
            _online.value = false
            _changes.tryEmit(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != current) return
            _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Signal strength and bandwidth updates arrive here constantly; only a new route
            // (a VPN coming up, Wi-Fi joined on top of mobile) is worth a re-probe.
            val t = transportMask(caps)
            if (transports != -1 && t != transports) _changes.tryEmit(Unit)
            transports = t
        }
    }

    init {
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "network callback unavailable: ${it.message}") }
    }

    private fun initialOnline(): Boolean {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun transportMask(caps: NetworkCapabilities): Int =
        TRANSPORTS.foldIndexed(0) { i, acc, t -> if (caps.hasTransport(t)) acc or (1 shl i) else acc }

    private companion object {
        val TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
        )
    }
}
