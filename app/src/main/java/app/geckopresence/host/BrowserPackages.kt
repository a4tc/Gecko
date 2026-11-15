package app.geckopresence.host

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Browser packages supported by the native address-bar observer. */
object BrowserPackages {
    val known = linkedMapOf(
        "org.mozilla.firefox" to "Firefox",
        "org.mozilla.fennec_fdroid" to "Firefox (F-Droid)",
        "org.torproject.torbrowser" to "Tor Browser",
        "com.android.chrome" to "Chrome",
        "com.vivaldi.browser" to "Vivaldi",
        "com.brave.browser" to "Brave",
        "com.opera.browser" to "Opera",
        "com.microsoft.emmx" to "Edge",
        "com.sec.android.app.sbrowser" to "Samsung Internet",
        "com.duckduckgo.mobile.android" to "DuckDuckGo",
    )

    fun installed(context: Context): List<Pair<String, String>> = known.mapNotNull { (packageName, label) ->
        val exists = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
        }.isSuccess
        if (exists) packageName to label else null
    }
}
