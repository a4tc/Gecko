package app.geckopresence.host.core

/** Best-effort redaction for diagnostics and user-visible error reports. */
object SensitiveDataRedactor {
    private val authorizationPattern = Regex("(?im)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}")
    private val labeledSecretPattern = Regex("(?i)((?:discord[_ -]?)?(?:user[_ -]?)?(?:token|access_token|refresh_token)\\s*[:=]\\s*)[\\\"']?[^\\s,;\\\"']+")
    private val dottedTokenPattern = Regex("(?<![A-Za-z0-9])[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{20,}(?![A-Za-z0-9])")
    private val mfaTokenPattern = Regex("(?<![A-Za-z0-9])mfa\\.[A-Za-z0-9_-]{20,}(?![A-Za-z0-9])")

    fun redact(input: String): String {
        var value = authorizationPattern.replace(input) { "${it.groupValues[1]}[REDACTED]" }
        value = bearerPattern.replace(value, "Bearer [REDACTED]")
        value = labeledSecretPattern.replace(value) { "${it.groupValues[1]}[REDACTED]" }
        value = dottedTokenPattern.replace(value, "[REDACTED_DISCORD_TOKEN]")
        return mfaTokenPattern.replace(value, "[REDACTED_DISCORD_TOKEN]")
    }
}
