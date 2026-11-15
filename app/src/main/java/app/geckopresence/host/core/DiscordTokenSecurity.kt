package app.geckopresence.host.core

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Input checks only; Discord may change token formats, so this deliberately avoids a brittle regex. */
object DiscordTokenInputPolicy {
    const val MIN_TOKEN_LENGTH = 16
    const val MAX_TOKEN_LENGTH = 4096

    fun normalize(input: String): String? {
        val token = input.trim()
        if (token.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) return null
        if (token.any(Char::isWhitespace) || token.any { it.code < 0x20 || it.code == 0x7f }) return null
        return token
    }
}

/** AES-256-GCM envelope for account secrets. The key itself is managed by Android Keystore. */
object AesGcmTokenCodec {
    const val VERSION_PREFIX = "v1."
    const val MAX_ENVELOPE_LENGTH = 5_600
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private val associatedData = "gecko-presence:discord-user-token:v1".toByteArray(StandardCharsets.UTF_8)

    fun encrypt(token: String, key: SecretKey, random: SecureRandom = SecureRandom()): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val envelope = nonce + encrypted
        return VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope)
    }

    fun decrypt(envelope: String, key: SecretKey): String {
        require(envelope.length <= MAX_ENVELOPE_LENGTH) { "Encrypted credential envelope is too large" }
        require(envelope.startsWith(VERSION_PREFIX)) { "Unsupported encrypted credential format" }
        val payload = Base64.getUrlDecoder().decode(envelope.removePrefix(VERSION_PREFIX))
        require(payload.size >= NONCE_BYTES + (TAG_BITS / 8)) { "Encrypted credential is truncated" }
        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val encrypted = payload.copyOfRange(NONCE_BYTES, payload.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }
}

/** Decodes only the JSON-string result returned by WebView's evaluateJavascript callback. */
object DiscordSessionImportDecoder {
    fun decodeJavascriptResult(result: String?): String? {
        val raw = result?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val encoded = raw.removeSurrounding("\"")
        if (encoded.isEmpty() || encoded.length > 12_000 || !encoded.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))) {
            return null
        }
        val decoded = runCatching {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        return DiscordTokenInputPolicy.normalize(decoded)
    }
}
