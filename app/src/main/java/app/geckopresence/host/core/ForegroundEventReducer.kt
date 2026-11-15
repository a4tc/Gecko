package app.geckopresence.host.core

enum class ForegroundEventKind {
    RESUMED,
    PAUSED,
    SCREEN_NON_INTERACTIVE,
    OTHER,
}

/** Pure reducer for Android UsageEvents; package names are never persisted here. */
object ForegroundEventReducer {
    fun reduce(
        currentPackage: String?,
        eventPackage: String?,
        eventKind: ForegroundEventKind,
        isAllowedPackage: Boolean,
    ): String? = when (eventKind) {
        ForegroundEventKind.RESUMED -> eventPackage?.takeIf { isAllowedPackage }
        ForegroundEventKind.PAUSED -> if (currentPackage == eventPackage) null else currentPackage
        ForegroundEventKind.SCREEN_NON_INTERACTIVE -> null
        ForegroundEventKind.OTHER -> currentPackage
    }
}
