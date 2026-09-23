package io.github.superthom196.maa.browse

import io.github.superthom196.maa.data.NotLoggedInException
import io.github.superthom196.maa.data.OfflineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseCacheTest {
    /**
     * Not backgroundScope: TestScope.advanceUntilIdle() skips background work, and the tests wait
     * for the fire-and-forget disk writes and late server answers.
     */
    private fun TestScope.workScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @get:Rule val tmp = TemporaryFolder()

    private var now = 0L

    private fun TestScope.cache(maxBytes: Long = 20L * 1024 * 1024) = BrowseCache(
        tmp.root, workScope(), clock = { now }, maxBytes = maxBytes, io = StandardTestDispatcher(testScheduler),
    )

    private val tracks = listOf(item("1", "One", "track"), item("2", "Two", "track"))

    @Test fun keysAreStableAndDistinct() {
        assertEquals(BrowseCache.keyOf("album_tracks", listOf("s", "library", "1")), BrowseCache.keyOf("album_tracks", listOf("s", "library", "1")))
        assertNotEquals(BrowseCache.keyOf("c", listOf("a/b", "c")), BrowseCache.keyOf("c", listOf("a", "b/c")))
        assertNotEquals(BrowseCache.keyOf("x", listOf("1")), BrowseCache.keyOf("y", listOf("1")))
        assertTrue(BrowseCache.keyOf("x", emptyList()).matches(Regex("[0-9a-f]{40}")))
    }

    @Test fun networkFirstWritesThroughAndDiskServesWhenOffline() = runTest {
        var calls = 0
        assertEquals(tracks, cache().get("album_tracks", listOf("s", "1")) { calls++; tracks })
        advanceUntilIdle()
        // A new process, offline: the disk copy answers.
        val offline = cache().get("album_tracks", listOf("s", "1")) { calls++; throw OfflineException() }
        assertEquals(tracks.map { it.name }, offline.map { it.name })
        assertEquals(2, calls)
    }

    @Test fun memoryAnswersRepeatsWithinTtl() = runTest {
        val c = cache()
        var calls = 0
        repeat(3) { c.get("playlists", listOf("s")) { calls++; tracks } }
        assertEquals(1, calls)
        now += 6 * 60_000L
        c.get("playlists", listOf("s")) { calls++; tracks }
        assertEquals(2, calls)
        c.clearMemory()
        c.get("playlists", listOf("s")) { calls++; tracks }
        assertEquals(3, calls)
    }

    @Test fun slowServerFallsBackToDiskAfterTimeoutAndLateAnswerLands() = runTest {
        cache().get("k", listOf("s")) { listOf(item("1", "Old", "track")) }
        advanceUntilIdle()
        val c = cache()
        val t0 = currentTime
        val got = c.get("k", listOf("s")) { delay(30_000); listOf(item("1", "New", "track")) }
        assertEquals("Old", got.single().name)
        assertEquals(4_000L, currentTime - t0)
        advanceUntilIdle()
        assertEquals("New", c.get("k", listOf("s")) { error("memory should answer") }.single().name)
    }

    @Test fun slowServerWithoutDiskCopyIsAwaited() = runTest {
        val got = cache().get("k", listOf("s")) { delay(30_000); tracks }
        assertEquals(tracks, got)
    }

    @Test fun bothFailingRethrows() = runTest {
        try {
            cache().get("k", listOf("s")) { throw OfflineException() }
            fail("expected OfflineException")
        } catch (_: OfflineException) {
        }
    }

    @Test fun signedOutIsNotMaskedByDisk() = runTest {
        cache().get("k", listOf("s")) { tracks }
        advanceUntilIdle()
        try {
            cache().get("k", listOf("s")) { throw NotLoggedInException() }
            fail("expected NotLoggedInException")
        } catch (_: NotLoggedInException) {
        }
    }

    @Test fun peekIsLocalOnly() = runTest {
        val c = cache()
        assertNull(c.peek("k", listOf("s")))
        c.get("k", listOf("s")) { tracks }
        assertEquals(tracks, c.peek("k", listOf("s")))
        advanceUntilIdle()
        assertEquals(tracks.map { it.name }, cache().peek("k", listOf("s"))?.map { it.name })
    }

    @Test fun evictsLeastRecentlyUsedOverCap() = runTest {
        val big = (1..40).map { item("$it", "Track number $it with a longish name", "track") }
        now = 1_000_000
        cache(maxBytes = Long.MAX_VALUE).get("k", listOf("a")) { big }
        advanceUntilIdle()
        val size = tmp.root.listFiles()!!.single { it.name.endsWith(".json") }.length()

        val c = cache(maxBytes = size * 2 + size / 2) // room for two lists
        now += 1_000; c.get("k", listOf("b")) { big }; advanceUntilIdle()
        now += 1_000; c.peek("k", listOf("a")) // a read makes "a" more recent than "b"
        now += 1_000; c.get("k", listOf("c")) { big }; advanceUntilIdle()

        val left = tmp.root.listFiles()!!.filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.toSet()
        assertEquals(setOf(BrowseCache.keyOf("k", listOf("a")), BrowseCache.keyOf("k", listOf("c"))), left)
    }
}
