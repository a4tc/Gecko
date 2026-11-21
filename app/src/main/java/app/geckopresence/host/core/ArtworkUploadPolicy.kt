package app.geckopresence.host.core

import java.net.URI

/** Validation for a user-owned Cloudflare Worker and its public R2 asset origin. */
object ArtworkUploadPolicy {
    private const val MAX_ENDPOINT_LENGTH = 512
    private const val MAX_ORIGIN_LENGTH = 253
    private val hostnamePattern = Regex(
        "^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*$",
    )
    private val assetPathPattern = Regex("^/artwork/[a-f0-9]{64}\\.(?:jpg|png|webp)$")

    fun normalizeWorkerEndpoint(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.length > MAX_ENDPOINT_LENGTH || containsUnsafeCharacters(value)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = validHttpsHost(uri) ?: return null
        if (uri.path !in setOf("", "/", "/v1/upload") || uri.rawQuery != null || uri.rawFragment != null) return null
        return "https://$host/v1/upload"
    }

    fun normalizeAssetBaseUrl(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.length > MAX_ORIGIN_LENGTH || containsUnsafeCharacters(value)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = validHttpsHost(uri) ?: return null
        if (uri.path !in setOf("", "/") || uri.rawQuery != null || uri.rawFragment != null) return null
        return "https://$host"
    }

    fun isValidUploadToken(input: String): Boolean {
        if (input.length !in 32..256) return false
        return input.all { it.code in 0x21..0x7e }
    }

    fun isSafeCloudflareAssetUrl(value: String): Boolean {
        if (value.isBlank() || value.length > 512 || containsUnsafeCharacters(value)) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            isPublicDnsHostname(host) &&
            uri.port in setOf(-1, 443) &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            assetPathPattern.matches(uri.rawPath.orEmpty())
    }

    fun isSafeCloudflareAssetUrl(value: String, assetBaseUrl: String): Boolean {
        if (!isSafeCloudflareAssetUrl(value)) return false
        val base = normalizeAssetBaseUrl(assetBaseUrl) ?: return false
        val baseUri = URI(base)
        val uri = URI(value)
        return uri.host?.equals(baseUri.host, ignoreCase = true) == true
    }

    fun isExpectedCloudflareAssetUrl(value: String, sha256: String): Boolean {
        if (!sha256.matches(Regex("^[a-f0-9]{64}$")) || !isSafeCloudflareAssetUrl(value)) return false
        return runCatching {
            val path = URI(value).rawPath.orEmpty()
            val extension = path.substringAfterLast('.', "")
            extension in setOf("jpg", "png", "webp") && path == "/artwork/$sha256.$extension"
        }.getOrDefault(false)
    }

    fun isExpectedCloudflareAssetUrl(
        value: String,
        assetBaseUrl: String,
        sha256: String,
        extension: String,
    ): Boolean {
        if (!sha256.matches(Regex("^[a-f0-9]{64}$")) || extension !in setOf("jpg", "png", "webp")) return false
        if (!isSafeCloudflareAssetUrl(value, assetBaseUrl)) return false
        val expectedPath = "/artwork/$sha256.$extension"
        return runCatching { URI(value).rawPath == expectedPath }.getOrDefault(false)
    }

    private fun validHttpsHost(uri: URI): String? {
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
        val host = uri.host?.lowercase() ?: return null
        if (!isPublicDnsHostname(host)) return null
        return host
    }

    private fun isPublicDnsHostname(host: String): Boolean {
        if (!hostnamePattern.matches(host) || !host.contains('.')) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        val labels = host.split('.')
        if (labels.last().all(Char::isDigit)) return false
        val ipv4Like = labels.size == 4 && labels.all { label ->
            label.isNotEmpty() && label.all(Char::isDigit) && label.toIntOrNull() in 0..255
        }
        return !ipv4Like
    }

    private fun containsUnsafeCharacters(value: String): Boolean =
        value.any { it.isWhitespace() || it.isISOControl() }
}
