package app.geckopresence.host.core

/** Maps the extension's versioned UAM payload into the host's compact Discord model. */
object BridgePayloadMapper {
    fun fromV2(payload: Map<String, Any?>, shareTitles: Boolean): PresenceUpdate? {
        if (payload["schemaVersion"] != 2) return null
        val source = PresenceRules.sanitizeHost(payload["source"] as? String) ?: return null
        if (PresenceRules.isSensitiveHost(source)) return null
        val mediaState = PresenceRules.normalizeMediaState(payload["mediaState"] as? String)
        val name = PresenceRules.cleanText(payload["name"] as? String, 80) ?: source
        val requestedDetails = PresenceRules.cleanText(payload["details"] as? String, 120)
        val details = if (shareTitles) requestedDetails ?: PresenceRules.defaultDetails(mediaState)
            else PresenceRules.defaultDetails(mediaState)
        val state = if (shareTitles) PresenceRules.cleanText(payload["state"] as? String, 120) else null
        val requestedStart = (payload["startTimestamp"] as? Number)?.toLong() ?: 0L
        val sourceType = when ((payload["sourceType"] as? String)?.lowercase()) {
            "manual" -> "manual"
            "android-app" -> "android-app"
            else -> "website"
        }
        val priority = ((payload["priority"] as? Number)?.toInt() ?: if (sourceType == "manual") 100 else 10).coerceIn(0, 100)
        val startTime = if (requestedStart in 1L..4_102_444_800L) requestedStart
            else System.currentTimeMillis() / 1000L

        return PresenceUpdate(
            source = source,
            name = name,
            details = details,
            state = state,
            mediaState = mediaState,
            startTimestampSeconds = startTime,
            sourceType = sourceType,
            priority = priority,
        )
    }
}
