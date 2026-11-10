package app.geckopresence.host

import app.geckopresence.host.core.BridgePayloadMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgePayloadMapperTest {
    @Test
    fun mapsUamV2MovieAndEpisodeFieldsIntoHostPresence() {
        val update = BridgePayloadMapper.fromV2(
            mapOf(
                "schemaVersion" to 2,
                "source" to "z2.idlixku.com",
                "name" to "IDLIX",
                "details" to "The Bear",
                "state" to "Season 2 · Episode 4 — Honeydew",
                "mediaState" to "playing",
                "startTimestamp" to 1_800_000_000L,
            ),
            shareTitles = true,
        )
        assertEquals("z2.idlixku.com", update?.source)
        assertEquals("The Bear", update?.details)
        assertEquals("Season 2 · Episode 4 — Honeydew", update?.state)
        assertEquals("playing", update?.mediaState)
        assertEquals(1_800_000_000L, update?.startTimestampSeconds)
        assertEquals("website", update?.sourceType)
        assertEquals(10, update?.priority)
    }

    @Test
    fun hidesMediaTitleAndEpisodeWhenTitleSharingIsDisabled() {
        val update = BridgePayloadMapper.fromV2(
            mapOf("schemaVersion" to 2, "source" to "z2.idlixku.com", "name" to "IDLIX", "details" to "The Bear", "state" to "Season 2", "mediaState" to "playing"),
            shareTitles = false,
        )
        assertEquals("Watching", update?.details)
        assertNull(update?.state)
    }

    @Test
    fun preservesManualOverridePriorityAndSourceType() {
        val update = BridgePayloadMapper.fromV2(
            mapOf("schemaVersion" to 2, "source" to "gecko-presence.local", "sourceType" to "manual", "priority" to 100, "name" to "Manual", "details" to "Custom status"),
            shareTitles = true,
        )
        assertEquals("manual", update?.sourceType)
        assertEquals(100, update?.priority)
    }

    @Test
    fun rejectsUnsupportedSchemaAndSensitiveDomains() {
        assertNull(BridgePayloadMapper.fromV2(mapOf("schemaVersion" to 1, "source" to "example.com"), true))
        assertNull(BridgePayloadMapper.fromV2(mapOf("schemaVersion" to 2, "source" to "bank.example", "mediaState" to "playing"), true))
    }
}
