package app.geckopresence.host

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import android.widget.Toast
import app.geckopresence.host.core.DiscordSessionImportDecoder

/** Explicit, first-party Discord session import. No JavaScript bridge or external redirect is used. */
class DiscordLoginActivity : Activity() {
    private var discordWebView: WebView? = null
    private var importButton: Button? = null
    private var statusView: TextView? = null
    private var importInProgress = false
    private var cleanupStarted = false
    private var activityDestroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WebView.setWebContentsDebuggingEnabled(false)
        if (!DiscordWebViewIsolation.isAvailable()) {
            Toast.makeText(this, "Isolated Discord sign-in is unavailable on this Android version", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        if (!DiscordWebSessionCleanup.markCleanupRequired(this)) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 18, 24))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "DISCORD SESSION IMPORT"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        statusView = TextView(this).apply {
            text = "Sign in to Discord below. Import is manual and will only work on a signed-in Discord app page."
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusView)

        val webView = WebView(this)
        discordWebView = webView
        webView.setBackgroundColor(Color.WHITE)
        webView.isSaveEnabled = false
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            saveFormData = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            databaseEnabled = false
            setGeolocationEnabled(false)
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, false, false)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                handler?.cancel()
                updateStatus("Discord TLS certificate validation failed. Sign-in was stopped.")
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Discord may use third-party challenge frames during sign-in. Keep those isolated
                // inside the WebView, but never allow them to replace the top-level Discord page.
                if (!request.isForMainFrame) return false
                val allowed = isAllowedDiscordUrl(request.url)
                if (!allowed) {
                    updateStatus("External top-level navigation was blocked. Continue only on discord.com.")
                }
                return !allowed
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val allowed = isAllowedDiscordUrl(Uri.parse(url))
                if (!allowed) updateStatus("External navigation was blocked. Continue only on discord.com.")
                return !allowed
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                importButton?.isEnabled = false
                if (!isAllowedDiscordUrl(Uri.parse(url))) {
                    view.stopLoading()
                    updateStatus("Navigation blocked: only the Discord HTTPS origin is allowed.")
                } else {
                    updateStatus("Discord page loading…")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                val ready = isDiscordAppPage(Uri.parse(url))
                importButton?.isEnabled = ready && !importInProgress
                updateStatus(
                    if (ready) "Discord app page detected. Press IMPORT SESSION only when you are ready."
                    else "Sign in to your Discord account. The import button activates after Discord opens the app page.",
                )
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        importButton = Button(this).apply {
            text = "IMPORT SESSION TO ANDROID KEYSTORE"
            isEnabled = false
            setOnClickListener { importSessionFromDiscordPage() }
        }
        root.addView(importButton)
        root.addView(Button(this).apply {
            text = "CANCEL AND CLEAR WEB SESSION"
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                clearWebSession { finish() }
            }
        })
        setContentView(root)
        DiscordWebSessionCleanup.clear(this) { cleared ->
            if (!activityDestroyed && !isFinishing && !cleanupStarted) {
                if (cleared && DiscordWebSessionCleanup.markCleanupRequired(this)) {
                    webView.loadUrl(DISCORD_LOGIN_URL)
                } else {
                    updateStatus("Could not clear an earlier sign-in session. Close Gecko and retry before importing.")
                }
            }
        }
    }

    private fun importSessionFromDiscordPage() {
        val view = discordWebView ?: return
        val currentUri = runCatching { Uri.parse(view.url.orEmpty()) }.getOrNull()
        if (!isDiscordAppPage(currentUri)) {
            updateStatus("Import is allowed only after the signed-in Discord app page has loaded.")
            return
        }
        if (importInProgress) return

        importInProgress = true
        importButton?.isEnabled = false
        updateStatus("Reading the session locally and encrypting it…")
        view.evaluateJavascript(READ_SESSION_TOKEN_SCRIPT) { javascriptResult ->
            // A late JS callback must not import anything after the user cancelled or the Activity closed.
            if (activityDestroyed || isFinishing || cleanupStarted) return@evaluateJavascript
            val token = DiscordSessionImportDecoder.decodeJavascriptResult(javascriptResult)
            if (token == null) {
                importInProgress = false
                importButton?.isEnabled = true
                updateStatus("No usable signed-in session was found. Confirm the Discord login completed, then retry.")
                return@evaluateJavascript
            }
            try {
                DiscordCredentialVault.save(this, token)
                setResult(Activity.RESULT_OK)
                clearWebSession { finish() }
            } catch (_: Exception) {
                importInProgress = false
                importButton?.isEnabled = true
                updateStatus("Could not store the session in Android Keystore. No credential was imported.")
            }
        }
    }

    private fun isAllowedDiscordUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            host in setOf("discord.com", "www.discord.com") &&
            uri.port in setOf(-1, 443) && uri.userInfo == null
    }

    private fun isDiscordAppPage(uri: Uri?): Boolean {
        if (uri == null || !isAllowedDiscordUrl(uri)) return false
        val path = uri.path.orEmpty()
        return path == "/app" || path == "/channels" || path.startsWith("/channels/")
    }

    private fun updateStatus(message: String) {
        statusView?.text = message
    }

    private fun clearWebSession(onComplete: (() -> Unit)? = null) {
        if (cleanupStarted) return
        cleanupStarted = true
        val view = discordWebView
        discordWebView = null
        runCatching {
            view?.stopLoading()
            view?.loadUrl("about:blank")
            view?.clearHistory()
            view?.clearCache(true)
            view?.clearFormData()
            view?.clearSslPreferences()
            WebViewDatabase.getInstance(this).clearFormData()
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
            WebViewDatabase.getInstance(this).clearUsernamePassword()
            view?.removeAllViews()
            view?.destroy()
        }
        DiscordWebSessionCleanup.clear(this) { _ -> onComplete?.invoke() }
    }

    override fun onDestroy() {
        activityDestroyed = true
        if (!cleanupStarted) clearWebSession()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DISCORD_LOGIN_URL = "https://discord.com/login"
        private const val READ_SESSION_TOKEN_SCRIPT = """
            (() => {
              try {
                const stored = window.localStorage.getItem('token');
                if (!stored) return null;
                let value = stored;
                try { value = JSON.parse(stored); } catch (_) {}
                if (typeof value !== 'string' || value.length < 16 || value.length > 4096) return null;
                if (/\s/.test(value)) return null;
                return window.btoa(value);
              } catch (_) {
                return null;
              }
            })();
        """
    }
}
