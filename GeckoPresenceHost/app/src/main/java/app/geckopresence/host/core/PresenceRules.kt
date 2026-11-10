package app.geckopresence.host.core

import java.util.Locale

object PresenceRules {
    private val hostPattern = Regex("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")
    private val controlCharacters = Regex("[\\u0000-\\u001f\\u007f]")
    private val whitespace = Regex("\\s+")
    private val sensitiveLabels = Regex("(^|[.-])(bank|banking|finance|fintech|wallet|payment|pay|invest|trading|broker|crypto|exchange|auth|authenticator|otp|password|passkey|health|medical|clinic|hospital|insurance|government|gov|tax|identity|mail|email|webmail|messaging|messages|whatsapp|telegram|signal|discord|slack|teams|contacts|sms|phone|account|accounts|login|signin|checkout|billing|security|dukcapil|passport|imigrasi|ktp|pharmacy|apotek|medicine|bca|bni|bri|mandiri|seabank|jago|livin|dana|ovo|gopay|bibit|ajaib|bareksa|stockbit|pluang|indodax|pintu|binance|bybit|coinbase|bpjs|jkn|satusehat|credit|loan)([.-]|$)", RegexOption.IGNORE_CASE)
    private val sensitiveHosts = setOf("gmail.com", "outlook.com", "proton.me", "web.whatsapp.com", "web.telegram.org", "discord.com", "seabank.co.id", "paypal.com", "wise.com")

    fun sanitizeHost(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val normalized = raw.trim()
            .lowercase(Locale.ROOT)
            .removePrefix("www.")
            .removeSuffix(".")

        if (normalized.isEmpty() || normalized.length > 253) return null
        if (!hostPattern.matches(normalized)) return null
        if (normalized.contains("..")) return null

        val labels = normalized.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) {
            return null
        }

        return normalized
    }

    fun isSensitiveHost(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.ROOT).removePrefix("www.").removeSuffix(".")
        return sensitiveHosts.any { normalized == it || normalized.endsWith(".$it") } || sensitiveLabels.containsMatchIn(normalized)
    }

    fun cleanText(raw: String?, maxLength: Int): String? {
        require(maxLength > 0) { "maxLength must be positive" }
        if (raw.isNullOrBlank()) return null

        val cleaned = raw
            .replace(controlCharacters, " ")
            .replace(whitespace, " ")
            .trim()

        if (cleaned.isEmpty()) return null
        return cleaned.take(maxLength)
    }

    fun normalizeMediaState(raw: String?): String {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "playing" -> "playing"
            "paused" -> "paused"
            "ended" -> "ended"
            else -> "browsing"
        }
    }

    fun defaultAppDetails(packageName: String, label: String): String {
        return AppActivityRules.defaultActivityDetails(packageName, label)
    }

    fun defaultDetails(mediaState: String): String {
        return when (normalizeMediaState(mediaState)) {
            "playing" -> "Watching"
            "paused" -> "Paused"
            "ended" -> "Finished"
            else -> "Browsing"
        }
    }
}
