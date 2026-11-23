package app.geckopresence.host.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

fun main() {
    check(PresenceTimestampPolicy.parse(null) == null)
    check(PresenceTimestampPolicy.parse("") == null)
    check(PresenceTimestampPolicy.parse("  0  ") == null)
    check(PresenceTimestampPolicy.parse("  1700000000  ") == 1_700_000_000L)
    check(PresenceTimestampPolicy.parse("not-a-timestamp") == null)
    check(PresenceTimestampPolicy.parse((Long.MAX_VALUE / 1_000L + 1L).toString()) == null)
    check(PresenceTimestampPolicy.format(null) == null)
    check(PresenceTimestampPolicy.format(0L) == null)

    val utc = TimeZone.getTimeZone("UTC")
    val seconds = PresenceTimestampPolicy.fromLocalFields(2026, Calendar.JANUARY, 2, 3, 4, utc)
    val expected = Calendar.getInstance(utc, Locale.US).apply {
        clear()
        set(2026, Calendar.JANUARY, 2, 3, 4, 0)
    }.timeInMillis / 1_000L
    check(seconds == expected)
    check(PresenceTimestampPolicy.format(seconds, Locale.US, utc) == "Fri, 2 Jan 2026 · 03:04")
    check(runCatching {
        PresenceTimestampPolicy.fromLocalFields(2026, Calendar.FEBRUARY, 30, 9, 0, utc)
    }.isFailure)
    println("PresenceTimestampPolicySelfTest: parsing, epoch bounds, local conversion and formatting passed")
}
