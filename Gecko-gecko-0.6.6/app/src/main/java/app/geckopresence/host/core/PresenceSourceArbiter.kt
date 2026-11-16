package app.geckopresence.host.core

/** Keeps one current candidate per source class and selects the highest-priority source. */
object PresenceSourceArbiter {
    fun upsert(current: Map<String, PresenceUpdate>, update: PresenceUpdate): Map<String, PresenceUpdate> {
        val next = current.filterValues { it.sourceType != update.sourceType }.toMutableMap()
        next[update.source] = update
        return next
    }

    fun removeSourceType(current: Map<String, PresenceUpdate>, sourceType: String): Map<String, PresenceUpdate> {
        return current.filterValues { it.sourceType != sourceType }
    }

    fun active(
        current: Map<String, PresenceUpdate>,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Map<String, PresenceUpdate> = current.filterValues { candidate ->
        candidate.expiresAtEpochMillis == 0L || candidate.expiresAtEpochMillis > nowEpochMillis
    }

    fun winner(
        current: Map<String, PresenceUpdate>,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PresenceUpdate? = active(current, nowEpochMillis).values.maxWithOrNull(
        compareBy<PresenceUpdate> { it.priority }.thenBy { it.startTimestampSeconds },
    )
}
