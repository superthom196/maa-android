package io.github.superthom196.maa.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** WS-B: ConnectivityManager-backed [NetworkMonitor]. Stub. */
class AndroidNetworkMonitor(context: Context, scope: CoroutineScope) : NetworkMonitor {
    override val online: StateFlow<Boolean> get() = TODO()
    override val changes: Flow<Unit> get() = TODO()
}
