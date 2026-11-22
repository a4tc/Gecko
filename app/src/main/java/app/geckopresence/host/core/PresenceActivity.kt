package app.geckopresence.host.core

/** Transport-neutral Rich Presence model. No Discord transport-specific types cross this boundary. */
enum class PresenceActivityType(val wireValue: Int, val displayLabel: String) {
    PLAYING(0, "Playing"),
    STREAMING(1, "Streaming"),
    LISTENING(2, "Listening"),
    WATCHING(3, "Watching"),
    CUSTOM_STATUS(4, "Custom Status"),
    COMPETING(5, "Competing");

    companion object {
        fun fromWireValue(value: Int): PresenceActivityType? = entries.firstOrNull { it.wireValue == value }
    }
}

data class PresenceAsset(
    val image: String,
    val tooltip: String? = null,
    val clickUrl: String? = null,
)

data class PresenceButton(
    val label: String,
    val url: String,
)

private val APPLICATION_ASSET_KEY_PATTERN =
    Regex("^(?=.*[A-Za-z])[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

data class PresenceActivity(
    val name: String,
    val type: PresenceActivityType = PresenceActivityType.PLAYING,
    val details: String? = null,
    val state: String? = null,
    val startedAtEpochSeconds: Long? = null,
    val endsAtEpochSeconds: Long? = null,
    val largeImage: PresenceAsset? = null,
    val smallImage: PresenceAsset? = null,
    val buttons: List<PresenceButton> = emptyList(),
    val streamUrl: String? = null,
    val sourceId: String = "manual",
    val priority: Int = 0,
    val expiresAtEpochMillis: Long = 0L,
) {
    fun validate(): List<String> = buildList {
        if (name.isBlank() || name.length > 128) add("name must contain 1–128 characters")
        if (!details.isNullOrBlank() && details.length !in 2..128) add("details must contain 2–128 characters when provided")
        if (!state.isNullOrBlank() && state.length !in 2..128) add("state must contain 2–128 characters when provided")
        if (startedAtEpochSeconds != null && startedAtEpochSeconds <= 0L) add("start timestamp must be positive")
        if (endsAtEpochSeconds != null && endsAtEpochSeconds <= 0L) add("end timestamp must be positive")
        if (startedAtEpochSeconds != null && startedAtEpochSeconds > Long.MAX_VALUE / 1_000L) add("start timestamp is out of range")
        if (endsAtEpochSeconds != null && endsAtEpochSeconds > Long.MAX_VALUE / 1_000L) add("end timestamp is out of range")
        if (startedAtEpochSeconds != null && endsAtEpochSeconds != null && endsAtEpochSeconds <= startedAtEpochSeconds) {
            add("end timestamp must be later than start timestamp")
        }
        if (type == PresenceActivityType.CUSTOM_STATUS && state.isNullOrBlank()) add("custom status requires state text")
        if (type == PresenceActivityType.CUSTOM_STATUS && !state.isNullOrBlank() && state.length < 2) add("custom status text must contain at least 2 characters")
        if (type == PresenceActivityType.STREAMING) {
            val uri = runCatching { java.net.URI(streamUrl.orEmpty()) }.getOrNull()
            val host = uri?.host?.lowercase()
            if (!isSafeHttpsUrl(streamUrl, 512) ||
                host !in setOf("twitch.tv", "www.twitch.tv", "youtube.com", "www.youtube.com")) {
                add("streaming activity requires an HTTPS Twitch or YouTube URL")
            }
        } else if (streamUrl != null) {
            add("stream URL is only valid for Streaming activities")
        }
        if (buttons.size > 2) add("at most two buttons are supported")
        buttons.forEachIndexed { index, button ->
            if (button.label.isBlank() || button.label.length > 32) add("button ${index + 1} label must contain 1–32 characters")
            if (!isSafeHttpsUrl(button.url, 512)) add("button ${index + 1} must use a safe HTTPS URL of at most 512 characters")
        }
        listOfNotNull(largeImage, smallImage).forEachIndexed { index, asset ->
            val slot = if (index == 0 && largeImage != null) "large" else "small"
            if (!isAllowedImageReference(asset.image)) add("$slot image reference must be HTTPS, a Discord media-proxy reference, a Gecko gallery reference, or an application asset ID")
            if (asset.tooltip != null && asset.tooltip.length !in 2..128) add("$slot image tooltip must contain 2–128 characters")
            if (asset.clickUrl != null && !isSafeHttpsUrl(asset.clickUrl, 256)) add("$slot image click URL must be safe HTTPS and at most 256 characters")
        }
    }

    private fun isAllowedImageReference(value: String): Boolean {
        if (value.isBlank() || value.length > 520 || value.any(Char::isWhitespace)) return false
        if (DiscordArtworkPolicy.isLocalReference(value)) return true
        if (value.startsWith("mp:", ignoreCase = true)) return value.substring(3).isNotBlank()
        if (value.all(Char::isDigit)) return value.length in 16..22
        if (APPLICATION_ASSET_KEY_PATTERN.matches(value)) return true
        return isSafeHttpsUrl(value, 300)
    }

    private fun isSafeHttpsUrl(value: String?, maxLength: Int): Boolean {
        if (value.isNullOrBlank() || value.length > maxLength || value.any { it.isWhitespace() || it.isISOControl() }) return false
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null || uri.port !in setOf(-1, 443)) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || host.contains(":")) return false
        return true
    }
}


/** Local preview formatter follows Discord's documented Gateway Activity type formats. */
object PresenceActivityDisplayFormatter {
    data class Preview(
        val headline: String,
        val details: String?,
        val state: String?,
    )

    fun format(
        type: PresenceActivityType,
        name: String,
        details: String?,
        state: String?,
    ): Preview {
        val cleanName = name.trim()
        val cleanDetails = details?.trim()?.takeIf(String::isNotEmpty)
        val cleanState = state?.trim()?.takeIf(String::isNotEmpty)
        return when (type) {
            PresenceActivityType.PLAYING -> Preview("Playing $cleanName", cleanDetails, cleanState)
            PresenceActivityType.STREAMING -> Preview(
                cleanDetails?.let { "Streaming $it" } ?: "Streaming",
                null,
                cleanState,
            )
            PresenceActivityType.LISTENING -> Preview("Listening to $cleanName", cleanDetails, cleanState)
            PresenceActivityType.WATCHING -> Preview("Watching $cleanName", cleanDetails, cleanState)
            PresenceActivityType.CUSTOM_STATUS -> Preview(cleanState ?: "Custom Status", null, null)
            PresenceActivityType.COMPETING -> Preview("Competing in $cleanName", cleanDetails, cleanState)
        }
    }
}
