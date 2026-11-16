package app.geckopresence.host.core

enum class ForegroundEventKind {
    RESUMED,
    PAUSED,
    SCREEN_NON_INTERACTIVE,
    OTHER,
}

/** Tracks the latest foreground Activity while keeping package names in memory only. */
object ForegroundEventReducer {
    data class State(
        val packageName: String? = null,
        val activityClassName: String? = null,
        val lastResumedTimestampMillis: Long = 0L,
    )

    /** Compatibility adapter for older package-only call sites and tests. */
    fun reduce(
        currentPackage: String?,
        eventPackage: String?,
        eventKind: ForegroundEventKind,
        isAllowedPackage: Boolean,
    ): String? = reduce(
        current = State(packageName = currentPackage),
        eventPackage = eventPackage,
        eventClassName = null,
        eventKind = eventKind,
        isAllowedPackage = isAllowedPackage,
    ).packageName

    fun reduce(
        current: State,
        eventPackage: String?,
        eventClassName: String?,
        eventKind: ForegroundEventKind,
        isAllowedPackage: Boolean,
        eventTimestampMillis: Long = 0L,
    ): State = when (eventKind) {
        ForegroundEventKind.RESUMED -> {
            if (isAllowedPackage && !eventPackage.isNullOrBlank()) {
                State(
                    packageName = eventPackage,
                    activityClassName = eventClassName?.takeIf(String::isNotBlank),
                    lastResumedTimestampMillis = eventTimestampMillis.coerceAtLeast(0L),
                )
            } else {
                State()
            }
        }
        ForegroundEventKind.PAUSED -> {
            val samePackage = current.packageName == eventPackage
            val sameActivity = current.activityClassName.isNullOrBlank() ||
                eventClassName.isNullOrBlank() || current.activityClassName == eventClassName
            val stalePause = current.lastResumedTimestampMillis > 0L && eventTimestampMillis > 0L &&
                eventTimestampMillis < current.lastResumedTimestampMillis
            if (samePackage && sameActivity && !stalePause) State() else current
        }
        ForegroundEventKind.SCREEN_NON_INTERACTIVE -> State()
        ForegroundEventKind.OTHER -> current
    }
}
