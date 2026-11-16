package app.geckopresence.host.core

fun main() {
    val empty = ForegroundEventReducer.State()
    val first = ForegroundEventReducer.reduce(
        current = empty,
        eventPackage = "org.example.reader",
        eventClassName = "org.example.reader.ReaderActivity",
        eventKind = ForegroundEventKind.RESUMED,
        isAllowedPackage = true,
    )
    check(first.packageName == "org.example.reader")
    check(first.activityClassName == "org.example.reader.ReaderActivity")

    val secondActivity = ForegroundEventReducer.reduce(
        current = first,
        eventPackage = "org.example.reader",
        eventClassName = "org.example.reader.SettingsActivity",
        eventKind = ForegroundEventKind.RESUMED,
        isAllowedPackage = true,
    )
    check(secondActivity.packageName == "org.example.reader")
    check(secondActivity.activityClassName == "org.example.reader.SettingsActivity")

    // A delayed PAUSED event from the previous Activity must not clear the
    // foreground package after another Activity in the same package resumed.
    val delayedPause = ForegroundEventReducer.reduce(
        current = secondActivity,
        eventPackage = "org.example.reader",
        eventClassName = "org.example.reader.ReaderActivity",
        eventKind = ForegroundEventKind.PAUSED,
        isAllowedPackage = true,
    )
    check(delayedPause == secondActivity)

    // Even two instances of the same Activity class must ignore a pause older than its latest resume.
    val sameClassResume = ForegroundEventReducer.reduce(
        current = empty,
        eventPackage = "org.example.reader",
        eventClassName = "org.example.reader.ReaderActivity",
        eventKind = ForegroundEventKind.RESUMED,
        isAllowedPackage = true,
        eventTimestampMillis = 2_000L,
    )
    val olderSameClassPause = ForegroundEventReducer.reduce(
        current = sameClassResume,
        eventPackage = "org.example.reader",
        eventClassName = "org.example.reader.ReaderActivity",
        eventKind = ForegroundEventKind.PAUSED,
        isAllowedPackage = true,
        eventTimestampMillis = 1_500L,
    )
    check(olderSameClassPause == sameClassResume)

    val currentPause = ForegroundEventReducer.reduce(
        current = delayedPause,
        eventPackage = "org.example.reader",
        eventClassName = "org.example.reader.SettingsActivity",
        eventKind = ForegroundEventKind.PAUSED,
        isAllowedPackage = true,
    )
    check(currentPause == empty)

    val unrelatedPause = ForegroundEventReducer.reduce(
        current = first,
        eventPackage = "org.example.other",
        eventClassName = "org.example.other.MainActivity",
        eventKind = ForegroundEventKind.PAUSED,
        isAllowedPackage = false,
    )
    check(unrelatedPause == first)

    val rejectedResume = ForegroundEventReducer.reduce(
        current = first,
        eventPackage = "org.example.private",
        eventClassName = "org.example.private.MainActivity",
        eventKind = ForegroundEventKind.RESUMED,
        isAllowedPackage = false,
    )
    check(rejectedResume == empty)

    val screenOff = ForegroundEventReducer.reduce(
        current = first,
        eventPackage = null,
        eventClassName = null,
        eventKind = ForegroundEventKind.SCREEN_NON_INTERACTIVE,
        isAllowedPackage = false,
    )
    check(screenOff == empty)

    println("ForegroundEventReducerSelfTest: package/class transitions and delayed pause handling passed")
}
