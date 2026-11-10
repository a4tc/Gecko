package app.geckopresence.host

import android.content.Context
import app.geckopresence.host.core.PresenceRules
import app.geckopresence.host.core.BridgePayloadMapper
import app.geckopresence.host.core.PresenceUpdate
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BridgeHttpServer(
    context: Context,
    private val onPresence: (PresenceUpdate) -> Unit,
    private val onClear: () -> Unit,
    private val onManualClear: () -> Unit,
    private val sharePageTitles: () -> Boolean,
    private val healthSnapshot: () -> JSONObject,
    private val onServerState: (String, String?) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val workers = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "GPB-HTTP-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        acceptThread = Thread({ acceptLoop() }, "GPB-localhost-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8)
            serverSocket = socket
            onServerState("LISTENING", null)

            while (running.get()) {
                try {
                    val client = socket.accept()
                    try {
                        workers.execute { handleClient(client) }
                    } catch (_: RejectedExecutionException) {
                        client.use { rejected ->
                            runCatching {
                                respond(
                                    rejected.getOutputStream(),
                                    503,
                                    errorJson("Local bridge is busy; retry shortly"),
                                    null,
                                )
                            }
                        }
                    }
                } catch (exception: Exception) {
                    if (running.get()) {
                        onServerState("SERVER_ERROR", exception.message ?: "Local bridge server error")
                    }
                }
            }
        } catch (exception: Exception) {
            if (running.get()) {
                onServerState(
                    if (exception.message.orEmpty().contains("Address already in use", true)) {
                        "PORT_IN_USE"
                    } else {
                        "SERVER_ERROR"
                    },
                    "Could not bind the local bridge to 127.0.0.1:$PORT",
                )
            }
        } finally {
            runCatching { serverSocket?.close() }
            serverSocket = null
            running.set(false)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = CLIENT_TIMEOUT_MS
                val input = client.getInputStream()
                val output = client.getOutputStream()
                val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES)
                    ?: return
                val requestParts = requestLine.split(' ', limit = 3)
                if (requestParts.size != 3 || !requestParts[2].startsWith("HTTP/1.")) {
                    respond(output, 400, errorJson("Malformed HTTP request"), null)
                    return
                }

                val method = requestParts[0].uppercase(Locale.ROOT)
                val path = requestParts[1].substringBefore('?')
                val headers = readHeaders(input)
                val origin = headers["origin"]

                if (!isAllowedOrigin(origin)) {
                    respond(output, 403, errorJson("Origin not allowed"), null)
                    return
                }

                if (method == "OPTIONS") {
                    respond(output, 204, "", origin, preflight = true)
                    return
                }

                if (method == "GET" && path in setOf("/v1/health", "/v2/health")) {
                    val snapshot = healthSnapshot()
                    if (path == "/v2/health") snapshot.put("apiVersion", 2)
                    respond(output, 200, snapshot.toString(), origin)
                    return
                }

                val validWritePath = path in setOf("/v1/activity", "/v1/clear", "/v2/activity", "/v2/clear", "/v2/manual/clear")
                if (method != "POST" || !validWritePath) {
                    respond(output, 404, errorJson("Endpoint not found"), origin)
                    return
                }

                if (!isValidBridgeKey(headers[BRIDGE_KEY_HEADER])) {
                    respond(output, 401, errorJson("Bridge pairing key is invalid"), origin)
                    return
                }

                val contentType = headers["content-type"].orEmpty()
                if (!contentType.lowercase(Locale.ROOT).startsWith("application/json")) {
                    respond(output, 415, errorJson("Content-Type must be application/json"), origin)
                    return
                }

                val contentLength = headers["content-length"]?.toIntOrNull()
                if (contentLength == null || contentLength < 0) {
                    respond(output, 411, errorJson("Content-Length is required"), origin)
                    return
                }
                if (contentLength > MAX_BODY_BYTES) {
                    respond(output, 413, errorJson("Request body is too large"), origin)
                    return
                }

                val body = String(readExactly(input, contentLength), StandardCharsets.UTF_8)
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json == null) {
                    respond(output, 400, errorJson("Request body is not valid JSON"), origin)
                    return
                }

                if (path == "/v2/manual/clear") {
                    onManualClear()
                    respond(output, 200, "{\"ok\":true,\"cleared\":true,\"scope\":\"manual\"}", origin)
                    return
                }

                if (path == "/v1/clear" || path == "/v2/clear") {
                    onClear()
                    respond(output, 200, "{\"ok\":true,\"cleared\":true}", origin)
                    return
                }

                val update = if (path == "/v2/activity") parsePresenceV2(json) else parsePresence(json)
                if (update == null) {
                    respond(output, 400, errorJson("Activity source is missing or invalid"), origin)
                    return
                }

                onPresence(update)
                respond(output, 202, "{\"ok\":true,\"accepted\":true}", origin)
            } catch (_: EOFException) {
                runCatching { respond(socket.getOutputStream(), 400, errorJson("Incomplete request"), null) }
            } catch (_: Exception) {
                runCatching { respond(socket.getOutputStream(), 400, errorJson("Request could not be processed"), null) }
            }
        }
    }


    private fun parsePresenceV2(json: JSONObject): PresenceUpdate? {
        val payload = linkedMapOf<String, Any?>(
            "schemaVersion" to json.optInt("schemaVersion", -1),
            "source" to json.optString("source", null),
            "name" to json.optString("name", null),
            "details" to json.optString("details", null),
            "state" to json.optString("state", null),
            "mediaState" to json.optString("mediaState", null),
            "startTimestamp" to json.optLong("startTimestamp", 0L),
            "sourceType" to json.optString("sourceType", "website"),
            "priority" to json.optInt("priority", 10),
        )
        return BridgePayloadMapper.fromV2(payload, shareTitles())
    }

    private fun parsePresence(json: JSONObject): PresenceUpdate? {
        val source = PresenceRules.sanitizeHost(json.optString("source", null)) ?: return null
        val mediaState = PresenceRules.normalizeMediaState(json.optString("mediaState", "browsing"))
        val name = PresenceRules.cleanText(json.optString("name", source), 80) ?: source
        val details = PresenceRules.cleanText(
            json.optString("details", PresenceRules.defaultDetails(mediaState)),
            120,
        ) ?: PresenceRules.defaultDetails(mediaState)
        val title = if (sharePageTitles()) {
            PresenceRules.cleanText(json.optString("state", ""), 120)
        } else {
            null
        }
        val requestedStart = json.optLong("startTimestamp", 0L)
        val startTime = if (requestedStart in 1L..4_102_444_800L) {
            requestedStart
        } else {
            System.currentTimeMillis() / 1000L
        }

        return PresenceUpdate(
            source = source,
            name = name,
            details = details,
            state = title,
            mediaState = mediaState,
            startTimestampSeconds = startTime,
        )
    }

    private fun isValidBridgeKey(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = runCatching { SecureStore.getOrCreateBridgeKey(appContext) }.getOrNull()
            ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            candidate.trim().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var totalBytes = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES)
                ?: throw EOFException("Connection ended in headers")
            totalBytes += line.length
            if (totalBytes > MAX_HEADER_BYTES) throw IllegalArgumentException("Headers too large")
            if (line.isEmpty()) break

            val separator = line.indexOf(':')
            if (separator <= 0) throw IllegalArgumentException("Malformed header")
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (name == "transfer-encoding") throw IllegalArgumentException("Transfer encoding is not supported")
            headers[name] = value
        }
        return headers
    }

    private fun readAsciiLine(input: InputStream, maximumBytes: Int): String? {
        val output = ByteArrayOutputStream()
        while (output.size() <= maximumBytes) {
            val value = input.read()
            if (value < 0) {
                if (output.size() == 0) return null
                break
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
        }
        if (output.size() > maximumBytes) throw IllegalArgumentException("HTTP line too large")
        return output.toString("US-ASCII")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            if (count < 0) throw EOFException("Request body ended early")
            offset += count
        }
        return data
    }

    private fun isAllowedOrigin(origin: String?): Boolean {
        if (origin == null) return true
        return origin.startsWith("moz-extension://", ignoreCase = true)
    }

    private fun respond(
        output: java.io.OutputStream,
        status: Int,
        body: String,
        origin: String?,
        preflight: Boolean = false,
    ) {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            503 -> "Service Unavailable"
            else -> "Error"
        }

        val header = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            .append("Connection: close\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("Content-Length: ").append(payload.size).append("\r\n")

        if (!preflight) header.append("Content-Type: application/json; charset=utf-8\r\n")
        if (!origin.isNullOrBlank()) {
            header.append("Access-Control-Allow-Origin: ").append(origin).append("\r\n")
            header.append("Vary: Origin\r\n")
        }
        if (preflight) {
            header.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            header.append("Access-Control-Allow-Headers: Content-Type, X-GPB-Key\r\n")
            header.append("Access-Control-Max-Age: 600\r\n")
        }
        header.append("\r\n")

        output.write(header.toString().toByteArray(StandardCharsets.US_ASCII))
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
        payload.fill(0)
    }

    private fun errorJson(message: String): String {
        return JSONObject().put("ok", false).put("error", message).toString()
    }

    override fun close() {
        val wasRunning = running.getAndSet(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
        acceptThread?.join(1000L)
        acceptThread = null
        if (wasRunning) onServerState("STOPPED", null)
    }

    companion object {
        const val PORT = 32145
        private const val BRIDGE_KEY_HEADER = "x-gpb-key"
        private const val CLIENT_TIMEOUT_MS = 5000
        private const val MAX_REQUEST_LINE_BYTES = 2048
        private const val MAX_HEADER_BYTES = 8192
        private const val MAX_BODY_BYTES = 16 * 1024
    }
}
