package app.geckopresence.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.net.Uri
import app.geckopresence.host.core.ArtworkMediaPolicy
import app.geckopresence.host.core.ArtworkUploadPolicy
import app.geckopresence.host.core.PresenceAsset
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Resolves app/media artwork and publishes it only after the user enables standing public-upload consent. */
object ArtworkAutoPublisher {
    const val KEY_AUTO_PUBLIC_UPLOAD = "artwork_auto_public_upload_cloudflare_v1"
    private const val PREFS_SETTINGS = "gpb_settings"
    private const val PREFS_AUTO_CACHE = "gpb_artwork_auto_cache"
    private const val AUTO_CACHE_PREFIX = "auto:"
    private const val MAX_AUTO_CACHE_ENTRIES = 200
    private const val MAX_DECODE_EDGE = 1_024

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "gecko-artwork-auto-publisher").apply { isDaemon = true }
    }

    fun resolveMediaArtwork(
        context: Context,
        controller: MediaController,
        displayName: String,
        onResolved: (PresenceAsset?, String?) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            if (!isStandingConsentEnabled(app)) {
                onResolved(null, null)
                return@execute
            }
            val result = runCatching {
                val metadata = controller.metadata
                val artwork = metadata?.let { extractMediaArtwork(app, it) }
                    ?: loadApplicationIcon(app, controller.packageName)
                artwork?.let { publishBitmap(app, it, displayName) }
                    ?: (null to "No usable media artwork or app icon was available")
            }.getOrElse { null to "Artwork processing failed · ${it.javaClass.simpleName}" }
            onResolved(result.first, result.second)
        }
    }

    fun resolveApplicationIcon(
        context: Context,
        packageName: String,
        displayName: String,
        onResolved: (PresenceAsset?, String?) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            if (!isStandingConsentEnabled(app)) {
                onResolved(null, null)
                return@execute
            }
            val result = runCatching {
                loadApplicationIcon(app, packageName)?.let { publishBitmap(app, it, displayName) }
                    ?: (null to "Application artwork is unavailable")
            }.getOrElse { null to "Application artwork processing failed · ${it.javaClass.simpleName}" }
            onResolved(result.first, result.second)
        }
    }

    private fun isStandingConsentEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTO_PUBLIC_UPLOAD, false)

    /** Automatic uploads use a bounded SHA-256 URL cache, not the user's 40-item manual Gallery. */
    private fun publishBitmap(context: Context, bitmap: Bitmap, displayName: String): Pair<PresenceAsset?, String?> {
        if (!ArtworkMediaPolicy.hasValidDimensions(bitmap.width, bitmap.height)) {
            return null to "Artwork dimensions are invalid or exceed 40 megapixels"
        }
        if (!isStandingConsentEnabled(context)) return null to null

        val config = ArtworkUploadConfigStore.load(context)
            ?: return null to "Configure your artwork host before enabling automatic uploads"
        val token = runCatching { ArtworkUploadCredentialVault.loadForUpload(context) }
            .getOrNull()
            ?.takeIf(ArtworkUploadPolicy::isValidUploadToken)
            ?: return null to "Save a valid private artwork-host token before uploading"

        val boundedBitmap = downsample(bitmap, MAX_DECODE_EDGE)
        val encoded = try {
            ByteArrayOutputStream().use { output ->
                val success = boundedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                if (!success) return null to "Artwork could not be encoded"
                output.toByteArray()
            }
        } finally {
            if (boundedBitmap !== bitmap) boundedBitmap.recycle()
        }
        if (encoded.isEmpty() || encoded.size > ArtworkMediaPolicy.MAX_FILE_BYTES) {
            return null to "Artwork exceeds the 8 MiB upload limit"
        }

        val hash = ArtworkMediaPolicy.sha256(encoded)
        cachedUrl(context, hash, config.assetBaseUrl)?.let { return PresenceAsset(it, displayName.take(80)) to null }
        if (!isStandingConsentEnabled(context)) {
            return null to "Public upload consent was revoked before artwork upload started"
        }

        val stagedFile = stageTemporaryArtwork(context, hash, encoded)
            ?: return null to "Could not stage artwork in private temporary storage"
        val upload = try {
            CloudflareArtworkUploader.upload(stagedFile, config, token)
        } finally {
            stagedFile.delete()
        }
        return when (upload) {
            is CloudflareArtworkUploader.Result.Success -> {
                val url = upload.directUrl
                if (!isStandingConsentEnabled(context)) {
                    null to "Public-upload consent was revoked during upload; future uploads are disabled"
                } else if (url.isNullOrBlank() || !cacheUrl(context, hash, config.assetBaseUrl, url)) {
                    null to "Artwork uploaded but its local URL cache could not be updated"
                } else {
                    PresenceAsset(url, displayName.take(80)) to null
                }
            }
            is CloudflareArtworkUploader.Result.Failure -> null to upload.message
        }
    }

    private fun cachedUrl(context: Context, hash: String, assetBaseUrl: String): String? {
        if (!Regex("^[a-f0-9]{64}$").matches(hash)) return null
        val prefs = context.getSharedPreferences(PREFS_AUTO_CACHE, Context.MODE_PRIVATE)
        val key = AUTO_CACHE_PREFIX + hash
        val raw = prefs.getString(key, null) ?: return null
        val url = runCatching { JSONObject(raw).optString("url") }.getOrNull()
        if (url.isNullOrBlank() ||
            !ArtworkUploadPolicy.isSafeCloudflareAssetUrl(url, assetBaseUrl) ||
            !ArtworkUploadPolicy.isExpectedCloudflareAssetUrl(url, hash)
        ) {
            prefs.edit().remove(key).apply()
            return null
        }
        return url
    }

    private fun cacheUrl(context: Context, hash: String, assetBaseUrl: String, url: String): Boolean {
        if (!Regex("^[a-f0-9]{64}$").matches(hash) ||
            !ArtworkUploadPolicy.isSafeCloudflareAssetUrl(url, assetBaseUrl) ||
            !ArtworkUploadPolicy.isExpectedCloudflareAssetUrl(url, hash)
        ) return false
        val prefs = context.getSharedPreferences(PREFS_AUTO_CACHE, Context.MODE_PRIVATE)
        val key = AUTO_CACHE_PREFIX + hash
        val value = JSONObject().put("url", url).put("createdAt", System.currentTimeMillis()).toString()
        if (!prefs.edit().putString(key, value).commit()) return false

        val staleKeys = prefs.all.entries.asSequence()
            .filter { it.key.startsWith(AUTO_CACHE_PREFIX) }
            .mapNotNull { entry ->
                val createdAt = runCatching { JSONObject(entry.value as? String ?: "{}").optLong("createdAt") }.getOrDefault(0L)
                entry.key to createdAt
            }
            .sortedBy { it.second }
            .toList()
        if (staleKeys.size > MAX_AUTO_CACHE_ENTRIES) {
            val editor = prefs.edit()
            staleKeys.take(staleKeys.size - MAX_AUTO_CACHE_ENTRIES).forEach { (staleKey, _) -> editor.remove(staleKey) }
            editor.apply()
        }
        return true
    }

    private fun stageTemporaryArtwork(context: Context, hash: String, bytes: ByteArray): File? = runCatching {
        val cacheRoot = context.cacheDir.canonicalFile
        val directory = File(cacheRoot, "gecko-auto-artwork").apply { mkdirs() }.canonicalFile
        if (directory.parentFile != cacheRoot) return null
        val target = File(directory, "$hash.png").canonicalFile
        if (target.parentFile != directory) return null
        FileOutputStream(target).use { it.write(bytes) }
        target.takeIf { it.isFile && it.length() == bytes.size.toLong() }
    }.getOrNull()

    private fun extractMediaArtwork(context: Context, metadata: MediaMetadata): Bitmap? {
        val uriCandidates = listOfNotNull(
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
        ).distinct()
        uriCandidates.forEach { value ->
            val decoded = decodeContentArtwork(context, value)
            if (decoded != null) return decoded
        }

        return sequenceOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).mapNotNull { key -> runCatching { metadata.getBitmap(key) }.getOrNull() }
            .firstOrNull { ArtworkMediaPolicy.hasValidDimensions(it.width, it.height) }
    }

    /** Only content:// artwork is opened. HTTP(S) metadata URLs are not fetched implicitly. */
    private fun decodeContentArtwork(context: Context, value: String): Bitmap? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsRead = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
                true
            } ?: false
        }.getOrDefault(false)
        if (!boundsRead) return null
        if (!ArtworkMediaPolicy.hasValidDimensions(bounds.outWidth, bounds.outHeight)) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DECODE_EDGE || bounds.outHeight / sampleSize > MAX_DECODE_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()?.takeIf { ArtworkMediaPolicy.hasValidDimensions(it.width, it.height) }
    }

    private fun loadApplicationIcon(context: Context, packageName: String?): Bitmap? {
        if (packageName.isNullOrBlank() || packageName == context.packageName) return null
        val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() ?: return null
        return drawableToBitmap(drawable)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap.takeIf { ArtworkMediaPolicy.hasValidDimensions(it.width, it.height) }
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_DECODE_EDGE) ?: 256
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_DECODE_EDGE) ?: 256
        if (!ArtworkMediaPolicy.hasValidDimensions(width, height)) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }.getOrNull()
    }

    private fun downsample(bitmap: Bitmap, maxEdge: Int): Bitmap {
        if (!ArtworkMediaPolicy.hasValidDimensions(bitmap.width, bitmap.height)) return bitmap
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longestEdge.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
