package app.geckopresence.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.geckopresence.host.core.PresenceRules
import java.util.ArrayDeque

/**
 * Optional browser adapter. It reads only a browser's visible URL/address field and emits
 * the normalized host; it does not inspect DOM content, page titles, history, or URL paths.
 */
class BrowserAccessibilityService : AccessibilityService() {
    private var lastHost: String? = null
    private var hostStartedAtSeconds = 0L
    private var reconfigureReceiverRegistered = false

    private val reconfigureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RECONFIGURE) configureFromPreferences()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_RECONFIGURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                reconfigureReceiver,
                filter,
                PresenceEngineService.INTERNAL_BROADCAST_PERMISSION,
                null,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                reconfigureReceiver,
                filter,
                PresenceEngineService.INTERNAL_BROADCAST_PERMISSION,
                null,
            )
        }
        reconfigureReceiverRegistered = true
        configureFromPreferences()
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
            .edit()
            .putString(KEY_BROWSER_STATUS, "Accessibility service connected")
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        if (!settings.getBoolean(KEY_TRACK_BROWSER, false) ||
            !settings.getBoolean(PresenceEngineService.KEY_AUTO_SOURCES_ENABLED, true)) {
            clearWebsiteCandidate()
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val selected = settings.getStringSet(KEY_BROWSER_PACKAGES, emptySet()).orEmpty()
        if (packageName !in selected) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != packageName) {
            root.recycle()
            return
        }
        val addressText = try { findAddressFieldText(root) } finally { root.recycle() } ?: return
        val host = PresenceRules.extractPublicWebHost(addressText)
        if (host == null) {
            clearWebsiteCandidate()
            return
        }

        val nowSeconds = System.currentTimeMillis() / 1_000L
        if (host != lastHost) {
            lastHost = host
            hostStartedAtSeconds = nowSeconds
        }

        sendBroadcast(
            Intent(PresenceEngineService.ACTION_BROWSER_PRESENCE)
                .setPackage(this.packageName)
                .putExtra(PresenceEngineService.EXTRA_SOURCE, host)
                .putExtra(PresenceEngineService.EXTRA_NAME, host)
                .putExtra(PresenceEngineService.EXTRA_DETAILS, "Browsing")
                .putExtra(PresenceEngineService.EXTRA_STATE, "")
                .putExtra(PresenceEngineService.EXTRA_MEDIA_STATE, "browsing")
                .putExtra(PresenceEngineService.EXTRA_START_TIMESTAMP, hostStartedAtSeconds)
                .putExtra(PresenceEngineService.EXTRA_SOURCE_TYPE, "website")
                .putExtra(PresenceEngineService.EXTRA_PRIORITY, PresencePriorityPolicy.get(this, "website"))
                .putExtra(PresenceEngineService.EXTRA_EXPIRES_AT, System.currentTimeMillis() + WEBSITE_CANDIDATE_TTL_MS),
        )
    }

    override fun onInterrupt() {
        clearWebsiteCandidate()
    }

    override fun onDestroy() {
        clearWebsiteCandidate()
        if (reconfigureReceiverRegistered) {
            runCatching { unregisterReceiver(reconfigureReceiver) }
            reconfigureReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun configureFromPreferences(selected: Set<String> = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        .getStringSet(KEY_BROWSER_PACKAGES, emptySet()).orEmpty()) {
        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        val active = settings.getBoolean(KEY_TRACK_BROWSER, false) &&
            settings.getBoolean(PresenceEngineService.KEY_AUTO_SOURCES_ENABLED, true)
        val configuredPackages = if (active) selected else emptySet()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 450L
        info.packageNames = configuredPackages.toTypedArray()
        serviceInfo = info
    }

    /** Address fields only; never scrape generic text from browser page content. */
    private fun findAddressFieldText(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        // Caller owns and recycles the root node; children are recycled here.
        for (index in 0 until root.childCount) root.getChild(index)?.let(queue::addLast)
        val rootResourceId = root.viewIdResourceName.orEmpty().lowercase()
        var found: String? = null
        if (ADDRESS_FIELD_MARKERS.any(rootResourceId::contains)) {
            val candidate = root.text?.toString() ?: root.contentDescription?.toString()
            if (PresenceRules.extractPublicWebHost(candidate) != null) found = candidate
        }
        var inspected = 0
        try {
            while (queue.isNotEmpty() && inspected < MAX_NODES && found == null) {
                val node = queue.removeFirst()
                inspected += 1
                try {
                    val resourceId = node.viewIdResourceName.orEmpty().lowercase()
                    val addressField = ADDRESS_FIELD_MARKERS.any(resourceId::contains)
                    if (addressField) {
                        val candidate = node.text?.toString()
                            ?: node.contentDescription?.toString()
                        if (PresenceRules.extractPublicWebHost(candidate) != null) {
                            found = candidate
                        }
                    }
                    if (found == null) {
                        for (index in 0 until node.childCount) {
                            node.getChild(index)?.let(queue::addLast)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().recycle()
        }
        return found
    }

    private fun clearWebsiteCandidate() {
        if (lastHost == null) return
        lastHost = null
        hostStartedAtSeconds = 0L
        sendBroadcast(
            Intent(PresenceEngineService.ACTION_BROWSER_CLEAR)
                .setPackage(packageName)
                .putExtra(PresenceEngineService.EXTRA_SOURCE_TYPE, "website"),
        )
    }

    companion object {
        const val PREFS_SETTINGS = "gpb_settings"
        const val KEY_TRACK_BROWSER = "track_browser_urls"
        const val KEY_BROWSER_PACKAGES = "browser_packages"
        const val KEY_BROWSER_STATUS = "browser_status"
        const val ACTION_RECONFIGURE = "app.geckopresence.host.action.RECONFIGURE_BROWSER_SOURCE"

        private const val MAX_NODES = 90
        private const val WEBSITE_CANDIDATE_TTL_MS = 75_000L
        private val ADDRESS_FIELD_MARKERS = listOf(
            "url_bar", "urlbar", "url_view", "url_input", "address_bar", "addressbar",
            "omnibox", "location_bar", "toolbar_url",
        )
    }
}
