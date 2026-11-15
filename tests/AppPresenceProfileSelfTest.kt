package app.geckopresence.host.core

fun main() {
    val image = PresenceAsset("https://i.imgur.com/example.png", "Artwork")
    val button = PresenceButton("Open", "https://example.com")
    val full = AppPresenceProfile(
        activityLabel = "Firefox",
        details = "Reading documentation",
        state = "Research",
        activityType = PresenceActivityType.PLAYING,
        largeImage = image,
        smallImage = image,
        buttons = listOf(button),
        startedAtEpochSeconds = 1_800_000_000L,
        endsAtEpochSeconds = 1_800_000_600L,
        priorityOverride = 75,
        accountStatus = "idle",
    )
    check(AppPresenceProfilePolicy.validate(full).isEmpty())
    val resolved = AppPresenceProfilePolicy.resolve("Browser", "Browsing", full)
    check(resolved == full)
    check(AppPresenceProfilePolicy.validate(full.copy(priorityOverride = 1001)).isNotEmpty())
    check(AppPresenceProfilePolicy.validate(full.copy(accountStatus = "invisible-ish")).isNotEmpty())
    check(AppPresenceProfilePolicy.validate(full.copy(buttons = listOf(button, button, button))).isNotEmpty())
    check(AppPresenceProfilePolicy.validate(full.copy(endsAtEpochSeconds = 1_799_999_999L)).isNotEmpty())
    val expired = full.copy(startedAtEpochSeconds = null, endsAtEpochSeconds = System.currentTimeMillis() / 1_000L - 1L)
    check(AppPresenceProfilePolicy.validate(expired).isNotEmpty())
    check(AppPresenceProfilePolicy.validate(expired, requireFutureEnd = false).isEmpty())
    check(AppPresenceProfilePolicy.validate(full.copy(activityType = PresenceActivityType.STREAMING)).isNotEmpty())
    val streaming = full.copy(
        activityType = PresenceActivityType.STREAMING,
        streamUrl = "https://www.twitch.tv/example",
    )
    check(AppPresenceProfilePolicy.validate(streaming).isEmpty())
    check(AppPresenceProfilePolicy.validate(full.copy(enabled = false, priorityOverride = 9999)).isNotEmpty())
    println("AppPresenceProfileSelfTest: advanced per-app profile policy checks passed")
}
