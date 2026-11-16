package app.geckopresence.host.core

import javax.crypto.KeyGenerator
import java.util.Base64
import javax.crypto.SecretKey

object DiscordTokenSecuritySelfTest {
    @JvmStatic
    fun main(args: Array<String>) = run()

    @JvmStatic
    fun run() {
        val key = newTestKey()
        val token = "mfa.test-user-owned-secret-token-value"
        val encrypted = AesGcmTokenCodec.encrypt(token, key)

        check(encrypted.startsWith(AesGcmTokenCodec.VERSION_PREFIX))
        check(!encrypted.contains(token)) { "Ciphertext must not expose the token" }
        check(AesGcmTokenCodec.decrypt(encrypted, key) == token) { "Encrypted token must round-trip" }
        check(runCatching {
            AesGcmTokenCodec.decrypt(AesGcmTokenCodec.VERSION_PREFIX + "A".repeat(AesGcmTokenCodec.MAX_ENVELOPE_LENGTH + 1), key)
        }.isFailure) { "Oversized encrypted credential envelopes must be rejected before Base64 decoding" }
        check(AesGcmTokenCodec.encrypt(token, key) != encrypted) { "Each encryption must use a fresh nonce" }

        val tamperedBytes = Base64.getUrlDecoder().decode(encrypted.removePrefix(AesGcmTokenCodec.VERSION_PREFIX))
        tamperedBytes[tamperedBytes.lastIndex] = (tamperedBytes.last().toInt() xor 0x01).toByte()
        val tampered = AesGcmTokenCodec.VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedBytes)
        check(runCatching { AesGcmTokenCodec.decrypt(tampered, key) }.isFailure) {
            "Authenticated encryption must reject modified ciphertext"
        }

        check(DiscordTokenInputPolicy.normalize("  $token  ") == token)
        check(DiscordTokenInputPolicy.normalize("short") == null)
        check(DiscordTokenInputPolicy.normalize("token with spaces is invalid") == null)
        check(DiscordTokenInputPolicy.normalize("x".repeat(DiscordTokenInputPolicy.MAX_TOKEN_LENGTH + 1)) == null)
        val jsEncoded = Base64.getEncoder().encodeToString(token.toByteArray(Charsets.UTF_8))
        check(DiscordSessionImportDecoder.decodeJavascriptResult("\"$jsEncoded\"") == token)
        check(DiscordSessionImportDecoder.decodeJavascriptResult("null") == null)
        check(DiscordSessionImportDecoder.decodeJavascriptResult("\"not base64 ###\"") == null)

        val tokenLike = "AbCdEf0123456789AbCdEf.abcdef.ABCDEf0123456789AbCdEf0123456789"
        val redacted = SensitiveDataRedactor.redact(
            "Authorization: Bearer $tokenLike\nuser_token=$tokenLike\nplain=$tokenLike",
        )
        check(!redacted.contains(tokenLike)) { "Diagnostic redaction must remove token-like secrets" }
        check(redacted.contains("[REDACTED"))
        val structuredSecrets = SensitiveDataRedactor.redact(
            "{\"access_token\":\"$tokenLike\", \"refresh_token\":\"$tokenLike\"}\nAuthorization: $tokenLike",
        )
        check(!structuredSecrets.contains(tokenLike)) { "Structured credential labels must be redacted" }
        println("DiscordTokenSecuritySelfTest: authenticated encryption, session-result parsing, input policy, and secret redaction passed")
    }

    private fun newTestKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}
