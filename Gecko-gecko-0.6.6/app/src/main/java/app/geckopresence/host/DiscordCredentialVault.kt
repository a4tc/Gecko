package app.geckopresence.host

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.geckopresence.host.core.AesGcmTokenCodec
import app.geckopresence.host.core.DiscordTokenInputPolicy
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Local-only credential vault. Ciphertext is stored in private preferences while the
 * non-exportable AES key is held by Android Keystore. No network or logging is performed here.
 */
object DiscordCredentialVault {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "app.geckopresence.host.discord.gateway.aes.v1"
    private const val PREFS_NAME = "gpb_discord_credential_v1"
    private const val KEY_CIPHERTEXT = "token_ciphertext"
    private val lock = Any()

    fun hasCredential(context: Context): Boolean = preferences(context).contains(KEY_CIPHERTEXT)

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun save(context: Context, rawToken: String) = synchronized(lock) {
        val token = DiscordTokenInputPolicy.normalize(rawToken)
            ?: throw IllegalArgumentException("Token format is empty, too short, too long, or contains whitespace")
        val key = getOrCreateKey()
        val ciphertext = AesGcmTokenCodec.encrypt(token, key)
        check(preferences(context).edit().putString(KEY_CIPHERTEXT, ciphertext).commit()) {
            "Could not persist encrypted Discord credential"
        }
    }

    /** Call only from the foreground publisher path. Never log or expose the returned value to UI. */
    @Throws(IllegalStateException::class)
    fun loadForConnection(context: Context): String? = synchronized(lock) {
        val ciphertext = preferences(context).getString(KEY_CIPHERTEXT, null) ?: return null
        val key = getExistingKey()
            ?: throw IllegalStateException("Credential key is missing; remove and add the Discord credential again")
        try {
            val token = AesGcmTokenCodec.decrypt(ciphertext, key)
            DiscordTokenInputPolicy.normalize(token)
                ?: throw IllegalStateException("Stored Discord credential failed validation")
        } catch (error: Exception) {
            throw IllegalStateException("Stored Discord credential cannot be decrypted; remove and add it again", error)
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        check(preferences(context).edit().remove(KEY_CIPHERTEXT).commit()) {
            "Could not remove encrypted Discord credential"
        }
        runCatching {
            loadKeyStore().deleteEntry(KEY_ALIAS)
        }.getOrElse { error ->
            throw IllegalStateException("Could not remove Android Keystore key", error)
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey? =
        (loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey)

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
}
