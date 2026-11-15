package app.geckopresence.host

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.geckopresence.host.core.DiscordSessionImportDecoder

/**
 * Discord web-session import. The user signs in at discord.com, then presses Import.
 * Not OAuth2. No JavaScript-to-native bridge; the fixed script below is evaluated only on user action.
 */
class DiscordLoginActivity : Activity() {
    private var webView: WebView? = null
    private var importButton: Button? = null
    private var statusView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var importInProgress = false
    private var closed = false
    private var attempt = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        if (!DiscordWebViewIsolation.isAvailable()) {
            Toast.makeText(this, "Secure sign-in unavailable: WebView isolation could not be set up.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        // Mark before any session data can exist so a killed process still gets cleaned up later.
        DiscordWebSessionCleanup.markCleanupRequired(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            filterTouchesWhenObscured = true
            setBackgroundColor(Color.rgb(16, 18, 24))
        }
        val web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !isDiscordHost(request.url.scheme, request.url.host)

                // Discord is a SPA: route changes after login do not trigger onPageFinished.
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    updateImportAvailability(url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    updateImportAvailability(url)
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                }
            }
        }
        webView = web
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        statusView = TextView(this).apply {
            text = "Sign in to Discord, wait for the app to load, then press Import."
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(statusView)

        importButton = Button(this).apply {
            text = "IMPORT SESSION TO ANDROID KEYSTORE"
            isEnabled = false
            setOnClickListener { startImport() }
        }
        root.addView(importButton)
        root.addView(Button(this).apply {
            text = "CANCEL"
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        })
        setContentView(root)
        web.loadUrl(LOGIN_URL)
    }

    private fun updateImportAvailability(url: String?) {
        if (closed || importInProgress) return
        val uri = url?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() } ?: return
        val path = uri.path.orEmpty()
        val onLoggedInRoute = isDiscordHost(uri.scheme, uri.host) &&
            path.isNotEmpty() && path != "/" &&
            !path.startsWith("/login") && !path.startsWith("/register")
        importButton?.isEnabled = onLoggedInRoute
    }

    private fun startImport() {
        if (importInProgress || closed) return
        importInProgress = true
        importButton?.isEnabled = false
        attempt = 0
        updateStatus("Reading Discord session…")
        readSession()
    }

    /** The session can appear in storage after the page has loaded, so retry for a bounded time. */
    private fun readSession() {
        val web = webView ?: return
        attempt++
        web.evaluateJavascript(READ_SCRIPT) { result ->
            if (closed) return@evaluateJavascript
            val raw = result?.trim()
            if (raw.isNullOrEmpty() || raw == "null") {
                if (attempt < MAX_ATTEMPTS) {
                    handler.postDelayed({ if (!closed) readSession() }, RETRY_DELAY_MS)
                } else {
                    fail("No Discord session found after ${MAX_ATTEMPTS * RETRY_DELAY_MS / 1000}s. Make sure sign-in finished and Discord fully loaded, then press Import again.")
                }
                return@evaluateJavascript
            }
            val token = DiscordSessionImportDecoder.decodeJavascriptResult(raw)
            if (token == null) {
                fail("A session value was found but its format was rejected. Nothing was stored.")
                return@evaluateJavascript
            }
            try {
                DiscordCredentialVault.save(this, token)
                setResult(RESULT_OK)
                finish()
            } catch (_: IllegalArgumentException) {
                fail("The credential format was rejected. Nothing was stored.")
            } catch (_: Exception) {
                fail("Android Keystore could not store the credential. Nothing was imported.")
            }
        }
    }

    private fun fail(message: String) {
        importInProgress = false
        updateStatus(message)
        updateImportAvailability(webView?.url)
    }

    private fun updateStatus(message: String) {
        statusView?.text = message
    }

    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        importButton = null
        statusView = null
        if (DiscordWebViewIsolation.isAvailable()) DiscordWebSessionCleanup.clear(applicationContext)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isDiscordHost(scheme: String?, host: String?): Boolean =
        scheme == "https" && (host == "discord.com" || host == "www.discord.com")

    private companion object {
        const val LOGIN_URL = "https://discord.com/login"
        const val MAX_ATTEMPTS = 16
        const val RETRY_DELAY_MS = 500L

        // Reads the session via a throwaway iframe (the page's own window.localStorage may be removed)
        // and returns it base64-encoded; DiscordSessionImportDecoder decodes and validates it.
        const val READ_SCRIPT =
            "(function(){try{var f=document.createElement('iframe');f.style.display='none';" +
                "document.body.appendChild(f);var s=null;" +
                "try{s=f.contentWindow.localStorage.getItem('token')}catch(e){}" +
                "document.body.removeChild(f);if(!s)return null;" +
                "try{var p=JSON.parse(s);if(typeof p==='string')s=p}catch(e){}" +
                "return btoa(s)}catch(e){return null}})()"
    }
}
