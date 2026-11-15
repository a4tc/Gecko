package app.geckopresence.host

import android.os.Build
import app.geckopresence.host.core.DiscordGatewayProtocol
import app.geckopresence.host.core.DiscordTokenInputPolicy
import app.geckopresence.host.core.GatewayEnvelope
import app.geckopresence.host.core.GatewayJsonNull
import app.geckopresence.host.core.GatewayCloseCodePolicy
import app.geckopresence.host.core.GatewayCloseDisposition
import app.geckopresence.host.core.GatewayInvalidSessionPolicy
import app.geckopresence.host.core.PresenceActivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Owns the single Discord Gateway WebSocket session.
 *
 * Ktor 2.3.13 client APIs are used with the Android CIO engine. The user-session Identify
 * shape intentionally follows Kizzy's published implementation. Its
 * `capabilities` and camelCase `largeThreshold` fields are undocumented client-session details;
 * this transport is therefore compatibility-sensitive and not an officially supported bot flow.
 * No token, frame body, server close reason, or exception message is written to logs.
 */
class DiscordGatewayPublisher(
    private val applicationId: Long,
    private val onSessionRejected: () -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
    private val onState: (state: String, message: String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            maxFrameSize = MAX_GATEWAY_FRAME_BYTES
        }
    }
    private val sendMutex = Mutex()
    private val updateEvents = Channel<Unit>(Channel.CONFLATED)
    private val awaitingHeartbeatAck = AtomicBoolean(false)

    @Volatile private var socket: DefaultClientWebSocketSession? = null
    @Volatile private var activeToken: String? = null
    @Volatile private var connectionJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var userRequestedDisconnect = false
    @Volatile private var isReady = false

    private val stateLock = Any()
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null
    private var lastSequence: Long? = null
    private var pendingActivity: PresenceActivity? = null
    private var pendingStatus: String = "online"
    private var pendingRevision = 0L
    private var lastSentRevision = -1L
    private var lastPresenceUpdateAt = 0L

    init {
        scope.launch {
            for (ignored in updateEvents) {
                flushPendingPresence()
            }
        }
    }

    fun connect(rawToken: String) {
        val token = DiscordTokenInputPolicy.normalize(rawToken)
        if (token == null) {
            emitState(STATE_ERROR, "Stored Discord session is invalid. Sign in again from Settings.")
            return
        }
        if (connectionJob?.isActive == true) {
            emitState(if (isReady) STATE_CONNECTED else STATE_CONNECTING, if (isReady) "Discord Gateway is already connected" else "Discord Gateway connection is already starting")
            return
        }

        userRequestedDisconnect = false
        activeToken = token
        clearResumeState()
        emitState(STATE_CONNECTING, "Connecting to Discord Gateway")
        connectionJob = scope.launch { connectionLoop() }
    }

    fun publish(activity: PresenceActivity?, status: String = "online") {
        val revision = synchronized(stateLock) {
            pendingActivity = activity
            pendingStatus = status.takeIf { it in ALLOWED_PRESENCE_STATUSES } ?: "online"
            ++pendingRevision
        }
        onDiagnostic(if (activity == null) "Presence clear queued revision=$revision" else "Presence queued revision=$revision")
        updateEvents.trySend(Unit)
    }

    fun disconnect() {
        if (userRequestedDisconnect && connectionJob?.isActive != true) return
        userRequestedDisconnect = true
        scope.launch {
            try {
                runCatching {
                    socket?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnected"))
                }
                connectionJob?.cancelAndJoin()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Disconnect is best-effort; never include protocol or credential data in diagnostics.
            } finally {
                resetConnectionState(clearSession = true)
                activeToken = null
                emitState(STATE_DISCONNECTED, "Discord Gateway disconnected; local sources remain available")
            }
        }
    }

    fun shutdown() {
        userRequestedDisconnect = true
        scope.launch {
            try {
                runCatching { socket?.close(CloseReason(CloseReason.Codes.NORMAL, "Gecko service stopped")) }
                withTimeoutOrNull(800L) { connectionJob?.cancelAndJoin() }
            } finally {
                resetConnectionState(clearSession = true)
                activeToken = null
                updateEvents.close()
                client.close()
                scope.cancel()
            }
        }
    }

    private suspend fun connectionLoop() {
        var retryDelayMs = 1_000L
        var reconnectDelayOverride: Long? = null

        while (currentCoroutineContext().isActive && !userRequestedDisconnect) {
            val token = activeToken ?: break
            val resumable = hasResumeState()
            val targetUrl = if (resumable) resumeGatewayUrl!! else DiscordGatewayProtocol.GATEWAY_URL
            emitState(if (retryDelayMs == 1_000L && !resumable) STATE_CONNECTING else STATE_RECONNECTING,
                if (resumable) "Resuming Discord Gateway session" else "Opening Discord Gateway connection")

            var receivedCloseCode: Int? = null
            var localHeartbeat: Job? = null
            var helloTimeoutJob: Job? = null
            try {
                val currentSocket = withTimeout(CONNECTION_TIMEOUT_MS) {
                    client.webSocketSession(urlString = targetUrl) {
                        header("User-Agent", "Discord-Android/314013;RNA")
                        header("Accept-Language", "en-US")
                        header("Cache-Control", "no-cache")
                        header("Pragma", "no-cache")
                    }
                }
                currentSocket.maxFrameSize = MAX_GATEWAY_FRAME_BYTES
                socket = currentSocket
                isReady = false
                awaitingHeartbeatAck.set(false)
                var handshakeSent = false
                val helloReceived = CompletableDeferred<Unit>()
                helloTimeoutJob = scope.launch {
                    try {
                        withTimeout(HELLO_TIMEOUT_MS) { helloReceived.await() }
                    } catch (_: TimeoutCancellationException) {
                        requestReconnect(currentSocket, "Gateway HELLO timed out")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    }
                }

                for (frame in currentSocket.incoming) {
                    if (frame !is Frame.Text) continue
                    val message = frame.readText()
                    val frameBytes = message.toByteArray(Charsets.UTF_8).size.toLong()
                    val packet = try {
                        JSONObject(message)
                    } catch (_: JSONException) {
                        onDiagnostic("Gateway frame probe: invalid JSON bytes=$frameBytes")
                        requestReconnect(currentSocket, "Invalid Gateway JSON")
                        continue
                    }
                    val opcode = packet.intOrDefault("op", -1)
                    val frameEvent = packet.stringOrDefault("t").ifBlank { "OP_$opcode" }

                    if (opcode == DiscordGatewayProtocol.OP_HELLO ||
                        (opcode == DiscordGatewayProtocol.OP_DISPATCH &&
                            frameEvent in setOf("READY", "RESUMED"))
                    ) {
                        onDiagnostic("Gateway frame probe: event=$frameEvent opcode=$opcode bytes=$frameBytes")
                    }

                    if (frameBytes > MAX_ACCEPTED_GATEWAY_FRAME_BYTES) {
                        onDiagnostic("Gateway frame cap hit: event=$frameEvent opcode=$opcode bytes=$frameBytes cap=$MAX_ACCEPTED_GATEWAY_FRAME_BYTES")
                        requestReconnect(currentSocket, "Gateway frame exceeded the local safety limit")
                        continue
                    }

                    val data = packet.valueOrNull("d")

                    when (opcode) {
                        DiscordGatewayProtocol.OP_HELLO -> {
                            if (handshakeSent) continue
                            helloReceived.complete(Unit)
                            val heartbeatInterval = (data as? JSONObject)?.longOrDefault("heartbeat_interval", 0L) ?: 0L
                            if (heartbeatInterval !in MIN_HEARTBEAT_INTERVAL_MS..MAX_HEARTBEAT_INTERVAL_MS) {
                                requestReconnect(currentSocket, "Invalid heartbeat interval")
                                continue
                            }
                            emitState(STATE_IDENTIFYING, "Discord Gateway handshake started")
                            val envelope = if (resumable) {
                                val id = synchronized(stateLock) { sessionId }
                                val sequence = synchronized(stateLock) { lastSequence }
                                if (id != null && sequence != null) {
                                    DiscordGatewayProtocol.resume(token, id, sequence)
                                } else {
                                    DiscordGatewayProtocol.identify(token, "Android", Build.DEVICE)
                                }
                            } else {
                                DiscordGatewayProtocol.identify(token, "Android", Build.DEVICE)
                            }
                            sendEnvelope(envelope, currentSocket)
                            handshakeSent = true
                            // Do not let the heartbeat coroutine race ahead of IDENTIFY/RESUME.
                            localHeartbeat = startHeartbeat(currentSocket, heartbeatInterval)
                            heartbeatJob = localHeartbeat
                        }

                        DiscordGatewayProtocol.OP_DISPATCH -> {
                            val sequence = packet.longOrDefault("s", -1L).takeIf { it >= 0L }
                            if (sequence != null) synchronized(stateLock) { lastSequence = sequence }
                            when (packet.stringOrDefault("t")) {
                                "READY" -> {
                                    val ready = data as? JSONObject
                                    val newSessionId = ready?.stringOrDefault("session_id")?.takeIf { it.isNotBlank() }
                                    val newResumeUrl = safeResumeUrl(ready?.stringOrDefault("resume_gateway_url"))
                                    synchronized(stateLock) {
                                        if (newSessionId != null) sessionId = newSessionId
                                        if (newResumeUrl != null) resumeGatewayUrl = newResumeUrl
                                    }
                                    isReady = true
                                    // A fresh Identify starts a new remote session; force the latest
                                    // activity to be replayed even if it was sent before reconnect.
                                    lastSentRevision = -1L
                                    lastPresenceUpdateAt = 0L
                                    retryDelayMs = 1_000L
                                    emitState(STATE_CONNECTED, "Discord Gateway connected")
                                    updateEvents.trySend(Unit)
                                }
                                "RESUMED" -> {
                                    isReady = true
                                    // Replay the latest snapshot after session resume.
                                    lastSentRevision = -1L
                                    lastPresenceUpdateAt = 0L
                                    retryDelayMs = 1_000L
                                    emitState(STATE_CONNECTED, "Discord Gateway session resumed")
                                    updateEvents.trySend(Unit)
                                }
                            }
                        }

                        DiscordGatewayProtocol.OP_HEARTBEAT_ACK -> awaitingHeartbeatAck.set(false)

                        DiscordGatewayProtocol.OP_HEARTBEAT -> {
                            // Gateway may request an immediate heartbeat outside the normal interval.
                            sendHeartbeat(currentSocket, allowOutstandingAck = true)
                        }

                        DiscordGatewayProtocol.OP_RECONNECT -> {
                            requestReconnect(currentSocket, "Gateway requested reconnect")
                        }

                        DiscordGatewayProtocol.OP_INVALID_SESSION -> {
                            val resumableSession = data as? Boolean ?: false
                            reconnectDelayOverride = GatewayInvalidSessionPolicy.sampleRetryDelay { from, until ->
                                Random.nextLong(from, until)
                            }
                            if (!resumableSession) clearResumeState()
                            requestReconnect(currentSocket, "Gateway session invalidated")
                        }
                    }
                }
                receivedCloseCode = currentSocket.closeReason.await()?.code?.toInt()
                onDiagnostic("Gateway socket closed code=${receivedCloseCode ?: "unknown"}")
            } catch (_: TimeoutCancellationException) {
                runCatching { socket?.close(CloseReason(GATEWAY_RECONNECT_CLOSE_CODE, "Gateway connection timed out")) }
                emitState(STATE_RECONNECTING, "Gateway connection timed out")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                // Close a half-open socket before the next retry; never include exception details.
                runCatching {
                    socket?.close(CloseReason(GATEWAY_RECONNECT_CLOSE_CODE, "Gateway connection interrupted"))
                }
                emitState(STATE_RECONNECTING, "Gateway connection interrupted (${exception.javaClass.simpleName})")
            } finally {
                helloTimeoutJob?.cancel()
                localHeartbeat?.cancel()
                if (heartbeatJob === localHeartbeat) heartbeatJob = null
                socket = null
                isReady = false
                awaitingHeartbeatAck.set(false)
            }

            if (userRequestedDisconnect || !currentCoroutineContext().isActive) break

            when (GatewayCloseCodePolicy.disposition(receivedCloseCode)) {
                GatewayCloseDisposition.AUTHENTICATION_REJECTED -> {
                    activeToken = null
                    clearResumeState()
                    runCatching { onSessionRejected() }
                    emitState(STATE_ERROR, "Discord rejected the saved session. Sign in again from Settings.")
                    return
                }
                GatewayCloseDisposition.RETRY_WITH_NEW_SESSION -> clearResumeState()
                GatewayCloseDisposition.BACKOFF -> reconnectDelayOverride = RATE_LIMIT_BACKOFF_MS
                GatewayCloseDisposition.TOO_MANY_ACCOUNT_SESSIONS -> {
                    clearResumeState()
                    emitState(
                        STATE_ERROR,
                        "Too many active Discord Gateway sessions (4015). Close other Rich Presence clients, then connect again. Your saved login is retained."
                    )
                    return
                }
                GatewayCloseDisposition.TERMINAL_CONFIGURATION_ERROR -> {
                    activeToken = null
                    clearResumeState()
                    emitState(STATE_ERROR, "Discord rejected the Gateway connection configuration (close code $receivedCloseCode).")
                    return
                }
                GatewayCloseDisposition.RETRY -> Unit
            }

            emitState(STATE_RECONNECTING, "Discord Gateway connection lost (close code ${receivedCloseCode ?: "unknown"}); reconnecting")
            val waitMs = reconnectDelayOverride ?: (retryDelayMs + Random.nextLong(0L, 501L))
            reconnectDelayOverride = null
            delay(waitMs)
            retryDelayMs = (retryDelayMs * 2L).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        if (userRequestedDisconnect) emitState(STATE_DISCONNECTED, "Discord Gateway disconnected")
    }

    private fun startHeartbeat(
        currentSocket: DefaultClientWebSocketSession,
        heartbeatIntervalMs: Long,
    ): Job = scope.launch {
        try {
            delay((heartbeatIntervalMs * Random.nextDouble()).toLong())
            while (isActive && currentSocket.isActive && !userRequestedDisconnect) {
                if (awaitingHeartbeatAck.get()) {
                    requestReconnect(currentSocket, "Gateway heartbeat acknowledgement timed out")
                    return@launch
                }
                sendHeartbeat(currentSocket, allowOutstandingAck = false)
                delay(heartbeatIntervalMs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A broken heartbeat sender must close the owning socket so the main loop can reconnect.
            requestReconnect(currentSocket, "Gateway heartbeat sender failed")
        }
    }

    private suspend fun sendHeartbeat(
        currentSocket: DefaultClientWebSocketSession,
        allowOutstandingAck: Boolean,
    ) {
        if (!allowOutstandingAck && awaitingHeartbeatAck.get()) {
            requestReconnect(currentSocket, "Gateway heartbeat acknowledgement timed out")
            return
        }
        awaitingHeartbeatAck.set(true)
        val sequence = synchronized(stateLock) { lastSequence }
        sendEnvelope(DiscordGatewayProtocol.heartbeat(sequence), currentSocket)
    }

    private suspend fun flushPendingPresence() {
        if (!isReady || userRequestedDisconnect) return
        val snapshot: PresenceSnapshot = synchronized(stateLock) {
            PresenceSnapshot(pendingActivity, pendingStatus, pendingRevision)
        }
        val alreadySent = synchronized(stateLock) { snapshot.revision <= lastSentRevision }
        if (alreadySent) return

        val waitMs = synchronized(stateLock) {
            (DiscordGatewayProtocol.MIN_PRESENCE_INTERVAL_MS -
                (System.currentTimeMillis() - lastPresenceUpdateAt)).coerceAtLeast(0L)
        }
        if (waitMs > 0L) delay(waitMs)
        if (!isReady || userRequestedDisconnect) return

        val latest: PresenceSnapshot = synchronized(stateLock) {
            PresenceSnapshot(pendingActivity, pendingStatus, pendingRevision)
        }
        val alreadySentLatest = synchronized(stateLock) { latest.revision <= lastSentRevision }
        if (alreadySentLatest) return
        val sent = try {
            sendEnvelope(DiscordGatewayProtocol.presenceUpdate(latest.activity, applicationId, latest.status))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GatewayPayloadTooLargeException) {
            emitState(STATE_ERROR, "Presence payload exceeds Discord's 4,096-byte limit. Shorten text or remove artwork/buttons.")
            return
        } catch (exception: Exception) {
            onDiagnostic("Presence opcode-3 write failed (${exception.javaClass.simpleName})")
            socket?.let { requestReconnect(it, "Gateway presence send failed") }
            false
        }
        if (sent) {
            synchronized(stateLock) {
                lastSentRevision = latest.revision
                lastPresenceUpdateAt = System.currentTimeMillis()
            }
        } else if (isReady && !userRequestedDisconnect) {
            emitState(STATE_RECONNECTING, "Presence update could not be sent; waiting for Gateway reconnect")
        }
    }

    private suspend fun sendEnvelope(
        envelope: GatewayEnvelope,
        targetSocket: DefaultClientWebSocketSession? = socket,
    ): Boolean = sendMutex.withLock {
        val currentSocket = targetSocket ?: return@withLock false
        val payload = JSONObject()
            .put("op", envelope.op)
            .put("d", toJsonValue(envelope.data))
            .toString()
        if (!DiscordGatewayProtocol.payloadFitsLimit(payload)) throw GatewayPayloadTooLargeException()
        if (envelope.op == DiscordGatewayProtocol.OP_PRESENCE_UPDATE) {
            onDiagnostic("Presence opcode-3 attempt bytes=${payload.toByteArray(Charsets.UTF_8).size}")
        }
        currentSocket.send(Frame.Text(payload))
        if (envelope.op == DiscordGatewayProtocol.OP_PRESENCE_UPDATE) {
            onDiagnostic("Presence opcode-3 write completed (local socket)")
        }
        true
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null, GatewayJsonNull -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, nested) ->
                if (key is String) put(key, toJsonValue(nested))
            }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        else -> value.toString()
    }

    private suspend fun requestReconnect(currentSocket: DefaultClientWebSocketSession, reason: String) {
        runCatching {
            currentSocket.close(CloseReason(GATEWAY_RECONNECT_CLOSE_CODE, reason.take(100)))
        }
    }

    private fun JSONObject.intOrDefault(key: String, fallback: Int): Int =
        try { getInt(key) } catch (_: JSONException) { fallback }

    private fun JSONObject.longOrDefault(key: String, fallback: Long): Long =
        try { getLong(key) } catch (_: JSONException) { fallback }

    private fun JSONObject.stringOrDefault(key: String): String =
        try { getString(key) } catch (_: JSONException) { "" }

    private fun JSONObject.valueOrNull(key: String): Any? =
        try { get(key) } catch (_: JSONException) { null }

    private fun safeResumeUrl(raw: String?): String? {
        val uri = runCatching { URI(raw ?: return null) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!uri.scheme.equals("wss", ignoreCase = true)) return null
        if (uri.port !in setOf(-1, 443) || uri.userInfo != null) return null
        if (host != "discord.gg" && !host.endsWith(".discord.gg")) return null
        return "wss://${uri.rawAuthority}/?v=9&encoding=json"
    }

    private fun hasResumeState(): Boolean = synchronized(stateLock) {
        !sessionId.isNullOrBlank() && !resumeGatewayUrl.isNullOrBlank() && lastSequence != null
    }

    private fun clearResumeState() = synchronized(stateLock) {
        sessionId = null
        resumeGatewayUrl = null
        lastSequence = null
    }

    private fun resetConnectionState(clearSession: Boolean) {
        isReady = false
        socket = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        awaitingHeartbeatAck.set(false)
        if (clearSession) clearResumeState()
    }


    private fun emitState(state: String, message: String) {
        runCatching { onState(state, message) }
    }

    private class GatewayPayloadTooLargeException : IllegalArgumentException()

    private data class PresenceSnapshot(
        val activity: PresenceActivity?,
        val status: String,
        val revision: Long,
    )

    companion object {
        private val ALLOWED_PRESENCE_STATUSES = setOf("online", "idle", "dnd", "invisible", "offline")
        const val STATE_DISCONNECTED = "GATEWAY_DISCONNECTED"
        const val STATE_CONNECTING = "GATEWAY_CONNECTING"
        const val STATE_IDENTIFYING = "GATEWAY_IDENTIFYING"
        const val STATE_CONNECTED = "GATEWAY_CONNECTED"
        const val STATE_RECONNECTING = "GATEWAY_RECONNECTING"
        const val STATE_ERROR = "GATEWAY_ERROR"

        private const val MIN_HEARTBEAT_INTERVAL_MS = 1_000L
        private const val MAX_HEARTBEAT_INTERVAL_MS = 120_000L
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val HELLO_TIMEOUT_MS = 15_000L
        private const val MAX_GATEWAY_FRAME_BYTES = 8L * 1024L * 1024L
        private const val MAX_ACCEPTED_GATEWAY_FRAME_BYTES = 8L * 1024L * 1024L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val RATE_LIMIT_BACKOFF_MS = 30_000L
        private val GATEWAY_RECONNECT_CLOSE_CODE = 4000.toShort()
    }
}
