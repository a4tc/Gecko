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
                val details = item.optString("details").takeIf(String::isNotBlank) ?: continue
                add(
                    PresencePreset(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        label = item.optString("label").ifBlank { name },
                        name = name,
                        details = details,
                        state = item.optString("state").takeIf(String::isNotBlank),
                        savedAtEpochMillis = item.optLong("savedAt", 0L),
                    ),
                )
            }
        }.sortedByDescending(PresencePreset::savedAtEpochMillis)
    }

    fun save(context: Context, label: String, name: String, details: String, state: String?): PresencePreset {
        val cleanLabel = label.trim().take(48).ifBlank { name.trim().take(48) }.ifBlank { "Untitled preset" }
        val cleanName = name.trim().take(80).ifBlank { "Gecko Presence" }
        val cleanDetails = details.trim().take(120).ifBlank { "Custom activity" }
        val preset = PresencePreset(
            id = UUID.randomUUID().toString(),
            label = cleanLabel,
            name = cleanName,
            details = cleanDetails,
            state = state?.trim()?.take(120)?.ifBlank { null },
            savedAtEpochMillis = System.currentTimeMillis(),
        )
        val values = (list(context).filterNot { it.label.equals(cleanLabel, ignoreCase = true) } + preset)
            .take(MAX_PRESETS)
        persist(context, values)
        return preset
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
                    .put("savedAt", preset.savedAtEpochMillis),
            )
        }
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PRESETS, array.toString()).commit(),
        ) { "Could not save the preset library" }
    }
}
