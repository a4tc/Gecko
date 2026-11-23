package app.geckopresence.host.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Local-time conversion and formatting for Discord activity timestamps stored as Unix seconds. */
object PresenceTimestampPolicy {
    fun parse(raw: String?): Long? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty)?.toLongOrNull() ?: return null
        return value.takeIf(::isValid)
    }

    fun isValid(epochSeconds: Long): Boolean = epochSeconds > 0L && epochSeconds <= Long.MAX_VALUE / 1_000L

    fun fromLocalFields(
        year: Int,
        monthZeroBased: Int,
        day: Int,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val calendar = Calendar.getInstance(timeZone, Locale.getDefault()).apply {
            isLenient = false
            clear()
            set(year, monthZeroBased, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (calendar.timeInMillis / 1_000L).also {
            require(isValid(it)) { "Timestamp is outside the supported epoch range" }
        }
    }

    fun format(
        epochSeconds: Long?,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String? {
        if (epochSeconds == null || !isValid(epochSeconds)) return null
        return SimpleDateFormat("EEE, d MMM yyyy · HH:mm", locale).apply {
            this.timeZone = timeZone
        }.format(Date(epochSeconds * 1_000L))
    }
}
