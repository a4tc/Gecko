package app.geckopresence.host

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/** Tracks and clears the temporary first-party WebView login profile across process death. */
object DiscordWebViewIsolation {
    @Volatile
    private var isolated = false

    fun initialize(): Boolean {
        isolated = false
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return false
        return runCatching {
            WebView.setDataDirectorySuffix("gecko_discord_session_import")
            isolated = true
            true
        }.getOrDefault(false)
    }

    fun isAvailable(): Boolean = isolated
}

object DiscordWebSessionCleanup {
    private const val PREFS = "gpb_web_session_cleanup"
    private const val KEY_CLEANUP_REQUIRED = "cleanup_required"

    fun hasPendingCleanup(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CLEANUP_REQUIRED, false)

    fun markCleanupRequired(context: Context): Boolean =
        preferences(context).edit().putBoolean(KEY_CLEANUP_REQUIRED, true).commit()

    fun clear(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val webStorageCleared = runCatching { WebStorage.getInstance().deleteAllData() }.isSuccess
        try {
            CookieManager.getInstance().removeAllCookies {
                val cookiesFlushed = runCatching { CookieManager.getInstance().flush() }.isSuccess
                val cleanupSucceeded = webStorageCleared && cookiesFlushed &&
                    preferences(context).edit().putBoolean(KEY_CLEANUP_REQUIRED, false).commit()
                onComplete?.invoke(cleanupSucceeded)
            }
        } catch (_: Exception) {
            // Keep the pending marker set so a later process start retries cleanup.
            onComplete?.invoke(false)
        }
    }

    /** Called from Application.onCreate before any app Activity can create a WebView. */
    fun clearStaleSessionAtStartup(context: Context) {
        if (!DiscordWebViewIsolation.isAvailable() || !hasPendingCleanup(context)) return
        clear(context)
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
