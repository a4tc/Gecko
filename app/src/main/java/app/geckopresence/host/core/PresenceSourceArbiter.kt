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

    /** Deterministic experimental selection; existing one-candidate-per-source semantics remain. */
    fun select(
        current: Map<String, PresenceUpdate>,
        nowEpochMillis: Long = System.currentTimeMillis(),
        maxActivities: Int = MAX_ACTIVITY_COUNT,
    ): List<PresenceUpdate> {
        val limit = maxActivities.coerceIn(0, MAX_ACTIVITY_COUNT)
        if (limit == 0) return emptyList()
        val ordered = active(current, nowEpochMillis).values.sortedWith(
            compareByDescending<PresenceUpdate> { it.priority }
                .thenByDescending { it.startTimestampSeconds }
                .thenBy { it.sourceType }
                .thenBy { it.source },
        )
        val seen = HashSet<PresenceActivity>()
        return ordered.filter { update ->
            val identity = PresenceActivityMapper.fromUpdate(update).copy(
                sourceId = "",
                priority = 0,
                expiresAtEpochMillis = 0L,
            )
            seen.add(identity)
        }.take(limit)
    }

    const val MAX_ACTIVITY_COUNT = 3
}
