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

    fun uploadAndRegister(
        webhookUrl: String,
        applicationId: Long,
        userToken: String,
        bytes: ByteArray,
        sha256: String,
    ): Result<Registered> {
        val hosted = when (val upload = uploadToWebhook(webhookUrl, bytes, sha256)) {
            is Result.Success -> upload.value
            is Result.Failure -> return upload
        }
        return when (val registered = registerExternalAsset(applicationId, userToken, hosted.attachmentUrl)) {
            is Result.Success -> Result.Success(Registered(registered.value, hosted.messageId))
            is Result.Failure -> {
                // Registration failed: remove the orphaned attachment instead of leaving it in the channel.
                deleteWebhookMessage(webhookUrl, hosted.messageId)
                registered
            }
        }
    }

    fun uploadToWebhook(webhookUrl: String, bytes: ByteArray, sha256: String): Result<Hosted> {
        val webhook = DiscordArtworkPolicy.normalizeWebhookUrl(webhookUrl)
            ?: return Result.Failure("Webhook URL is invalid. Use a Discord webhook URL from your own server.")
        if (bytes.isEmpty() || bytes.size > ArtworkMediaPolicy.MAX_FILE_BYTES) {
            return Result.Failure("Artwork is empty or exceeds the 8 MiB limit.")
        }
        val format = ArtworkMediaPolicy.detectImage(bytes)
            ?: return Result.Failure("Only JPEG, PNG and WebP images can be uploaded.")

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

    fun registerExternalAsset(applicationId: Long, userToken: String, attachmentUrl: String): Result<String> {
        if (applicationId <= 0L) return Result.Failure("Discord Application ID is not configured in this build.")
        if (!DiscordArtworkPolicy.isSafeAttachmentCdnUrl(attachmentUrl)) {
            return Result.Failure("Attachment URL failed validation.")
        }
        val payload = JSONObject().put("urls", JSONArray().put(attachmentUrl)).toString().toByteArray(Charsets.UTF_8)
        val connection = connect(EXTERNAL_ASSETS_ENDPOINT.format(applicationId), "POST").apply {
            doOutput = true
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", userToken)
        }
        return try {
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val body = readBody(connection, status)
                ?: return Result.Failure("Discord returned an unreadable or oversized response.")
            when {
                status == 429 -> return Result.Failure("Discord rate-limited artwork registration. It will be retried.")
                status == 401 || status == 403 -> return Result.Failure("Discord refused artwork registration (HTTP $status). Check the saved Discord credential and Application ID.")
                status !in 200..299 -> return Result.Failure("Discord rejected artwork registration (HTTP $status).")
            }
            val responseArray = runCatching { JSONArray(body) }.getOrNull()
                ?: return Result.Failure(
                    "Discord artwork response was not a JSON array (bodyLength=${body.length})."
                )

            if (responseArray.length() == 0) {
                return Result.Failure("Discord artwork response was an empty array.")
            }

            val firstItem = responseArray.optJSONObject(0)
                ?: return Result.Failure("Discord artwork response item was not a JSON object.")

            val path = firstItem.opt("external_asset_path") as? String
                ?: return Result.Failure(
                    "Discord artwork response had no string external_asset_path."
                )

            val reference = DiscordArtworkPolicy.toMediaProxyReference(path)
            if (reference == null) {
                val hasWhitespace = path.any(Char::isWhitespace)
                val hasControl = path.any(Char::isISOControl)
                val hasQuery = path.contains("?")
                val startsExternal = path.removePrefix("/").startsWith("external/")
                return Result.Failure(
                    "Discord media-proxy path rejected: length=${path.length}, " +
                        "startsExternal=$startsExternal, leadingSlash=${path.startsWith("/")}, " +
                        "whitespace=$hasWhitespace, control=$hasControl, query=$hasQuery, " +
                        "slashes=${path.count { it == '/' }}."
                )
            }

            Result.Success(reference)
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
