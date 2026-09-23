package io.github.superthom196.maa.ui

import io.github.superthom196.maa.data.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlsTest {
    @Test fun `bare host gets scheme and default port`() {
        assertEquals("http://192.168.1.50:8095", ServerUrls.normalize("192.168.1.50"))
        assertEquals("http://my-server:8095", ServerUrls.normalize("  my-server  "))
    }

    @Test fun `host with port keeps it`() {
        assertEquals("http://my-server:9000", ServerUrls.normalize("my-server:9000"))
        assertEquals("http://nas.tailnet.ts.net:8095", ServerUrls.normalize("nas.tailnet.ts.net:8095/"))
    }

    @Test fun `full url is kept minus trailing slash`() {
        assertEquals("http://100.97.96.36:8095", ServerUrls.normalize("http://100.97.96.36:8095/"))
        assertEquals("https://ma.example.com", ServerUrls.normalize("https://ma.example.com"))
        assertEquals("https://ma.example.com/music", ServerUrls.normalize("https://ma.example.com/music/"))
    }

    @Test fun `ipv6 literals`() {
        assertEquals("http://[fd00::5]:8095", ServerUrls.normalize("fd00::5"))
        assertEquals("http://[fd00::5]:9000", ServerUrls.normalize("[fd00::5]:9000"))
        assertEquals("http://[fd00::5]:8095", ServerUrls.normalize("[fd00::5]"))
    }

    @Test fun `junk is rejected`() {
        assertNull(ServerUrls.normalize(""))
        assertNull(ServerUrls.normalize("   "))
        assertNull(ServerUrls.normalize("my server"))
        assertNull(ServerUrls.normalize("ftp://host"))
        assertNull(ServerUrls.normalize("http://"))
    }

    @Test fun `lan classification`() {
        listOf("10.0.0.2", "172.16.0.1", "172.31.255.255", "192.168.0.150", "169.254.1.1", "nas.local", "fe80::1", "fd00::5")
            .forEach { assertTrue(it, ServerUrls.isLanHost(it)) }
        listOf("172.15.0.1", "172.32.0.1", "8.8.8.8", "100.97.96.36", "my-server", "fd7a:115c:a1e0::1", "example.com")
            .forEach { assertFalse(it, ServerUrls.isLanHost(it)) }
    }

    @Test fun `tailscale classification`() {
        listOf("100.64.0.1", "100.97.96.36", "100.127.255.254", "host.tailnet.ts.net", "fd7a:115c:a1e0::1")
            .forEach { assertTrue(it, ServerUrls.isTailscaleHost(it)) }
        listOf("100.63.0.1", "100.128.0.1", "192.168.1.1", "ts.net.example.com")
            .forEach { assertFalse(it, ServerUrls.isTailscaleHost(it)) }
    }

    @Test fun `url kinds`() {
        assertEquals(UrlKind.LAN, ServerUrls.kind("http://192.168.0.150:8095"))
        assertEquals(UrlKind.LAN, ServerUrls.kind("http://[fd00::5]:8095"))
        assertEquals(UrlKind.TAILSCALE, ServerUrls.kind("http://100.97.96.36:8095"))
        assertEquals(UrlKind.TAILSCALE, ServerUrls.kind("http://NAS.tailnet.TS.NET:8095"))
        assertEquals(UrlKind.REMOTE, ServerUrls.kind("https://ma.example.com"))
        assertEquals(UrlKind.REMOTE, ServerUrls.kind("http://my-server:8095"))
        assertEquals(UrlKind.REMOTE, ServerUrls.kind("http://my_server:8095"))
    }

    @Test fun `host of odd names`() {
        assertEquals("my_server", ServerUrls.hostOf("http://my_server:8095"))
        assertEquals("fd00::5", ServerUrls.hostOf("http://[fd00::5]:8095"))
    }

    @Test fun `login slots - lan address is the lan url`() {
        assertEquals("http://192.168.0.150:8095" to null, ServerUrls.slotsForLogin("http://192.168.0.150:8095", "http://192.168.0.150:8095"))
        assertEquals("http://192.168.0.150:8095" to null, ServerUrls.slotsForLogin("http://192.168.0.150:8095", null))
    }

    @Test fun `login slots - remote address keeps the server's lan base_url too`() {
        assertEquals(
            "http://192.168.0.150:8095" to "http://100.97.96.36:8095",
            ServerUrls.slotsForLogin("http://100.97.96.36:8095", "http://192.168.0.150:8095/"),
        )
        // base_url that is not a LAN address is not a useful second path.
        assertEquals(null to "http://100.97.96.36:8095", ServerUrls.slotsForLogin("http://100.97.96.36:8095", "http://100.97.96.36:8095"))
        assertEquals(null to "http://my-server:8095", ServerUrls.slotsForLogin("http://my-server:8095", null))
    }

    @Test fun `connection kind`() {
        val cfg = ServerConfig("id", "MA", lanUrl = "http://192.168.0.150:8095", remoteUrl = "http://100.97.96.36:8095", token = "t")
        assertEquals(ConnectionKind.UNREACHABLE, ServerUrls.connectionKind(null, cfg))
        assertEquals(ConnectionKind.LAN, ServerUrls.connectionKind("http://192.168.0.150:8095", cfg))
        assertEquals(ConnectionKind.TAILSCALE, ServerUrls.connectionKind("http://100.97.96.36:8095", cfg))
        assertEquals(ConnectionKind.REMOTE, ServerUrls.connectionKind("https://ma.example.com", cfg.copy(remoteUrl = "https://ma.example.com")))
        // A LAN slot holding a plain hostname still reads as LAN.
        assertEquals(ConnectionKind.LAN, ServerUrls.connectionKind("http://nas:8095", cfg.copy(lanUrl = "http://nas:8095")))
    }
}
