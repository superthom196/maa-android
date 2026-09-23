package io.github.superthom196.maa.playback

import kotlinx.coroutines.flow.StateFlow

/** What the prefetcher has on the phone for the current look-ahead window. */
data class PrefetchStatus(
    /** Current track plus look-ahead targets. */
    val targets: Int = 0,
    /** How many of those are fully on the phone. */
    val cached: Int = 0,
    /** Human-readable state, e.g. "Downloading 2/4", "Waiting for network". */
    val message: String = "",
)

/** The on-phone audio cache, as the Settings screen sees it. Implemented by [AudioCache]. */
interface CacheControl {
    val usedBytes: StateFlow<Long>
    val prefetch: StateFlow<PrefetchStatus>
    suspend fun clear()
}
