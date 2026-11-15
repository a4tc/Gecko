package app.geckopresence.host.core

/** Complete, package-scoped activity override. Blank optional fields inherit source defaults. */
data class AppPresenceProfile(
    val activityLabel: String,
    val details: String,
    val state: String?,
    val activityType: PresenceActivityType? = null,
    val streamUrl: String? = null,
    val largeImage: PresenceAsset? = null,
    val smallImage: PresenceAsset? = null,
    val buttons: List<PresenceButton> = emptyList(),
    val startedAtEpochSeconds: Long? = null,
    val endsAtEpochSeconds: Long? = null,
    val priorityOverride: Int? = null,
    val accountStatus: String? = null,
    val enabled: Boolean = true,
)

/** Resolves a stored per-package profile while preserving optional activity metadata. */
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
        val resolved = custom ?: AppPresenceProfile(safeLabel, safeDetails, null)
        return resolved.copy(
            activityLabel = safeLabel,
            details = safeDetails,
            state = safeState,
            streamUrl = resolved.streamUrl?.trim()?.takeIf(String::isNotEmpty),
            buttons = resolved.buttons.take(2),
        )
    }

    fun validate(profile: AppPresenceProfile, requireFutureEnd: Boolean = true): List<String> {
        val candidate = PresenceActivity(
            name = profile.activityLabel,
            type = profile.activityType ?: PresenceActivityType.PLAYING,
            details = profile.details,
            state = profile.state,
            startedAtEpochSeconds = profile.startedAtEpochSeconds,
            endsAtEpochSeconds = profile.endsAtEpochSeconds,
            largeImage = profile.largeImage,
            smallImage = profile.smallImage,
            buttons = profile.buttons,
            streamUrl = profile.streamUrl,
        )
        return buildList {
            addAll(candidate.validate())
            if (profile.activityLabel.isBlank()) add("activity name is required")
            if (profile.details.isBlank()) add("details are required for app profiles")
            if (profile.activityLabel.length > MAX_ACTIVITY_LABEL_LENGTH) add("activity name must be at most 40 characters")
            if (profile.details.length > MAX_DETAILS_LENGTH) add("details must be at most 120 characters")
            if (!profile.state.isNullOrBlank() && profile.state.length > MAX_STATE_LENGTH) add("state must be at most 120 characters")
            if (profile.priorityOverride != null && profile.priorityOverride !in 0..1000) {
                add("priority override must be between 0 and 1000")
            }
            if (profile.accountStatus != null && profile.accountStatus !in ALLOWED_ACCOUNT_STATUSES) {
                add("account status must be online, idle, dnd, invisible, or offline")
            }
            if (requireFutureEnd && profile.endsAtEpochSeconds != null && profile.endsAtEpochSeconds <= System.currentTimeMillis() / 1_000L) {
                add("end timestamp must be in the future")
            }
        }
    }

    private const val MAX_ACTIVITY_LABEL_LENGTH = 40
    private const val MAX_DETAILS_LENGTH = 120
    private const val MAX_STATE_LENGTH = 120
    private val ALLOWED_ACCOUNT_STATUSES = setOf("online", "idle", "dnd", "invisible", "offline")
}
