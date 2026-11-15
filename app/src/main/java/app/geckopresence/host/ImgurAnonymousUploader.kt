package app.geckopresence.host

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID

/** Explicit, anonymous Imgur upload. The user supplies their own public Client-ID. */
object ImgurAnonymousUploader {
    private const val ENDPOINT = "https://api.imgur.com/3/image"
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    sealed class Result {
        data class Success(val directUrl: String) : Result()
        data class Failure(val message: String) : Result()
    }

    fun upload(file: File, clientIdInput: String): Result = uploadAtEndpoint(file, clientIdInput, ENDPOINT, allowLoopbackHttp = false)

    /** Localhost-only injection point for deterministic integration tests; production calls upload(). */
    internal fun uploadForTest(file: File, clientIdInput: String, endpoint: String): Result {
        if (!BuildConfig.DEBUG) return Result.Failure("Test upload endpoint is disabled in release builds.")
        return uploadAtEndpoint(file, clientIdInput, endpoint, allowLoopbackHttp = true)
    }

    private fun uploadAtEndpoint(file: File, clientIdInput: String, endpoint: String, allowLoopbackHttp: Boolean): Result {
        val endpointUri = runCatching { java.net.URI(endpoint) }.getOrNull()
            ?: return Result.Failure("Upload endpoint is invalid.")
        val productionEndpoint = endpointUri.scheme == "https" && endpointUri.host == "api.imgur.com" && endpointUri.port in setOf(-1, 443)
        val loopbackTestEndpoint = allowLoopbackHttp && endpointUri.scheme == "http" && endpointUri.host in setOf("127.0.0.1", "localhost", "::1")
        if (!productionEndpoint && !loopbackTestEndpoint) return Result.Failure("Upload endpoint is not permitted.")
        val clientId = clientIdInput.trim()
        if (!app.geckopresence.host.core.ArtworkMediaPolicy.isValidClientId(clientId)) return Result.Failure("Enter a valid Imgur Client-ID in Settings.")
        if (!file.isFile || file.length() !in 1..ArtworkGalleryStore.MAX_FILE_BYTES.toLong()) {
            return Result.Failure("Artwork file is missing or exceeds the upload size limit.")
        }
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> return Result.Failure("Unsupported image format.")
        }
        val boundary = "GeckoPresence-${UUID.randomUUID()}"
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"artwork.${file.extension}\"\r\nContent-Type: $mime\r\n\r\n".toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val contentLength = prefix.size.toLong() + file.length() + suffix.size.toLong()
        val connection = (endpointUri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Client-ID $clientId")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(contentLength)
        }
        return try {
            connection.outputStream.use { output ->
                output.write(prefix)
                file.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
                output.write(suffix)
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                ?: return Result.Failure("Imgur upload failed (HTTP $status).")
            val response = BufferedInputStream(stream).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) return Result.Failure("Imgur response exceeded the safety limit.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            if (status !in 200..299) return Result.Failure("Imgur upload failed (HTTP $status). Check the Client-ID and try again.")
            val json = runCatching { JSONObject(response) }.getOrNull()
                ?: return Result.Failure("Imgur returned an invalid response.")
            if (!json.optBoolean("success", false)) return Result.Failure("Imgur did not accept the image.")
            val link = json.optJSONObject("data")?.optString("link")?.takeIf(String::isNotBlank)
                ?: return Result.Failure("Imgur response did not include an image URL.")
            val uri = runCatching { java.net.URI(link) }.getOrNull()
            if (uri?.scheme != "https" || uri.host?.lowercase() != "i.imgur.com" || uri.port !in setOf(-1, 443) || uri.userInfo != null) {
                return Result.Failure("Imgur returned an unexpected image host; the URL was rejected.")
            }
            Result.Success(link)
        } catch (_: Exception) {
            Result.Failure("Could not reach Imgur. Check your connection and try again.")
        } finally {
            connection.disconnect()
        }
    }
}
