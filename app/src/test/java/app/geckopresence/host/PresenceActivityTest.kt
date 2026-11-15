package app.geckopresence.host

import app.geckopresence.host.core.PresenceActivity
import app.geckopresence.host.core.PresenceActivityType
import app.geckopresence.host.core.PresenceActivityDisplayFormatter
import app.geckopresence.host.core.PresenceAsset
import app.geckopresence.host.core.PresenceButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceActivityTest {
    @Test fun `supports all documented activity type values`() {
        assertEquals(PresenceActivityType.PLAYING, PresenceActivityType.fromWireValue(0))
        assertEquals(PresenceActivityType.STREAMING, PresenceActivityType.fromWireValue(1))
        assertEquals(PresenceActivityType.LISTENING, PresenceActivityType.fromWireValue(2))
        assertEquals(PresenceActivityType.WATCHING, PresenceActivityType.fromWireValue(3))
        assertEquals(PresenceActivityType.CUSTOM_STATUS, PresenceActivityType.fromWireValue(4))
        assertEquals(PresenceActivityType.COMPETING, PresenceActivityType.fromWireValue(5))
        assertEquals(null, PresenceActivityType.fromWireValue(99))
    }

    @Test fun `previews each activity with Discord Gateway type-specific headline`() {
        assertEquals("Playing Minecraft", PresenceActivityDisplayFormatter.format(PresenceActivityType.PLAYING, "Minecraft", "Survival", "Day 3").headline)
        assertEquals("Streaming Live coding", PresenceActivityDisplayFormatter.format(PresenceActivityType.STREAMING, "Twitch", "Live coding", "Kotlin").headline)
        assertEquals("Listening to Spotify", PresenceActivityDisplayFormatter.format(PresenceActivityType.LISTENING, "Spotify", "Track title", "Artist").headline)
        assertEquals("Watching YouTube", PresenceActivityDisplayFormatter.format(PresenceActivityType.WATCHING, "YouTube", "Video title", null).headline)
        assertEquals("At work", PresenceActivityDisplayFormatter.format(PresenceActivityType.CUSTOM_STATUS, "unused", null, "At work").headline)
        assertEquals("Competing in Arena", PresenceActivityDisplayFormatter.format(PresenceActivityType.COMPETING, "Arena", null, null).headline)
    }

    @Test fun `rejects invalid timestamp ordering and millisecond overflow`() {
        assertTrue(PresenceActivity(name = "Game", startedAtEpochSeconds = 200, endsAtEpochSeconds = 100).validate().isNotEmpty())
        assertTrue(PresenceActivity(name = "Game", startedAtEpochSeconds = Long.MAX_VALUE).validate().isNotEmpty())
    }

    @Test fun `accepts complete presence with artwork and buttons`() {
        val activity = PresenceActivity(
            name = "Firefox",
            type = PresenceActivityType.WATCHING,
            details = "Watching a video",
            state = "YouTube",
            startedAtEpochSeconds = 100,
            largeImage = PresenceAsset("https://cdn.example/art.webp", "Video artwork"),
            smallImage = PresenceAsset("firefox-icon", "Firefox"),
            buttons = listOf(PresenceButton("Open source", "https://example.com")),
        )
        assertTrue(activity.validate().isEmpty())
    }

    @Test fun `requires supported https stream urls`() {
        val accepted = PresenceActivity(
            name = "Gecko Stream",
            type = PresenceActivityType.STREAMING,
            details = "Live",
            streamUrl = "https://www.twitch.tv/example",
        )
        assertTrue(accepted.validate().isEmpty())
        val rejected = accepted.copy(streamUrl = "https://example.org/live")
        assertFalse(rejected.validate().isEmpty())
    }

    @Test fun `custom status requires state text`() {
        val rejected = PresenceActivity(name = "Custom", type = PresenceActivityType.CUSTOM_STATUS)
        assertTrue(rejected.validate().any { it.contains("custom status requires state") })
    }

    @Test fun `rejects too-short optional text instead of dropping it during source mapping`() {
        assertTrue(PresenceActivity(name = "Game", details = "x").validate().isNotEmpty())
        assertTrue(PresenceActivity(name = "Custom", type = PresenceActivityType.CUSTOM_STATUS, state = "x").validate().isNotEmpty())
        assertTrue(PresenceActivity(name = "Game", details = "").validate().isEmpty())
    }

    @Test fun `rejects unsafe urls and excess buttons`() {
        val activity = PresenceActivity(
            name = "Example",
            buttons = listOf(
                PresenceButton("One", "http://example.com"),
                PresenceButton("Two", "https://example.com"),
                PresenceButton("Three", "https://example.com"),
            ),
        )
        val errors = activity.validate()
        assertTrue(errors.any { it.contains("at most two buttons") })
        assertTrue(errors.any { it.contains("HTTPS URL") })
    }
}
