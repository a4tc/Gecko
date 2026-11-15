package app.geckopresence.host

import android.content.Context
import app.geckopresence.host.core.AppPresenceProfile
import app.geckopresence.host.core.AppPresenceProfilePolicy
import app.geckopresence.host.core.PresenceActivityType
import app.geckopresence.host.core.PresenceAsset
import app.geckopresence.host.core.PresenceButton
import org.json.JSONArray
import org.json.JSONObject

/** Package-keyed profiles. Values contain user-authored activity metadata, never account credentials. */
object AppPresenceProfileStore {
    private const val PREFS = "gpb_app_profiles"
    private const val KEY_PREFIX = "profile:"
    private const val MAX_PROFILE_JSON_CHARS = 16 * 1024
    private val packageNamePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun get(context: Context, packageName: String): AppPresenceProfile? {
        if (!isValidPackageName(packageName)) return null
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(packageName), null) ?: return null
        if (raw.length > MAX_PROFILE_JSON_CHARS) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val label = json.optString("activityLabel").takeIf(String::isNotBlank) ?: return null
        val details = json.optString("details").takeIf(String::isNotBlank) ?: return null
        val state = json.optString("state").takeIf(String::isNotBlank)
        val type = if (json.has("activityType")) {
            PresenceActivityType.fromWireValue(json.optInt("activityType", -1))
        } else null
        val profile = AppPresenceProfile(
            activityLabel = label,
            details = details,
            state = state,
            activityType = type,
            streamUrl = json.optString("streamUrl").takeIf(String::isNotBlank),
            largeImage = json.optJSONObject("largeImage")?.toAsset(),
            smallImage = json.optJSONObject("smallImage")?.toAsset(),
            buttons = json.optJSONArray("buttons")?.toButtons().orEmpty(),
            startedAtEpochSeconds = json.optLongOrNull("startedAt"),
            endsAtEpochSeconds = json.optLongOrNull("endsAt"),
            priorityOverride = json.optIntOrNull("priorityOverride"),
            accountStatus = json.optString("accountStatus").takeIf(String::isNotBlank),
            enabled = json.optBoolean("enabled", true),
        )
        return profile.takeIf { AppPresenceProfilePolicy.validate(it, requireFutureEnd = false).isEmpty() }
    }

    fun save(context: Context, packageName: String, profile: AppPresenceProfile): Boolean {
        if (!isValidPackageName(packageName)) return false
        val errors = AppPresenceProfilePolicy.validate(profile)
        if (errors.isNotEmpty()) return false
        val json = JSONObject()
            .put("schemaVersion", 2)
            .put("activityLabel", profile.activityLabel.trim())
            .put("details", profile.details.trim())
            .put("state", profile.state.orEmpty().trim())
            .put("activityType", profile.activityType?.wireValue ?: JSONObject.NULL)
            .put("streamUrl", profile.streamUrl.orEmpty())
            .put("largeImage", profile.largeImage?.toJson() ?: JSONObject.NULL)
            .put("smallImage", profile.smallImage?.toJson() ?: JSONObject.NULL)
            .put("buttons", JSONArray().apply { profile.buttons.forEach { put(it.toJson()) } })
            .put("startedAt", profile.startedAtEpochSeconds ?: JSONObject.NULL)
            .put("endsAt", profile.endsAtEpochSeconds ?: JSONObject.NULL)
            .put("priorityOverride", profile.priorityOverride ?: JSONObject.NULL)
            .put("accountStatus", profile.accountStatus.orEmpty())
            .put("enabled", profile.enabled)
            .put("updatedAt", System.currentTimeMillis())
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(packageName), json.toString()).commit()
    }

    fun remove(context: Context, packageName: String): Boolean {
        if (!isValidPackageName(packageName)) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(packageName)).commit()
    }

    fun hasProfile(context: Context, packageName: String): Boolean = get(context, packageName) != null

    fun isValidPackageName(packageName: String): Boolean =
        packageName.length in 3..255 && packageNamePattern.matches(packageName)

    private fun key(packageName: String): String = "$KEY_PREFIX$packageName"

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0L }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name)

    private fun PresenceAsset.toJson(): JSONObject = JSONObject()
        .put("image", image).put("tooltip", tooltip ?: JSONObject.NULL).put("clickUrl", clickUrl ?: JSONObject.NULL)

    private fun JSONObject.toAsset(): PresenceAsset? {
        val image = optString("image").takeIf(String::isNotBlank) ?: return null
        return PresenceAsset(
            image = image,
            tooltip = optString("tooltip").takeIf(String::isNotBlank),
            clickUrl = optString("clickUrl").takeIf(String::isNotBlank),
        )
    }

    private fun PresenceButton.toJson(): JSONObject = JSONObject().put("label", label).put("url", url)

    private fun JSONArray.toButtons(): List<PresenceButton> = buildList {
        for (index in 0 until minOf(length(), 2)) {
            val item = optJSONObject(index) ?: continue
            val label = item.optString("label")
            val url = item.optString("url")
            if (label.isNotBlank() && url.isNotBlank()) add(PresenceButton(label, url))
        }
    }
}
