package io.github.superthom196.maa.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import io.github.superthom196.maa.data.ConfigStore
import io.github.superthom196.maa.data.MaApi
import io.github.superthom196.maa.data.NetworkMonitor
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keeps the current track and the next `settings.lookahead` ones fully downloaded, so the music
 * survives the network going away. This is why the app exists: streaming only buffers ~50 s, a
 * tunnel or a dead zone lasts longer.
 *
 * The only writer to the [AudioCache]. Two halves:
 * - main thread: the player is main-looper bound, so its queue is read there (on queue, shuffle and
 *   repeat changes, when the network comes back, and every minute) into a list of [Target]s;
 * - one IO coroutine downloads those in order, current first, with a blocking [CacheWriter].
 *   A download whose track is no longer a target is cancelled; one that still is carries on.
 *   Failures back off 2 s → 60 s, reset when the network comes back.
 *
 * Targets are also held to [PrefetchBudget], pinned against eviction, and announced to the server
 * (`/maa/prepare`) so it transcodes them ahead of our requests.
 */
@OptIn(UnstableApi::class)
class Prefetcher(
    private val player: Player,
    private val audioCache: AudioCache,
    private val sources: MaaDataSources,
    private val config: ConfigStore,
    private val network: NetworkMonitor,
    private val api: MaApi,
    parent: CoroutineScope,
) {
    data class Target(
        val uri: String,
        val key: String,
        val ref: TrackRef,
        val format: String,
        val durationMs: Long?,
    )

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var desired: List<Target> = emptyList()
    @Volatile private var inFlight: Download? = null
    @Volatile private var resetBackoff = false

    /** Real lengths learned while downloading; they outlive a cancelled download's metadata. */
    private val knownLengths = ConcurrentHashMap<String, Long>()

    /**
     * Keys the server refused for good (404 and friends) until the given time, so one missing
     * track does not hold up the ones after it. The player skips such a track by itself.
     */
    private val refused = ConcurrentHashMap<String, Long>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) refresh()
        }
    }

    /** Call on the main thread. */
    fun start() {
        player.addListener(listener)
        audioCache.cancelWrites = {
            inFlight?.cancel()
            // Wake an idle worker: it waits for the clear to finish, then downloads the window again.
            wake.trySend(Unit)
        }
        scope.launch(Dispatchers.Main.immediate) {
            launch { network.changes.collect { networkBack() } }
            launch { network.online.drop(1).collect { if (it) networkBack() } }
            launch { config.settings.map { it.lookahead }.distinctUntilChanged().collect { refresh() } }
            launch { config.settings.map { it.cacheMb }.distinctUntilChanged().drop(1).collect { wake.trySend(Unit) } }
            launch {
                while (isActive) {
                    delay(TICK_MS)
                    refresh()
                }
            }
        }
        scope.launch(Dispatchers.IO) { work() }
    }

    fun release() {
        player.removeListener(listener)
        audioCache.cancelWrites = {}
        inFlight?.cancel()
        scope.cancel()
    }

    private fun networkBack() {
        resetBackoff = true
        refresh()
        wake.trySend(Unit)
    }

    /** Main thread: snapshot the window and hand it to the worker if it changed. */
    fun refresh() {
        val timeline = player.currentTimeline
        val list = if (timeline.isEmpty) emptyList() else {
            val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
            val shuffle = player.shuffleModeEnabled
            val lookahead = config.settings.value.lookahead
            PrefetchWindow.targets(player.currentMediaItemIndex, lookahead) { timeline.getNextWindowIndex(it, repeat, shuffle) }
                .mapNotNull { player.getMediaItemAt(it).toTarget() }
                .distinctBy { it.key }
        }
        if (list == desired) return
        desired = list
        inFlight?.let { d -> if (list.none { it.key == d.key }) d.cancel() }
        wake.trySend(Unit)
    }

    private fun MediaItem.toTarget(): Target? {
        val uri = localConfiguration?.uri?.toString() ?: return null
        val (ref, format) = StreamUris.parse(uri) ?: return null
        val key = localConfiguration?.customCacheKey ?: sources.keyFor(uri)
        return Target(uri, key, ref, format, mediaMetadata.durationMs)
    }

    private sealed interface Outcome {
        data object Done : Outcome
        data object Cancelled : Outcome
        data class Failed(val e: IOException) : Outcome
    }

    private suspend fun work() {
        var failures = 0
        var announced: List<String> = emptyList()
        while (scope.isActive) {
            audioCache.awaitWritable()
            if (resetBackoff) {
                resetBackoff = false
                failures = 0
            }
            val targets = desired
            val plan = plan(targets)
            audioCache.pin(plan.fitting.mapTo(HashSet()) { it.key })

            val keys = targets.map { it.key }
            if (keys != announced) {
                announced = keys
                announce(targets.filterNot { audioCache.isFullyCached(it.key) })
            }

            val now = System.currentTimeMillis()
            refused.values.removeAll { it < now }
            val index = plan.fitting.indexOfFirst { !refused.containsKey(it.key) && !audioCache.isFullyCached(it.key) }
            if (index < 0) {
                publish(plan, null)
                // The timeout is a safety net; normally a queue change or the network wakes us.
                withTimeoutOrNull(TICK_MS) { wake.receive() }
                continue
            }
            when (val outcome = download(plan, index)) {
                Outcome.Done -> failures = 0
                Outcome.Cancelled -> Unit
                is Outcome.Failed -> {
                    failures++
                    val e = outcome.e
                    val status = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    val wait = when {
                        // The server is transcoding (it already held us up to 180 s): come back soon.
                        status == 503 -> 5_000L
                        else -> backoffMs(failures)
                    }
                    val message = when {
                        !network.online.value || e is NoServerException -> "Waiting for network"
                        status == 503 -> "Server is preparing ${index + 1}/${targets.size}"
                        status == 401 || status == 403 -> "Sign in again on the phone"
                        else -> "Download failed, retrying"
                    }
                    if (status != null && RetryingLoadErrorPolicy.retryDelayMs(status, null, false, 1) == RetryingLoadErrorPolicy.FATAL &&
                        status != 401 && status != 403
                    ) {
                        refused[plan.fitting[index].key] = System.currentTimeMillis() + REFUSED_MS
                    }
                    Log.w(TAG, "download ${index + 1}/${targets.size} failed (${status ?: e.javaClass.simpleName}: ${e.message}); retry in ${wait / 1000} s")
                    publish(plan, message)
                    withTimeoutOrNull(wait) { wake.receive() }
                }
            }
        }
    }

    private class Plan(val targets: List<Target>, val sizes: List<Long>, val fitting: List<Target>) {
        fun bytesBefore(index: Int): Long = sizes.take(index).sum()
    }

    private fun plan(targets: List<Target>): Plan {
        val sizes = targets.map { t ->
            knownLengths[t.key]
                ?: audioCache.contentLength(t.key).takeIf { it > 0 }
                ?: PrefetchBudget.estimateBytes(t.format, t.durationMs)
        }
        val fit = PrefetchBudget.fit(sizes, PrefetchBudget.budget(audioCache.maxBytes))
        return Plan(targets, sizes, targets.take(fit))
    }

    private suspend fun download(plan: Plan, index: Int): Outcome = audioCache.writeLock.withLock {
        if (!audioCache.writable) return Outcome.Cancelled
        val target = plan.fitting[index]
        val calls = CancellableCalls(sources.client)
        val dataSource = sources.prefetch(calls).createDataSourceForDownloading()
        val spec = DataSpec.Builder().setUri(target.uri).setKey(target.key).build()
        val budget = PrefetchBudget.budget(audioCache.maxBytes)
        val before = plan.bytesBefore(index)
        val download = Download(target.key, calls)
        var lastPercent = -1
        val writer = CacheWriter(dataSource, spec, null) { length, cached, _ ->
            if (length != C.LENGTH_UNSET.toLong()) {
                if (knownLengths.put(target.key, length) == null && index > 0 && before + length > budget) {
                    // Bigger than estimated and it no longer fits: stop, the next plan drops it.
                    Log.i(TAG, "${index + 1}/${plan.targets.size} is ${length / MB} MB, over the cache budget")
                    download.cancel()
                }
                val percent = (cached * 100 / length.coerceAtLeast(1)).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    publish(plan, "Downloading ${index + 1}/${plan.targets.size} · $percent%")
                    audioCache.refreshUsage()
                }
            }
        }
        download.writer = writer
        inFlight = download
        // The list may have moved on between planning and here.
        if (desired.none { it.key == target.key }) download.cancel()
        val started = System.currentTimeMillis()
        try {
            writer.cache()
            if (audioCache.isFullyCached(target.key)) {
                Log.i(TAG, "cached ${index + 1}/${plan.targets.size} (${(knownLengths[target.key] ?: 0) / MB} MB, ${(System.currentTimeMillis() - started) / 1000} s)")
                Outcome.Done
            } else {
                Outcome.Failed(IOException("incomplete download"))
            }
        } catch (e: IOException) {
            if (download.cancelled) Outcome.Cancelled else Outcome.Failed(e)
        } finally {
            inFlight = null
            audioCache.refreshUsage()
        }
    }

    private fun announce(missing: List<Target>) {
        if (missing.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            missing.groupBy { it.format }.forEach { (format, group) ->
                runCatching { api.prepare(group.map { it.ref }.take(10), format) }
                    .onFailure { Log.w(TAG, "prepare failed: ${it.message}") }
            }
        }
    }

    private fun publish(plan: Plan, activity: String?) {
        val total = plan.targets.size
        val cached = plan.targets.count { audioCache.isFullyCached(it.key) }
        val message = when {
            activity != null -> activity
            total == 0 -> ""
            plan.fitting.size < total -> "$cached/$total on phone · cache fits ${plan.fitting.size}"
            else -> "$cached/$total on phone"
        }
        audioCache.prefetchState.value = PrefetchStatus(total, cached, message)
    }

    /** One download in flight: cancelling stops the writer and the HTTP call the server may be holding. */
    private class Download(val key: String, private val calls: CancellableCalls) {
        @Volatile var writer: CacheWriter? = null
        @Volatile var cancelled = false
            private set

        fun cancel() {
            cancelled = true
            writer?.cancel()
            calls.cancel()
        }
    }

    private class CancellableCalls(private val client: OkHttpClient) : Call.Factory {
        @Volatile private var call: Call? = null
        @Volatile private var cancelled = false

        override fun newCall(request: Request): Call = client.newCall(request).also {
            call = it
            if (cancelled) it.cancel()
        }

        fun cancel() {
            cancelled = true
            call?.cancel()
        }
    }

    companion object {
        private const val TAG = "MAA/Prefetch"
        private const val TICK_MS = 60_000L
        private const val REFUSED_MS = 10 * 60_000L
        private const val MB = 1024 * 1024

        /** 2 s, 4 s, 8 s … capped at 60 s. */
        fun backoffMs(failures: Int): Long = (2_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(60_000L)
    }
}
