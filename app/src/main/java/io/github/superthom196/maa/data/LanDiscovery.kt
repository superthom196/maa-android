package io.github.superthom196.maa.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** WS-B: port of MATV's MaDiscovery. Stub. */
class LanDiscovery(context: Context, api: MaApi) : ServerDiscovery {
    override fun discover(knownHosts: List<String>): Flow<DiscoveredServer> = TODO()
}
