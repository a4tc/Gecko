package app.geckopresence.host

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DiscordTokenRefresher {
    private const val TOKEN_ENDPOINT = "https://discord.com/api/v10/oauth2/token"
    private const val MAX_RESPONSE_BYTES = 64 * 1024

    data class RefreshedTokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Int,
    )

    fun refresh(applicationId: Long, refreshToken: String): RefreshedTokens {
        require(applicationId > 0L) { "Discord Application ID is not configured" }
        require(refreshToken.isNotBlank()) { "Refresh token is empty" }

        val body = listOf(
            "client_id" to applicationId.toString(),
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ).joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

        val connection = (URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("Cache-Control", "no-store")
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBytes = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            val response = try {
                String(responseBytes, Charsets.UTF_8)
            } finally {
                responseBytes.fill(0)
            }

            if (status !in 200..299) {
                val code = runCatching { JSONObject(response).optString("error") }.getOrNull()
                throw IllegalStateException(code?.takeIf { it.isNotBlank() } ?: "Discord token refresh failed (HTTP $status)")
            }

            val json = JSONObject(response)
            val accessToken = json.optString("access_token")
            val rotatedRefreshToken = json.optString("refresh_token").ifBlank { refreshToken }
            val expiresIn = json.optInt("expires_in", 3600).coerceIn(60, 31_536_000)

            if (accessToken.isBlank()) {
                throw IllegalStateException("Discord returned an empty access token")
            }

            return RefreshedTokens(
                accessToken = accessToken,
                refreshToken = rotatedRefreshToken,
                expiresInSeconds = expiresIn,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximum) {
                throw IllegalStateException("Discord response exceeded the allowed size")
            }
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        return output.toByteArray()
    }
}
