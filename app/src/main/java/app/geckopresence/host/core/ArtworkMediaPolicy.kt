package app.geckopresence.host.core

import java.net.URI
import java.security.MessageDigest

/** Pure validation rules for local artwork and externally hosted image references. */
object ArtworkMediaPolicy {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    const val MAX_ITEMS = 40
    const val MAX_WIDTH_OR_HEIGHT = 8192
    const val MAX_PIXEL_COUNT = 40_000_000L

    data class ImageFormat(val mimeType: String, val extension: String)

    fun detectImage(bytes: ByteArray): ImageFormat? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            ImageFormat("image/jpeg", "jpg")
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        ) -> ImageFormat("image/png", "png")
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> ImageFormat("image/webp", "webp")
        else -> null
    }

    fun hasValidDimensions(width: Int, height: Int): Boolean =
        width in 1..MAX_WIDTH_OR_HEIGHT && height in 1..MAX_WIDTH_OR_HEIGHT &&
            width.toLong() * height.toLong() <= MAX_PIXEL_COUNT

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun isValidStoredFileName(id: String, fileName: String): Boolean {
        if (!id.matches(Regex("^[a-f0-9-]{36}$"))) return false
        val extension = fileName.substringAfterLast('.', "")
        return extension in setOf("jpg", "png", "webp") && fileName == "$id.$extension"
    }

    fun mimeMatchesExtension(mimeType: String, fileName: String): Boolean = when (mimeType) {
        "image/jpeg" -> fileName.endsWith(".jpg")
        "image/png" -> fileName.endsWith(".png")
        "image/webp" -> fileName.endsWith(".webp")
        else -> false
    }
}
