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
import app.geckopresence.host.core.DiscordArtworkPolicy
import app.geckopresence.host.core.DiscordTokenInputPolicy
import app.geckopresence.host.core.PresenceAsset
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Resolves app/media/gallery artwork into a Discord media-proxy reference ("mp:...") without any
 * third-party host. Images go to the user's own private webhook, then are registered with Discord's
 * external-assets endpoint. Everything runs on one background thread and never blocks presence tracking.
 */
object ArtworkAutoPublisher {
    const val KEY_AUTO_ARTWORK = "artwork_auto_discord_v1"
    private const val PREFS_SETTINGS = "gpb_settings"
    private const val PREFS_CACHE = "gpb_artwork_discord_cache_v2"
    private const val LEGACY_PREFS_CACHE = "gpb_artwork_auto_cache"
    private const val CACHE_PREFIX = "auto:"
    private const val MAX_CACHE_ENTRIES = 200
    private const val MAX_DECODE_EDGE = 1_024

    private val publishLock = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "gecko-artwork-auto-publisher").apply { isDaemon = true }
    }

    /** Automatic artwork is ON by default and only active once a webhook has been saved. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_ARTWORK, true) &&
            isConfigured(context)

    fun isConfigured(context: Context): Boolean = loadWebhook(context) != null

    fun resolveMediaArtwork(
        context: Context,
        controller: MediaController,
        displayName: String,
        onResolved: (PresenceAsset?, String?) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            if (!isEnabled(app)) {
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
            if (!isEnabled(app)) {
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

    /** Resolves a stable "gecko:<gallery id>" reference into a fresh media-proxy reference. */
    fun resolveGalleryReference(
        context: Context,
        galleryId: String,
        onResolved: (String?, String?) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { resolveGalleryReferenceBlocking(app, galleryId) }
                .getOrElse { null to "Gallery artwork processing failed · ${it.javaClass.simpleName}" }
            onResolved(result.first, result.second)
        }
    }

    /** For callers that already run on their own worker thread (Gallery / Studio upload). */
    fun resolveGalleryReferenceBlocking(context: Context, galleryId: String): Pair<String?, String?> {
        val app = context.applicationContext
        if (!isConfigured(app)) return null to "Save a Discord webhook in Settings before artwork can be published"
        val artwork = ArtworkGalleryStore.list(app).firstOrNull { it.id == galleryId }
            ?: return null to "Gallery artwork no longer exists"
        val file = ArtworkGalleryStore.file(app, artwork)
            ?: return null to "Gallery artwork file is missing or failed its integrity check"
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return null to "Gallery artwork could not be read"
        return publishBytes(app, bytes)
    }

    private fun publishBitmap(context: Context, bitmap: Bitmap, displayName: String): Pair<PresenceAsset?, String?> {
        if (!ArtworkMediaPolicy.hasValidDimensions(bitmap.width, bitmap.height)) {
            return null to "Artwork dimensions are invalid or exceed 40 megapixels"
        }
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
        val (reference, error) = publishBytes(context, encoded)
        return reference?.let { PresenceAsset(it, displayName.take(80)) to null } ?: (null to error)
    }

    /** Returns (mp reference, null) or (null, human-readable reason). Serialized so one image uploads once. */
    private fun publishBytes(context: Context, bytes: ByteArray): Pair<String?, String?> = synchronized(publishLock) {
        if (bytes.isEmpty() || bytes.size > ArtworkMediaPolicy.MAX_DISCORD_UPLOAD_BYTES) {
            return null to "Artwork exceeds the 25 MB delivery limit"
        }
        val webhook = loadWebhook(context)
            ?: return null to "Save a Discord webhook in Settings before artwork can be published"
        val hash = ArtworkMediaPolicy.sha256(bytes)
        val now = System.currentTimeMillis()
        val cached = readCache(context, hash)
        if (cached != null && DiscordArtworkPolicy.isFresh(cached.createdAt, now)) return cached.reference to null

        return when (val result = DiscordArtworkUploader.uploadAndReference(webhook, bytes, hash)) {
            is DiscordArtworkUploader.Result.Success -> {
                val registered = result.value
                if (!writeCache(context, hash, registered.mediaProxyReference, registered.messageId, now, webhook)) {
                    null to "Artwork was registered but its local cache could not be updated"
                } else {
                    // The replaced attachment is obsolete: remove it from the webhook channel.
                    cached?.messageId?.let { DiscordArtworkUploader.deleteWebhookMessage(webhook, it) }
                    registered.mediaProxyReference to null
                }
            }
            is DiscordArtworkUploader.Result.Failure -> null to result.message
        }
    }

    private fun loadWebhook(context: Context): String? = runCatching {
        ArtworkUploadCredentialVault.loadForUpload(context)
    }.getOrNull()?.let(DiscordArtworkPolicy::normalizeWebhookUrl)

    private data class CacheEntry(val reference: String, val messageId: String, val createdAt: Long)

    private fun readCache(context: Context, hash: String): CacheEntry? {
        if (!Regex("^[a-f0-9]{64}$").matches(hash)) return null
        val prefs = cachePrefs(context)
        val key = CACHE_PREFIX + hash
        val raw = prefs.getString(key, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val reference = json?.optString("mp").orEmpty()
        val messageId = json?.optString("messageId").orEmpty()
        val createdAt = json?.optLong("createdAt") ?: 0L
        if (!reference.startsWith("mp:") || DiscordArtworkPolicy.toMediaProxyReference(reference.removePrefix("mp:")) == null ||
            !DiscordArtworkPolicy.isValidMessageId(messageId)
        ) {
            prefs.edit().remove(key).apply()
            return null
        }
        return CacheEntry(reference, messageId, createdAt)
    }

    private fun writeCache(
        context: Context,
        hash: String,
        reference: String,
        messageId: String,
        now: Long,
        webhook: String,
    ): Boolean {
        if (!Regex("^[a-f0-9]{64}$").matches(hash)) return false
        val prefs = cachePrefs(context)
        val value = JSONObject().put("mp", reference).put("messageId", messageId).put("createdAt", now).toString()
        if (!prefs.edit().putString(CACHE_PREFIX + hash, value).commit()) return false

        val entries = prefs.all.entries.asSequence()
            .filter { it.key.startsWith(CACHE_PREFIX) }
            .map { entry ->
                val json = runCatching { JSONObject(entry.value as? String ?: "{}") }.getOrNull()
                Triple(entry.key, json?.optLong("createdAt") ?: 0L, json?.optString("messageId").orEmpty())
            }
            .sortedBy { it.second }
            .toList()
        if (entries.size > MAX_CACHE_ENTRIES) {
            val evicted = entries.take(entries.size - MAX_CACHE_ENTRIES)
            val editor = prefs.edit()
            evicted.forEach { (staleKey, _, staleMessage) ->
                editor.remove(staleKey)
                if (staleMessage.isNotBlank()) DiscordArtworkUploader.deleteWebhookMessage(webhook, staleMessage)
            }
            editor.apply()
        }
        return true
    }

    private fun cachePrefs(context: Context) = context.applicationContext.also {
        // Pre-0.7 cache held third-party URLs; it is obsolete.
        it.deleteSharedPreferences(LEGACY_PREFS_CACHE)
    }.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)

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
