package app.geckopresence.host

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureStore {
    private const val PREFS_NAME = "gpb_secure_store"
    private const val KEY_ALIAS = "gecko_presence_host_aes_key"
    private const val BRIDGE_KEY_IV = "bridge_key_iv"
    private const val BRIDGE_KEY_CIPHER = "bridge_key_cipher"
    private const val REFRESH_TOKEN_IV = "refresh_token_iv"
    private const val REFRESH_TOKEN_CIPHER = "refresh_token_cipher"
    private const val GCM_TAG_LENGTH_BITS = 128

    @Synchronized
    fun getOrCreateBridgeKey(context: Context): String {
        val existing = readEncrypted(context, BRIDGE_KEY_IV, BRIDGE_KEY_CIPHER)
        if (!existing.isNullOrBlank()) return existing

        val randomBytes = ByteArray(32)
        try {
            java.security.SecureRandom().nextBytes(randomBytes)
            val generated = Base64.encodeToString(
                randomBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            storeEncrypted(context, BRIDGE_KEY_IV, BRIDGE_KEY_CIPHER, generated)
            return generated
        } finally {
            randomBytes.fill(0)
        }
    }

    @Synchronized
    fun rotateBridgeKey(context: Context): String {
        val randomBytes = ByteArray(32)
        try {
            java.security.SecureRandom().nextBytes(randomBytes)
            val generated = Base64.encodeToString(
                randomBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            storeEncrypted(context, BRIDGE_KEY_IV, BRIDGE_KEY_CIPHER, generated)
            return generated
        } finally {
            randomBytes.fill(0)
        }
    }


    @Synchronized
    fun restoreBridgeKey(context: Context, candidate: String) {
        val normalized = candidate.trim()
        require(normalized.isNotBlank()) { "Pairing key cannot be empty" }
        val decoded = try {
            Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Pairing key format is invalid")
        }
        try {
            require(decoded.size == 32) { "Pairing key must be a 256-bit key" }
            storeEncrypted(context, BRIDGE_KEY_IV, BRIDGE_KEY_CIPHER, normalized)
        } finally {
            decoded.fill(0)
        }
    }

    @Synchronized
    fun storeRefreshToken(context: Context, refreshToken: String) {
        require(refreshToken.isNotBlank()) { "Refresh token must not be empty" }
        storeEncrypted(context, REFRESH_TOKEN_IV, REFRESH_TOKEN_CIPHER, refreshToken)
    }

    @Synchronized
    fun getRefreshToken(context: Context): String? {
        return readEncrypted(context, REFRESH_TOKEN_IV, REFRESH_TOKEN_CIPHER)
    }

    @Synchronized
    fun clearRefreshToken(context: Context) {
        val cleared = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(REFRESH_TOKEN_IV)
            .remove(REFRESH_TOKEN_CIPHER)
            .commit()
        check(cleared) { "Could not clear the encrypted Discord session" }
    }

    @Synchronized
    fun hasRefreshToken(context: Context): Boolean {
        return !getRefreshToken(context).isNullOrBlank()
    }

    private fun storeEncrypted(
        context: Context,
        ivKey: String,
        cipherKey: String,
        plaintext: String,
    ) {
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
            val encrypted = cipher.doFinal(plainBytes)
            val iv = cipher.iv
            try {
                val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(ivKey, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(cipherKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit()
                check(stored) { "Could not persist encrypted app data" }
            } finally {
                encrypted.fill(0)
                iv.fill(0)
            }
        } finally {
            plainBytes.fill(0)
        }
    }

    private fun readEncrypted(context: Context, ivKey: String, cipherKey: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encodedIv = prefs.getString(ivKey, null) ?: return null
        val encodedCiphertext = prefs.getString(cipherKey, null) ?: return null
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        var plaintext: ByteArray? = null

        return try {
            iv = Base64.decode(encodedIv, Base64.DEFAULT)
            ciphertext = Base64.decode(encodedCiphertext, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateAesKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
            plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove(ivKey).remove(cipherKey).commit()
            null
        } finally {
            iv?.fill(0)
            ciphertext?.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
