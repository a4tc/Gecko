package app.geckopresence.host

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import org.json.JSONObject
import app.geckopresence.host.core.ArtworkMediaPolicy
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Private local artwork library. Gallery files are never uploaded unless the user confirms. */
object ArtworkGalleryStore {
    const val MAX_FILE_BYTES = ArtworkMediaPolicy.MAX_FILE_BYTES
    private const val MAX_ITEMS = ArtworkMediaPolicy.MAX_ITEMS
    private const val MAX_TOTAL_BYTES = ArtworkMediaPolicy.MAX_TOTAL_BYTES
    private const val PREFS = "gpb_artwork_gallery"
    private const val PREFIX = "artwork:"

    data class Artwork(
        val id: String,
        val displayName: String,
        val fileName: String,
        val mimeType: String,
        val byteCount: Long,
        val sha256: String,
        val remoteUrl: String? = null,
        val createdAt: Long,
    )

    sealed class ImportResult {
        data class Added(val artwork: Artwork) : ImportResult()
        data class Duplicate(val artwork: Artwork) : ImportResult()
        data class Rejected(val reason: String) : ImportResult()
    }

    fun importUri(context: Context, uri: Uri): ImportResult {
        val app = context.applicationContext
        if (!uri.scheme.equals("content", ignoreCase = true)) {
            return ImportResult.Rejected("The document provider returned an unsupported URI.")
        }
        val directory = galleryDirectory(app).apply { mkdirs() }
        val privateRoot = app.filesDir.canonicalFile
        val canonicalDirectory = directory.canonicalFile
        if (canonicalDirectory.parentFile != privateRoot) {
            return ImportResult.Rejected("Private artwork storage path failed validation.")
        }
        val current = list(app)
        val localFiles = canonicalDirectory.listFiles().orEmpty().filter { candidate ->
            runCatching { candidate.isFile && candidate.canonicalFile.parentFile == canonicalDirectory }.getOrDefault(false)
        }
        if (localFiles.size >= MAX_ITEMS) return ImportResult.Rejected("Gallery is full (maximum $MAX_ITEMS images).")
        val bytes = try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_FILE_BYTES) return ImportResult.Rejected("Image exceeds the 8 MiB limit.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: return ImportResult.Rejected("The selected image could not be opened.")
        } catch (_: Exception) {
            return ImportResult.Rejected("The selected image could not be read.")
        }
        val detected = ArtworkMediaPolicy.detectImage(bytes) ?: return ImportResult.Rejected("Only JPEG, PNG, and WebP images are supported.")
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        val width = dimensions.outWidth
        val height = dimensions.outHeight
        if (!ArtworkMediaPolicy.hasValidDimensions(width, height)) {
            return ImportResult.Rejected("Image dimensions are invalid or exceed 40 megapixels.")
        }
        val digest = ArtworkMediaPolicy.sha256(bytes)
        current.firstOrNull { it.sha256 == digest }?.let { return ImportResult.Duplicate(it) }
        val totalBytes = localFiles.sumOf { it.length() }
        if (totalBytes + bytes.size > MAX_TOTAL_BYTES) return ImportResult.Rejected("Gallery storage limit reached (64 MiB).")

        val id = UUID.randomUUID().toString()
        val fileName = "$id.${detected.extension}"
        val destination = File(canonicalDirectory, fileName)
        try {
            FileOutputStream(destination).use { it.write(bytes) }
        } catch (_: Exception) {
            destination.delete()
            return ImportResult.Rejected("Could not save the image in Gecko's private storage.")
        }
        val displayName = queryDisplayName(app, uri).take(80).ifBlank { "Artwork ${current.size + 1}" }
        val artwork = Artwork(id, displayName, fileName, detected.mimeType, bytes.size.toLong(), digest, null, System.currentTimeMillis())
        val saved = preferences(app).edit().putString(PREFIX + id, artwork.toJson().toString()).commit()
        if (!saved) {
            destination.delete()
            return ImportResult.Rejected("Could not save artwork metadata.")
        }
        return ImportResult.Added(artwork)
    }

    fun list(context: Context): List<Artwork> = preferences(context).all.entries
        .asSequence().filter { it.key.startsWith(PREFIX) }
        .mapNotNull { (_, value) -> (value as? String)?.let(::decode) }
        .sortedByDescending { it.createdAt }
        .toList()

    fun file(context: Context, artwork: Artwork): File? {
        val target = safeLocalFile(context, artwork.id, artwork.fileName) ?: return null
        if (!target.isFile || target.length() != artwork.byteCount) return null
        val digest = runCatching {
            val hasher = MessageDigest.getInstance("SHA-256")
            target.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hasher.update(buffer, 0, count)
                }
            }
            hasher.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        if (digest != artwork.sha256) return null
        return target
    }

    fun markUploaded(context: Context, id: String, remoteUrl: String): Boolean {
        if (!ArtworkMediaPolicy.isSafeImgurUrl(remoteUrl)) return false
        val app = context.applicationContext
        val existing = list(app).firstOrNull { it.id == id } ?: return false
        return preferences(app).edit().putString(PREFIX + id, existing.copy(remoteUrl = remoteUrl).toJson().toString()).commit()
    }

    fun delete(context: Context, id: String): Boolean {
        if (!id.matches(Regex("^[a-f0-9-]{36}$"))) return false
        val app = context.applicationContext
        val existing = list(app).firstOrNull { it.id == id } ?: return false
        val file = safeLocalFile(app, existing.id, existing.fileName)
        if (file != null && file.exists() && !file.delete()) return false
        return preferences(app).edit().remove(PREFIX + id).commit()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("").replace(Regex("[\\p{Cntrl}/\\\\]"), "_")

    private fun safeLocalFile(context: Context, id: String, fileName: String): File? {
        if (!ArtworkMediaPolicy.isValidStoredFileName(id, fileName)) return null
        return runCatching {
            val app = context.applicationContext
            val privateRoot = app.filesDir.canonicalFile
            val directory = galleryDirectory(app).canonicalFile
            if (directory.parentFile != privateRoot) return null
            val target = File(directory, fileName).canonicalFile
            target.takeIf { it.parentFile == directory }
        }.getOrNull()
    }

    private fun galleryDirectory(context: Context) = File(context.filesDir, "artwork-gallery")
    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Artwork.toJson() = JSONObject().put("id", id).put("displayName", displayName)
        .put("fileName", fileName).put("mimeType", mimeType).put("byteCount", byteCount)
        .put("sha256", sha256).put("remoteUrl", remoteUrl.orEmpty()).put("createdAt", createdAt)

    private fun decode(raw: String): Artwork? = runCatching {
        val json = JSONObject(raw)
        val item = Artwork(
            id = json.getString("id"), displayName = json.getString("displayName"),
            fileName = json.getString("fileName"), mimeType = json.getString("mimeType"),
            byteCount = json.getLong("byteCount"), sha256 = json.getString("sha256"),
            remoteUrl = json.optString("remoteUrl").takeIf(String::isNotBlank), createdAt = json.getLong("createdAt"),
        )
        if (!ArtworkMediaPolicy.isValidStoredFileName(item.id, item.fileName) || item.byteCount !in 1..MAX_FILE_BYTES.toLong() ||
            !Regex("^[a-f0-9]{64}$").matches(item.sha256) || !ArtworkMediaPolicy.mimeMatchesExtension(item.mimeType, item.fileName) ||
            (item.remoteUrl != null && !ArtworkMediaPolicy.isSafeImgurUrl(item.remoteUrl))) null else item
    }.getOrNull()
}
