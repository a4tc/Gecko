package app.geckopresence.host

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PresencePreset(
    val id: String,
    val label: String,
    val name: String,
    val details: String,
    val state: String?,
    val savedAtEpochMillis: Long,
    val advancedFieldsJson: String = "{}",
)

/** Small local-only preset store; no account or network sync is involved. */
object PresencePresetStore {
    private const val PREFS = "gpb_library"
    private const val KEY_PRESETS = "presence_presets"
    private const val MAX_PRESETS = 40

    fun list(context: Context): List<PresencePreset> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PRESETS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
                val details = item.optString("details")
                add(
                    PresencePreset(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        label = item.optString("label").ifBlank { name },
                        name = name,
                        details = details,
                        state = item.optString("state").takeIf(String::isNotBlank),
                        savedAtEpochMillis = item.optLong("savedAt", 0L),
                        advancedFieldsJson = item.optString("advanced", "{}").takeIf { it.length <= 4096 } ?: "{}",
                    ),
                )
            }
        }.sortedByDescending(PresencePreset::savedAtEpochMillis)
    }

    fun save(
        context: Context,
        label: String,
        name: String,
        details: String,
        state: String?,
        advancedFieldsJson: String = "{}",
    ): PresencePreset {
        val cleanLabel = label.trim().take(48).ifBlank { name.trim().take(48) }.ifBlank { "Untitled preset" }
        val cleanName = name.trim().take(80).ifBlank { "Gecko Presence" }
        val cleanDetails = details.trim().take(120)
        val safeAdvancedFields = runCatching {
            require(advancedFieldsJson.length <= 4096)
            JSONObject(advancedFieldsJson).toString()
        }.getOrDefault("{}")
        val preset = PresencePreset(
            id = UUID.randomUUID().toString(),
            label = cleanLabel,
            name = cleanName,
            details = cleanDetails,
            state = state?.trim()?.take(120)?.ifBlank { null },
            savedAtEpochMillis = System.currentTimeMillis(),
            advancedFieldsJson = safeAdvancedFields,
        )
        val values = (list(context).filterNot { it.label.equals(cleanLabel, ignoreCase = true) } + preset)
            .take(MAX_PRESETS)
        persist(context, values)
        return preset
    }

    fun duplicate(context: Context, id: String): PresencePreset? {
        val original = list(context).firstOrNull { it.id == id } ?: return null
        val existing = list(context).map { it.label.lowercase() }.toSet()
        var suffix = 1
        var label: String
        do {
            val ending = if (suffix == 1) " copy" else " copy $suffix"
            label = original.label.take((48 - ending.length).coerceAtLeast(1)) + ending
            suffix++
        } while (label.lowercase() in existing && suffix <= MAX_PRESETS + 2)
        if (label.lowercase() in existing) return null
        return save(
            context = context,
            label = label,
            name = original.name,
            details = original.details,
            state = original.state,
            advancedFieldsJson = original.advancedFieldsJson,
        )
    }

    fun delete(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, values: List<PresencePreset>) {
        val array = JSONArray()
        values.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("label", preset.label)
                    .put("name", preset.name)
                    .put("details", preset.details)
                    .put("state", preset.state.orEmpty())
                    .put("savedAt", preset.savedAtEpochMillis)
                    .put("advanced", preset.advancedFieldsJson),
            )
        }
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PRESETS, array.toString()).commit(),
        ) { "Could not save the preset library" }
    }
}
