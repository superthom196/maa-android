package io.github.superthom196.maa.browse

import io.github.superthom196.maa.data.OfflineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryIndexTest {
    /**
     * Not backgroundScope: TestScope.advanceUntilIdle() skips background work, and the tests wait
     * for the fire-and-forget disk writes and late server answers.
     */
    private fun TestScope.workScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @get:Rule val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- pure

    @Test fun foldKeyIgnoresCaseAccentsAndPunctuation() {
        assertEquals("bjork", foldKey("Björk"))
        assertEquals("olafur arnalds", foldKey("  Ólafur\tArnalds "))
        assertEquals("acdc", foldKey("AC/DC"))
        assertEquals("oystein sevag", foldKey("Øystein Sevåg"))
        assertEquals("日本", foldKey("日本"))
    }

    @Test fun buckets() {
        assertEquals("B", bucketOf(foldKey("Björk")))
        assertEquals("E", bucketOf(foldKey("Élodie")))
        assertEquals("#", bucketOf(foldKey("2Pac")))
        assertEquals("#", bucketOf(foldKey("!!!")))
        assertEquals("#", bucketOf(foldKey("")))
        assertEquals("#", bucketOf(foldKey("坂本龍一")))
    }

    @Test fun sortsBySortNameCaseAndAccentInsensitiveWithHashLast() {
        val items = listOf(
            item("1", "zebra"),
            item("2", "The Beatles", sortName = "Beatles, The"),
            item("3", "Ábba"),
            item("4", "10cc"),
            item("5", "ace"),
            item("6", "Bach"),
        )
        assertEquals(listOf("Ábba", "ace", "Bach", "The Beatles", "zebra", "10cc"), sortForIndex(items).map { it.name })
    }

    @Test fun bucketRunsAndLookup() {
        val sorted = sortForIndex(listOf(item("1", "Alpha"), item("2", "Apple"), item("3", "Cat"), item("4", "123")))
        val b = bucketsOf(sorted)
        assertEquals(listOf(Bucket("A", 0, 2), Bucket("C", 2, 1), Bucket("#", 3, 1)), b)
        assertEquals(listOf("Alpha", "Apple"), itemsInBucket(sorted, b, "A").map { it.name })
        assertEquals(listOf("123"), itemsInBucket(sorted, b, "#").map { it.name })
        assertTrue(itemsInBucket(sorted, b, "B").isEmpty())
    }

    // ---------------------------------------------------------------- loading

    private fun TestScope.index(api: FakeMaApi, now: () -> Long) = LibraryIndex(
        tmp.root, api, workScope(), clock = now, pageSize = 2,
        io = StandardTestDispatcher(testScheduler), cpu = StandardTestDispatcher(testScheduler),
    )

    @Test fun firstUseFetchesAllPagesAndPersists() = runTest {
        val api = FakeMaApi().apply {
            artists = listOf(item("a1", "Zappa", "artist"), item("a2", "Abba", "artist"), item("a3", "Moby", "artist"))
            albums = listOf(item("b1", "Kid A", artist = "Radiohead", year = 2000), item("b2", "Blue"))
        }
        val snap = index(api) { 1_000L }.get("srv")
        assertEquals(listOf("Abba", "Moby", "Zappa"), snap.artists.map { it.name })
        assertEquals(listOf("Blue", "Kid A"), snap.albums.map { it.name })
        assertEquals("Radiohead", snap.album("library", "b1")?.artistLine)
        assertEquals("artist", snap.artist("library", "a1")?.mediaType)
        // pages of 2: artists 3 → 2 calls, albums 2 → 2 calls (a full page asks for more)
        assertEquals(2, api.calls.count { it == "music/artists/library_items" })
        assertEquals(2, api.calls.count { it == "libraryItems:albums" })
        advanceUntilIdle()
        assertTrue(tmp.root.resolve("index-srv.json").isFile)
    }

    @Test fun laterUseReadsDiskWithoutNetworkWhileFresh() = runTest {
        val api = FakeMaApi().apply { albums = listOf(item("b1", "Kid A")) }
        index(api) { 1_000L }.get("srv")
        advanceUntilIdle()
        api.calls.clear()
        api.failWith = OfflineException()
        // A new process 10 minutes later: disk copy, no request.
        val snap = index(api) { 1_000L + 10 * 60_000L }.get("srv")
        advanceUntilIdle()
        assertEquals(listOf("Kid A"), snap.albums.map { it.name })
        assertTrue(api.calls.isEmpty())
        // Another server's index is not ours.
        try {
            index(api) { 1_000L }.get("other")
            fail("expected OfflineException")
        } catch (_: OfflineException) {
        }
    }

    @Test fun staleIndexIsServedThenRefreshedInBackground() = runTest {
        val api = FakeMaApi().apply { albums = listOf(item("b1", "Old")) }
        index(api) { 0L }.get("srv")
        advanceUntilIdle()
        api.albums = listOf(item("b1", "Old"), item("b2", "New"))
        val idx = index(api) { 13 * 3600_000L }
        assertEquals(listOf("Old"), idx.get("srv").albums.map { it.name }) // served at once from disk
        advanceUntilIdle()
        assertEquals(listOf("New", "Old"), idx.peek("srv")!!.albums.map { it.name })
    }

    @Test fun failedRefreshKeepsServingTheCopy() = runTest {
        val api = FakeMaApi().apply { albums = listOf(item("b1", "Kept")) }
        index(api) { 0L }.get("srv")
        advanceUntilIdle()
        api.failWith = OfflineException()
        val idx = index(api) { 13 * 3600_000L }
        assertEquals("Kept", idx.get("srv").albums.single().name)
        advanceUntilIdle()
        assertNotNull(idx.peek("srv"))
        assertEquals("Kept", idx.get("srv").albums.single().name)
    }

    @Test fun peekNeverLoads() = runTest {
        assertNull(index(FakeMaApi()) { 0L }.peek("srv"))
    }
}
