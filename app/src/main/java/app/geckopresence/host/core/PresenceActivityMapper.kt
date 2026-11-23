package app.geckopresence.host.core

/** Maps source-arbitration winners into the transport-neutral Rich Presence model. */
object PresenceActivityMapper {
    fun stateLine(state: String?): String? = PresenceRules.cleanText(state, MAX_STATE_LENGTH)
        ?.takeIf { it.length >= 2 }

    fun fromUpdate(update: PresenceUpdate): PresenceActivity = PresenceActivity(
        name = PresenceRules.cleanText(update.name, MAX_ACTIVITY_NAME_LENGTH) ?: "Gecko Presence",
        type = update.activityType ?: if (update.sourceType == "media-session") PresenceActivityType.LISTENING else PresenceActivityType.PLAYING,
        details = PresenceRules.cleanText(update.details, MAX_DETAILS_LENGTH),
        state = stateLine(update.state),
        startedAtEpochSeconds = update.startTimestampSeconds.takeIf { it > 0L },
        endsAtEpochSeconds = update.endsAtEpochSeconds,
        timestampDisplayMode = update.timestampDisplayMode,
        platform = update.platform,
        largeImage = update.largeImage,
        smallImage = update.smallImage,
        buttons = update.buttons,
        streamUrl = update.streamUrl,
        sourceId = update.source,
        priority = update.priority,
        expiresAtEpochMillis = update.expiresAtEpochMillis,
    )

    private const val MAX_ACTIVITY_NAME_LENGTH = 128
    private const val MAX_DETAILS_LENGTH = 128
    private const val MAX_STATE_LENGTH = 128
}
