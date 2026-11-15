package org.json
class JSONObject(private val raw: String) {
    fun optBoolean(name: String, fallback: Boolean = false): Boolean {
        val match = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(true|false)").find(raw) ?: return fallback
        return match.groupValues[1] == "true"
    }
    fun optJSONObject(name: String): JSONObject? {
        val start = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\{").find(raw) ?: return null
        val open = raw.indexOf('{', start.range.first)
        var depth = 0
        for (index in open until raw.length) {
            when (raw[index]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return JSONObject(raw.substring(open, index + 1)) }
            }
        }
        return null
    }
    fun optString(name: String): String {
        val match = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(raw) ?: return ""
        return match.groupValues[1]
    }
}
