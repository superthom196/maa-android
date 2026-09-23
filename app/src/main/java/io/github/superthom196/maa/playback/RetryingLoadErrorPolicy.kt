package io.github.superthom196.maa.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import kotlin.math.min

/**
 * Retries a failed load for as long as it takes, instead of the stock three attempts. With the
 * stock policy a dead zone on the motorway turns into a player error and, via the error handling,
 * a skip through the whole queue; with this one the player sits in BUFFERING and carries on by
 * itself once the network (or the server's transcode) is back.
 *
 * Only answers that retrying cannot fix are fatal: 4xx other than 408/429, and what the stock
 * policy already treats as non-retriable (malformed media, cleartext refused, …).
 */
@OptIn(UnstableApi::class)
class RetryingLoadErrorPolicy(private val onNetworkFailure: () -> Unit) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        val http = e as? HttpDataSource.InvalidResponseCodeException
        val nonRetriable = super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET
        val delay = retryDelayMs(
            status = http?.responseCode,
            retryAfterSeconds = http?.headerFields?.let(::retryAfter),
            nonRetriable = nonRetriable,
            errorCount = loadErrorInfo.errorCount,
        )
        // A socket-level failure against the current base URL: let Reachability re-probe (LAN
        // may just have become Tailscale). Not for NoServerException, which already is that probe.
        if (http == null && e is HttpDataSource.HttpDataSourceException) onNetworkFailure()
        return delay
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    /** One URL per item: there is nothing to fall back to, only retrying. */
    override fun getFallbackSelectionFor(fallbackOptions: FallbackOptions, loadErrorInfo: LoadErrorInfo): FallbackSelection? = null

    companion object {
        const val FATAL = C.TIME_UNSET
        private const val MAX_DELAY_MS = 5_000L

        /** The decision, as a pure function: a delay in ms, or [FATAL]. */
        fun retryDelayMs(status: Int?, retryAfterSeconds: Long?, nonRetriable: Boolean, errorCount: Int): Long {
            if (nonRetriable) return FATAL
            if (status != null && status in 400..499 && status != 408 && status != 429) return FATAL
            // The plugin answers 503 with Retry-After: 5 while a transcode is still running (after
            // having held the request up to 180 s) and 30 when the provider is down.
            if (status == 503) return ((retryAfterSeconds ?: 5L) * 1000).coerceIn(1_000L, 10_000L)
            return min(1_000L * errorCount.coerceAtLeast(1), MAX_DELAY_MS)
        }

        fun retryAfter(headers: Map<String, List<String>>): Long? =
            headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.firstOrNull()?.trim()?.toLongOrNull()
    }
}

