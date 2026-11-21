package app.geckopresence.host

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.geckopresence.host.core.ArtworkUploadTokenCodec
import app.geckopresence.host.core.ArtworkUploadTokenPolicy
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Stores only ciphertext in private preferences; the non-exportable AES key lives in Android Keystore. */
object ArtworkUploadCredentialVault {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "app.geckopresence.host.artwork.worker.aes.v1"
    private const val PREFS_NAME = "gpb_artwork_upload_credential_v1"
    private const val KEY_CIPHERTEXT = "worker_token_ciphertext"
    private val lock = Any()

    fun hasCredential(context: Context): Boolean = preferences(context).contains(KEY_CIPHERTEXT)

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun save(context: Context, rawToken: String) = synchronized(lock) {
        val token = ArtworkUploadTokenPolicy.normalize(rawToken)
            ?: throw IllegalArgumentException("Worker upload token must be 32–256 printable characters without whitespace.")
        val ciphertext = ArtworkUploadTokenCodec.encrypt(token, getOrCreateKey())
        check(preferences(context).edit().putString(KEY_CIPHERTEXT, ciphertext).commit()) {
            "Could not persist encrypted Worker upload token"
        }
    }

    /** Only call on a foreground operation that needs to authenticate to the configured Worker. */
    @Throws(IllegalStateException::class)
    fun loadForUpload(context: Context): String? = synchronized(lock) {
        val ciphertext = preferences(context).getString(KEY_CIPHERTEXT, null) ?: return null
        val key = getExistingKey()
            ?: throw IllegalStateException("Worker token key is missing; save the token again in Settings")
        try {
            ArtworkUploadTokenCodec.decrypt(ciphertext, key)
        } catch (error: Exception) {
            throw IllegalStateException("Stored Worker token cannot be decrypted; save it again in Settings", error)
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        check(preferences(context).edit().remove(KEY_CIPHERTEXT).commit()) {
            "Could not remove encrypted Worker upload token"
        }
        runCatching { loadKeyStore().deleteEntry(KEY_ALIAS) }.getOrElse { error ->
            throw IllegalStateException("Could not remove Worker token Keystore key", error)
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private fun getExistingKey(): SecretKey? = loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
}
