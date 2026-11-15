package app.geckopresence.host

import app.geckopresence.host.core.PresenceSourceArbiter
import app.geckopresence.host.core.PresenceUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceSourceArbiterTest {
    @Test
    fun manualOverrideWinsAndRestoresTheHighestPriorityAutomaticSource() {
        val website = PresenceUpdate("idlix.example", "IDLIX", "A movie", "Movie", "playing", 100, "website", 90)
        val android = PresenceUpdate("android.app", "Spotify", "Listening to music", null, "browsing", 101, "android-app", 95)
        val manual = PresenceUpdate("gecko-presence.local", "Custom", "Manual status", "Override", "unknown", 102, "manual", 100)

        var candidates = PresenceSourceArbiter.upsert(emptyMap(), website)
        candidates = PresenceSourceArbiter.upsert(candidates, android)
        assertEquals("android.app", PresenceSourceArbiter.winner(candidates)?.source)
        candidates = PresenceSourceArbiter.upsert(candidates, manual)
        assertEquals("manual", PresenceSourceArbiter.winner(candidates)?.sourceType)
        candidates = PresenceSourceArbiter.removeSourceType(candidates, "manual")
        assertEquals("android.app", PresenceSourceArbiter.winner(candidates)?.source)
        candidates = PresenceSourceArbiter.removeSourceType(candidates, "android-app")
        assertEquals("idlix.example", PresenceSourceArbiter.winner(candidates)?.source)
    }
}
