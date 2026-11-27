package app.geckopresence.host.core

import java.nio.charset.StandardCharsets

/** Explicit JSON-null marker so Android's JSONObject encoder can preserve a literal null value. */
data object GatewayJsonNull

data class GatewayEnvelope(
    val op: Int,
    val data: Any?,
)

/**
 * Wire payload factory for Gecko's Kizzy-compatible user-session transport.
 * The Identify capability/largeThreshold fields follow Kizzy's observed user-session shape;
 * they are not part of the documented bot Identify structure and may change without notice.
 */
object DiscordGatewayProtocol {
    const val OP_DISPATCH = 0
    const val OP_HEARTBEAT = 1
    const val OP_IDENTIFY = 2
    const val OP_PRESENCE_UPDATE = 3
    const val OP_RESUME = 6
    const val OP_RECONNECT = 7
    const val OP_INVALID_SESSION = 9
    const val OP_HELLO = 10
    const val OP_HEARTBEAT_ACK = 11

    const val GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"
    const val MAX_PAYLOAD_BYTES = 4_096
    const val MIN_PRESENCE_INTERVAL_MS = 4_100L

    /** Gateway payload limits are byte-based, not Kotlin String character counts. */
    fun payloadFitsLimit(payload: String): Boolean =
        payload.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES

    fun identify(token: String, operatingSystem: String, device: String): GatewayEnvelope = GatewayEnvelope(
        op = OP_IDENTIFY,
        data = linkedMapOf(
            "capabilities" to 16381,
            "compress" to false,
            "largeThreshold" to 100,
            "properties" to linkedMapOf(
                "os" to operatingSystem,
                "browser" to "Discord Android",
                "device" to device,
            ),
            "token" to token,
        ),
    )

    fun heartbeat(sequence: Long?): GatewayEnvelope = GatewayEnvelope(OP_HEARTBEAT, sequence)

    fun resume(token: String, sessionId: String, sequence: Long?): GatewayEnvelope = GatewayEnvelope(
        op = OP_RESUME,
        data = linkedMapOf(
            "token" to token,
            "session_id" to sessionId,
            "seq" to sequence,
        ),
    )

    fun presenceUpdate(activity: PresenceActivity?, applicationId: Long, status: String = "online", nowMillis: Long = System.currentTimeMillis()): GatewayEnvelope =
        presenceUpdateMany(activity?.let { listOf(it) }.orEmpty(), applicationId, status, nowMillis)

    fun presenceUpdateMany(
        activities: List<PresenceActivity>,
        applicationId: Long,
        status: String = "online",
        nowMillis: Long = System.currentTimeMillis(),
    ): GatewayEnvelope {
        require(status in ALLOWED_STATUSES) { "Unsupported Discord presence status" }
        require(activities.size <= PresenceSourceArbiter.MAX_ACTIVITY_COUNT) {
            "At most ${PresenceSourceArbiter.MAX_ACTIVITY_COUNT} activities may be serialized"
        }
        activities.forEach { candidate ->
            val errors = candidate.validate()
            require(errors.isEmpty()) { "Invalid presence activity: ${errors.joinToString(", ")}" }
        }
        val wireActivities = activities.map { it.toGatewayActivity(applicationId) }
        val empty = activities.isEmpty()
        return GatewayEnvelope(
            op = OP_PRESENCE_UPDATE,
            data = linkedMapOf(
                "since" to if (empty) 0L else nowMillis,
                "activities" to wireActivities,
                "status" to status,
                "afk" to empty,
            ),
        )
    }

    private val ALLOWED_STATUSES = setOf("online", "idle", "dnd", "invisible", "offline")

    private fun PresenceActivity.toGatewayActivity(applicationId: Long): Map<String, Any?> = buildMap {
        platform?.wireValue?.let { put("platform", it) }
        if (type == PresenceActivityType.CUSTOM_STATUS) {
            put("name", "Custom Status")
            put("type", type.wireValue)
            put("details", GatewayJsonNull)
            put("state", state!!)
            return@buildMap
        }

        put("name", name)
        put("type", type.wireValue)
        put("status_display_type", 0)
        put("details", details?.takeIf(String::isNotBlank) ?: GatewayJsonNull)
        put("state", state?.takeIf(String::isNotBlank) ?: GatewayJsonNull)
        streamUrl?.takeIf(String::isNotBlank)?.let { put("url", it) }
        if (applicationId > 0L && (largeImage != null || smallImage != null || buttons.isNotEmpty())) {
            put("application_id", applicationId.toString())
        }

        val timestamps = buildMap<String, Any> {
            when (timestampDisplayMode) {
                PresenceTimestampDisplayMode.ELAPSED ->
                    startedAtEpochSeconds?.let { put("start", it * 1_000L) }
                PresenceTimestampDisplayMode.COUNTDOWN ->
                    endsAtEpochSeconds?.let { put("end", it * 1_000L) }
                PresenceTimestampDisplayMode.ACTIVITY_RANGE -> {
                    startedAtEpochSeconds?.let { put("start", it * 1_000L) }
                    endsAtEpochSeconds?.let { put("end", it * 1_000L) }
                }
            }
        }
        if (timestamps.isNotEmpty()) put("timestamps", timestamps)

        val assets = buildMap<String, Any> {
            largeImage?.let { image ->
                put("large_image", image.image)
                image.tooltip?.let { put("large_text", it) }
                image.clickUrl?.let { put("large_url", it) }
            }
            smallImage?.let { image ->
                put("small_image", image.image)
                image.tooltip?.let { put("small_text", it) }
                image.clickUrl?.let { put("small_url", it) }
            }
        }
        if (assets.isNotEmpty()) put("assets", assets)

        if (buttons.isNotEmpty()) {
            put("buttons", buttons.map(PresenceButton::label))
            put("metadata", mapOf("button_urls" to buttons.map(PresenceButton::url)))
        }
    }
}
