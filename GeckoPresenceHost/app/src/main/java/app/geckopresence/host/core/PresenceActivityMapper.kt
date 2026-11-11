package app.geckopresence.host.core

/** Maps source metadata into the two user-editable Rich Presence text fields. */
object PresenceActivityMapper {
    const val MAX_DETAILS_LENGTH = 128

    /**
     * Discord owns Activity.Name through the registered application name. The source label and
     * source details therefore share the editable Activity.details line.
     */
    fun detailsLine(activityLabel: String?, details: String?): String? {
        val label = PresenceRules.cleanText(activityLabel, 40)
        val description = PresenceRules.cleanText(details, 120)
        val joined = when {
            label == null -> description
            description == null -> label
            label.equals(description, ignoreCase = true) -> label
            else -> {
                val labelPoints = label.codePointCount(0, label.length)
                val separatorPoints = SEPARATOR.codePointCount(0, SEPARATOR.length)
                val remaining = (MAX_DETAILS_LENGTH - labelPoints - separatorPoints).coerceAtLeast(0)
                "$label$SEPARATOR${PresenceRules.cleanText(description, remaining).orEmpty()}"
            }
        } ?: return null
        return PresenceRules.cleanText(joined, MAX_DETAILS_LENGTH)?.takeIf {
            it.codePointCount(0, it.length) >= 2
        }
    }

    fun stateLine(state: String?): String? = PresenceRules.cleanText(state, MAX_DETAILS_LENGTH)
        ?.takeIf { it.length >= 2 }

    private const val SEPARATOR = " · "
}
