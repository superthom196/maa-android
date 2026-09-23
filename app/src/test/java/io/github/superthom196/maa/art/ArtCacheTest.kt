package io.github.superthom196.maa.art

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ArtRequestTest {
    private fun parse(path: String, vararg q: Pair<String, String>): ArtRequest? =
        ArtRequest.parse(path.split('/').filter { it.isNotEmpty() }) { name -> q.toMap()[name] }

    @Test fun proxyWithSize() {
        assertEquals(ArtRequest.Proxy("abc123", 256), parse("proxy/abc123", "size" to "256"))
    }

    @Test fun proxySizeSnapsToAnAllowedOne() {
        assertEquals(ArtRequest.Proxy("a", 512), parse("proxy/a", "size" to "300"))
        assertEquals(ArtRequest.Proxy("a", 1024), parse("proxy/a", "size" to "4000"))
        assertEquals(ArtRequest.Proxy("a", 512), parse("proxy/a"))
        assertEquals(ArtRequest.Proxy("a", 512), parse("proxy/a", "size" to "big"))
    }

    @Test fun remoteHttpAndHttpsOnly() {
        assertEquals(ArtRequest.Remote("https://assets.fanart.tv/x.jpg"), parse("url", "u" to "https://assets.fanart.tv/x.jpg"))
        assertEquals(ArtRequest.Remote("http://h/x.png"), parse("url", "u" to "http://h/x.png"))
        assertNull(parse("url", "u" to "file:///data/data/x"))
        assertNull(parse("url", "u" to "content://other/x"))
        assertNull(parse("url", "u" to "https://"))
        assertNull(parse("url"))
    }

    @Test fun otherShapesAreRejected() {
        assertNull(parse(""))
        assertNull(parse("proxy"))
        assertNull(parse("proxy/a/b"))
        assertNull(parse("url/extra", "u" to "https://h/x.jpg"))
        assertNull(parse("track/1"))
    }

    @Test fun canonicalIgnoresHost() {
        assertEquals("proxy/abc?size=256", ArtRequest.Proxy("abc", 256).canonical)
    }
}

class ArtCacheTest {
    private lateinit var dir: File
    private var clock = 1_700_000_000_000L

    @Before fun setUp() { dir = Files.createTempDirectory("art").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun cache(max: Long = 1_000) = ArtCache(dir, max) { clock }
    private fun bytes(n: Int, v: Int = 1) = ByteArray(n) { v.toByte() }

    @Test fun keyIsStableSha1() {
        val c = cache()
        assertEquals(40, c.key("proxy/a?size=512").length)
        assertEquals(c.key("proxy/a?size=512"), c.key("proxy/a?size=512"))
        assertNotEquals(c.key("proxy/a?size=512"), c.key("proxy/a?size=256"))
    }

    @Test fun putThenGet() {
        val c = cache()
        val k = c.key("x")
        assertNull(c.get(k))
        c.put(k, ByteArrayInputStream(bytes(10, 7)))
        assertArrayEquals(bytes(10, 7), c.get(k)!!.readBytes())
        assertEquals(listOf(k), dir.list()!!.toList()) // no temp file left
    }

    @Test fun tooLargeIsRefusedAndLeavesNothing() {
        val c = cache()
        try {
            c.put(c.key("x"), ByteArrayInputStream(bytes(101)), limit = 100)
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun emptyIsRefused() {
        val c = cache()
        try {
            c.put(c.key("x"), ByteArrayInputStream(ByteArray(0)))
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun evictsLeastRecentlyUsed() {
        val c = cache(max = 300)
        val a = c.key("a"); val b = c.key("b"); val d = c.key("d")
        c.put(a, ByteArrayInputStream(bytes(100))); clock += 10_000
        c.put(b, ByteArrayInputStream(bytes(100))); clock += 10_000
        c.get(a); clock += 10_000 // a is now fresher than b
        c.put(d, ByteArrayInputStream(bytes(150)))
        assertTrue(File(dir, a).exists())
        assertFalse(File(dir, b).exists())
        assertTrue(File(dir, d).exists())
        assertTrue(c.sizeBytes() <= 300)
    }

    @Test fun staleTempFilesAreSwept() {
        val c = cache()
        val tmp = File(dir, "deadbeef.tmp3").apply { writeBytes(bytes(5)); setLastModified(0) }
        c.put(c.key("x"), ByteArrayInputStream(bytes(5)))
        assertFalse(tmp.exists())
    }

    @Test fun sniffsCommonImageTypes() {
        assertEquals("image/jpeg", ArtCache.sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/png", ArtCache.sniffMime(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("image/webp", ArtCache.sniffMime("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("image/gif", ArtCache.sniffMime("GIF89a".toByteArray()))
        assertNull(ArtCache.sniffMime("<html>".toByteArray()))
        assertNull(ArtCache.sniffMime(ByteArray(0)))
    }
}
