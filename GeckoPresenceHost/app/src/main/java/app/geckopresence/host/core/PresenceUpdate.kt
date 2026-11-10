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
)
