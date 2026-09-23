package io.github.superthom196.maa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsTest {
    @Test fun settingsAreClamped() {
        assertEquals(PlaybackSettings(lookahead = 1, cacheMb = 64), Prefs.clamp(PlaybackSettings(lookahead = 0, cacheMb = 1)))
        assertEquals(PlaybackSettings(lookahead = 10, cacheMb = 4096), Prefs.clamp(PlaybackSettings(lookahead = 99, cacheMb = 100_000)))
        assertEquals(PlaybackSettings(lookahead = 3, cacheMb = 512), Prefs.clamp(PlaybackSettings(lookahead = 3, cacheMb = 512)))
    }

    @Test fun serverRoundTrips() {
        val cfg = ServerConfig("4d79", "serverpi", "http://192.168.0.150:8095", "http://100.97.96.36:8095", "tok", "thom", "flac-16-44", true)
        assertEquals(cfg, Prefs.decodeServer(maJson.encodeToString(ServerConfig.serializer(), cfg)))
    }

    @Test fun missingOrCorruptValuesFallBack() {
        assertNull(Prefs.decodeServer(null))
        assertNull(Prefs.decodeServer("{not json"))
        assertEquals(Prefs.clamp(PlaybackSettings()), Prefs.decodeSettings(null))
        assertEquals(Prefs.clamp(PlaybackSettings()), Prefs.decodeSettings("[]"))
    }

    @Test fun storedSettingsOutOfRangeAreClampedOnRead() {
        assertEquals(PlaybackSettings(lookahead = 10, cacheMb = 4096), Prefs.decodeSettings("""{"lookahead":50,"cacheMb":32768}"""))
    }

    @Test fun olderServerJsonGetsNewFieldDefaults() {
        val cfg = Prefs.decodeServer("""{"serverId":"s","name":"n","lanUrl":null,"remoteUrl":"http://h:8095","token":"t"}""")!!
        assertEquals(ServerConfig.DEFAULT_FORMAT, cfg.format)
        assertEquals(listOf("http://h:8095"), cfg.urls)
    }
}
