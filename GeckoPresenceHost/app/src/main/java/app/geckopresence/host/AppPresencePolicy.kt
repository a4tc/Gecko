package app.geckopresence.host

import android.content.Context
import app.geckopresence.host.core.AppActivityRules
import app.geckopresence.host.core.PresenceRules
import app.geckopresence.host.core.PresenceUpdate

object AppPresencePolicy {
    const val SOURCE = "android.app"
    const val KEY_TRACK_APPS = "track_selected_apps"
    const val KEY_TRACKED_PACKAGES = "tracked_app_packages"
    private const val NAME_PREFIX = "app_presence_name__"
    private const val DETAILS_PREFIX = "app_presence_details__"
    const val KEY_CUSTOM_STATE_PREFIX = "app_presence_state__"

    fun buildUpdate(context: Context, packageName: String): PresenceUpdate? {
        val settings = context.getSharedPreferences("gpb_settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean(KEY_TRACK_APPS, false)) return null
        val selected = settings.getStringSet(KEY_TRACKED_PACKAGES, emptySet()).orEmpty()
        if (packageName !in selected) return null

        val appInfo = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        val label = runCatching { context.packageManager.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)
        if (!AppActivityRules.isSelectableApp(packageName, label, context.packageName)) return null

        val customName = settings.getString(NAME_PREFIX + packageName, null)
        val customDetails = settings.getString(DETAILS_PREFIX + packageName, null)
        val customState = settings.getString(KEY_CUSTOM_STATE_PREFIX + packageName, null)
        val name = PresenceRules.cleanText(customName, 80)
            ?: PresenceRules.cleanText(label, 80)
            ?: "Android app"
        val details = PresenceRules.cleanText(customDetails, 120)
            ?: PresenceRules.defaultAppDetails(packageName, label)

        return PresenceUpdate(
            source = SOURCE,
            name = name,
            details = details,
            state = PresenceRules.cleanText(customState, 120),
            mediaState = "browsing",
            startTimestampSeconds = System.currentTimeMillis() / 1000L,
            sourceType = "android-app",
            priority = 95,
        )
    }

    fun defaultName(label: String): String = PresenceRules.cleanText(label, 80) ?: "Android app"

    fun defaultDetails(packageName: String, label: String): String = AppActivityRules.defaultActivityDetails(packageName, label)

    fun storeCustom(context: Context, packageName: String, name: String, details: String, state: String) {
        context.getSharedPreferences("gpb_settings", Context.MODE_PRIVATE)
            .edit()
            .putString(NAME_PREFIX + packageName, name.trim().take(80))
            .putString(DETAILS_PREFIX + packageName, details.trim().take(120))
            .putString(KEY_CUSTOM_STATE_PREFIX + packageName, state.trim().take(120))
            .apply()
    }
}
