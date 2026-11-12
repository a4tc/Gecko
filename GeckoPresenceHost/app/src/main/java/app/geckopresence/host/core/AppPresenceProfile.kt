package app.geckopresence.host.core

/** User-authored text overrides for one Android package. Blank fields inherit defaults. */
data class AppPresenceProfile(
    val activityLabel: String,
    val details: String,
    val state: String?,
)

/** Resolves a stored per-package profile without allowing it to bypass text limits. */
object AppPresenceProfilePolicy {
    fun resolve(
        appLabel: String,
        defaultDetails: String,
        custom: AppPresenceProfile?,
    ): AppPresenceProfile {
        val safeLabel = PresenceRules.cleanText(custom?.activityLabel, MAX_ACTIVITY_LABEL_LENGTH)
            ?: PresenceRules.cleanText(appLabel, MAX_ACTIVITY_LABEL_LENGTH)
            ?: "Android app"
        val safeDetails = PresenceRules.cleanText(custom?.details, MAX_DETAILS_LENGTH)
            ?: PresenceRules.cleanText(defaultDetails, MAX_DETAILS_LENGTH)
            ?: "Using an app"
        val safeState = PresenceRules.cleanText(custom?.state, MAX_STATE_LENGTH)

        return AppPresenceProfile(
            activityLabel = safeLabel,
            details = safeDetails,
            state = safeState,
        )
    }

    private const val MAX_ACTIVITY_LABEL_LENGTH = 40
    private const val MAX_DETAILS_LENGTH = 120
    private const val MAX_STATE_LENGTH = 120
}
