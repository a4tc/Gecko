package app.geckopresence.host.core

import java.net.URI

/**
 * Pure rules for the Discord-native artwork path:
 * private webhook upload -> attachment CDN URL -> external-assets media-proxy path -> "mp:" reference.
 *
 * Attachment CDN URLs are signed and expire, so a resolved "mp:" reference is treated as
 * time-limited and re-created after [MP_REFERENCE_TTL_MS]. Studio and app profiles therefore store a
 * stable local reference ("gecko:<gallery id>") that the engine resolves at publish time.
 */
object DiscordArtworkPolicy {
    const val LOCAL_PREFIX = "gecko:"

    /** Conservative: Discord's signed attachment URLs live about a day. Re-create well before that. */
    const val MP_REFERENCE_TTL_MS: Long = 12L * 60L * 60L * 1000L

    private const val MAX_CDN_URL_LENGTH = 1_024
    private const val MAX_MP_PATH_LENGTH = 512 // "mp:" + path must stay within the 300 character image-reference limit

    private val webhookHosts = setOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")
    private val cdnHosts = setOf("cdn.discordapp.com", "media.discordapp.net")
    private val snowflakePattern = Regex("^[0-9]{15,22}$")
    private val webhookTokenPattern = Regex("^[A-Za-z0-9_-]{40,128}$")
    private val localIdPattern = Regex("^[a-f0-9-]{36}$")
    private val apiVersionPattern = Regex("^v[0-9]{1,2}$")

    /** Returns https://discord.com/api/webhooks/{id}/{token}, or null when the input is not a plain webhook URL. */
    fun normalizeWebhookUrl(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.length > 256 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in webhookHosts) return null

        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val offset = if (segments.getOrNull(1)?.let(apiVersionPattern::matches) == true) 1 else 0
        if (segments.size != 4 + offset) return null
        if (segments[0] != "api" || segments[1 + offset] != "webhooks") return null
        val id = segments[2 + offset]
        val token = segments[3 + offset]
        if (!snowflakePattern.matches(id) || !webhookTokenPattern.matches(token)) return null
        return "https://discord.com/api/webhooks/$id/$token"
    }

    fun webhookExecuteUrl(normalizedWebhookUrl: String): String = "$normalizedWebhookUrl?wait=true"

    fun webhookMessageUrl(normalizedWebhookUrl: String, messageId: String): String? =
        if (snowflakePattern.matches(messageId)) "$normalizedWebhookUrl/messages/$messageId" else null

    fun isValidMessageId(value: String): Boolean = snowflakePattern.matches(value)

    /** A signed Discord attachment URL returned by the webhook. Query parameters (ex/is/hm) are expected. */
    fun isSafeAttachmentCdnUrl(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_CDN_URL_LENGTH || value.any { it.isWhitespace() || it.isISOControl() }) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            host in cdnHosts &&
            uri.port in setOf(-1, 443) &&
            uri.userInfo == null &&
            uri.rawFragment == null &&
            uri.rawPath.orEmpty().startsWith("/attachments/")
    }

    /** Relative external-assets paths must retain the external/ prefix. */
    fun isValidExternalAssetPath(path: String): Boolean =
        path.startsWith("external/") &&
            path.length in 10..MAX_MP_PATH_LENGTH &&
            path.none { it.isWhitespace() || it.isISOControl() || it == '#' }

    fun toMediaProxyReference(externalAssetPath: String): String? {
        var path = externalAssetPath.trim()
        var trustedAttachmentPath = false

        if (path.startsWith("mp:", ignoreCase = true)) {
            path = path.substring(3)
        }

        if (path.startsWith("https://", ignoreCase = true) ||
            path.startsWith("http://", ignoreCase = true)
        ) {
            val uri = runCatching { URI(path) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals("media.discordapp.net", ignoreCase = true) ||
                uri.port !in setOf(-1, 443) ||
                uri.userInfo != null ||
                uri.rawFragment != null
            ) return null

            path = uri.rawPath.orEmpty().removePrefix("/")
            uri.rawQuery?.let { path += "?$it" }

            val mediaPath = path.substringBefore('?')
            val segments = mediaPath.split('/').filter { it.isNotEmpty() }
            trustedAttachmentPath = mediaPath.startsWith("attachments/") &&
                segments.size >= 4 &&
                path.length in 10..MAX_MP_PATH_LENGTH &&
                path.none { it.isWhitespace() || it.isISOControl() || it == '#' }
        } else {
            path = path.removePrefix("/")
        }

        if (!isValidExternalAssetPath(path) && !trustedAttachmentPath) return null
        return "mp:$path"
    }

    fun localReference(galleryId: String): String? =
        if (localIdPattern.matches(galleryId)) "$LOCAL_PREFIX$galleryId" else null

    fun isLocalReference(value: String): Boolean = localId(value) != null

    fun localId(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith(LOCAL_PREFIX, ignoreCase = true)) return null
        return trimmed.substring(LOCAL_PREFIX.length).takeIf(localIdPattern::matches)
    }

    fun isFresh(createdAtMillis: Long, nowMillis: Long): Boolean =
        createdAtMillis in 1..nowMillis && nowMillis - createdAtMillis < MP_REFERENCE_TTL_MS
}
