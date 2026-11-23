package app.geckopresence.host.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Input policy and AES-GCM envelope for a user's private Worker upload token. */
object ArtworkUploadTokenPolicy {
    fun normalize(input: String): String? {
        val token = input.trim()
        if (token.length !in 32..256) return null
        return token.takeIf { value -> value.all { it.code in 0x21..0x7e } }
    }
}

/** Kept separate from Discord's token codec so the two credential domains have distinct AAD. */
object ArtworkUploadTokenCodec {
    const val VERSION_PREFIX = "v1."
    const val MAX_ENVELOPE_LENGTH = 512
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private val associatedData = "gecko-presence:cloudflare-artwork-upload-token:v1".toByteArray(StandardCharsets.UTF_8)

    fun encrypt(token: String, key: SecretKey): String {
        require(ArtworkUploadTokenPolicy.normalize(token) == token) { "Worker upload token is invalid" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv ?: throw IllegalStateException("AES-GCM provider did not generate an IV")
        require(nonce.size == NONCE_BYTES) { "AES-GCM provider generated an unsupported IV length" }
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        return VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + encrypted)
    }

    fun decrypt(envelope: String, key: SecretKey): String {
        require(envelope.length <= MAX_ENVELOPE_LENGTH) { "Encrypted Worker token envelope is too large" }
        require(envelope.startsWith(VERSION_PREFIX)) { "Unsupported encrypted Worker token format" }
        val payload = Base64.getUrlDecoder().decode(envelope.removePrefix(VERSION_PREFIX))
        require(payload.size >= NONCE_BYTES + TAG_BITS / 8) { "Encrypted Worker token is truncated" }
        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val encrypted = payload.copyOfRange(NONCE_BYTES, payload.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        val token = String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        return ArtworkUploadTokenPolicy.normalize(token)
            ?: throw IllegalStateException("Stored Worker upload token failed validation")
    }
}
