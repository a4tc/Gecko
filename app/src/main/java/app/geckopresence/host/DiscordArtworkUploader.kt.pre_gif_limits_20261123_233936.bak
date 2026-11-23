package app.geckopresence.host

import app.geckopresence.host.core.ArtworkMediaPolicy
import app.geckopresence.host.core.DiscordArtworkPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * Discord-native artwork transport. No third-party host is involved:
 *  1. POST the image to the user's own private webhook -> signed attachment CDN URL.
 *  2. Register that URL with the app's external-assets endpoint -> media-proxy path ("mp:" reference).
 *  3. Optionally delete the webhook message once the reference is obsolete.
 *
 * Step 2 uses the user's Discord credential, the same one the Gateway already holds.
 * It is sent only to discord.com, redirects are disabled, and it is never logged.
 */
object DiscordArtworkUploader {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val EXTERNAL_ASSETS_ENDPOINT = "https://discord.com/api/v9/applications/%d/external-assets"

    sealed class Result<out T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Failure(val message: String) : Result<Nothing>()
    }

    data class Hosted(val attachmentUrl: String, val messageId: String)

    data class Registered(val mediaProxyReference: String, val messageId: String)

    fun uploadAndReference(
        webhookUrl: String,
        bytes: ByteArray,
        sha256: String,
    ): Result<Registered> {
        val hosted = when (val upload = uploadToWebhook(webhookUrl, bytes, sha256)) {
            is Result.Success -> upload.value
            is Result.Failure -> return upload
        }

        // Keep the signed Discord CDN attachment URL intact.
        val reference = DiscordArtworkPolicy.toMediaProxyReference(hosted.attachmentUrl)
        if (reference == null) {
            deleteWebhookMessage(webhookUrl, hosted.messageId)
            return Result.Failure("Discord attachment URL could not be converted to an artwork reference.")
        }

        return Result.Success(Registered(reference, hosted.messageId))
    }

    fun uploadToWebhook(webhookUrl: String, bytes: ByteArray, sha256: String): Result<Hosted> {
        val webhook = DiscordArtworkPolicy.normalizeWebhookUrl(webhookUrl)
            ?: return Result.Failure("Webhook URL is invalid. Use a Discord webhook URL from your own server.")
        if (bytes.isEmpty() || bytes.size > ArtworkMediaPolicy.MAX_FILE_BYTES) {
            return Result.Failure("Artwork is empty or exceeds the 8 MiB limit.")
        }
        val format = ArtworkMediaPolicy.detectImage(bytes)
            ?: return Result.Failure("Only JPEG, PNG, WebP and GIF images can be uploaded.")

        // The filename carries no personal information: a prefix of the content hash only.
        val filename = "${sha256.take(16)}.${format.extension}"
        val boundary = "gecko-" + UUID.randomUUID().toString().replace("-", "")
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"files[0]\"; filename=\"$filename\"\r\n" +
            "Content-Type: ${format.mimeType}\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val connection = connect(DiscordArtworkPolicy.webhookExecuteUrl(webhook), "POST").apply {
            doOutput = true
            setFixedLengthStreamingMode(head.size + bytes.size + tail.size)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { out ->
                out.write(head)
                out.write(bytes)
                out.write(tail)
            }
            val status = connection.responseCode
            val body = readBody(connection, status)
                ?: return Result.Failure("Discord returned an unreadable or oversized response.")
            when {
                status == 429 -> return Result.Failure("Discord rate-limited the artwork upload. It will be retried.")
                status == 401 || status == 404 -> return Result.Failure("The webhook no longer exists. Create a new webhook and save it again.")
                status !in 200..299 -> return Result.Failure("Discord rejected the artwork upload (HTTP $status).")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return Result.Failure("Discord returned invalid upload data.")
            val messageId = json.optString("id")
            val url = json.optJSONArray("attachments")?.optJSONObject(0)?.optString("url").orEmpty()
            if (!DiscordArtworkPolicy.isValidMessageId(messageId) || !DiscordArtworkPolicy.isSafeAttachmentCdnUrl(url)) {
                return Result.Failure("Discord upload response failed validation.")
            }
            Result.Success(Hosted(url, messageId))
        } catch (_: Exception) {
            Result.Failure("Could not reach Discord. Check your connection.")
        } finally {
            connection.disconnect()
        }
    }

    /** Best effort. A failed delete never affects presence; the message stays in the user's own channel. */
    fun deleteWebhookMessage(webhookUrl: String, messageId: String): Boolean {
        val webhook = DiscordArtworkPolicy.normalizeWebhookUrl(webhookUrl) ?: return false
        val url = DiscordArtworkPolicy.webhookMessageUrl(webhook, messageId) ?: return false
        val connection = connect(url, "DELETE")
        return try {
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: String, method: String): HttpURLConnection {
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("discord.com", ignoreCase = true)) {
            "Only discord.com is allowed"
        }
        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
        }
    }

    private fun readBody(connection: HttpURLConnection, status: Int): String? {
        val stream = (if (status in 200..299) connection.inputStream else connection.errorStream) ?: return ""
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
