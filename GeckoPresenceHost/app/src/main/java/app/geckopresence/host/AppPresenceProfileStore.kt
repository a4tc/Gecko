package app.geckopresence.host

import android.content.Context
import app.geckopresence.host.core.AppPresenceProfile
import app.geckopresence.host.core.PresenceRules
import org.json.JSONObject

/** Package-keyed, on-device profiles used only by Android app detection. */
object AppPresenceProfileStore {
    private const val PREFS = "gpb_app_profiles"
    private const val KEY_PREFIX = "profile:"
    private val packageNamePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun get(context: Context, packageName: String): AppPresenceProfile? {
        if (!isValidPackageName(packageName)) return null
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(packageName), null)
            ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val label = json.optString("activityLabel").takeIf(String::isNotBlank) ?: return null
        val details = json.optString("details").takeIf(String::isNotBlank) ?: return null
        val state = json.optString("state").takeIf(String::isNotBlank)
        return AppPresenceProfile(label, details, state)
    }

    fun save(context: Context, packageName: String, profile: AppPresenceProfile): Boolean {
        if (!isValidPackageName(packageName)) return false
        val label = PresenceRules.cleanText(profile.activityLabel, MAX_ACTIVITY_LABEL_LENGTH) ?: return false
        val details = PresenceRules.cleanText(profile.details, MAX_DETAILS_LENGTH) ?: return false
        val state = PresenceRules.cleanText(profile.state, MAX_STATE_LENGTH)
        val json = JSONObject()
            .put("activityLabel", label)
            .put("details", details)
            .put("state", state.orEmpty())
            .put("updatedAt", System.currentTimeMillis())
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(packageName), json.toString())
            .commit()
    }

    fun remove(context: Context, packageName: String): Boolean {
        if (!isValidPackageName(packageName)) return false
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(packageName))
            .commit()
    }

    fun hasProfile(context: Context, packageName: String): Boolean = get(context, packageName) != null

    fun isValidPackageName(packageName: String): Boolean =
        packageName.length in 3..255 && packageNamePattern.matches(packageName)

    private fun key(packageName: String): String = "$KEY_PREFIX$packageName"

    private const val MAX_ACTIVITY_LABEL_LENGTH = 40
    private const val MAX_DETAILS_LENGTH = 120
    private const val MAX_STATE_LENGTH = 120
}
