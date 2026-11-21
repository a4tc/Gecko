package app.geckopresence.host

import app.geckopresence.host.core.ArtworkMediaPolicy
import app.geckopresence.host.core.ArtworkUploadPolicy
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Native HTTPS client for the user's own Cloudflare Worker + R2 bucket. */
object CloudflareArtworkUploader {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    sealed class Result {
        data class Success(val directUrl: String? = null) : Result()
        data class Failure(val message: String) : Result()
    }

    fun verifyConnection(config: ArtworkUploadConfigStore.Config, tokenInput: String): Result {
        val token = tokenInput.trim()
        if (!ArtworkUploadPolicy.isValidUploadToken(token)) {
            return Result.Failure("Worker token is invalid or has not been saved.")
        }
        val endpoint = runCatching { verifyEndpoint(config.uploadEndpoint) }.getOrNull()
            ?: return Result.Failure("Worker endpoint is invalid.")
        val connection = openConnection(endpoint, "GET", token).apply {
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = connection.responseCode
            val body = readResponse(connection, status)
                ?: return Result.Failure("Worker returned an unreadable or oversized response.")
            if (status != 200) return Result.Failure("Worker verification failed (HTTP $status). Check the URL and token.")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return Result.Failure("Worker returned invalid verification data.")
            val returnedAssetBase = json.optString("assetBaseUrl")
            if (!json.optBoolean("success", false) ||
                json.optString("service") != "gecko-artwork-upload" ||
                ArtworkUploadPolicy.normalizeAssetBaseUrl(returnedAssetBase) != config.assetBaseUrl
            ) {
                return Result.Failure("Worker token is accepted, but its public R2 asset domain does not match this device's configuration.")
            }
            Result.Success()
        } catch (_: Exception) {
            Result.Failure("Could not reach the Cloudflare Worker. Check the URL and internet connection.")
        } finally {
            connection.disconnect()
        }
    }

    fun upload(file: File, config: ArtworkUploadConfigStore.Config, tokenInput: String): Result {
        val token = tokenInput.trim()
        if (!ArtworkUploadPolicy.isValidUploadToken(token)) {
            return Result.Failure("Worker token is invalid or has not been saved.")
        }
        val endpoint = runCatching { URI(ArtworkUploadPolicy.normalizeWorkerEndpoint(config.uploadEndpoint) ?: "") }.getOrNull()
            ?: return Result.Failure("Worker endpoint is invalid.")
        val assetBase = ArtworkUploadPolicy.normalizeAssetBaseUrl(config.assetBaseUrl)
            ?: return Result.Failure("Public R2 asset domain is invalid.")
        if (!file.isFile || file.length() !in 1..ArtworkMediaPolicy.MAX_FILE_BYTES.toLong()) {
            return Result.Failure("Artwork file is missing or exceeds the 8 MiB upload limit.")
        }
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return Result.Failure("Could not read the private artwork file.")
        }
        if (bytes.size != file.length().toInt()) return Result.Failure("Artwork file changed while it was being read.")
        val format = ArtworkMediaPolicy.detectImage(bytes)
            ?: return Result.Failure("Only JPEG, PNG and WebP images can be uploaded.")
        val extension = file.extension.lowercase()
        val allowedExtension = when (format.extension) {
            "jpg" -> extension in setOf("jpg", "jpeg")
            else -> extension == format.extension
        }
        if (!allowedExtension) return Result.Failure("Artwork file extension does not match its image content.")

        val digest = ArtworkMediaPolicy.sha256(bytes)
        val connection = openConnection(endpoint, "POST", token).apply {
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", format.mimeType)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { output -> output.write(bytes) }
            val status = connection.responseCode
            val body = readResponse(connection, status)
                ?: return Result.Failure("Worker returned an unreadable or oversized response.")
            if (status != 201) return Result.Failure("Cloudflare Worker rejected the artwork (HTTP $status). Check Worker configuration and R2 access.")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return Result.Failure("Worker returned invalid upload data.")
            val returnedHash = json.optString("sha256")
            val returnedUrl = json.optString("url")
            val returnedSize = json.optLong("size", -1L)
            val returnedMime = json.optString("contentType")
            if (!json.optBoolean("success", false) || returnedHash != digest || returnedSize != bytes.size.toLong() ||
                returnedMime != format.mimeType ||
                !ArtworkUploadPolicy.isExpectedCloudflareAssetUrl(returnedUrl, assetBase, digest, format.extension)
            ) {
                return Result.Failure("Worker upload response failed integrity or asset-domain validation.")
            }
            Result.Success(returnedUrl)
        } catch (_: Exception) {
            Result.Failure("Could not reach the Cloudflare Worker. Check your connection and try again.")
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyEndpoint(uploadEndpoint: String): URI {
        val normalized = ArtworkUploadPolicy.normalizeWorkerEndpoint(uploadEndpoint)
            ?: throw IllegalArgumentException("Invalid Worker endpoint")
        val endpoint = URI(normalized)
        return URI("https", null, endpoint.host, -1, "/v1/verify", null, null)
    }

    private fun openConnection(uri: URI, method: String, token: String): HttpURLConnection =
        (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun readResponse(connection: HttpURLConnection, status: Int): String? {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream ?: return ""
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) return null
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
}
