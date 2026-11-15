package app.geckopresence.host.core

data class PresenceUpdate(
    val source: String,
    val name: String,
    val details: String,
    val state: String?,
    val mediaState: String,
    val startTimestampSeconds: Long,
    val sourceType: String = "website",
    val priority: Int = 10,
    /** A zero value means the candidate does not expire. */
    val expiresAtEpochMillis: Long = 0L,
    val activityType: PresenceActivityType? = null,
    val endsAtEpochSeconds: Long? = null,
    val largeImage: PresenceAsset? = null,
    val smallImage: PresenceAsset? = null,
    val buttons: List<PresenceButton> = emptyList(),
    val streamUrl: String? = null,
    val accountStatus: String? = null,
)
