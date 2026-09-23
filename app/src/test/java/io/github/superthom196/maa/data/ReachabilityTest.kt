package io.github.superthom196.maa.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LAN = "http://192.168.0.150:8095"
private const val REMOTE = "http://100.97.96.36:8095"

class EndpointSelectorTest {
    private val order = listOf(LAN, REMOTE)

    @Test fun prefersLan() {
        assertEquals(LAN, EndpointSelector.choose(listOf(ProbeResult(REMOTE, "s1"), ProbeResult(LAN, "s1")), "s1", order))
    }

    @Test fun fallsBackToRemote() {
        assertEquals(REMOTE, EndpointSelector.choose(listOf(ProbeResult(LAN, null), ProbeResult(REMOTE, "s1")), "s1", order))
    }

    @Test fun wrongServerIdIsRejected() {
        assertEquals(REMOTE, EndpointSelector.choose(listOf(ProbeResult(LAN, "other"), ProbeResult(REMOTE, "s1")), "s1", order))
        assertNull(EndpointSelector.choose(listOf(ProbeResult(LAN, "other"), ProbeResult(REMOTE, null)), "s1", order))
    }

    @Test fun trailingSlashesDoNotMatter() {
        assertEquals(LAN, EndpointSelector.choose(listOf(ProbeResult("$LAN/", "s1")), "s1", listOf("$LAN/")))
    }

    @Test fun nothingToChooseFrom() {
        assertNull(EndpointSelector.choose(emptyList(), "s1", order))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReachabilityTest {
    private class FakeConfig(initial: ServerConfig?) : ConfigStore {
        override val server = MutableStateFlow(initial)
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun saveServer(cfg: ServerConfig) { server.value = cfg }
        override suspend fun updateServer(transform: (ServerConfig) -> ServerConfig) { server.value = server.value?.let(transform) }
        override suspend fun saveSettings(s: PlaybackSettings) { settings.value = s }
        override suspend fun signOut() { server.value = null }
    }

    private class FakeNetwork : NetworkMonitor {
        override val online = MutableStateFlow(true)
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    }

    private val cfg = ServerConfig("s1", "serverpi", LAN, REMOTE, "tok")

    /** Which server id each URL answers with right now; absent = no answer. */
    private val answers = mutableMapOf(LAN to "s1", REMOTE to "s1")
    private var probes = 0

    private fun TestScope.reachability(config: ConfigStore, network: NetworkMonitor) = Reachability(
        OkHttpClient(), config, network, backgroundScope,
        now = { testScheduler.currentTime },
        probe = { url -> probes++; delay(100); answers[url]?.let { ServerInfo(serverId = it) } },
        log = {},
    )

    @Test fun picksLanAtStartup() = runTest {
        val r = reachability(FakeConfig(cfg), FakeNetwork())
        runCurrent(); advanceTimeBy(200)
        assertEquals(LAN, r.current.value)
    }

    @Test fun awaitBaseProbesAndWaits() = runTest {
        answers.remove(LAN)
        val r = reachability(FakeConfig(cfg), FakeNetwork())
        assertEquals(REMOTE, r.awaitBase(1_000))
    }

    @Test fun awaitBaseTimesOutWithNull() = runTest {
        answers.clear()
        val r = reachability(FakeConfig(cfg), FakeNetwork())
        assertNull(r.awaitBase(1_000))
    }

    @Test fun awaitBaseWithoutServerIsImmediatelyNull() = runTest {
        val r = reachability(FakeConfig(null), FakeNetwork())
        assertNull(r.awaitBase(60_000))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun offlineClearsWithoutProbing() = runTest {
        val net = FakeNetwork()
        val r = reachability(FakeConfig(cfg), net)
        runCurrent(); advanceTimeBy(200)
        assertEquals(LAN, r.current.value)
        val before = probes
        net.online.value = false
        runCurrent(); advanceTimeBy(5_000)
        assertNull(r.current.value)
        assertEquals(before, probes)
    }

    @Test fun failureSwitchesToRemote() = runTest {
        val r = reachability(FakeConfig(cfg), FakeNetwork())
        runCurrent(); advanceTimeBy(200)
        assertEquals(LAN, r.current.value)
        answers.remove(LAN) // left home
        r.reportFailure()
        advanceTimeBy(Reachability.FAILURE_MIN_INTERVAL_MS + 200)
        assertEquals(REMOTE, r.current.value)
    }

    @Test fun failuresAreRateLimited() = runTest {
        val r = reachability(FakeConfig(cfg), FakeNetwork())
        runCurrent(); advanceTimeBy(200)
        val before = probes
        repeat(20) { r.reportFailure(); runCurrent() }
        advanceTimeBy(Reachability.FAILURE_MIN_INTERVAL_MS + 500)
        assertTrue("probes ${probes - before}", probes - before <= 4) // at most two rounds of two URLs
    }

    @Test fun networkChangeReprobesAfterDebounce() = runTest {
        val net = FakeNetwork()
        val r = reachability(FakeConfig(cfg), net)
        runCurrent(); advanceTimeBy(200)
        answers[LAN] = "someone-else" // the LAN address now belongs to another box
        net.changes.tryEmit(Unit); net.changes.tryEmit(Unit)
        advanceTimeBy(Reachability.NETWORK_DEBOUNCE_MS + 300)
        assertEquals(REMOTE, r.current.value)
    }

    @Test fun retriesEveryMinuteWhileNothingAnswers() = runTest {
        answers.clear()
        val r = reachability(FakeConfig(cfg), FakeNetwork())
        runCurrent(); advanceTimeBy(200)
        assertNull(r.current.value)
        answers[REMOTE] = "s1"
        advanceTimeBy(Reachability.RETRY_INTERVAL_MS + 200)
        assertEquals(REMOTE, r.current.value)
    }

    @Test fun signOutClearsAndNewServerIsProbed() = runTest {
        val config = FakeConfig(cfg)
        val r = reachability(config, FakeNetwork())
        runCurrent(); advanceTimeBy(200)
        config.signOut()
        runCurrent(); advanceTimeBy(200)
        assertNull(r.current.value)
        answers["http://10.0.0.2:8095"] = "s2"
        config.saveServer(ServerConfig("s2", "other", "http://10.0.0.2:8095", null, "t2"))
        runCurrent(); advanceTimeBy(200)
        assertEquals("http://10.0.0.2:8095", r.current.value)
    }
}
