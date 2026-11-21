package app.geckopresence.host

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import app.geckopresence.host.core.PresenceRules
import app.geckopresence.host.core.PresenceActivityMapper
import app.geckopresence.host.core.AppPresenceProfile
import app.geckopresence.host.core.RuntimeTogglePolicy
import app.geckopresence.host.core.ArtworkUploadPolicy

class MainActivity : Activity() {
    private val COLOR_BACKGROUND: Int get() = getColor(R.color.screen_bg)
    private val COLOR_PANEL: Int get() = getColor(R.color.panel_bg)
    private val COLOR_ACCENT: Int get() = getColor(R.color.signal_yellow)
    private val COLOR_TEXT: Int get() = getColor(R.color.text_primary)
    private val COLOR_TEXT_SECONDARY: Int get() = getColor(R.color.text_secondary)
    private val COLOR_MUTED: Int get() = getColor(R.color.text_muted)
    private val COLOR_LINE: Int get() = getColor(R.color.line)
    private val COLOR_DISCORD_CARD: Int get() = getColor(R.color.discord_card_bg)
    private val COLOR_CYAN: Int get() = getColor(R.color.signal_cyan)
    private val COLOR_MAGENTA: Int get() = getColor(R.color.signal_magenta)
    private val COLOR_GREEN: Int get() = getColor(R.color.signal_green)
    private val COLOR_GLASS_TOP: Int get() = getColor(R.color.panel_glass_top)

    private val settings by lazy { getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE) }
    private val runtime by lazy { getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE) }
    private lateinit var pageContent: LinearLayout
    private lateinit var navContainer: LinearLayout
    private lateinit var engineStatus: TextView
    private lateinit var discordStatus: TextView
    private lateinit var currentActivity: TextView
    private lateinit var statusMessage: TextView
    private lateinit var appSourceStatus: TextView
    private lateinit var mediaSourceStatus: TextView
    private lateinit var developerEngineStatus: TextView
    private lateinit var developerRpcStatus: TextView
    private lateinit var developerMessage: TextView
    private lateinit var developerActivity: TextView
    private lateinit var developerEventLog: TextView
    private lateinit var developerCrashStatus: TextView
    private var runtimeToggleButton: Button? = null
    private var activePage = PAGE_DASHBOARD
    private val pageHistory = java.util.ArrayDeque<String>()
    private var receiverRegistered = false
    private var artworkPreviewExecutor: java.util.concurrent.ExecutorService? = null
    @Volatile private var artworkUploadInProgress = false
    @Volatile private var artworkImportInProgress = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PresenceEngineService.ACTION_STATUS_CHANGED) return
            renderRuntimeStatus(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        buildShell()
        showPage(activePage)
    }

    override fun onDestroy() {
        artworkPreviewExecutor?.shutdownNow()
        artworkPreviewExecutor = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(PresenceEngineService.ACTION_STATUS_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (DiscordWebViewIsolation.isAvailable() && DiscordWebSessionCleanup.hasPendingCleanup(this)) {
            DiscordWebSessionCleanup.clear(this)
        }
        restoreCompletedPendingArtwork()
        showPage(activePage)
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(statusReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DISCORD_SESSION_IMPORT) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Discord session stored securely", Toast.LENGTH_SHORT).show()
            }
            showPage(PAGE_SETTINGS)
            return
        }
        if (requestCode == REQUEST_ARTWORK_PICKER) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                if (uri != null) importArtworkSelection(uri) else clearPendingArtworkSelection()
            } else {
                clearPendingArtworkSelection()
            }
            return
        }
        if (requestCode != REQUEST_EXPORT_CRASH_BUNDLE || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        val exported = runCatching {
            contentResolver.openOutputStream(destination)?.use { output ->
                CrashDiagnostics.exportBundle(this, output)
            } ?: 0
        }.getOrElse { error ->
            Toast.makeText(this, "Crash export failed: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Exported $exported diagnostic files", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Use the Android system Back gesture")
    override fun onBackPressed() {
        if (pageHistory.isNotEmpty()) {
            showPage(pageHistory.removeLast(), recordHistory = false)
        } else {
            super.onBackPressed()
        }
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setOnApplyWindowInsetsListener { view, insets ->
                val systemInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                    )
                    intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    intArrayOf(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                }
                val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.ime()).bottom
                } else 0
                view.setPadding(
                    systemInsets[0], systemInsets[1], systemInsets[2],
                    maxOf(systemInsets[3], imeBottom),
                )
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(13), dp(18), dp(13))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("GECKO / PRESENCE", 10, COLOR_ACCENT, true, mono = true))
        brand.addView(text("SIGNAL DECK", 19, COLOR_TEXT, true).apply {
            letterSpacing = -0.035f
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val version = text("ANDROID\n0.6.6", 9, COLOR_MUTED, true, mono = true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        header.addView(version)
        root.addView(header)
        root.addView(divider())

        val bodyScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        bodyScroll.addView(pageContent)
        root.addView(bodyScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_PANEL)
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        root.addView(divider())
        root.addView(navContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64),
        ))
        setContentView(root)
        buildNavigation()
    }

    private fun buildNavigation() {
        navContainer.removeAllViews()
        val destinations = listOf(
            "HOME" to PAGE_DASHBOARD,
            "SOURCES" to PAGE_SOURCES,
            "STUDIO" to PAGE_STUDIO,
            "LIBRARY" to PAGE_LIBRARY,
            "SETTINGS" to PAGE_SETTINGS,
        )
        val selectedDestination = when (activePage) {
            PAGE_AUTOMATION, PAGE_DEVELOPER -> PAGE_SETTINGS
            else -> activePage
        }
        destinations.forEach { (label, destination) ->
            val selected = selectedDestination == destination
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setBackgroundColor(if (selected) COLOR_BACKGROUND else Color.TRANSPARENT)
                contentDescription = "Open $label"
                setOnClickListener { showPage(destination) }
            }
            item.addView(View(this).apply {
                setBackgroundColor(if (selected) COLOR_ACCENT else Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
            item.addView(text(label, 9, if (selected) COLOR_ACCENT else COLOR_MUTED, true, mono = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(3))
            })
            navContainer.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }
    }

    private fun showPage(page: String, recordHistory: Boolean = true) {
        if (recordHistory && page != activePage) {
            pageHistory.addLast(activePage)
            while (pageHistory.size > 16) pageHistory.removeFirst()
        }
        activePage = page
        if (page == PAGE_SETTINGS) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        buildNavigation()
        pageContent.removeAllViews()
        when (page) {
            PAGE_DASHBOARD -> buildDashboard()
            PAGE_SOURCES -> buildSources()
            PAGE_STUDIO -> buildStudio()
            PAGE_AUTOMATION -> buildAutomation()
            PAGE_LIBRARY -> buildLibrary()
            PAGE_SETTINGS -> buildSettings()
            PAGE_DEVELOPER -> buildDeveloper()
        }
    }

    private fun buildDashboard() {
        pageContent.addView(sectionTitle("LIVE SIGNAL", "Current publisher state and active source"))
        val statusCard = card()
        engineStatus = text("ENGINE / ${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED")}", 12, COLOR_ACCENT, true, mono = true)
        discordStatus = text("GATEWAY / ${runtime.getString(PresenceEngineService.KEY_GATEWAY_STATE, "GATEWAY_DISCONNECTED")}", 12, COLOR_TEXT, true, mono = true)
        statusMessage = text(runtime.getString(PresenceEngineService.KEY_STATUS_MESSAGE, "Turn on to start local detection and connect Discord.").orEmpty(), 12, COLOR_MUTED)
        statusCard.addView(engineStatus)
        statusCard.addView(discordStatus, marginTop(8))
        statusCard.addView(statusMessage, marginTop(12))
        runtimeToggleButton = actionButton(runtimeToggleLabel(), true) {
            toggleRuntime()
        }.apply {
            minHeight = dp(58)
            textSize = 15f
            letterSpacing = 0.08f
        }
        statusCard.addView(runtimeToggleButton, marginTop(16))
        statusCard.addView(actionButton("OPEN DEVELOPER CONSOLE", false) {
            showPage(PAGE_DEVELOPER)
        }, marginTop(8))
        pageContent.addView(statusCard)

        pageContent.addView(sectionTitle("CURRENT ACTIVITY", "A local preview of the winning source"), marginTop(22))
        val previewCard = card()
        currentActivity = text(runtime.getString(PresenceEngineService.KEY_PRESENCE_PREVIEW, "No active source").orEmpty(), 14, COLOR_TEXT, true)
        previewCard.addView(text("DISCORD ACTIVITY PREVIEW", 10, COLOR_ACCENT, true, mono = true))
        previewCard.addView(currentActivity, marginTop(12))
        previewCard.addView(text("The card reflects the current winning source. Discord delivery status is shown separately above.", 11, COLOR_MUTED), marginTop(12))
        pageContent.addView(previewCard)

        pageContent.addView(sectionTitle("SOURCE HEALTH", "Permission and detector status"), marginTop(22))
        val sourceCard = card()
        appSourceStatus = text(runtime.getString(PresenceEngineService.KEY_APP_TRACKER_STATUS, "App detector not started").orEmpty(), 12, COLOR_TEXT)
        mediaSourceStatus = text(runtime.getString(PresenceEngineService.KEY_MEDIA_TRACKER_STATUS, "Media detector not started").orEmpty(), 12, COLOR_TEXT)
        sourceCard.addView(text("ANDROID APP DETECTOR", 10, COLOR_ACCENT, true, mono = true))
        sourceCard.addView(appSourceStatus, marginTop(6))
        sourceCard.addView(text("MEDIA SESSION DETECTOR", 10, COLOR_ACCENT, true, mono = true), marginTop(14))
        sourceCard.addView(mediaSourceStatus, marginTop(6))
        pageContent.addView(sourceCard)
        renderDashboardStatus()
    }

    private fun buildSources() {
        pageContent.addView(sectionTitle("SOURCE CATALOG", "Choose which native signals can publish activity"))

        if (!BuildConfig.AUTOMATION_SOURCES_ENABLED) {
            val secureCard = card()
            secureCard.addView(text("MINIMAL-PERMISSION BUILD", 11, COLOR_ACCENT, true, mono = true))
            secureCard.addView(text("Automatic app, media, and browser monitoring is excluded from this build. No Usage Access, Notification Listener, or Accessibility Service is declared. Use Presence Studio for manual activity profiles.", 12, COLOR_MUTED), marginTop(8))
            pageContent.addView(secureCard)
            val customOnly = card()
            customOnly.addView(text("CUSTOM SOURCE", 11, COLOR_ACCENT, true, mono = true))
            customOnly.addView(text("Create a user-authored activity in Presence Studio. It runs as a manual override until released.", 12, COLOR_MUTED), marginTop(8))
            customOnly.addView(actionButton("OPEN PRESENCE STUDIO", false) { showPage(PAGE_STUDIO) }, marginTop(8))
            pageContent.addView(customOnly, marginTop(12))
            return
        }

        val appCard = card()
        appCard.addView(text("ANDROID APPLICATIONS", 11, COLOR_ACCENT, true, mono = true))
        appCard.addView(text("Uses Usage Access to identify only apps you explicitly select. It does not read their screen contents or notifications.", 12, COLOR_MUTED), marginTop(8))
        addSwitch(appCard, "Track selected Android apps", AppPresencePolicy.KEY_TRACK_APPS, false) {
            updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
        }
        appCard.addView(actionButton("SELECT APPLICATIONS", false) { showAppPicker() }, marginTop(8))
        appCard.addView(actionButton("OPEN USAGE ACCESS SETTINGS", false) {
            openSystemSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }, marginTop(8))
        pageContent.addView(appCard)

        val mediaCard = card()
        mediaCard.addView(text("MEDIA SESSIONS", 11, COLOR_ACCENT, true, mono = true))
        mediaCard.addView(text("Reads the active playback session from supported media apps. Notification text is never read or stored.", 12, COLOR_MUTED), marginTop(8))
        addSwitch(mediaCard, "Track active media sessions", PresenceEngineService.KEY_TRACK_MEDIA_SESSIONS, false) {
            updateSources(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
        }
        addSwitch(mediaCard, "Share track / video titles", MediaSessionPresenceTracker.KEY_SHARE_MEDIA_TITLES, false) {
            updateSources(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
        }
        mediaCard.addView(actionButton("OPEN NOTIFICATION ACCESS SETTINGS", false) {
            openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }, marginTop(8))
        pageContent.addView(mediaCard, marginTop(12))

        val browserCard = card()
        browserCard.addView(text("BROWSER / WEBSITE", 11, COLOR_ACCENT, true, mono = true))
        browserCard.addView(text("Optional Accessibility Service. It reads only the visible address field from browsers you select, then keeps the hostname. It has no DOM access and does not collect paths, queries, page titles, or history.", 12, COLOR_MUTED), marginTop(8))
        addSwitch(browserCard, "Detect browser website host", BrowserAccessibilityService.KEY_TRACK_BROWSER, false) { enabled ->
            if (enabled) {
                if (settings.getStringSet(BrowserAccessibilityService.KEY_BROWSER_PACKAGES, emptySet()).orEmpty().isEmpty()) {
                    showBrowserPicker()
                    return@addSwitch
                }
                openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            } else {
                sendBroadcast(Intent(PresenceEngineService.ACTION_BROWSER_CLEAR)
                    .setPackage(packageName)
                    .putExtra(PresenceEngineService.EXTRA_SOURCE_TYPE, "website"), PresenceEngineService.INTERNAL_BROADCAST_PERMISSION)
            }
            sendBroadcast(Intent(BrowserAccessibilityService.ACTION_RECONFIGURE).setPackage(packageName), PresenceEngineService.INTERNAL_BROADCAST_PERMISSION)
        }
        browserCard.addView(actionButton("SELECT SUPPORTED BROWSERS", false) { showBrowserPicker() }, marginTop(8))
        browserCard.addView(actionButton("OPEN ACCESSIBILITY SETTINGS", false) {
            openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }, marginTop(8))
        browserCard.addView(text("Service: ${accessibilityServiceStatus()}", 11, COLOR_TEXT), marginTop(10))
        pageContent.addView(browserCard, marginTop(12))

        val policyCard = card()
        policyCard.addView(text("SOURCE FILTERS", 11, COLOR_ACCENT, true, mono = true))
        policyCard.addView(text("Banking, finance, identity, health, messaging, email, system and password-manager apps are excluded from app tracking. Sensitive website hosts are excluded by the domain filter.", 12, COLOR_MUTED))
        pageContent.addView(policyCard, marginTop(12))

        val custom = card()
        custom.addView(text("CUSTOM SOURCE", 11, COLOR_ACCENT, true, mono = true))
        custom.addView(text("Create a user-authored activity in Presence Studio. It runs as a manual override until released.", 12, COLOR_MUTED), marginTop(8))
        custom.addView(actionButton("OPEN PRESENCE STUDIO", false) { showPage(PAGE_STUDIO) }, marginTop(8))
        pageContent.addView(custom, marginTop(12))
    }

    private fun buildStudio() {
        pageContent.addView(sectionTitle("PRESENCE STUDIO", "Create a manual Rich Presence override"))
        val editor = card()
        editor.addView(text("LOCAL DISCORD PREVIEW", 10, COLOR_ACCENT, true, mono = true))
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = outline(COLOR_LINE, COLOR_DISCORD_CARD)
        }
        val previewType = text("PLAYING", 10, COLOR_CYAN, true, mono = true)
        val previewActivityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previewArtworkStack = FrameLayout(this).apply {
            background = outline(COLOR_CYAN, COLOR_PANEL)
            clipToOutline = true
        }
        val previewLargeArtwork = ImageView(this).apply {
            contentDescription = "Large Discord artwork preview"
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        previewArtworkStack.addView(previewLargeArtwork, FrameLayout.LayoutParams(dp(104), dp(104)))
        val previewSmallArtwork = ImageView(this).apply {
            contentDescription = "Small Discord artwork overlay preview"
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_gallery)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_DISCORD_CARD)
                setStroke(dp(2), COLOR_CYAN)
            }
            clipToOutline = true
            visibility = View.GONE
        }
        previewArtworkStack.addView(
            previewSmallArtwork,
            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(3)
                bottomMargin = dp(3)
            },
        )
        previewActivityRow.addView(previewArtworkStack, LinearLayout.LayoutParams(dp(104), dp(104)))
        val previewCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val previewName = text("Playing Gecko Presence", 14, COLOR_TEXT, true)
        previewCopy.addView(previewType)
        previewCopy.addView(previewName, marginTop(5))
        val previewDetails = text("No details", 12, COLOR_TEXT_SECONDARY)
        previewCopy.addView(previewDetails, marginTop(4))
        val previewState = text("Android · Native", 11, COLOR_MUTED)
        previewCopy.addView(previewState, marginTop(4))
        previewActivityRow.addView(previewCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewCard.addView(previewActivityRow, marginTop(12))
        val previewTimestamp = text("STARTS NOW", 10, COLOR_MUTED, mono = true)
        previewCard.addView(previewTimestamp, marginTop(12))
        val previewButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previewButtonOne = text("", 10, COLOR_TEXT, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(38)
            background = outline(COLOR_CYAN, COLOR_DISCORD_CARD)
            visibility = View.GONE
        }
        val previewButtonTwo = text("", 10, COLOR_TEXT, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(38)
            background = outline(COLOR_CYAN, COLOR_DISCORD_CARD)
            visibility = View.GONE
        }
        previewButtons.addView(previewButtonOne, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        previewButtons.addView(previewButtonTwo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        previewCard.addView(previewButtons, marginTop(12))
        val previewAccountStatus = text("ACCOUNT · ONLINE", 10, COLOR_MUTED, true, mono = true)
        previewCard.addView(previewAccountStatus, marginTop(12))
        editor.addView(previewCard, marginTop(10))

        var updatePreview: () -> Unit = {}
        var scheduleStudioSync: () -> Unit = {}
        val name = textField("Activity name", "studio_draft_name", "Gecko Presence", 80)
        val details = textField("Details", "studio_draft_details", "Working on something", 120)
        val state = textField("State", "studio_draft_state", "Android · Native", 120)
        val activityType = optionSpinner(
            "Activity type",
            "studio_draft_type",
            listOf("Playing", "Streaming", "Listening", "Watching", "Custom Status", "Competing"),
            0,
            onSelectionChanged = { updatePreview(); scheduleStudioSync() },
        )
        val accountStatus = optionSpinner(
            "Account status",
            "studio_draft_account_status_index",
            listOf("Online", "Idle", "Do Not Disturb", "Invisible", "Offline"),
            0,
            onSelectionChanged = { updatePreview(); scheduleStudioSync() },
        )
        val uriInput = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val streamUrl = textField("Twitch or YouTube URL (Streaming only)", "studio_draft_stream_url", "", 512, uriInput)
        val largeImage = textField("Large image: HTTPS URL, mp: reference, or asset ID", "studio_draft_large_image", "", 300, uriInput)
        val largeTooltip = textField("Large image tooltip", "studio_draft_large_tooltip", "", 128)
        val smallImage = textField("Small image: HTTPS URL, mp: reference, or asset ID", "studio_draft_small_image", "", 300, uriInput)
        val smallTooltip = textField("Small image tooltip", "studio_draft_small_tooltip", "", 128)
        val button1Label = textField("Button 1 label", "studio_draft_button1_label", "", 32)
        val button1Url = textField("Button 1 HTTPS URL", "studio_draft_button1_url", "", 512, uriInput)
        val button2Label = textField("Button 2 label", "studio_draft_button2_label", "", 32)
        val button2Url = textField("Button 2 HTTPS URL", "studio_draft_button2_url", "", 512, uriInput)
        val startsAt = textField("Start timestamp · Unix seconds (optional, defaults to now)", "studio_draft_start_timestamp", "", 16).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val endsAt = textField("End timestamp · Unix seconds (optional)", "studio_draft_end_timestamp", "", 16).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        updatePreview = {
            val activityLabel = name.text.toString().trim()
            val activityDetails = details.text.toString().trim()
            val selectedType = app.geckopresence.host.core.PresenceActivityType.entries
                .getOrElse(activityType.selectedItemPosition) { app.geckopresence.host.core.PresenceActivityType.PLAYING }
            previewType.text = selectedType.displayLabel.uppercase()
            val preview = app.geckopresence.host.core.PresenceActivityDisplayFormatter.format(
                type = selectedType,
                name = activityLabel.ifBlank { "Gecko Presence" },
                details = activityDetails,
                state = PresenceActivityMapper.stateLine(state.text.toString()),
            )
            previewName.text = preview.headline
            previewDetails.text = preview.details ?: "No additional details"
            previewDetails.visibility = if (preview.details == null) View.GONE else View.VISIBLE
            previewState.text = preview.state.orEmpty()
            previewState.visibility = if (preview.state == null) View.GONE else View.VISIBLE
            previewTimestamp.text = startsAt.text.toString().trim().takeIf(String::isNotEmpty)
                ?.let { "STARTS · $it" } ?: "STARTS NOW"
            previewButtonOne.text = button1Label.text.toString().trim()
            previewButtonTwo.text = button2Label.text.toString().trim()
            previewButtonOne.visibility = if (previewButtonOne.text.isNullOrBlank()) View.GONE else View.VISIBLE
            previewButtonTwo.visibility = if (previewButtonTwo.text.isNullOrBlank()) View.GONE else View.VISIBLE
            previewSmallArtwork.visibility = if (smallImage.text.toString().isBlank()) View.GONE else View.VISIBLE
            val accountLabel = when (accountStatus.selectedItemPosition) {
                1 -> "IDLE"
                2 -> "DND"
                3 -> "INVISIBLE"
                4 -> "OFFLINE"
                else -> "ONLINE"
            }
            previewAccountStatus.text = "ACCOUNT · $accountLabel"
            loadArtworkPreview(previewLargeArtwork, largeImage.text.toString())
            loadArtworkPreview(previewSmallArtwork, smallImage.text.toString())
        }
        listOf(
            name, details, state, streamUrl, largeImage, largeTooltip, smallImage, smallTooltip,
            button1Label, button1Url, button2Label, button2Url, startsAt, endsAt,
        ).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updatePreview()
                    scheduleStudioSync()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        editor.addView(text("ACTIVITY NAME", 10, COLOR_MUTED, true, mono = true), marginTop(16))
        editor.addView(name, marginTop(6))
        editor.addView(text("DETAILS", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        editor.addView(details, marginTop(6))
        editor.addView(text("STATE", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        editor.addView(state, marginTop(6))
        editor.addView(text("ACTIVITY TYPE", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        editor.addView(activityType, marginTop(6))
        editor.addView(text("ACCOUNT STATUS", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        editor.addView(accountStatus, marginTop(6))
        editor.addView(text("STREAMING URL", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        editor.addView(streamUrl, marginTop(6))
        editor.addView(text("ARTWORK · LARGE IMAGE", 10, COLOR_ACCENT, true, mono = true), marginTop(16))
        editor.addView(largeImage, marginTop(6))
        editor.addView(actionButton("PICK LARGE ARTWORK FROM GALLERY", false) { launchArtworkPicker(KEY_STUDIO_DRAFT_LARGE_IMAGE) }, marginTop(5))
        editor.addView(largeTooltip, marginTop(6))
        editor.addView(text("This is the text Discord shows when someone hovers over the large artwork. It is not a help tooltip for this editor.", 10, COLOR_MUTED), marginTop(4))
        editor.addView(text("ARTWORK · SMALL IMAGE", 10, COLOR_ACCENT, true, mono = true), marginTop(12))
        editor.addView(smallImage, marginTop(6))
        editor.addView(actionButton("PICK SMALL ARTWORK FROM GALLERY", false) { launchArtworkPicker(KEY_STUDIO_DRAFT_SMALL_IMAGE) }, marginTop(5))
        editor.addView(smallTooltip, marginTop(6))
        editor.addView(text("This text is displayed by Discord when someone hovers over the small artwork.", 10, COLOR_MUTED), marginTop(4))
        editor.addView(text("RICH PRESENCE BUTTONS · MAX 2", 10, COLOR_ACCENT, true, mono = true), marginTop(16))
        editor.addView(button1Label, marginTop(6))
        editor.addView(button1Url, marginTop(6))
        editor.addView(button2Label, marginTop(6))
        editor.addView(button2Url, marginTop(6))
        editor.addView(text("TIMESTAMPS · UNIX SECONDS", 10, COLOR_MUTED, true, mono = true), marginTop(16))
        editor.addView(startsAt, marginTop(6))
        editor.addView(endsAt, marginTop(6))
        editor.addView(text("DISCORD ACTIVITY DISPLAY", 10, COLOR_ACCENT, true, mono = true), marginTop(16))
        editor.addView(text("Discord formats the headline by activity type: Playing/Listening/Watching/Competing use Activity name; Streaming uses Details; Custom Status uses State. The other fields remain separate activity metadata. The Application ID associates this activity with your registered Discord application and its assets.", 11, COLOR_MUTED), marginTop(5))
        editor.addView(text("TRANSPORT", 10, COLOR_ACCENT, true, mono = true), marginTop(12))
        editor.addView(text("Once the manual override is active, field changes are validated and sent automatically after a short pause. External artwork URLs must be publicly reachable by Discord.", 11, COLOR_MUTED), marginTop(5))
        val applyStudio: (Boolean) -> Boolean = applyStudio@{ showConfirmation ->
            val currentName = name.text.toString().trim()
            val currentDetails = details.text.toString().trim()
            val currentState = state.text.toString().trim().takeIf(String::isNotEmpty)
            if (currentName.isEmpty()) {
                if (showConfirmation) Toast.makeText(this, "Activity name is required", Toast.LENGTH_SHORT).show()
                return@applyStudio false
            }
            val startRaw = startsAt.text.toString().trim()
            val startValue = startRaw.takeIf(String::isNotEmpty)?.toLongOrNull()
            if (startRaw.isNotEmpty() && startValue == null) {
                if (showConfirmation) Toast.makeText(this, "Start timestamp must be Unix seconds", Toast.LENGTH_LONG).show()
                return@applyStudio false
            }
            val manualAlreadyActive = settings.getBoolean(PresenceEngineService.KEY_MANUAL_ACTIVE, false)
            val existingStart = settings.getLong(PresenceEngineService.KEY_MANUAL_START, 0L)
            val resolvedStart = startValue ?: if (manualAlreadyActive && existingStart > 0L) {
                existingStart
            } else {
                System.currentTimeMillis() / 1_000L
            }
            val endRaw = endsAt.text.toString().trim()
            val endValue = endRaw.takeIf(String::isNotEmpty)?.toLongOrNull()
            if (endRaw.isNotEmpty() && endValue == null) {
                if (showConfirmation) Toast.makeText(this, "End timestamp must be Unix seconds", Toast.LENGTH_LONG).show()
                return@applyStudio false
            }
            fun assetFrom(image: EditText, tooltip: EditText): app.geckopresence.host.core.PresenceAsset? {
                val reference = image.text.toString().trim().takeIf(String::isNotEmpty) ?: return null
                return app.geckopresence.host.core.PresenceAsset(
                    reference,
                    tooltip.text.toString().trim().takeIf(String::isNotEmpty),
                )
            }
            val buttons = buildList {
                val label1 = button1Label.text.toString().trim()
                val url1 = button1Url.text.toString().trim()
                if (label1.isNotEmpty() || url1.isNotEmpty()) add(app.geckopresence.host.core.PresenceButton(label1, url1))
                val label2 = button2Label.text.toString().trim()
                val url2 = button2Url.text.toString().trim()
                if (label2.isNotEmpty() || url2.isNotEmpty()) add(app.geckopresence.host.core.PresenceButton(label2, url2))
            }
            val types = app.geckopresence.host.core.PresenceActivityType.entries
            val selectedType = types.getOrElse(activityType.selectedItemPosition) { app.geckopresence.host.core.PresenceActivityType.PLAYING }
            val candidate = app.geckopresence.host.core.PresenceActivity(
                name = currentName,
                type = selectedType,
                details = currentDetails,
                state = currentState,
                startedAtEpochSeconds = resolvedStart,
                endsAtEpochSeconds = endValue,
                largeImage = assetFrom(largeImage, largeTooltip),
                smallImage = assetFrom(smallImage, smallTooltip),
                buttons = buttons,
                streamUrl = streamUrl.text.toString().trim().takeIf(String::isNotEmpty),
            )
            val validationErrors = candidate.validate()
            if (validationErrors.isNotEmpty()) {
                if (showConfirmation) Toast.makeText(this, validationErrors.first(), Toast.LENGTH_LONG).show()
                return@applyStudio false
            }
            val accountStatuses = listOf("online", "idle", "dnd", "invisible", "offline")
            val selectedStatus = accountStatuses.getOrElse(accountStatus.selectedItemPosition) { "online" }
            settings.edit()
                .putBoolean(PresenceEngineService.KEY_MANUAL_ACTIVE, true)
                .putString(PresenceEngineService.KEY_MANUAL_NAME, currentName.take(80))
                .putString(PresenceEngineService.KEY_MANUAL_DETAILS, currentDetails.take(120))
                .putString(PresenceEngineService.KEY_MANUAL_STATE, currentState.orEmpty().take(120))
                .putInt(PresenceEngineService.KEY_MANUAL_ACTIVITY_TYPE, selectedType.wireValue)
                .putLong(PresenceEngineService.KEY_MANUAL_START, resolvedStart)
                .putString(PresenceEngineService.KEY_MANUAL_END, endValue?.toString().orEmpty())
                .putString(PresenceEngineService.KEY_MANUAL_STREAM_URL, streamUrl.text.toString().trim())
                .putString(PresenceEngineService.KEY_MANUAL_LARGE_IMAGE, largeImage.text.toString().trim())
                .putString(PresenceEngineService.KEY_MANUAL_LARGE_TOOLTIP, largeTooltip.text.toString().trim())
                .putString(PresenceEngineService.KEY_MANUAL_SMALL_IMAGE, smallImage.text.toString().trim())
                .putString(PresenceEngineService.KEY_MANUAL_SMALL_TOOLTIP, smallTooltip.text.toString().trim())
                .putString(PresenceEngineService.KEY_MANUAL_BUTTON1_LABEL, button1Label.text.toString().trim())
                .putString(PresenceEngineService.KEY_MANUAL_BUTTON1_URL, button1Url.text.toString().trim())
                .putString(PresenceEngineService.KEY_MANUAL_BUTTON2_LABEL, button2Label.text.toString().trim())
                .putString(PresenceEngineService.KEY_MANUAL_BUTTON2_URL, button2Url.text.toString().trim())
                .putString(PresenceEngineService.KEY_PRESENCE_STATUS, selectedStatus)
                .apply()
            val engineRunning = runtime.getString(
                PresenceEngineService.KEY_ENGINE_STATE,
                PresenceEngineService.STATE_STOPPED,
            ) == PresenceEngineService.STATE_RUNNING
            if (engineRunning) {
                startServiceAction(PresenceEngineService.ACTION_APPLY_MANUAL)
            } else if (showConfirmation) {
                Toast.makeText(this, "Presence saved. Press TURN ON on Home to publish it.", Toast.LENGTH_LONG).show()
            }
            if (showConfirmation && engineRunning) Toast.makeText(this, "Manual Rich Presence applied", Toast.LENGTH_SHORT).show()
            true
        }
        val studioSyncHandler = Handler(Looper.getMainLooper())
        val studioSyncTask = Runnable {
            if (settings.getBoolean(PresenceEngineService.KEY_MANUAL_ACTIVE, false)) {
                applyStudio(false)
            }
        }
        scheduleStudioSync = {
            if (settings.getBoolean(PresenceEngineService.KEY_MANUAL_ACTIVE, false)) {
                studioSyncHandler.removeCallbacks(studioSyncTask)
                studioSyncHandler.postDelayed(studioSyncTask, STUDIO_SYNC_DEBOUNCE_MS)
            }
        }
        editor.addView(actionButton("APPLY MANUAL OVERRIDE", true) { applyStudio(true) }, marginTop(16))
        editor.addView(actionButton("RELEASE MANUAL OVERRIDE", false) {
            studioSyncHandler.removeCallbacks(studioSyncTask)
            settings.edit().putBoolean(PresenceEngineService.KEY_MANUAL_ACTIVE, false).remove(PresenceEngineService.KEY_MANUAL_START).apply()
            if (runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, PresenceEngineService.STATE_STOPPED) == PresenceEngineService.STATE_RUNNING) {
                startServiceAction(PresenceEngineService.ACTION_CLEAR_MANUAL)
            }
            Toast.makeText(this, "Manual override released", Toast.LENGTH_SHORT).show()
        }, marginTop(8))
        editor.addView(actionButton("SAVE TO LIBRARY", false) { showSavePresetDialog(name.text.toString(), details.text.toString(), state.text.toString()) }, marginTop(8))
        pageContent.addView(editor)
        updatePreview()
        scheduleStudioSync()
        buildAppProfileEditor()
    }

    private fun buildAppProfileEditor() {
        pageContent.addView(
            sectionTitle("PER-APP PROFILES", "Give each selected Android app its own complete Rich Presence profile"),
            marginTop(22),
        )
        val profileCard = card()
        profileCard.addView(text("AUTOMATIC APP PRESENCE", 10, COLOR_ACCENT, true, mono = true))
        profileCard.addView(
            text(
                "Profiles apply only when the matching package wins Android app detection. The package name stays local. Global manual override and other sources remain separate.",
                11, COLOR_MUTED,
            ), marginTop(6),
        )

        val selectedPackages = settings.getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet()).orEmpty()
        val availableApps = UsageAppTracker.selectableApps(this)
            .filter { it.packageName in selectedPackages }
            .sortedBy { it.label.lowercase() }
        var selectedPackage = settings.getString(KEY_STUDIO_PROFILE_PACKAGE, "").orEmpty()
        if (availableApps.none { it.packageName == selectedPackage }) selectedPackage = ""
        val selectedApp = availableApps.firstOrNull { it.packageName == selectedPackage }
        val selectedLabel = text(
            selectedApp?.let { "${it.label}\n${it.packageName}" } ?: "No selected app",
            12, COLOR_TEXT, true, mono = selectedApp == null,
        )
        profileCard.addView(text("TARGET APPLICATION", 10, COLOR_MUTED, true, mono = true), marginTop(14))
        profileCard.addView(selectedLabel, marginTop(6))
        profileCard.addView(actionButton("CHOOSE SELECTED APP", false) {
            if (availableApps.isEmpty()) {
                AlertDialog.Builder(this).setTitle("No app selected")
                    .setMessage("Select applications in Sources first. Only allow-listed apps can have automatic profiles.")
                    .setNegativeButton("CLOSE", null)
                    .setPositiveButton("OPEN SOURCES") { _, _ -> showPage(PAGE_SOURCES) }.show()
                return@actionButton
            }
            val items = availableApps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Choose app profile").setItems(items) { _, index ->
                val packageName = availableApps[index].packageName
                settings.edit().putString(KEY_STUDIO_PROFILE_PACKAGE, packageName).apply()
                showPage(PAGE_STUDIO)
            }.setNegativeButton("CANCEL", null).show()
        }, marginTop(8))

        if (selectedApp == null) {
            profileCard.addView(text("Select apps in Sources, then choose one here to create its profile.", 11, COLOR_MUTED), marginTop(12))
            pageContent.addView(profileCard, marginTop(12))
            return
        }

        val stored = AppPresenceProfileStore.get(this, selectedPackage)
        fun field(field: String, label: String, fallback: String, max: Int, input: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) =
            textField(label, appProfileDraftKey(selectedPackage, field), fallback, max, input)
        val uriInput = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val numberInput = InputType.TYPE_CLASS_NUMBER
        val labelField = field("label", "Activity name", stored?.activityLabel ?: selectedApp.label.take(40), 40)
        val detailsField = field("details", "Details", stored?.details ?: PresenceRules.defaultAppDetails(selectedPackage, selectedApp.label), 120)
        val stateField = field("state", "State (optional)", stored?.state.orEmpty(), 120)
        val typeKey = appProfileDraftKey(selectedPackage, "type")
        if (!settings.contains(typeKey)) settings.edit().putInt(typeKey, stored?.activityType?.wireValue ?: 0).apply()
        val typeSpinner = optionSpinner(
            "Activity type", typeKey,
            listOf("Playing", "Streaming", "Listening", "Watching", "Custom Status", "Competing"),
            stored?.activityType?.wireValue ?: 0,
        )
        val streamField = field("streamUrl", "Twitch / YouTube URL (Streaming only)", stored?.streamUrl.orEmpty(), 512, uriInput)
        val largeField = field("largeImage", "Large image URL / asset key", stored?.largeImage?.image.orEmpty(), 300, uriInput)
        val largeTooltip = field("largeTooltip", "Large image tooltip", stored?.largeImage?.tooltip.orEmpty(), 128)
        val smallField = field("smallImage", "Small image URL / asset key", stored?.smallImage?.image.orEmpty(), 300, uriInput)
        val smallTooltip = field("smallTooltip", "Small image tooltip", stored?.smallImage?.tooltip.orEmpty(), 128)
        val button1Label = field("button1Label", "Button 1 label", stored?.buttons?.getOrNull(0)?.label.orEmpty(), 32)
        val button1Url = field("button1Url", "Button 1 HTTPS URL", stored?.buttons?.getOrNull(0)?.url.orEmpty(), 512, uriInput)
        val button2Label = field("button2Label", "Button 2 label", stored?.buttons?.getOrNull(1)?.label.orEmpty(), 32)
        val button2Url = field("button2Url", "Button 2 HTTPS URL", stored?.buttons?.getOrNull(1)?.url.orEmpty(), 512, uriInput)
        val startField = field("startedAt", "Start timestamp · Unix seconds (optional)", stored?.startedAtEpochSeconds?.toString().orEmpty(), 16, numberInput)
        val endField = field("endsAt", "End timestamp · Unix seconds (optional)", stored?.endsAtEpochSeconds?.toString().orEmpty(), 16, numberInput)
        val priorityField = field("priority", "Priority override · 0–1000 (blank = source default)", stored?.priorityOverride?.toString().orEmpty(), 4, numberInput)
        val statusKey = appProfileDraftKey(selectedPackage, "accountStatus")
        val statusIndex = when (stored?.accountStatus) {
            "online" -> 1; "idle" -> 2; "dnd" -> 3; "invisible" -> 4; "offline" -> 5; else -> 0
        }
        if (!settings.contains(statusKey)) settings.edit().putInt(statusKey, statusIndex).apply()
        val statusSpinner = optionSpinner(
            "Account status override", statusKey,
            listOf("Use global setting", "Online", "Idle", "Do Not Disturb", "Invisible", "Offline"),
            statusIndex,
        )
        val enabledSwitch = Switch(this).apply {
            text = "Enable this app profile"
            setTextColor(COLOR_TEXT)
            textSize = 13f
            isChecked = stored?.enabled ?: true
        }

        profileCard.addView(text("ACTIVITY NAME", 10, COLOR_MUTED, true, mono = true), marginTop(14))
        profileCard.addView(labelField, marginTop(6))
        profileCard.addView(text("DETAILS", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        profileCard.addView(detailsField, marginTop(6))
        profileCard.addView(text("STATE · OPTIONAL", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        profileCard.addView(stateField, marginTop(6))
        profileCard.addView(text("ACTIVITY TYPE", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        profileCard.addView(typeSpinner, marginTop(6))
        profileCard.addView(text("STREAMING URL", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        profileCard.addView(streamField, marginTop(6))
        profileCard.addView(text("LARGE ARTWORK", 10, COLOR_ACCENT, true, mono = true), marginTop(14))
        profileCard.addView(largeField, marginTop(6))
        profileCard.addView(actionButton("PICK / UPLOAD LARGE ARTWORK", false) { launchArtworkPicker(appProfileDraftKey(selectedPackage, "largeImage")) }, marginTop(5))
        profileCard.addView(largeTooltip, marginTop(6))
        profileCard.addView(text("SMALL ARTWORK", 10, COLOR_ACCENT, true, mono = true), marginTop(12))
        profileCard.addView(smallField, marginTop(6))
        profileCard.addView(actionButton("PICK / UPLOAD SMALL ARTWORK", false) { launchArtworkPicker(appProfileDraftKey(selectedPackage, "smallImage")) }, marginTop(5))
        profileCard.addView(smallTooltip, marginTop(6))
        profileCard.addView(text("BUTTONS · MAXIMUM TWO", 10, COLOR_ACCENT, true, mono = true), marginTop(14))
        profileCard.addView(button1Label, marginTop(6))
        profileCard.addView(button1Url, marginTop(6))
        profileCard.addView(button2Label, marginTop(6))
        profileCard.addView(button2Url, marginTop(6))
        profileCard.addView(text("TIMESTAMPS & PRIORITY", 10, COLOR_MUTED, true, mono = true), marginTop(14))
        profileCard.addView(startField, marginTop(6))
        profileCard.addView(endField, marginTop(6))
        profileCard.addView(priorityField, marginTop(6))
        profileCard.addView(text("ACCOUNT STATUS OVERRIDE", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        profileCard.addView(statusSpinner, marginTop(6))
        profileCard.addView(enabledSwitch, marginTop(10))
        profileCard.addView(text("Image uploads are optional and public when sent to Imgur. External image URLs must be publicly reachable by Discord. Blank timestamps use the app-detection start time.", 10, COLOR_MUTED), marginTop(8))

        profileCard.addView(actionButton("SAVE ADVANCED APP PROFILE", true) {
            fun optionalLong(field: EditText, label: String): Long? {
                val raw = field.text.toString().trim()
                if (raw.isEmpty()) return null
                return raw.toLongOrNull()?.takeIf { it > 0L } ?: throw IllegalArgumentException("$label must be a positive Unix timestamp")
            }
            fun asset(image: EditText, tooltip: EditText): app.geckopresence.host.core.PresenceAsset? {
                val reference = image.text.toString().trim().takeIf(String::isNotEmpty) ?: return null
                return app.geckopresence.host.core.PresenceAsset(reference, tooltip.text.toString().trim().takeIf(String::isNotEmpty))
            }
            val candidate = try {
                val priorityRaw = priorityField.text.toString().trim()
                val priority = if (priorityRaw.isEmpty()) null else priorityRaw.toIntOrNull()
                    ?: throw IllegalArgumentException("Priority must be an integer between 0 and 1000")
                app.geckopresence.host.core.AppPresenceProfile(
                    activityLabel = labelField.text.toString().trim(),
                    details = detailsField.text.toString().trim(),
                    state = stateField.text.toString().trim().takeIf(String::isNotEmpty),
                    activityType = app.geckopresence.host.core.PresenceActivityType.entries.getOrElse(typeSpinner.selectedItemPosition) { app.geckopresence.host.core.PresenceActivityType.PLAYING },
                    streamUrl = streamField.text.toString().trim().takeIf(String::isNotEmpty),
                    largeImage = asset(largeField, largeTooltip),
                    smallImage = asset(smallField, smallTooltip),
                    buttons = buildList {
                        val l1 = button1Label.text.toString().trim(); val u1 = button1Url.text.toString().trim()
                        if (l1.isNotEmpty() || u1.isNotEmpty()) add(app.geckopresence.host.core.PresenceButton(l1, u1))
                        val l2 = button2Label.text.toString().trim(); val u2 = button2Url.text.toString().trim()
                        if (l2.isNotEmpty() || u2.isNotEmpty()) add(app.geckopresence.host.core.PresenceButton(l2, u2))
                    },
                    startedAtEpochSeconds = optionalLong(startField, "Start timestamp"),
                    endsAtEpochSeconds = optionalLong(endField, "End timestamp"),
                    priorityOverride = priority,
                    accountStatus = when (statusSpinner.selectedItemPosition) {
                        1 -> "online"; 2 -> "idle"; 3 -> "dnd"; 4 -> "invisible"; 5 -> "offline"; else -> null
                    },
                    enabled = enabledSwitch.isChecked,
                )
            } catch (error: IllegalArgumentException) {
                Toast.makeText(this, error.message ?: "Invalid profile", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            val errors = app.geckopresence.host.core.AppPresenceProfilePolicy.validate(candidate)
            if (errors.isNotEmpty()) {
                Toast.makeText(this, errors.first(), Toast.LENGTH_LONG).show()
                return@actionButton
            }
            if (!AppPresenceProfileStore.save(this, selectedPackage, candidate)) {
                Toast.makeText(this, "Could not save this app profile", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
            Toast.makeText(this, "Advanced profile saved for ${selectedApp.label}", Toast.LENGTH_SHORT).show()
            showPage(PAGE_STUDIO)
        }, marginTop(16))
        profileCard.addView(actionButton("RESET APP PROFILE", false) {
            AppPresenceProfileStore.remove(this, selectedPackage)
            settings.edit().remove(typeKey).apply()
            listOf("label", "details", "state", "type", "streamUrl", "largeImage", "largeTooltip", "smallImage", "smallTooltip", "button1Label", "button1Url", "button2Label", "button2Url", "startedAt", "endsAt", "priority", "accountStatus").forEach {
                settings.edit().remove(appProfileDraftKey(selectedPackage, it)).apply()
            }
            updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
            Toast.makeText(this, "Default profile restored for ${selectedApp.label}", Toast.LENGTH_SHORT).show()
            showPage(PAGE_STUDIO)
        }, marginTop(8))
        profileCard.addView(text(if (stored == null) "No custom profile saved · automatic defaults are active." else "Profile saved · ${if (stored.enabled) "enabled" else "disabled"} · type ${stored.activityType?.displayLabel ?: "Playing"} · status ${stored.accountStatus ?: "global"}.", 10, COLOR_MUTED), marginTop(10))
        pageContent.addView(profileCard, marginTop(12))
    }

    private fun appProfileDraftKey(packageName: String, field: String): String =
        "studio_app_profile_${packageName}_$field"

    private fun isAllowedArtworkTarget(targetKey: String): Boolean {
        if (targetKey == KEY_STUDIO_DRAFT_LARGE_IMAGE ||
            targetKey == KEY_STUDIO_DRAFT_SMALL_IMAGE
        ) return true

        val trackedPackages = settings
            .getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet())
            .orEmpty()

        return trackedPackages.any { packageName ->
            targetKey == appProfileDraftKey(packageName, "largeImage") ||
                targetKey == appProfileDraftKey(packageName, "smallImage")
        }
    }

    private fun buildAutomation() {
        pageContent.addView(sectionTitle("AUTOMATION", "Source arbitration and global behavior"))
        val rules = card()
        rules.addView(text("AUTOMATIC SOURCE ENGINE", 11, COLOR_ACCENT, true, mono = true))
        addSwitch(rules, "Allow automatic source detection", PresenceEngineService.KEY_AUTO_SOURCES_ENABLED, true) { enabled ->
            updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
            updateSources(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
            if (!enabled) {
                sendBroadcast(Intent(PresenceEngineService.ACTION_BROWSER_CLEAR)
                    .setPackage(packageName)
                    .putExtra(PresenceEngineService.EXTRA_SOURCE_TYPE, "website"), PresenceEngineService.INTERNAL_BROADCAST_PERMISSION)
            }
            sendBroadcast(Intent(BrowserAccessibilityService.ACTION_RECONFIGURE).setPackage(packageName), PresenceEngineService.INTERNAL_BROADCAST_PERMISSION)
        }
        rules.addView(text("Manual override", 12, COLOR_TEXT, true), marginTop(12))
        rules.addView(text("Priority 100 · stays active until released", 11, COLOR_MUTED), marginTop(4))
        rules.addView(text("Automatic source priorities", 10, COLOR_ACCENT, true, mono = true), marginTop(16))
        addPriorityRow(rules, "Media sessions", "media-session")
        addPriorityRow(rules, "Android applications", "android-app")
        addPriorityRow(rules, "Website host", "website")
        pageContent.addView(rules)

        val behavior = card()
        behavior.addView(text("CONFLICT RESOLUTION", 11, COLOR_ACCENT, true, mono = true))
        behavior.addView(text("Only the highest-priority unexpired source is published. When a source clears or expires, the next valid candidate becomes active. Browser candidates expire if address-bar updates stop.", 12, COLOR_MUTED))
        pageContent.addView(behavior, marginTop(12))
    }

    private fun addPriorityRow(parent: LinearLayout, label: String, sourceType: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val labelView = text(label, 12, COLOR_TEXT, true)
        row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val priority = PresencePriorityPolicy.get(this, sourceType)
        row.addView(text("$priority", 12, COLOR_ACCENT, true, mono = true))
        row.addView(actionButton("SET", false) {
            val values = arrayOf("70", "80", "85", "90", "95", "98", "99")
            AlertDialog.Builder(this)
                .setTitle("$label priority")
                .setItems(values) { _, which ->
                    PresencePriorityPolicy.set(this, sourceType, values[which].toInt())
                    showPage(PAGE_AUTOMATION)
                }.show()
        }, LinearLayout.LayoutParams(dp(84), dp(48)).apply { marginStart = dp(10) })
        parent.addView(row)
        parent.addView(divider())
    }

    private fun buildLibrary() {
        pageContent.addView(sectionTitle("LIBRARY", "Saved activity presets and artwork"))
        buildArtworkGallerySection()
        pageContent.addView(actionButton("SAVE CURRENT STUDIO DRAFT", true) {
            val name = settings.getString(KEY_STUDIO_DRAFT_NAME, "Gecko Presence").orEmpty()
            val details = settings.getString(KEY_STUDIO_DRAFT_DETAILS, "Custom activity").orEmpty()
            val state = settings.getString(KEY_STUDIO_DRAFT_STATE, "").orEmpty()
            showSavePresetDialog(name, details, state)
        })
        val presets = PresencePresetStore.list(this)
        if (presets.isEmpty()) {
            val empty = card()
            empty.addView(text("No saved presets", 15, COLOR_TEXT, true))
            empty.addView(text("Create a presence in Studio and save it here for reuse.", 12, COLOR_MUTED), marginTop(6))
            pageContent.addView(empty, marginTop(12))
            return
        }
        presets.forEach { preset ->
            val entry = card()
            entry.addView(text(preset.label, 14, COLOR_TEXT, true))
            entry.addView(text("PRESET ID · ${preset.id}", 9, COLOR_MUTED, mono = true), marginTop(5))
            entry.addView(actionButton("COPY PRESET ID", false) { copyText(preset.id, "Preset ID copied") }, marginTop(4))
            val presetArtworkRef = runCatching {
                org.json.JSONObject(preset.advancedFieldsJson).optString(KEY_STUDIO_DRAFT_LARGE_IMAGE)
            }.getOrDefault("")
            val presetArtworkPreview = ImageView(this).apply {
                contentDescription = "${preset.label} artwork preview"
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackground(outline(COLOR_LINE, COLOR_PANEL))
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
            entry.addView(presetArtworkPreview, LinearLayout.LayoutParams(dp(112), dp(112)).apply { topMargin = dp(8) })
            loadArtworkPreview(presetArtworkPreview, presetArtworkRef)
            entry.addView(text("${preset.name}\n${preset.details}${preset.state?.let { "\n$it" }.orEmpty()}", 11, COLOR_MUTED), marginTop(6))
            entry.addView(actionButton("DUPLICATE PRESET", false) {
                PresencePresetStore.duplicate(this, preset.id)
                showPage(PAGE_LIBRARY)
            }, marginTop(8))
            entry.addView(actionButton("LOAD INTO STUDIO", true) {
                settings.edit()
                    .putString(KEY_STUDIO_DRAFT_NAME, preset.name)
                    .putString(KEY_STUDIO_DRAFT_DETAILS, preset.details)
                    .putString(KEY_STUDIO_DRAFT_STATE, preset.state.orEmpty())
                    .apply()
                applyAdvancedStudioDraft(preset.advancedFieldsJson)
                activePage = PAGE_STUDIO
                showPage(PAGE_STUDIO)
            }, marginTop(10))
            entry.addView(actionButton("DELETE PRESET", false) {
                AlertDialog.Builder(this)
                    .setTitle("Delete preset?")
                    .setMessage(preset.label)
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("DELETE") { _, _ ->
                        PresencePresetStore.delete(this, preset.id)
                        showPage(PAGE_LIBRARY)
                    }.show()
            }, marginTop(8))
            pageContent.addView(entry, marginTop(10))
        }
    }

    private fun buildDeveloper() {
        pageContent.addView(sectionTitle("DEVELOPER CONSOLE", "Discord Gateway · source runtime · diagnostics"))

        val runtimeCard = card()
        runtimeCard.addView(text("RUNTIME STATE", 10, COLOR_ACCENT, true, mono = true))
        developerEngineStatus = text("ENGINE / ${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED").orEmpty()}", 12, COLOR_TEXT, true, mono = true)
        developerRpcStatus = text("GATEWAY / ${runtime.getString(PresenceEngineService.KEY_GATEWAY_STATE, "GATEWAY_DISCONNECTED").orEmpty()}", 12, COLOR_TEXT, true, mono = true)
        developerMessage = text(runtime.getString(PresenceEngineService.KEY_STATUS_MESSAGE, "Local engine has not been started.").orEmpty(), 11, COLOR_MUTED)
        runtimeCard.addView(developerEngineStatus, marginTop(8))
        runtimeCard.addView(developerRpcStatus, marginTop(5))
        runtimeCard.addView(developerMessage, marginTop(10))
        runtimeCard.addView(
            text("Runtime is controlled from Home with one Turn On / Turn Off control. This console is diagnostics-only so the engine and Gateway cannot drift into separate states.", 11, COLOR_MUTED),
            marginTop(12),
        )
        pageContent.addView(runtimeCard)

        val activityCard = card()
        activityCard.addView(text("WINNING ACTIVITY PAYLOAD", 10, COLOR_ACCENT, true, mono = true))
        developerActivity = text(runtime.getString(PresenceEngineService.KEY_PRESENCE_PREVIEW, "No active source").orEmpty(), 12, COLOR_TEXT, true, mono = true)
        activityCard.addView(developerActivity, marginTop(8))
        activityCard.addView(text("Local preview is available while Gateway is disconnected. A preview does not imply Discord accepted an update.", 10, COLOR_MUTED), marginTop(8))
        pageContent.addView(activityCard, marginTop(12))

        val identityCard = card()
        identityCard.addView(text("BUILD / TRANSPORT IDENTITY", 10, COLOR_ACCENT, true, mono = true))
        identityCard.addView(text(
            "PACKAGE / $packageName\nANDROID / ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nTRANSPORT / Ktor 2.3.13 Gateway WebSocket\nNATIVE DISCORD SDK / REMOVED\nAPPLICATION ID / ${if (BuildConfig.DISCORD_APP_ID > 0L) BuildConfig.DISCORD_APP_ID else "NOT SET"}",
            10, COLOR_TEXT, mono = true,
        ), marginTop(8))
        pageContent.addView(identityCard, marginTop(12))

        val events = card()
        events.addView(text("ENGINE EVENT LOG", 10, COLOR_ACCENT, true, mono = true))
        developerEventLog = text("No events recorded", 10, COLOR_MUTED, mono = true)
        events.addView(developerEventLog, marginTop(8))
        events.addView(actionButton("COPY DIAGNOSTICS + EVENT LOG", false) { copyDiagnostics() }, marginTop(10))
        pageContent.addView(events, marginTop(12))

        val crashCard = card()
        crashCard.addView(text("APP / PROCESS EXIT DIAGNOSTICS", 10, COLOR_ACCENT, true, mono = true))
        developerCrashStatus = text(CrashDiagnostics.summary(this), 10, COLOR_TEXT, mono = true)
        crashCard.addView(developerCrashStatus, marginTop(8))
        crashCard.addView(actionButton("COPY LATEST CRASH REPORT", false) {
            val report = CrashDiagnostics.latestReportText(this)
            if (report.isNullOrBlank()) {
                Toast.makeText(this, "No crash report recorded", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Gecko Presence crash report", report))
                Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show()
            }
        }, marginTop(10))
        crashCard.addView(actionButton("SHARE LATEST CRASH REPORT", false) {
            val report = CrashDiagnostics.latestReportText(this)
            if (report.isNullOrBlank()) {
                Toast.makeText(this, "No crash report recorded", Toast.LENGTH_SHORT).show()
            } else {
                val share = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Gecko Presence crash report")
                    .putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(share, "Share crash report"))
            }
        }, marginTop(8))
        crashCard.addView(actionButton("EXPORT REDACTED CRASH REPORTS (TXT ONLY)", false) {
            if (CrashDiagnostics.exportableFileCount(this) == 0) {
                Toast.makeText(this, "No crash files are available to export", Toast.LENGTH_SHORT).show()
            } else {
                val create = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "GeckoPresence-Crash-Diagnostics.zip")
                startActivityForResult(create, REQUEST_EXPORT_CRASH_BUNDLE)
            }
        }, marginTop(8))
        pageContent.addView(crashCard, marginTop(12))
        renderDeveloperStatus()
    }

    private fun buildArtworkUploadSettings() {
        val savedConfig = ArtworkUploadConfigStore.load(this)
        val card = card()
        card.addView(text("CLOUDFLARE ARTWORK HOSTING", 11, COLOR_ACCENT, true, mono = true))
        card.addView(
            text(
                "Connect your own Cloudflare Worker and R2 bucket. Gecko sends uploads directly to your Worker; your Cloudflare account credentials never enter the app. Uploaded R2 URLs are public to anyone who has the link. The private upload token is encrypted with Android Keystore.",
                11, COLOR_MUTED,
            ),
            marginTop(7),
        )

        val workerUrl = EditText(this).apply {
            hint = "Worker URL · https://your-worker.workers.dev"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(savedConfig?.uploadEndpoint?.removeSuffix("/v1/upload").orEmpty())
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            background = outline(COLOR_LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48)
        }
        card.addView(workerUrl, marginTop(10))

        val assetBase = EditText(this).apply {
            hint = "Public R2 asset domain · https://assets.example.com"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(savedConfig?.assetBaseUrl.orEmpty())
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            background = outline(COLOR_LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48)
        }
        card.addView(assetBase, marginTop(8))

        val token = EditText(this).apply {
            hint = if (ArtworkUploadCredentialVault.hasCredential(this@MainActivity)) {
                "Token saved securely · enter a new token to rotate"
            } else {
                "Private Worker upload token · 64-character secret"
            }
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            filters = arrayOf(android.text.InputFilter.LengthFilter(256))
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            background = outline(COLOR_LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48)
        }
        card.addView(token, marginTop(8))

        card.addView(actionButton("SAVE WORKER CONFIGURATION", true) {
            val endpoint = ArtworkUploadPolicy.normalizeWorkerEndpoint(workerUrl.text.toString())
            val publicOrigin = ArtworkUploadPolicy.normalizeAssetBaseUrl(assetBase.text.toString())
            val newToken = token.text.toString().trim()
            if (endpoint == null || publicOrigin == null) {
                Toast.makeText(this, "Enter a valid HTTPS Worker URL and public asset origin", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            if (newToken.isNotEmpty() && !ArtworkUploadPolicy.isValidUploadToken(newToken)) {
                Toast.makeText(this, "Worker token must contain 32–256 printable characters without spaces", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            val storedTokenIsValid = runCatching {
                ArtworkUploadCredentialVault.loadForUpload(this)
            }.getOrNull()?.let { ArtworkUploadPolicy.isValidUploadToken(it) } == true
            if (newToken.isEmpty() && !storedTokenIsValid) {
                Toast.makeText(this, "Enter your private Worker token before saving", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            try {
                ArtworkUploadConfigStore.save(this, endpoint, publicOrigin)
                if (newToken.isNotEmpty()) ArtworkUploadCredentialVault.save(this, newToken)
                token.setText("")
                Toast.makeText(this, "Worker configuration saved on this device", Toast.LENGTH_SHORT).show()
                if (BuildConfig.AUTOMATION_SOURCES_ENABLED &&
                    settings.getBoolean(ArtworkAutoPublisher.KEY_AUTO_PUBLIC_UPLOAD, false)
                ) {
                    updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
                    updateSources(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
                }
                resumePendingArtworkUpload()
                showPage(PAGE_SETTINGS)
            } catch (error: Exception) {
                Toast.makeText(this, error.message ?: "Could not save Worker configuration", Toast.LENGTH_LONG).show()
            }
        }, marginTop(8))

        card.addView(actionButton("TEST WORKER CONNECTION", false) {
            val endpoint = ArtworkUploadPolicy.normalizeWorkerEndpoint(workerUrl.text.toString())
            val publicOrigin = ArtworkUploadPolicy.normalizeAssetBaseUrl(assetBase.text.toString())
            val enteredToken = token.text.toString().trim()
            if (endpoint == null || publicOrigin == null) {
                Toast.makeText(this, "Enter a valid Worker URL and public asset origin first", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            val savedToken = if (enteredToken.isNotEmpty()) enteredToken else
                runCatching { ArtworkUploadCredentialVault.loadForUpload(this) }.getOrNull().orEmpty()
            if (!ArtworkUploadPolicy.isValidUploadToken(savedToken)) {
                Toast.makeText(this, "Enter or save your private Worker token first", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            val config = ArtworkUploadConfigStore.Config(endpoint, publicOrigin)
            Toast.makeText(this, "Checking Worker…", Toast.LENGTH_SHORT).show()
            Thread({
                val result = CloudflareArtworkUploader.verifyConnection(config, savedToken)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val message = when (result) {
                        is CloudflareArtworkUploader.Result.Success -> "Worker connection and token verified"
                        is CloudflareArtworkUploader.Result.Failure -> result.message
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }, "gecko-worker-verify").start()
        }, marginTop(6))

        card.addView(actionButton("OPEN CLOUDFLARE DASHBOARD", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com/")))
        }, marginTop(6))

        card.addView(actionButton("REMOVE WORKER CONFIGURATION", false) {
            AlertDialog.Builder(this)
                .setTitle("Remove Cloudflare configuration?")
                .setMessage("This deletes the saved Worker URL, public asset domain and encrypted upload token from this device. Images already uploaded to R2 are not deleted.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("REMOVE") { _, _ ->
                    settings.edit().putBoolean(ArtworkAutoPublisher.KEY_AUTO_PUBLIC_UPLOAD, false).apply()
                    runCatching { ArtworkUploadCredentialVault.clear(this) }
                    runCatching { ArtworkUploadConfigStore.clear(this) }
                    if (BuildConfig.AUTOMATION_SOURCES_ENABLED) {
                        updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
                        updateSources(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
                    }
                    Toast.makeText(this, "Worker configuration removed", Toast.LENGTH_SHORT).show()
                    showPage(PAGE_SETTINGS)
                }
                .show()
        }, marginTop(6))

        card.addView(divider(), marginTop(16))
        card.addView(text("STANDING PUBLIC-UPLOAD CONSENT", 10, COLOR_ACCENT, true, mono = true), marginTop(14))
        card.addView(
            text(
                "When enabled, Gecko may automatically upload detected media covers and selected-app icons to your Worker. Those R2 objects are publicly reachable by URL. This setting stays OFF until you explicitly enable it, and you can revoke it at any time.",
                11, COLOR_MUTED,
            ),
            marginTop(6),
        )
        val autoUpload = Switch(this).apply {
            text = "Automatically host detected artwork in my R2 bucket"
            textSize = 13f
            setTextColor(COLOR_TEXT)
            minHeight = dp(52)
            isChecked = settings.getBoolean(ArtworkAutoPublisher.KEY_AUTO_PUBLIC_UPLOAD, false)
        }
        autoUpload.setOnCheckedChangeListener { button, checked ->
            if (!checked) {
                settings.edit().putBoolean(ArtworkAutoPublisher.KEY_AUTO_PUBLIC_UPLOAD, false).apply()
                if (BuildConfig.AUTOMATION_SOURCES_ENABLED) {
                    updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
                    updateSources(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
                }
                return@setOnCheckedChangeListener
            }
            val storedTokenIsValid = runCatching {
                ArtworkUploadCredentialVault.loadForUpload(this)
            }.getOrNull()?.let { ArtworkUploadPolicy.isValidUploadToken(it) } == true
            if (ArtworkUploadConfigStore.load(this) == null || !storedTokenIsValid) {
                Toast.makeText(this, "Save a valid Cloudflare Worker URL, asset domain and private token first", Toast.LENGTH_LONG).show()
                button.isChecked = false
                return@setOnCheckedChangeListener
            }
            AlertDialog.Builder(this)
                .setTitle("Allow automatic public uploads?")
                .setMessage("This is standing consent. Gecko will send newly detected media covers and selected-app icons to your Cloudflare Worker automatically. R2 objects are public to anyone who has their URL. Your Cloudflare account password and API token are never sent. Enable only if you accept public hosting.")
                .setNegativeButton("CANCEL") { _, _ -> button.isChecked = false }
                .setPositiveButton("ENABLE PUBLIC UPLOADS") { _, _ ->
                    settings.edit().putBoolean(ArtworkAutoPublisher.KEY_AUTO_PUBLIC_UPLOAD, true).apply()
                    if (BuildConfig.AUTOMATION_SOURCES_ENABLED) {
                        updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
                        updateSources(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
                    }
                    Toast.makeText(this, "Standing public-upload consent enabled", Toast.LENGTH_SHORT).show()
                }
                .setOnCancelListener { button.isChecked = false }
                .show()
        }
        card.addView(autoUpload, marginTop(4))
        pageContent.addView(card, marginTop(12))
    }

    private fun buildArtworkGallerySection() {
        val items = ArtworkGalleryStore.list(this)
        val gallery = card()
        gallery.addView(text("ARTWORK GALLERY · PRIVATE LOCAL FILES", 11, COLOR_ACCENT, true, mono = true))
        gallery.addView(text("Choose an image from Studio. Files stay in app-private storage until a per-image approval or your standing public-upload consent allows an upload to your Cloudflare Worker.", 11, COLOR_MUTED), marginTop(6))
        gallery.addView(text("${items.size} / 40 images · 64 MiB storage limit", 10, COLOR_MUTED, mono = true), marginTop(8))
        if (items.isEmpty()) {
            gallery.addView(text("No artwork stored yet.", 11, COLOR_MUTED), marginTop(8))
        } else {
            items.forEach { item ->
                gallery.addView(divider(), marginTop(10))
                gallery.addView(text(item.displayName, 13, COLOR_TEXT, true), marginTop(8))
                val thumbnail = ImageView(this).apply {
                    contentDescription = "${item.displayName} artwork thumbnail"
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackground(outline(COLOR_LINE, COLOR_PANEL))
                    setImageResource(android.R.drawable.ic_menu_gallery)
                }
                gallery.addView(thumbnail, LinearLayout.LayoutParams(dp(112), dp(112)).apply { topMargin = dp(8) })
                loadArtworkPreview(thumbnail, item.id)
                gallery.addView(text("ID · ${item.id}", 9, COLOR_MUTED, mono = true), marginTop(6))
                gallery.addView(actionButton("COPY IMAGE ID", false) {
                    copyText(item.id, "Artwork ID copied")
                }, marginTop(4))
                gallery.addView(text("${item.mimeType} · ${item.byteCount / 1024} KiB\n${item.remoteUrl ?: "LOCAL ONLY · NOT UPLOADED"}", 10, COLOR_MUTED, mono = true), marginTop(4))
                if (item.remoteUrl != null) {
                    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    actions.addView(actionButton("USE LARGE", false) {
                        settings.edit().putString("studio_draft_large_image", item.remoteUrl).apply()
                        showPage(PAGE_STUDIO)
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
                    actions.addView(actionButton("USE SMALL", false) {
                        settings.edit().putString("studio_draft_small_image", item.remoteUrl).apply()
                        showPage(PAGE_STUDIO)
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
                    gallery.addView(actions, marginTop(6))
                } else {
                    gallery.addView(actionButton("UPLOAD TO CLOUDFLARE", false) {
                        clearPendingArtworkSelection()
                        confirmArtworkUpload(item)
                    }, marginTop(6))
                }
                gallery.addView(actionButton("DELETE ARTWORK", false) {
                    AlertDialog.Builder(this)
                        .setTitle("Delete artwork?")
                        .setMessage("This removes the local copy and gallery entry. An already uploaded public R2 object is not deleted from your Cloudflare bucket.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("DELETE") { _, _ ->
                            ArtworkGalleryStore.delete(this, item.id)
                            showPage(PAGE_LIBRARY)
                        }.show()
                }, marginTop(6))
            }
        }
        pageContent.addView(gallery, marginTop(12))
    }

    private fun launchArtworkPicker(targetKey: String) {
        if (artworkUploadInProgress || artworkImportInProgress) {
            Toast.makeText(this, "An artwork operation is already running", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAllowedArtworkTarget(targetKey)) {
            Toast.makeText(this, "Invalid Studio artwork destination", Toast.LENGTH_SHORT).show()
            return
        }
        // Persist a logical target, not an EditText reference that can disappear when
        // the Activity is recreated or the user visits Settings to configure Imgur.
        settings.edit()
            .putString(KEY_PENDING_ARTWORK_TARGET, targetKey)
            .remove(KEY_PENDING_ARTWORK_ID)
            .commit()
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivityForResult(picker, REQUEST_ARTWORK_PICKER) }
            .onFailure {
                clearPendingArtworkSelection()
                Toast.makeText(this, "Could not open the Android image picker", Toast.LENGTH_LONG).show()
            }
    }

    private fun importArtworkSelection(uri: Uri) {
        if (artworkImportInProgress) return
        artworkImportInProgress = true
        Toast.makeText(this, "Importing artwork into private storage…", Toast.LENGTH_SHORT).show()
        Thread({
            val result = ArtworkGalleryStore.importUri(this, uri)
            runOnUiThread {
                artworkImportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is ArtworkGalleryStore.ImportResult.Rejected -> {
                        clearPendingArtworkSelection()
                        Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                    }
                    is ArtworkGalleryStore.ImportResult.Added -> {
                        settings.edit().putString(KEY_PENDING_ARTWORK_ID, result.artwork.id).commit()
                        confirmArtworkUpload(result.artwork)
                    }
                    is ArtworkGalleryStore.ImportResult.Duplicate -> {
                        settings.edit().putString(KEY_PENDING_ARTWORK_ID, result.artwork.id).commit()
                        confirmArtworkUpload(result.artwork)
                    }
                }
            }
        }, "gecko-artwork-import").start()
    }

    private fun confirmArtworkUpload(artwork: ArtworkGalleryStore.Artwork) {
        artwork.remoteUrl?.let { url ->
            val targetKey = settings.getString(KEY_PENDING_ARTWORK_TARGET, "").orEmpty()
            if (isAllowedArtworkTarget(targetKey)) {
                settings.edit().putString(targetKey, url).commit()
                clearPendingArtworkSelection()
                Toast.makeText(this, "Existing artwork linked to Studio", Toast.LENGTH_SHORT).show()
                showPage(PAGE_STUDIO)
            } else {
                clearPendingArtworkSelection()
                Toast.makeText(this, "Artwork is already hosted", Toast.LENGTH_SHORT).show()
                showPage(PAGE_LIBRARY)
            }
            return
        }
        settings.edit().putString(KEY_PENDING_ARTWORK_ID, artwork.id).commit()
        if (ArtworkUploadConfigStore.load(this) == null || !ArtworkUploadCredentialVault.hasCredential(this)) {
            AlertDialog.Builder(this)
                .setTitle("Cloudflare Worker required")
                .setMessage("The image is saved locally and its Studio destination has been retained. Configure your Worker URL, public R2 asset domain and private upload token in Settings. The token is stored encrypted on this device.")
                .setPositiveButton("OPEN SETTINGS") { _, _ -> showPage(PAGE_SETTINGS) }
                .setNegativeButton("KEEP LOCAL") { _, _ -> clearPendingArtworkSelection(); showPage(PAGE_LIBRARY) }
                .show()
            return
        }
        if (settings.getBoolean(ArtworkAutoPublisher.KEY_AUTO_PUBLIC_UPLOAD, false)) {
            uploadArtwork(artwork, requireStandingConsent = true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Upload artwork publicly?")
            .setMessage("Gecko will upload '${artwork.displayName}' (${artwork.byteCount / 1024} KiB) to your Cloudflare Worker and R2 bucket. Anyone with the resulting URL may view it. The local copy remains on this device. Continue?")
            .setNegativeButton("CANCEL") { _, _ -> clearPendingArtworkSelection() }
            .setPositiveButton("UPLOAD") { _, _ -> uploadArtwork(artwork) }
            .show()
    }

    private fun uploadArtwork(
        artwork: ArtworkGalleryStore.Artwork,
        requireStandingConsent: Boolean = false,
    ) {
        if (artworkUploadInProgress || artworkImportInProgress) return
        artworkUploadInProgress = true
        settings.edit().putString(KEY_PENDING_ARTWORK_ID, artwork.id).commit()
        Toast.makeText(this, "Uploading artwork to your Cloudflare Worker…", Toast.LENGTH_SHORT).show()
        Thread({
            val consentStillEnabled = !requireStandingConsent || settings.getBoolean(
                ArtworkAutoPublisher.KEY_AUTO_PUBLIC_UPLOAD,
                false,
            )
            val config = if (consentStillEnabled) ArtworkUploadConfigStore.load(this) else null
            val token = if (config != null) runCatching {
                ArtworkUploadCredentialVault.loadForUpload(this)
            }.getOrNull() else null
            val file = if (config != null && token != null) ArtworkGalleryStore.file(this, artwork) else null
            val result = when {
                !consentStillEnabled -> CloudflareArtworkUploader.Result.Failure("Automatic public-upload consent was revoked before upload started.")
                config == null -> CloudflareArtworkUploader.Result.Failure("Cloudflare Worker settings are missing or invalid.")
                token == null -> CloudflareArtworkUploader.Result.Failure("Private Worker token is missing or cannot be decrypted. Save it again in Settings.")
                file == null -> CloudflareArtworkUploader.Result.Failure("Local artwork file is missing or failed its integrity check.")
                else -> CloudflareArtworkUploader.upload(file, config, token)
            }
            val directUrl = (result as? CloudflareArtworkUploader.Result.Success)?.directUrl
            val metadataSaved = directUrl != null && ArtworkGalleryStore.markUploaded(this, artwork.id, directUrl)
            runOnUiThread {
                artworkUploadInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CloudflareArtworkUploader.Result.Success -> {
                        if (metadataSaved && directUrl != null) {
                            val targetKey = settings.getString(KEY_PENDING_ARTWORK_TARGET, "").orEmpty()
                            if (isAllowedArtworkTarget(targetKey)) {
                                settings.edit().putString(targetKey, directUrl).commit()
                                clearPendingArtworkSelection()
                                Toast.makeText(this, "Artwork uploaded and inserted into Studio", Toast.LENGTH_LONG).show()
                                showPage(PAGE_STUDIO)
                            } else {
                                clearPendingArtworkSelection()
                                Toast.makeText(this, "Artwork uploaded and saved to Gallery", Toast.LENGTH_LONG).show()
                                showPage(PAGE_LIBRARY)
                            }
                        } else {
                            Toast.makeText(this, "Upload succeeded but local Gallery metadata could not be updated", Toast.LENGTH_LONG).show()
                        }
                    }
                    is CloudflareArtworkUploader.Result.Failure ->
                        Toast.makeText(this, "${result.message} Pending artwork selection was preserved for retry.", Toast.LENGTH_LONG).show()
                }
            }
        }, "gecko-artwork-upload").start()
    }

    private fun clearPendingArtworkSelection() {
        settings.edit()
            .remove(KEY_PENDING_ARTWORK_TARGET)
            .remove(KEY_PENDING_ARTWORK_ID)
            .apply()
    }

    private fun resumePendingArtworkUpload() {
        val pendingId = settings.getString(KEY_PENDING_ARTWORK_ID, null) ?: return
        val artwork = ArtworkGalleryStore.list(this).firstOrNull { it.id == pendingId }
        if (artwork == null) {
            clearPendingArtworkSelection()
            Toast.makeText(this, "Pending artwork was not found in Gallery", Toast.LENGTH_LONG).show()
            return
        }
        confirmArtworkUpload(artwork)
    }

    private fun restoreCompletedPendingArtwork() {
        val pendingId = settings.getString(KEY_PENDING_ARTWORK_ID, null) ?: return
        val targetKey = settings.getString(KEY_PENDING_ARTWORK_TARGET, "").orEmpty()
        if (!isAllowedArtworkTarget(targetKey)) return
        val hostedUrl = ArtworkGalleryStore.list(this)
            .firstOrNull { it.id == pendingId }
            ?.remoteUrl
            ?: return
        settings.edit()
            .putString(targetKey, hostedUrl)
            .remove(KEY_PENDING_ARTWORK_TARGET)
            .remove(KEY_PENDING_ARTWORK_ID)
            .apply()
    }

    private fun copyText(value: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Gecko Presence", value))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Local preview lookup is intentionally limited to files Gecko already imported.
     *  It never fetches arbitrary user-entered URLs or performs network I/O on the UI thread.
     */
    private fun loadArtworkPreview(target: ImageView, reference: String) {
        val key = reference.trim()
        if (target.tag == key && target.drawable != null) return
        target.tag = key
        target.setImageResource(android.R.drawable.ic_menu_gallery)
        if (key.isBlank()) return
        val artwork = ArtworkGalleryStore.list(this).firstOrNull {
            it.id == key || it.remoteUrl.equals(key, ignoreCase = false)
        } ?: return
        val file = ArtworkGalleryStore.previewFile(this, artwork) ?: return
        artworkPreviewExecutor().execute {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 480) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            })
            runOnUiThread {
                if (!isFinishing && !isDestroyed && target.tag == key && bitmap != null) {
                    target.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun artworkPreviewExecutor(): java.util.concurrent.ExecutorService {
        return artworkPreviewExecutor ?: java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "gecko-artwork-preview").apply { isDaemon = true }
        }.also { artworkPreviewExecutor = it }
    }

    private fun buildSettings() {
        pageContent.addView(sectionTitle("SETTINGS & DIAGNOSTICS", "Privacy, permissions, and runtime information"))
        buildDiscordCredentialSection()
        buildArtworkUploadSettings()

        val privacy = card()
        privacy.addView(text("PRIVACY", 11, COLOR_ACCENT, true, mono = true))
        if (BuildConfig.AUTOMATION_SOURCES_ENABLED) {
            addSwitch(privacy, "Share track / video titles", MediaSessionPresenceTracker.KEY_SHARE_MEDIA_TITLES, false) {
                updateSources(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
            }
            privacy.addView(text("Disabled by default. When enabled, titles and artist names from active media sessions may appear in your Discord activity. Notification contents and browser page content are not collected.", 11, COLOR_MUTED), marginTop(6))
            privacy.addView(text("Browser mode stores only the hostname from the visible browser address field. It excludes known sensitive hosts and expires stale candidates.", 11, COLOR_MUTED), marginTop(10))
        } else {
            privacy.addView(text("Automated app, media, and browser detection are not included in this secure build. No Usage Access, Notification Listener, or Accessibility source is registered here.", 11, COLOR_MUTED), marginTop(8))
        }
        pageContent.addView(privacy)

        val permissions = card()
        permissions.addView(text("ANDROID PERMISSIONS", 11, COLOR_ACCENT, true, mono = true))
        if (BuildConfig.AUTOMATION_SOURCES_ENABLED) {
            permissions.addView(actionButton("USAGE ACCESS", false) { openSystemSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, marginTop(8))
            permissions.addView(actionButton("NOTIFICATION ACCESS", false) { openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }, marginTop(8))
            permissions.addView(actionButton("ACCESSIBILITY SERVICES", false) { openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, marginTop(8))
        } else {
            permissions.addView(text("This variant does not request or expose the elevated permissions required by automation sources.", 11, COLOR_MUTED), marginTop(8))
        }
        pageContent.addView(permissions, marginTop(12))

        val diagnostics = card()
        diagnostics.addView(text("RUNTIME", 11, COLOR_ACCENT, true, mono = true))
        diagnostics.addView(text("Engine: ${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED")}\nDiscord Gateway: ${runtime.getString(PresenceEngineService.KEY_GATEWAY_STATE, "GATEWAY_DISCONNECTED")}\nApp source: ${runtime.getString(PresenceEngineService.KEY_APP_TRACKER_STATUS, "unknown")}\nMedia source: ${runtime.getString(PresenceEngineService.KEY_MEDIA_TRACKER_STATUS, "unknown")}\nApplication ID: ${BuildConfig.DISCORD_APP_ID}", 11, COLOR_TEXT, mono = true), marginTop(8))
        diagnostics.addView(actionButton("COPY REDACTED DIAGNOSTICS", false) { copyDiagnostics() }, marginTop(10))
        pageContent.addView(diagnostics, marginTop(12))

        val tools = card()
        tools.addView(text("MORE CONTROLS", 11, COLOR_ACCENT, true, mono = true))
        tools.addView(text("Source priorities and deep runtime diagnostics live here, away from the primary Turn On / Turn Off action.", 11, COLOR_MUTED), marginTop(6))
        if (BuildConfig.AUTOMATION_SOURCES_ENABLED) {
            tools.addView(actionButton("SOURCE AUTOMATION & PRIORITIES", false) { showPage(PAGE_AUTOMATION) }, marginTop(10))
        }
        tools.addView(actionButton("DEVELOPER CONSOLE", false) { showPage(PAGE_DEVELOPER) }, marginTop(8))
        pageContent.addView(tools, marginTop(12))
    }


    private fun buildDiscordCredentialSection() {
        val credentialCard = card()
        credentialCard.addView(text("DISCORD GATEWAY CREDENTIAL VAULT", 11, COLOR_ACCENT, true, mono = true))
        val configured = runCatching { DiscordCredentialVault.hasCredential(this) }.getOrDefault(false)
        credentialCard.addView(
            text(
                if (configured) "ENCRYPTED CREDENTIAL STORED LOCALLY" else "NO GATEWAY CREDENTIAL STORED",
                13, COLOR_TEXT, true, mono = true,
            ),
            marginTop(8),
        )
        credentialCard.addView(
            text(
                "This is not OAuth2. Sign in to your own Discord account in an isolated WebView, then press Import; Gecko reads the session from Discord's page storage only on that press and stores it encrypted with Android Keystore. It is a full account bearer secret. After saving, return Home and press Turn On to start the engine and connect the Gateway together.",
                11, COLOR_MUTED,
            ),
            marginTop(8),
        )

        if (!DiscordWebViewIsolation.isAvailable()) {
            credentialCard.addView(
                text("SECURE SIGN-IN DISABLED: this Android runtime could not establish an isolated WebView data directory.", 11, COLOR_MUTED),
                marginTop(8),
            )
        }
        val loginButton = actionButton("IMPORT EXISTING DISCORD SESSION", true) {
            AlertDialog.Builder(this)
                .setTitle("Import your Discord session?")
                .setMessage("Sign in with your own account only. The imported session is a bearer credential with broad account access. Gecko stores it encrypted on this device and does not send it to a Gecko server. This is not OAuth2 and Gateway publishing uses an undocumented user-session connection. Continue only with your own account.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    runCatching {
                        startActivityForResult(
                            Intent(this, DiscordLoginActivity::class.java),
                            REQUEST_DISCORD_SESSION_IMPORT,
                        )
                    }.onFailure {
                        Toast.makeText(this, "Could not open the Discord sign-in page", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        }
        credentialCard.addView(loginButton, marginTop(10))
        credentialCard.addView(actionButton("REMOVE STORED CREDENTIAL", false) {
            AlertDialog.Builder(this)
                .setTitle("Remove Discord credential?")
                .setMessage("The encrypted value and its Android Keystore key will be deleted from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    runCatching { DiscordCredentialVault.clear(this) }
                        .onSuccess { Toast.makeText(this, "Credential removed", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(this, "Could not completely remove credential storage", Toast.LENGTH_LONG).show() }
                    showPage(PAGE_SETTINGS)
                }
                .show()
        }, marginTop(8))
        pageContent.addView(credentialCard)
    }

    private fun addSwitch(
        parent: LinearLayout,
        label: String,
        key: String,
        defaultValue: Boolean,
        onChanged: ((Boolean) -> Unit)? = null,
    ) {
        val control = Switch(this).apply {
            text = label
            textSize = 13f
            setTextColor(COLOR_TEXT)
            minHeight = dp(50)
            isChecked = settings.getBoolean(key, defaultValue)
            val listener = object : CompoundButton.OnCheckedChangeListener {
                override fun onCheckedChanged(button: CompoundButton, checked: Boolean) {
                    settings.edit().putBoolean(key, checked).apply()
                    try {
                        onChanged?.invoke(checked)
                    } catch (exception: Exception) {
                        Log.e(TAG, "Toggle failed: $key (${exception.javaClass.simpleName}): ${app.geckopresence.host.core.SensitiveDataRedactor.redact(exception.message.orEmpty())}")
                        settings.edit().putBoolean(key, !checked).apply()
                        button.setOnCheckedChangeListener(null)
                        button.isChecked = !checked
                        button.setOnCheckedChangeListener(this)
                        Toast.makeText(this@MainActivity, "Could not apply setting: ${exception.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            setOnCheckedChangeListener(listener)
        }
        parent.addView(control, marginTop(8))
    }

    private fun optionSpinner(
        label: String,
        key: String,
        options: List<String>,
        defaultIndex: Int,
        onSelectionChanged: (Int) -> Unit = {},
    ): Spinner {
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        val savedIndex = settings.getInt(key, defaultIndex).coerceIn(options.indices)
        spinner.setSelection(savedIndex, false)
        spinner.minimumHeight = dp(48)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.edit().putInt(key, position).apply()
                onSelectionChanged(position)
            }
        }
        return spinner
    }

    private fun textField(
        label: String,
        key: String,
        fallback: String,
        maxLength: Int,
        keyboardInputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
    ): EditText {
        val field = EditText(this).apply {
            setText(settings.getString(key, fallback))
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            hint = label
            textSize = 14f
            setSingleLine(true)
            inputType = keyboardInputType
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            minHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = outline(COLOR_LINE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    settings.edit().putString(key, s?.toString().orEmpty()).apply()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        return field
    }

    private fun showAppPicker() {
        if (!UsageAppTracker.hasUsageAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle("Usage Access required")
                .setMessage("Android must grant Usage Access before Gecko Presence can detect selected foreground apps.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("OPEN SETTINGS") { _, _ -> openSystemSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
                .show()
            return
        }
        val apps = UsageAppTracker.selectableApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "No eligible launchable applications found", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = settings.getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
        val labels = apps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].packageName in selected }
        AlertDialog.Builder(this)
            .setTitle("Select applications")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                val packageName = apps[index].packageName
                if (isChecked) selected.add(packageName) else selected.remove(packageName)
            }
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                settings.edit().putStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, selected.toSet()).apply()
                updateSources(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
            }
            .show()
    }

    private fun showBrowserPicker() {
        val browsers = BrowserPackages.installed(this)
        if (browsers.isEmpty()) {
            Toast.makeText(this, "No supported browser package was found", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = settings.getStringSet(BrowserAccessibilityService.KEY_BROWSER_PACKAGES, emptySet()).orEmpty().toMutableSet()
        val labels = browsers.map { it.second }.toTypedArray()
        val checked = BooleanArray(browsers.size) { browsers[it].first in selected }
        AlertDialog.Builder(this)
            .setTitle("Supported browsers")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                val packageName = browsers[index].first
                if (isChecked) selected.add(packageName) else selected.remove(packageName)
            }
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                settings.edit().putStringSet(BrowserAccessibilityService.KEY_BROWSER_PACKAGES, selected.toSet()).apply()
                sendBroadcast(Intent(BrowserAccessibilityService.ACTION_RECONFIGURE).setPackage(packageName), PresenceEngineService.INTERNAL_BROADCAST_PERMISSION)
                if (settings.getBoolean(BrowserAccessibilityService.KEY_TRACK_BROWSER, false) &&
                    settings.getBoolean(PresenceEngineService.KEY_AUTO_SOURCES_ENABLED, true)) {
                    openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
                Toast.makeText(this, "Browser allowlist saved", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun captureAdvancedStudioDraft(): String {
        val keys = listOf(
            "studio_draft_type",
            "studio_draft_account_status_index",
            "studio_draft_stream_url",
            "studio_draft_large_image",
            "studio_draft_large_tooltip",
            "studio_draft_small_image",
            "studio_draft_small_tooltip",
            "studio_draft_button1_label",
            "studio_draft_button1_url",
            "studio_draft_button2_label",
            "studio_draft_button2_url",
            "studio_draft_start_timestamp",
            "studio_draft_end_timestamp",
        )
        return org.json.JSONObject().apply {
            keys.forEach { key ->
                if (key.endsWith("_index") || key.endsWith("_type")) put(key, settings.getInt(key, 0))
                else put(key, settings.getString(key, "").orEmpty())
            }
        }.toString()
    }

    private fun applyAdvancedStudioDraft(raw: String) {
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return
        val keys = listOf(
            "studio_draft_type",
            "studio_draft_account_status_index",
            "studio_draft_stream_url",
            "studio_draft_large_image",
            "studio_draft_large_tooltip",
            "studio_draft_small_image",
            "studio_draft_small_tooltip",
            "studio_draft_button1_label",
            "studio_draft_button1_url",
            "studio_draft_button2_label",
            "studio_draft_button2_url",
            "studio_draft_start_timestamp",
            "studio_draft_end_timestamp",
        )
        val editor = settings.edit()
        keys.forEach { key ->
            if (!json.has(key)) return@forEach
            if (key.endsWith("_index") || key.endsWith("_type")) {
                editor.putInt(key, json.optInt(key, 0).coerceIn(0, 5))
            } else {
                editor.putString(key, json.optString(key).take(512))
            }
        }
        editor.apply()
    }

    private fun showSavePresetDialog(name: String, details: String, state: String) {
        val label = EditText(this).apply {
            hint = "Preset name"
            setText(name.trim().take(48))
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            minHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = outline(COLOR_LINE)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label)
        }
        AlertDialog.Builder(this)
            .setTitle("Save presence preset")
            .setView(container)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                if (name.isBlank()) {
                    Toast.makeText(this, "Activity name is required", Toast.LENGTH_SHORT).show()
                } else {
                    PresencePresetStore.save(this, label.text.toString(), name, details, state, captureAdvancedStudioDraft())
                    Toast.makeText(this, "Preset saved locally", Toast.LENGTH_SHORT).show()
                    if (activePage == PAGE_LIBRARY) showPage(PAGE_LIBRARY)
                }
            }.show()
    }

    private fun copyDiagnostics() {
        val value = listOf(
            "Gecko Presence diagnostics",
            "engine=${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED")}",
            "gateway=${runtime.getString(PresenceEngineService.KEY_GATEWAY_STATE, "GATEWAY_DISCONNECTED")}",
            "appTracker=${runtime.getString(PresenceEngineService.KEY_APP_TRACKER_STATUS, "unknown")}",
            "mediaTracker=${runtime.getString(PresenceEngineService.KEY_MEDIA_TRACKER_STATUS, "unknown")}",
            "usageAccess=${UsageAppTracker.hasUsageAccess(this)}",
            "notificationAccess=${MediaSessionPresenceTracker.hasNotificationAccess(this)}",
            "browserAccessibility=${accessibilityServiceStatus()}",
            "applicationId=${BuildConfig.DISCORD_APP_ID}",
            "lastCheckpoint=${CrashDiagnostics.checkpointSummary(this)}",
            "crashSummary=${CrashDiagnostics.summary(this)}",
            "latestCrashReport=${CrashDiagnostics.latestReportText(this).orEmpty()}",
            "eventLog=${runtime.getString(PresenceEngineService.KEY_EVENT_LOG, "").orEmpty()}",
        ).joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Gecko Presence diagnostics", value))
        Toast.makeText(this, "Redacted diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun renderRuntimeStatus(intent: Intent) {
        val engine = intent.getStringExtra(PresenceEngineService.EXTRA_ENGINE_STATE)
        val gateway = intent.getStringExtra(PresenceEngineService.EXTRA_GATEWAY_STATE)
        val message = intent.getStringExtra(PresenceEngineService.EXTRA_STATUS_MESSAGE)
        val preview = intent.getStringExtra(PresenceEngineService.EXTRA_PRESENCE_PREVIEW)
        if (activePage == PAGE_DASHBOARD) {
            if (::engineStatus.isInitialized) engineStatus.text = "ENGINE / ${engine ?: "UNKNOWN"}"
            if (::discordStatus.isInitialized) discordStatus.text = "GATEWAY / ${gateway ?: "UNKNOWN"}"
            if (::statusMessage.isInitialized && !message.isNullOrBlank()) statusMessage.text = message
            if (::currentActivity.isInitialized && !preview.isNullOrBlank()) currentActivity.text = preview
            if (::appSourceStatus.isInitialized) appSourceStatus.text = intent.getStringExtra(PresenceEngineService.EXTRA_APP_TRACKING_STATUS).orEmpty()
            if (::mediaSourceStatus.isInitialized) mediaSourceStatus.text = intent.getStringExtra(PresenceEngineService.EXTRA_MEDIA_TRACKING_STATUS).orEmpty()
            updateRuntimeToggle()
            updateRuntimeStatusColors(engine, gateway)
        }
        if (activePage == PAGE_DEVELOPER) renderDeveloperStatus(intent)
    }

    private fun renderDeveloperStatus(intent: Intent? = null) {
        if (::developerEngineStatus.isInitialized) {
            developerEngineStatus.text = "ENGINE / ${intent?.getStringExtra(PresenceEngineService.EXTRA_ENGINE_STATE) ?: runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED").orEmpty()}"
            developerRpcStatus.text = "GATEWAY / ${intent?.getStringExtra(PresenceEngineService.EXTRA_GATEWAY_STATE) ?: runtime.getString(PresenceEngineService.KEY_GATEWAY_STATE, "GATEWAY_DISCONNECTED").orEmpty()}"
            developerMessage.text = intent?.getStringExtra(PresenceEngineService.EXTRA_STATUS_MESSAGE)
                ?: runtime.getString(PresenceEngineService.KEY_STATUS_MESSAGE, "No status").orEmpty()
            developerActivity.text = intent?.getStringExtra(PresenceEngineService.EXTRA_PRESENCE_PREVIEW)
                ?: runtime.getString(PresenceEngineService.KEY_PRESENCE_PREVIEW, "No active source").orEmpty()
            developerEventLog.text = runtime.getString(PresenceEngineService.KEY_EVENT_LOG, "No events recorded").orEmpty().ifBlank { "No events recorded" }
            if (::developerCrashStatus.isInitialized) developerCrashStatus.text = CrashDiagnostics.summary(this)
        }
    }

    private fun renderDashboardStatus() {
        engineStatus.text = "ENGINE / ${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED")}" 
        discordStatus.text = "GATEWAY / ${runtime.getString(PresenceEngineService.KEY_GATEWAY_STATE, "GATEWAY_DISCONNECTED")}" 
        statusMessage.text = runtime.getString(PresenceEngineService.KEY_STATUS_MESSAGE, "Press TURN ON to start local detection and connect Discord together.").orEmpty()
        currentActivity.text = runtime.getString(PresenceEngineService.KEY_PRESENCE_PREVIEW, "No active source").orEmpty()
        appSourceStatus.text = runtime.getString(PresenceEngineService.KEY_APP_TRACKER_STATUS, "App detector not started").orEmpty()
        mediaSourceStatus.text = runtime.getString(PresenceEngineService.KEY_MEDIA_TRACKER_STATUS, "Media detector not started").orEmpty()
        updateRuntimeToggle()
        updateRuntimeStatusColors(
            runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, PresenceEngineService.STATE_STOPPED),
            runtime.getString(PresenceEngineService.KEY_GATEWAY_STATE, DiscordGatewayPublisher.STATE_DISCONNECTED),
        )
    }

    private fun updateRuntimeStatusColors(engine: String?, gateway: String?) {
        if (::engineStatus.isInitialized) engineStatus.setTextColor(
            if (engine == PresenceEngineService.STATE_RUNNING) COLOR_GREEN else COLOR_MUTED,
        )
        if (::discordStatus.isInitialized) discordStatus.setTextColor(
            when (gateway) {
                DiscordGatewayPublisher.STATE_CONNECTED -> COLOR_CYAN
                DiscordGatewayPublisher.STATE_ERROR -> COLOR_MAGENTA
                else -> COLOR_MUTED
            },
        )
    }

    private fun runtimeIsOn(): Boolean = RuntimeTogglePolicy.isOn(
        runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, PresenceEngineService.STATE_STOPPED),
    )

    private fun runtimeToggleLabel(): String = if (runtimeIsOn()) "TURN OFF" else "TURN ON"

    private fun updateRuntimeToggle() {
        val on = runtimeIsOn()
        runtimeToggleButton?.apply {
            text = if (on) "TURN OFF" else "TURN ON"
            background = outline(if (on) COLOR_MAGENTA else COLOR_ACCENT, if (on) COLOR_MAGENTA else COLOR_ACCENT)
            setTextColor(COLOR_BACKGROUND)
        }
    }

    private fun toggleRuntime() {
        if (runtimeIsOn()) {
            stopService(Intent(this, PresenceEngineService::class.java))
            Toast.makeText(this, "Turning Gecko off…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!DiscordCredentialVault.hasCredential(this)) {
            AlertDialog.Builder(this)
                .setTitle("Discord connection is not configured")
                .setMessage("Sign in from Settings first. Turn On will then start the local engine and connect the Discord Gateway together.")
                .setNegativeButton("NOT NOW", null)
                .setPositiveButton("OPEN SETTINGS") { _, _ -> showPage(PAGE_SETTINGS) }
                .show()
            return
        }
        requestNotificationPermissionIfNeeded()
        startServiceAction(PresenceEngineService.ACTION_TURN_ON)
    }

    private fun accessibilityServiceStatus(): String {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = android.content.ComponentName(this, BrowserAccessibilityService::class.java).flattenToString()
        return if (enabled.split(':').any { it.equals(component, ignoreCase = true) }) "Enabled" else "Not enabled"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun updateSources(action: String) {
        if (!settings.getBoolean(PresenceEngineService.KEY_AUTO_SOURCES_ENABLED, true)) return
        if (runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, PresenceEngineService.STATE_STOPPED) != PresenceEngineService.STATE_RUNNING) return
        startServiceAction(action)
    }

    private fun startServiceAction(action: String): Boolean {
        val intent = Intent(this, PresenceEngineService::class.java).setAction(action)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            true
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not dispatch service action: $action (${exception.javaClass.simpleName}): ${app.geckopresence.host.core.SensitiveDataRedactor.redact(exception.message.orEmpty())}")
            Toast.makeText(this, "Service action failed: ${exception.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun openSystemSettings(action: String) {
        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, "Android settings page is unavailable on this device", Toast.LENGTH_SHORT).show() }
    }

    private fun sectionTitle(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 19, COLOR_TEXT, true).apply { letterSpacing = -0.02f })
        addView(text(subtitle, 11, COLOR_MUTED), marginTop(5))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(COLOR_GLASS_TOP, COLOR_PANEL),
        ).apply {
            setStroke(dp(1), COLOR_LINE)
            cornerRadius = dp(9).toFloat()
        }
    }

    private fun text(
        value: String,
        sizeSp: Int,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp.toFloat()
        setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        typeface = Typeface.MONOSPACE
        setTextColor(if (primary) COLOR_BACKGROUND else COLOR_TEXT)
        minHeight = dp(48)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = outline(if (primary) COLOR_ACCENT else COLOR_TEXT_SECONDARY, if (primary) COLOR_ACCENT else COLOR_PANEL)
        setOnClickListener { action() }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(COLOR_LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun solid(color: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = 0f }

    private fun outline(stroke: Int, fill: Int = COLOR_PANEL): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(9).toFloat()
    }

    private fun marginTop(value: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(value) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_DASHBOARD = "Dashboard"
        private const val PAGE_SOURCES = "Sources"
        private const val PAGE_STUDIO = "Studio"
        private const val PAGE_AUTOMATION = "Automation"
        private const val PAGE_LIBRARY = "Library"
        private const val PAGE_SETTINGS = "Settings"
        private const val PAGE_DEVELOPER = "Developer"
        private const val PREFS_SETTINGS = "gpb_settings"
        private const val PREFS_RUNTIME = "gpb_runtime"
        private const val KEY_STUDIO_DRAFT_NAME = "studio_draft_name"
        private const val KEY_STUDIO_DRAFT_DETAILS = "studio_draft_details"
        private const val KEY_STUDIO_DRAFT_STATE = "studio_draft_state"
        private const val KEY_STUDIO_DRAFT_LARGE_IMAGE = "studio_draft_large_image"
        private const val KEY_STUDIO_DRAFT_SMALL_IMAGE = "studio_draft_small_image"
        private const val KEY_PENDING_ARTWORK_TARGET = "pending_artwork_target"
        private const val KEY_PENDING_ARTWORK_ID = "pending_artwork_id"
        private const val KEY_STUDIO_PROFILE_PACKAGE = "studio_app_profile_package"
        private const val REQUEST_NOTIFICATIONS = 4401
        private const val REQUEST_EXPORT_CRASH_BUNDLE = 4402
        private const val REQUEST_DISCORD_SESSION_IMPORT = 4403
        private const val REQUEST_ARTWORK_PICKER = 4404
        private const val KEY_IMGUR_CLIENT_ID = "artwork_imgur_client_id"
        private const val STUDIO_SYNC_DEBOUNCE_MS = 700L
        private const val TAG = "GeckoPresenceUI"

    }
}
