package app.geckopresence.host

import android.content.Context
import app.geckopresence.host.core.AppActivityRules
import app.geckopresence.host.core.AppPresenceProfilePolicy
import app.geckopresence.host.core.PresenceRules
import app.geckopresence.host.core.PresenceUpdate

object AppPresencePolicy {
    const val SOURCE = "android.app"
    const val KEY_TRACK_APPS = "track_selected_apps"
    const val KEY_TRACKED_PACKAGES = "tracked_app_packages"

    fun buildUpdate(context: Context, packageName: String): PresenceUpdate? {
        val settings = context.getSharedPreferences("gpb_settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean(KEY_TRACK_APPS, false)) return null
        val selected = settings.getStringSet(KEY_TRACKED_PACKAGES, emptySet()).orEmpty()
        if (packageName !in selected) return null

        val appInfo = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        val label = runCatching { context.packageManager.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)
        if (!AppActivityRules.isSelectableApp(packageName, label, context.packageName)) return null

        val storedProfile = AppPresenceProfileStore.get(context, packageName)
        if (storedProfile?.enabled == false) return null
        val profile = AppPresenceProfilePolicy.resolve(
            appLabel = label,
            defaultDetails = PresenceRules.defaultAppDetails(packageName, label),
            custom = storedProfile,
        )
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val startTimestamp = profile.startedAtEpochSeconds ?: nowSeconds
        if (profile.endsAtEpochSeconds != null && profile.endsAtEpochSeconds <= nowSeconds) return null

        return PresenceUpdate(
            source = SOURCE,
            name = profile.activityLabel,
            details = profile.details,
            state = profile.state,
            mediaState = "browsing",
            startTimestampSeconds = startTimestamp,
            sourceType = "android-app",
            expiresAtEpochMillis = profile.endsAtEpochSeconds?.times(1_000L) ?: 0L,
            priority = profile.priorityOverride ?: PresencePriorityPolicy.get(context, "android-app"),
            activityType = profile.activityType,
            endsAtEpochSeconds = profile.endsAtEpochSeconds,
            largeImage = profile.largeImage,
            smallImage = profile.smallImage,
            buttons = profile.buttons,
            streamUrl = profile.streamUrl,
            accountStatus = profile.accountStatus,
        )
    }
}
