package app.geckopresence.host

import android.content.Context

object PresencePriorityPolicy {
    const val PREFS = "gpb_settings"

    private val keys = mapOf(
        "website" to "priority_website",
        "android-app" to "priority_android_app",
        "media-session" to "priority_media_session",
    )
    private val defaults = mapOf(
        "website" to 85,
        "android-app" to 95,
        "media-session" to 98,
    )

    fun get(context: Context, sourceType: String): Int {
        val key = keys[sourceType] ?: return 100
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key, defaults[sourceType] ?: 80).coerceIn(1, 99)
    }

    fun set(context: Context, sourceType: String, value: Int) {
        val key = keys[sourceType] ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key, value.coerceIn(1, 99)).apply()
    }
}
