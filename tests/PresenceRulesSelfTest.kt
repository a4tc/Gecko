package app.geckopresence.host.core

import app.geckopresence.host.core.AppPresenceProfile
import app.geckopresence.host.core.AppPresenceProfilePolicy

fun main() {
    check(RpcStartupPolicy.commandFor("app.geckopresence.host.action.START") == RpcCommand.NONE)
    check(RpcStartupPolicy.commandFor("app.geckopresence.host.action.UPDATE_APP_TRACKING") == RpcCommand.NONE)
    check(RpcStartupPolicy.commandFor("app.geckopresence.host.action.UPDATE_MEDIA_TRACKING") == RpcCommand.NONE)
    check(RpcStartupPolicy.commandFor(RpcStartupPolicy.ACTION_CONNECT_DISCORD) == RpcCommand.CONNECT)
    check(RpcStartupPolicy.commandFor(RpcStartupPolicy.ACTION_DISCONNECT_DISCORD) == RpcCommand.DISCONNECT)
    check(RpcStartupPolicy.commandFor(null) == RpcCommand.NONE)
    check(RpcStartupPolicy.commandFor("app.geckopresence.host.action.APPLY_MANUAL") == RpcCommand.NONE)
    check(RpcStartupPolicy.commandFor("app.geckopresence.host.action.CLEAR_MANUAL") == RpcCommand.NONE)
    check(RpcStartupPolicy.commandFor("app.geckopresence.host.action.REFRESH_MEDIA") == RpcCommand.NONE)
    check(RpcStartupPolicy.commandFor("unknown-action") == RpcCommand.NONE)
    val activityLines = PresenceActivityMapper.fromUpdate(
        PresenceUpdate(
            source = "manual",
            name = "YouTube",
            details = "Never Gonna Give You Up",
            state = "Rick Astley",
            mediaState = "playing",
            startTimestampSeconds = 1_800_000_000L,
            sourceType = "manual",
        ),
    )
    check(activityLines.name == "YouTube")
    check(activityLines.details == "Never Gonna Give You Up")
    check(activityLines.state == "Rick Astley")
    val noDetails = PresenceActivityMapper.fromUpdate(
        PresenceUpdate(
            source = "manual",
            name = "Focus",
            details = "",
            state = null,
            mediaState = "playing",
            startTimestampSeconds = 1_800_000_000L,
            sourceType = "manual",
        ),
    )
    check(noDetails.name == "Focus")
    check(noDetails.details == null)
    check(noDetails.state == null)
    check(PresenceActivityMapper.stateLine(" Paused ") == "Paused")
    check(PresenceActivityMapper.stateLine("  ") == null)
    val defaultAppProfile = AppPresenceProfilePolicy.resolve(
        appLabel = "YouTube",
        defaultDetails = "Watching",
        custom = null,
    )
    check(defaultAppProfile == AppPresenceProfile("YouTube", "Watching", null))
    val customAppProfile = AppPresenceProfilePolicy.resolve(
        appLabel = "YouTube",
        defaultDetails = "Watching",
        custom = AppPresenceProfile("  YouTube Music  ", "  Listening to albums  ", "  Focus mode  "),
    )
    check(customAppProfile == AppPresenceProfile("YouTube Music", "Listening to albums", "Focus mode"))
    val partialAppProfile = AppPresenceProfilePolicy.resolve(
        appLabel = "Spotify",
        defaultDetails = "Listening to music",
        custom = AppPresenceProfile("  ", "  ", "  "),
    )
    check(partialAppProfile == AppPresenceProfile("Spotify", "Listening to music", null))
    val boundedAppProfile = AppPresenceProfilePolicy.resolve(
        appLabel = "A".repeat(100),
        defaultDetails = "D".repeat(180),
        custom = AppPresenceProfile("N".repeat(90), "X".repeat(150), "S".repeat(150)),
    )
    check(boundedAppProfile.activityLabel.codePointCount(0, boundedAppProfile.activityLabel.length) == 40)
    check(boundedAppProfile.details.length == 120)
    check(boundedAppProfile.state?.length == 120)
    val unicodeBounded = PresenceRules.cleanText("A".repeat(127) + "😀X", 128)!!
    check(unicodeBounded.codePointCount(0, unicodeBounded.length) == 128)
    check(!unicodeBounded.last().isHighSurrogate())
    check(PresenceRules.sanitizeHost("www.YouTube.com") == "youtube.com")
    check(PresenceRules.sanitizeHost("example.org") == "example.org")
    check(PresenceRules.sanitizeHost("https://example.org/path") == null)
    check(PresenceRules.sanitizeHost("example.org/path") == null)
    check(PresenceRules.sanitizeHost("bad..host") == null)
    check(PresenceRules.sanitizeHost("-invalid.example") == null)
    check(PresenceRules.extractPublicWebHost("https://www.example.org/watch?id=123") == "example.org")
    check(PresenceRules.extractPublicWebHost("youtube.com") == "youtube.com")
    check(PresenceRules.extractPublicWebHost("https://mail.google.com") == null)
    check(PresenceRules.extractPublicWebHost("https://bank.example/login") == null)
    check(PresenceRules.extractPublicWebHost("https://user:pass@example.org") == null)
    check(PresenceRules.extractPublicWebHost("https://localhost/admin") == null)
    check(PresenceRules.extractPublicWebHost("not a domain") == null)
    check(PresenceRules.extractPublicWebHost("HTTPS://WWW.Example.org:8443/watch?token=private") == "example.org")
    check(PresenceRules.extractPublicWebHost("ftp://example.org/file") == null)
    check(PresenceRules.extractPublicWebHost("https://127.0.0.1/admin") == null)
    check(PresenceRules.extractPublicWebHost("https://192.168.1.10/router") == null)
    check(PresenceRules.extractPublicWebHost("https://printer.local/status") == null)
    check(PresenceRules.extractPublicWebHost("https://mail.example.org") == null)
    check(PresenceRules.extractPublicWebHost("https://example.org:443/path") == "example.org")
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
    val browserCandidate = PresenceUpdate("idlix.example", "IDLIX", "Browsing", null, "browsing", 1_800_000_000L, "website", 85)
    val androidCandidate = browserCandidate.copy(source = "android.app", sourceType = "android-app", priority = 95)
    val manualCandidate = browserCandidate.copy(source = "gecko-presence.local", sourceType = "manual", priority = 100)
    var candidates = PresenceSourceArbiter.upsert(emptyMap(), browserCandidate)
    candidates = PresenceSourceArbiter.upsert(candidates, androidCandidate)
    check(PresenceSourceArbiter.winner(candidates)?.source == "android.app")
    candidates = PresenceSourceArbiter.upsert(candidates, manualCandidate)
    check(PresenceSourceArbiter.winner(candidates)?.sourceType == "manual")
    candidates = PresenceSourceArbiter.removeSourceType(candidates, "manual")
    check(PresenceSourceArbiter.winner(candidates)?.source == "android.app")
    candidates = PresenceSourceArbiter.removeSourceType(candidates, "android-app")
    check(PresenceSourceArbiter.winner(candidates)?.source == "idlix.example")
    val expiring = browserCandidate.copy(expiresAtEpochMillis = 1_000L)
    check(PresenceSourceArbiter.winner(mapOf("web" to expiring), 999L) != null)
    check(PresenceSourceArbiter.winner(mapOf("web" to expiring), 1_000L) == null)
    check(PresenceSourceArbiter.winner(mapOf("web" to browserCandidate), 99_999L) != null)
    println("PresenceRulesSelfTest: core policy checks passed")
}
