package app.geckopresence.host

import android.content.Context
import app.geckopresence.host.core.ArtworkUploadPolicy

/** Non-secret Worker settings. The upload token is intentionally stored by a separate vault. */
object ArtworkUploadConfigStore {
    private const val PREFS_NAME = "gpb_artwork_upload_config_v1"
    private const val KEY_WORKER_ENDPOINT = "worker_upload_endpoint"
    private const val KEY_ASSET_BASE_URL = "public_asset_base_url"

    data class Config(
        val uploadEndpoint: String,
        val assetBaseUrl: String,
    )

    fun load(context: Context): Config? {
        val prefs = preferences(context)
        val endpoint = ArtworkUploadPolicy.normalizeWorkerEndpoint(
            prefs.getString(KEY_WORKER_ENDPOINT, "").orEmpty(),
        ) ?: return null
        val assetBase = ArtworkUploadPolicy.normalizeAssetBaseUrl(
            prefs.getString(KEY_ASSET_BASE_URL, "").orEmpty(),
        ) ?: return null
        return Config(endpoint, assetBase)
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun save(context: Context, workerEndpoint: String, assetBaseUrl: String): Config {
        val endpoint = ArtworkUploadPolicy.normalizeWorkerEndpoint(workerEndpoint)
            ?: throw IllegalArgumentException("Enter a valid HTTPS Cloudflare Worker URL without a path other than /v1/upload.")
        val assetBase = ArtworkUploadPolicy.normalizeAssetBaseUrl(assetBaseUrl)
            ?: throw IllegalArgumentException("Enter a valid HTTPS public R2 asset origin, without a path or query.")
        check(preferences(context).edit()
            .putString(KEY_WORKER_ENDPOINT, endpoint)
            .putString(KEY_ASSET_BASE_URL, assetBase)
            .commit()) {
            "Could not save Cloudflare Worker settings"
        }
        return Config(endpoint, assetBase)
    }

    fun clear(context: Context) {
        check(preferences(context).edit()
            .remove(KEY_WORKER_ENDPOINT)
            .remove(KEY_ASSET_BASE_URL)
            .commit()) {
            "Could not remove Cloudflare Worker settings"
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
