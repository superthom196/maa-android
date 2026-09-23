package io.github.superthom196.maa.playback

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueStoreTest {
    private val full = StoredItem(
        mediaId = "track/album/library/12/library/345",
        uri = StreamUris.trackUri(TrackRef("library", "345"), "opus-192"),
        cacheKey = StreamUris.cacheKey("srv", TrackRef("library", "345"), "opus-192"),
        mimeType = "audio/ogg",
        title = "Söng \"quoted\"",
        artist = "Artist",
        album = "Album",
        artworkUri = "content://io.github.superthom196.maa.art/proxy/abc?size=512",
        durationMs = 245_000,
    )
    private val bare = StoredItem(mediaId = "x", uri = StreamUris.trackUri(TrackRef("filesystem--1", "a/b.flac"), "flac-16-44"))

    @Test fun roundTrip() {
        val q = StoredQueue(listOf(full, bare), currentIndex = 1, positionMs = 61_500, shuffle = true, repeat = 2)
        assertEquals(q, QueueStore.decode(QueueStore.encode(q)))
    }

    @Test fun nullsAreOmittedAndRestored() {
        val json = QueueStore.encode(StoredQueue(listOf(bare)))
        assertEquals(false, json.contains("null"))
        assertEquals(bare, QueueStore.decode(json)!!.items.single())
    }

    @Test fun rejectsGarbageAndEmptyQueues() {
        assertNull(QueueStore.decode(""))
        assertNull(QueueStore.decode("{not json"))
        assertNull(QueueStore.decode(QueueStore.encode(StoredQueue(emptyList()))))
    }

    @Test fun clampsIndexAndPosition() {
        val q = QueueStore.decode(QueueStore.encode(StoredQueue(listOf(full, bare), currentIndex = 7, positionMs = -5)))!!
        assertEquals(1, q.currentIndex)
        assertEquals(0L, q.positionMs)
    }

    @Test fun toleratesUnknownFields() {
        val q = QueueStore.decode("""{"items":[{"mediaId":"x","uri":"maa://track?provider=a&item_id=b&format=opus-192","extra":1}],"future":true}""")!!
        assertEquals("x", q.items.single().mediaId)
    }

    @Test fun writesAndReadsFile() {
        val dir = Files.createTempDirectory("queue").toFile()
        try {
            val store = QueueStore(File(dir, "queue.json"))
            assertNull(store.read())
            val q = StoredQueue(listOf(full), positionMs = 1_000)
            store.write(q)
            assertEquals(q, store.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
