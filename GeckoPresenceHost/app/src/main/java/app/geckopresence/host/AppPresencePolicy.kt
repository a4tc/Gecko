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

        val profile = AppPresenceProfilePolicy.resolve(
            appLabel = label,
            defaultDetails = PresenceRules.defaultAppDetails(packageName, label),
            custom = AppPresenceProfileStore.get(context, packageName),
        )

        return PresenceUpdate(
            source = SOURCE,
            name = profile.activityLabel,
            details = profile.details,
            state = profile.state,
            mediaState = "browsing",
            startTimestampSeconds = System.currentTimeMillis() / 1000L,
            sourceType = "android-app",
            priority = PresencePriorityPolicy.get(context, "android-app"),
        )
    }
}
