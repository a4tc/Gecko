package app.geckopresence.host

import app.geckopresence.host.core.PresenceRules
import app.geckopresence.host.core.AppActivityRules
import app.geckopresence.host.core.ForegroundEventKind
import app.geckopresence.host.core.ForegroundEventReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresenceRulesTest {
    @Test
    fun normalizesWebsiteHostWithoutSharingPath() {
        assertEquals("youtube.com", PresenceRules.sanitizeHost("www.YouTube.com"))
        assertNull(PresenceRules.sanitizeHost("https://youtube.com/watch?v=secret"))
        assertNull(PresenceRules.sanitizeHost("youtube.com/private/path"))
    }

    @Test
    fun dropsMalformedHosts() {
        assertNull(PresenceRules.sanitizeHost("bad..host"))
        assertNull(PresenceRules.sanitizeHost("-bad.example"))
        assertNull(PresenceRules.sanitizeHost(""))
    }

    @Test
    fun textIsWhitespaceNormalizedAndLengthBounded() {
        assertEquals("Video title", PresenceRules.cleanText("  Video\n title  ", 120))
        assertEquals(120, PresenceRules.cleanText("x".repeat(200), 120)?.length)
    }

    @Test
    fun excludesSensitiveAndBrowserAppsFromTheAllowlist() {
        assertEquals(false, AppActivityRules.isSelectableApp("id.co.bca.bcamobile", "myBCA", "app.geckopresence.host"))
        assertEquals(false, AppActivityRules.isSelectableApp("com.android.chrome", "Chrome", "app.geckopresence.host"))
        assertEquals(false, AppActivityRules.isSelectableApp("com.android.settings", "Settings", "app.geckopresence.host"))
        assertEquals(true, AppActivityRules.isSelectableApp("com.instagram.android", "Instagram", "app.geckopresence.host"))
        assertEquals(true, AppActivityRules.isSelectableApp("com.example.game", "Example Game", "app.geckopresence.host"))
    }

    @Test
    fun foregroundEventReducerTracksOnlyAllowListedPackages() {
        assertEquals(
            "com.spotify.music",
            ForegroundEventReducer.reduce(null, "com.spotify.music", ForegroundEventKind.RESUMED, true),
        )
        assertNull(
            ForegroundEventReducer.reduce("com.spotify.music", "com.android.launcher", ForegroundEventKind.RESUMED, false),
        )
    }

    @Test
    fun foregroundEventReducerDoesNotClearNewerAppForOldPauseEvent() {
        assertEquals(
            "com.youtube",
            ForegroundEventReducer.reduce("com.youtube", "com.spotify.music", ForegroundEventKind.PAUSED, false),
        )
        assertNull(
            ForegroundEventReducer.reduce("com.youtube", "com.youtube", ForegroundEventKind.PAUSED, false),
        )
    }

    @Test
    fun foregroundEventReducerClearsOnScreenOff() {
        assertNull(ForegroundEventReducer.reduce("com.youtube", null, ForegroundEventKind.SCREEN_NON_INTERACTIVE, false))
    }

    @Test
    fun providesUsefulDefaultLabelsForKnownApps() {
        assertEquals("Watching short videos", AppActivityRules.defaultActivityDetails("com.zhiliaoapp.musically", "TikTok"))
        assertEquals("Watching Reels", AppActivityRules.defaultActivityDetails("com.instagram.android", "Instagram"))
        assertEquals("Watching videos", AppActivityRules.defaultActivityDetails("com.google.android.youtube", "YouTube"))
        assertEquals("Listening to music", AppActivityRules.defaultActivityDetails("com.spotify.music", "Spotify"))
        assertEquals("Listening to music", AppActivityRules.defaultActivityDetails("com.google.android.apps.youtube.music", "YouTube Music"))
        assertEquals("Watching", AppActivityRules.defaultActivityDetails("com.netflix.mediaclient", "Netflix"))
    }

    @Test
    fun mediaStatesMapToStableActivityDetails() {
        assertEquals("playing", PresenceRules.normalizeMediaState("PLAYING"))
        assertEquals("browsing", PresenceRules.normalizeMediaState("unrecognized"))
        assertEquals("Watching", PresenceRules.defaultDetails("playing"))
    }
}
