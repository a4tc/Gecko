package app.geckopresence.host.core

fun main() {
    check(PresenceRules.sanitizeHost("www.YouTube.com") == "youtube.com")
    check(PresenceRules.sanitizeHost("example.org") == "example.org")
    check(PresenceRules.sanitizeHost("https://example.org/path") == null)
    check(PresenceRules.sanitizeHost("example.org/path") == null)
    check(PresenceRules.sanitizeHost("bad..host") == null)
    check(PresenceRules.sanitizeHost("-invalid.example") == null)
    check(PresenceRules.cleanText("  Video\n  title  ", 80) == "Video title")
    check(PresenceRules.cleanText("\u0000\u0001", 80) == null)
    check(PresenceRules.cleanText("A".repeat(140), 120)?.length == 120)
    check(PresenceRules.normalizeMediaState("PLAYING") == "playing")
    check(PresenceRules.normalizeMediaState("paused") == "paused")
    check(PresenceRules.normalizeMediaState("something-else") == "browsing")
    check(PresenceRules.defaultDetails("playing") == "Watching")
    check(PresenceRules.defaultDetails("paused") == "Paused")
    check(!AppActivityRules.isSelectableApp("com.mandiri.livin", "Livin' by Mandiri", "app.geckopresence.host"))
    check(!AppActivityRules.isSelectableApp("com.somewallet.app", "Some Wallet", "app.geckopresence.host"))
    check(!AppActivityRules.isSelectableApp("com.google.android.gm", "Gmail", "app.geckopresence.host"))
    check(!AppActivityRules.isSelectableApp("com.whatsapp", "WhatsApp", "app.geckopresence.host"))
    check(AppActivityRules.isSelectableApp("com.instagram.android", "Instagram", "app.geckopresence.host"))
    check(AppActivityRules.isKnownApp("com.google.android.youtube", "YouTube"))
    check(AppActivityRules.defaultActivityDetails("com.zhiliaoapp.musically.go", "TikTok Lite") == "Watching short videos")
    check(AppActivityRules.defaultActivityDetails("com.spotify.music", "Spotify") == "Listening to music")
    check(AppActivityRules.defaultActivityDetails("com.google.android.apps.youtube.music", "YouTube Music") == "Listening to music")
    check(AppActivityRules.defaultActivityDetails("com.netflix.mediaclient", "Netflix") == "Watching")
    check(ForegroundEventReducer.reduce(null, "com.spotify.music", ForegroundEventKind.RESUMED, true) == "com.spotify.music")
    check(ForegroundEventReducer.reduce("com.spotify.music", "com.android.launcher", ForegroundEventKind.RESUMED, false) == null)
    check(ForegroundEventReducer.reduce("com.spotify.music", "com.spotify.music", ForegroundEventKind.PAUSED, false) == null)
    check(ForegroundEventReducer.reduce("com.youtube", "com.spotify.music", ForegroundEventKind.PAUSED, false) == "com.youtube")
    check(ForegroundEventReducer.reduce("com.youtube", null, ForegroundEventKind.SCREEN_NON_INTERACTIVE, false) == null)
    val v2 = BridgePayloadMapper.fromV2(mapOf(
        "schemaVersion" to 2,
        "source" to "z2.idlixku.com",
        "name" to "IDLIX",
        "details" to "The Bear",
        "state" to "Season 2 · Episode 4 — Honeydew",
        "mediaState" to "playing",
        "startTimestamp" to 1_800_000_000L,
    ), shareTitles = true)
    check(v2?.source == "z2.idlixku.com")
    check(v2?.details == "The Bear")
    check(v2?.state == "Season 2 · Episode 4 — Honeydew")
    check(v2?.mediaState == "playing")
    val privateV2 = BridgePayloadMapper.fromV2(mapOf(
        "schemaVersion" to 2, "source" to "z2.idlixku.com", "name" to "IDLIX",
        "details" to "The Bear", "state" to "Season 2", "mediaState" to "playing",
    ), shareTitles = false)
    check(privateV2?.details == "Watching")
    check(privateV2?.state == null)
    check(BridgePayloadMapper.fromV2(mapOf("schemaVersion" to 1, "source" to "example.com"), true) == null)
    check(BridgePayloadMapper.fromV2(mapOf("schemaVersion" to 2, "source" to "bank.example", "mediaState" to "playing"), true) == null)
    val browserCandidate = v2!!.copy(source = "idlix.example", sourceType = "website", priority = 90)
    val androidCandidate = v2.copy(source = "android.app", sourceType = "android-app", priority = 95)
    val manualCandidate = v2.copy(source = "gecko-presence.local", sourceType = "manual", priority = 100)
    var candidates = PresenceSourceArbiter.upsert(emptyMap(), browserCandidate)
    candidates = PresenceSourceArbiter.upsert(candidates, androidCandidate)
    check(PresenceSourceArbiter.winner(candidates)?.source == "android.app")
    candidates = PresenceSourceArbiter.upsert(candidates, manualCandidate)
    check(PresenceSourceArbiter.winner(candidates)?.sourceType == "manual")
    candidates = PresenceSourceArbiter.removeSourceType(candidates, "manual")
    check(PresenceSourceArbiter.winner(candidates)?.source == "android.app")
    candidates = PresenceSourceArbiter.removeSourceType(candidates, "android-app")
    check(PresenceSourceArbiter.winner(candidates)?.source == "idlix.example")
    println("PresenceRulesSelfTest: 39 checks passed")
}
