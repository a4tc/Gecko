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
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import app.geckopresence.host.core.PresenceRules
import app.geckopresence.host.core.PresenceActivityMapper

class MainActivity : Activity() {
    private val COLOR_BACKGROUND: Int get() = getColor(R.color.screen_bg)
    private val COLOR_PANEL: Int get() = getColor(R.color.panel_bg)
    private val COLOR_ACCENT: Int get() = getColor(R.color.signal_yellow)
    private val COLOR_TEXT: Int get() = getColor(R.color.text_primary)
    private val COLOR_TEXT_SECONDARY: Int get() = getColor(R.color.text_secondary)
    private val COLOR_MUTED: Int get() = getColor(R.color.text_muted)
    private val COLOR_LINE: Int get() = getColor(R.color.line)
    private val COLOR_DISCORD_CARD: Int get() = getColor(R.color.discord_card_bg)

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
    private var activePage = PAGE_DASHBOARD
    private var receiverRegistered = false

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
        showPage(activePage)
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(statusReceiver) }
            receiverRegistered = false
        }
        super.onStop()
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
        val version = text("ANDROID\n0.6.0", 9, COLOR_MUTED, true, mono = true).apply {
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
            "DEV" to PAGE_DEVELOPER,
            "MORE" to PAGE_MORE,
        )
        val selectedDestination = when (activePage) {
            PAGE_AUTOMATION, PAGE_LIBRARY, PAGE_SETTINGS -> PAGE_MORE
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

    private fun showPage(page: String) {
        activePage = page
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
            PAGE_MORE -> buildMore()
        }
    }

    private fun buildDashboard() {
        pageContent.addView(sectionTitle("LIVE SIGNAL", "Current publisher state and active source"))
        val statusCard = card()
        engineStatus = text("ENGINE / ${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED")}", 12, COLOR_ACCENT, true, mono = true)
        discordStatus = text("DISCORD / ${runtime.getString(PresenceEngineService.KEY_SDK_STATE, "SDK_MISSING")}", 12, COLOR_TEXT, true, mono = true)
        statusMessage = text(runtime.getString(PresenceEngineService.KEY_STATUS_MESSAGE, "Start the engine to initialize Direct RPC.").orEmpty(), 12, COLOR_MUTED)
        statusCard.addView(engineStatus)
        statusCard.addView(discordStatus, marginTop(8))
        statusCard.addView(statusMessage, marginTop(12))
        statusCard.addView(actionButton("START PRESENCE ENGINE", true) {
            requestNotificationPermissionIfNeeded()
            startServiceAction(PresenceEngineService.ACTION_START)
        }, marginTop(16))
        statusCard.addView(actionButton("STOP ENGINE", false) {
            stopService(Intent(this, PresenceEngineService::class.java))
            Toast.makeText(this, "Presence engine stopped", Toast.LENGTH_SHORT).show()
            showPage(PAGE_DASHBOARD)
        }, marginTop(8))
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
                if (settings.getBoolean(PresenceEngineService.KEY_AUTO_SOURCES_ENABLED, true)) {
                    requestNotificationPermissionIfNeeded()
                    startServiceAction(PresenceEngineService.ACTION_START)
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
        val previewHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previewMark = TextView(this).apply {
            text = "GP"
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(COLOR_BACKGROUND)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(COLOR_ACCENT)
                cornerRadius = dp(9).toFloat()
            }
        }
        previewHeader.addView(previewMark, LinearLayout.LayoutParams(dp(44), dp(44)))
        val previewHeading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        previewHeading.addView(text("PLAYING", 10, COLOR_MUTED, true, mono = true))
        val previewName = text("Gecko Presence", 14, COLOR_TEXT, true)
        previewHeading.addView(previewName, marginTop(3))
        previewHeader.addView(previewHeading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewCard.addView(previewHeader)
        val previewDetails = text("Working on something", 12, COLOR_TEXT)
        previewCard.addView(previewDetails, marginTop(14))
        val previewState = text("Android · Native", 11, COLOR_MUTED)
        previewCard.addView(previewState, marginTop(5))
        editor.addView(previewCard, marginTop(10))

        val name = textField("Activity label", "studio_draft_name", "Gecko Presence", 80)
        val details = textField("Details", "studio_draft_details", "Working on something", 120)
        val state = textField("State", "studio_draft_state", "Android · Native", 120)
        val updatePreview = {
            val activityLabel = name.text.toString().trim()
            val activityDetails = details.text.toString().trim()
            previewName.text = "Discord application name"
            previewDetails.text = PresenceActivityMapper.detailsLine(activityLabel, activityDetails).orEmpty()
            val optionalState = PresenceActivityMapper.stateLine(state.text.toString())
            previewState.text = optionalState.orEmpty()
            previewState.visibility = if (optionalState == null) View.GONE else View.VISIBLE
        }
        listOf(name, details, state).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        editor.addView(text("ACTIVITY LABEL", 10, COLOR_MUTED, true, mono = true), marginTop(16))
        editor.addView(name, marginTop(6))
        editor.addView(text("DETAILS", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        editor.addView(details, marginTop(6))
        editor.addView(text("STATE", 10, COLOR_MUTED, true, mono = true), marginTop(12))
        editor.addView(state, marginTop(6))
        editor.addView(text("DISCORD APP NAME", 10, COLOR_ACCENT, true, mono = true), marginTop(16))
        editor.addView(text("The first line uses the name registered in Discord Developer Portal. It is not editable per activity. Activity label and details are combined into the editable details line.", 11, COLOR_MUTED), marginTop(5))
        editor.addView(text("TRANSPORT", 10, COLOR_ACCENT, true, mono = true), marginTop(12))
        editor.addView(text("Playing activity type · elapsed timestamp · activity label + details and state. Rich Presence artwork, buttons, and other activity types are not exposed by this JNI adapter yet.", 11, COLOR_MUTED), marginTop(5))
        editor.addView(actionButton("APPLY MANUAL OVERRIDE", true) {
            val currentName = name.text.toString().trim()
            val currentDetails = details.text.toString().trim()
            if (currentName.isEmpty() || currentDetails.isEmpty()) {
                Toast.makeText(this, "Activity label and details are required", Toast.LENGTH_SHORT).show()
                return@actionButton
            }
            settings.edit()
                .putBoolean(PresenceEngineService.KEY_MANUAL_ACTIVE, true)
                .putString(PresenceEngineService.KEY_MANUAL_NAME, currentName.take(80))
                .putString(PresenceEngineService.KEY_MANUAL_DETAILS, currentDetails.take(120))
                .putString(PresenceEngineService.KEY_MANUAL_STATE, state.text.toString().trim().take(120))
                .putLong(PresenceEngineService.KEY_MANUAL_START, System.currentTimeMillis() / 1_000L)
                .apply()
            startServiceAction(PresenceEngineService.ACTION_START)
            startServiceAction(PresenceEngineService.ACTION_APPLY_MANUAL)
            Toast.makeText(this, "Manual presence applied", Toast.LENGTH_SHORT).show()
        }, marginTop(16))
        editor.addView(actionButton("RELEASE MANUAL OVERRIDE", false) {
            startServiceAction(PresenceEngineService.ACTION_CLEAR_MANUAL)
            Toast.makeText(this, "Automatic sources can take priority again", Toast.LENGTH_SHORT).show()
        }, marginTop(8))
        editor.addView(actionButton("SAVE TO LIBRARY", false) { showSavePresetDialog(name.text.toString(), details.text.toString(), state.text.toString()) }, marginTop(8))
        pageContent.addView(editor)
        updatePreview()
    }

    private fun buildAutomation() {
        pageContent.addView(sectionTitle("AUTOMATION", "Source arbitration and global behavior"))
        val rules = card()
        rules.addView(text("AUTOMATIC SOURCE ENGINE", 11, COLOR_ACCENT, true, mono = true))
        addSwitch(rules, "Allow automatic source detection", PresenceEngineService.KEY_AUTO_SOURCES_ENABLED, true) { enabled ->
            startServiceAction(PresenceEngineService.ACTION_UPDATE_APP_TRACKING)
            startServiceAction(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
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
        pageContent.addView(sectionTitle("LIBRARY", "Saved local activity presets"))
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
            entry.addView(text("${preset.name}\n${preset.details}${preset.state?.let { "\n$it" }.orEmpty()}", 11, COLOR_MUTED), marginTop(6))
            entry.addView(actionButton("LOAD INTO STUDIO", true) {
                settings.edit()
                    .putString(KEY_STUDIO_DRAFT_NAME, preset.name)
                    .putString(KEY_STUDIO_DRAFT_DETAILS, preset.details)
                    .putString(KEY_STUDIO_DRAFT_STATE, preset.state.orEmpty())
                    .apply()
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
        pageContent.addView(sectionTitle("DEVELOPER CONSOLE", "Direct RPC · source runtime · diagnostics"))

        val runtimeCard = card()
        runtimeCard.addView(text("RUNTIME STATE", 10, COLOR_ACCENT, true, mono = true))
        developerEngineStatus = text("ENGINE / ${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED").orEmpty()}", 12, COLOR_TEXT, true, mono = true)
        developerRpcStatus = text("RPC / ${runtime.getString(PresenceEngineService.KEY_SDK_STATE, "SDK_NOT_INITIALIZED").orEmpty()}", 12, COLOR_TEXT, true, mono = true)
        developerMessage = text(runtime.getString(PresenceEngineService.KEY_STATUS_MESSAGE, "Engine has not been started.").orEmpty(), 11, COLOR_MUTED)
        runtimeCard.addView(developerEngineStatus, marginTop(8))
        runtimeCard.addView(developerRpcStatus, marginTop(5))
        runtimeCard.addView(developerMessage, marginTop(10))
        runtimeCard.addView(actionButton("START LOCAL ENGINE", true) {
            requestNotificationPermissionIfNeeded()
            startServiceAction(PresenceEngineService.ACTION_START)
        }, marginTop(14))
        runtimeCard.addView(actionButton("CONNECT DISCORD RPC", true) {
            requestNotificationPermissionIfNeeded()
            startServiceAction(PresenceEngineService.ACTION_CONNECT_DISCORD)
        }, marginTop(8))
        runtimeCard.addView(actionButton("DISCONNECT DISCORD RPC", false) {
            startServiceAction(PresenceEngineService.ACTION_DISCONNECT_DISCORD)
        }, marginTop(8))
        runtimeCard.addView(actionButton("STOP LOCAL ENGINE", false) {
            startServiceAction(PresenceEngineService.ACTION_STOP)
        }, marginTop(8))
        pageContent.addView(runtimeCard)

        val activityCard = card()
        activityCard.addView(text("WINNING ACTIVITY PAYLOAD", 10, COLOR_ACCENT, true, mono = true))
        developerActivity = text(runtime.getString(PresenceEngineService.KEY_PRESENCE_PREVIEW, "No active source").orEmpty(), 12, COLOR_TEXT, true, mono = true)
        activityCard.addView(developerActivity, marginTop(8))
        activityCard.addView(text("Local preview is available while RPC is disconnected. A preview does not imply Discord accepted an update.", 10, COLOR_MUTED), marginTop(8))
        pageContent.addView(activityCard, marginTop(12))

        val identityCard = card()
        identityCard.addView(text("BUILD / NATIVE IDENTITY", 10, COLOR_ACCENT, true, mono = true))
        identityCard.addView(text(
            "PACKAGE / $packageName\nANDROID / ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nSDK AAR / ${if (BuildConfig.DISCORD_SDK_ENABLED) "PRESENT" else "NOT CONFIGURED"}\nAPPLICATION ID / ${if (BuildConfig.DISCORD_APP_ID > 0L) BuildConfig.DISCORD_APP_ID else "NOT SET"}",
            10, COLOR_TEXT, mono = true,
        ), marginTop(8))
        pageContent.addView(identityCard, marginTop(12))

        val events = card()
        events.addView(text("ENGINE EVENT LOG", 10, COLOR_ACCENT, true, mono = true))
        developerEventLog = text("No events recorded", 10, COLOR_MUTED, mono = true)
        events.addView(developerEventLog, marginTop(8))
        events.addView(actionButton("COPY DIAGNOSTICS + EVENT LOG", false) { copyDiagnostics() }, marginTop(10))
        pageContent.addView(events, marginTop(12))
        renderDeveloperStatus()
    }

    private fun buildMore() {
        pageContent.addView(sectionTitle("MORE", "Configuration and local data"))
        val routes = listOf(
            Triple("AUTOMATION", "Source priorities and global detection rules", PAGE_AUTOMATION),
            Triple("LIBRARY", "Saved local activity presets", PAGE_LIBRARY),
            Triple("SETTINGS", "Privacy, Android permissions and diagnostics", PAGE_SETTINGS),
        )
        routes.forEach { (label, description, destination) ->
            val entry = card()
            entry.addView(text(label, 12, COLOR_ACCENT, true, mono = true))
            entry.addView(text(description, 11, COLOR_MUTED), marginTop(5))
            entry.addView(actionButton("OPEN $label", false) { showPage(destination) }, marginTop(10))
            pageContent.addView(entry, marginTop(10))
        }
    }

    private fun buildSettings() {
        pageContent.addView(sectionTitle("SETTINGS & DIAGNOSTICS", "Privacy, permissions, and runtime information"))
        val discord = card()
        discord.addView(text("DISCORD SOCIAL SDK", 11, COLOR_ACCENT, true, mono = true))
        discord.addView(text(if (BuildConfig.DISCORD_SDK_ENABLED) "OFFICIAL SDK AAR DETECTED" else "LOCAL PREVIEW MODE", 14, COLOR_TEXT, true), marginTop(8))
        discord.addView(text("Application ID: ${if (BuildConfig.DISCORD_APP_ID > 0L) BuildConfig.DISCORD_APP_ID else "Not configured"}", 11, COLOR_MUTED, mono = true), marginTop(6))
        discord.addView(text("Direct Rich Presence does not use OAuth login or a saved Discord user token. Publishing requires the official AAR and an enabled Application ID. Preview mode never claims a live Discord update.", 11, COLOR_MUTED), marginTop(8))
        pageContent.addView(discord)

        val privacy = card()
        privacy.addView(text("PRIVACY", 11, COLOR_ACCENT, true, mono = true))
        addSwitch(privacy, "Share track / video titles", MediaSessionPresenceTracker.KEY_SHARE_MEDIA_TITLES, false) {
            startServiceAction(PresenceEngineService.ACTION_UPDATE_MEDIA_TRACKING)
        }
        privacy.addView(text("Disabled by default. When enabled, titles and artist names from active media sessions may appear in your Discord activity. Notification contents and browser page content are not collected.", 11, COLOR_MUTED), marginTop(6))
        privacy.addView(text("Browser mode stores only the hostname from the visible browser address field. It excludes known sensitive hosts and expires stale candidates.", 11, COLOR_MUTED), marginTop(10))
        pageContent.addView(privacy)

        val permissions = card()
        permissions.addView(text("ANDROID PERMISSIONS", 11, COLOR_ACCENT, true, mono = true))
        permissions.addView(actionButton("USAGE ACCESS", false) { openSystemSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, marginTop(8))
        permissions.addView(actionButton("NOTIFICATION ACCESS", false) { openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }, marginTop(8))
        permissions.addView(actionButton("ACCESSIBILITY SERVICES", false) { openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, marginTop(8))
        pageContent.addView(permissions, marginTop(12))

        val diagnostics = card()
        diagnostics.addView(text("RUNTIME", 11, COLOR_ACCENT, true, mono = true))
        diagnostics.addView(text("Engine: ${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED")}\nDiscord SDK: ${runtime.getString(PresenceEngineService.KEY_SDK_STATE, "SDK_MISSING")}\nApp source: ${runtime.getString(PresenceEngineService.KEY_APP_TRACKER_STATUS, "unknown")}\nMedia source: ${runtime.getString(PresenceEngineService.KEY_MEDIA_TRACKER_STATUS, "unknown")}\nApplication ID: ${BuildConfig.DISCORD_APP_ID}", 11, COLOR_TEXT, mono = true), marginTop(8))
        diagnostics.addView(actionButton("COPY REDACTED DIAGNOSTICS", false) { copyDiagnostics() }, marginTop(10))
        pageContent.addView(diagnostics, marginTop(12))
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
                        Log.e(TAG, "Toggle failed: $key", exception)
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

    private fun textField(label: String, key: String, fallback: String, maxLength: Int): EditText {
        val field = EditText(this).apply {
            setText(settings.getString(key, fallback))
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            hint = label
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
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
                    startServiceAction(PresenceEngineService.ACTION_START)
                    openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
                Toast.makeText(this, "Browser allowlist saved", Toast.LENGTH_SHORT).show()
            }
            .show()
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
                if (name.isBlank() || details.isBlank()) {
                    Toast.makeText(this, "Name and details are required", Toast.LENGTH_SHORT).show()
                } else {
                    PresencePresetStore.save(this, label.text.toString(), name, details, state)
                    Toast.makeText(this, "Preset saved locally", Toast.LENGTH_SHORT).show()
                    if (activePage == PAGE_LIBRARY) showPage(PAGE_LIBRARY)
                }
            }.show()
    }

    private fun copyDiagnostics() {
        val value = listOf(
            "Gecko Presence diagnostics",
            "engine=${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED")}",
            "discordSdk=${runtime.getString(PresenceEngineService.KEY_SDK_STATE, "SDK_MISSING")}",
            "appTracker=${runtime.getString(PresenceEngineService.KEY_APP_TRACKER_STATUS, "unknown")}",
            "mediaTracker=${runtime.getString(PresenceEngineService.KEY_MEDIA_TRACKER_STATUS, "unknown")}",
            "usageAccess=${UsageAppTracker.hasUsageAccess(this)}",
            "notificationAccess=${MediaSessionPresenceTracker.hasNotificationAccess(this)}",
            "browserAccessibility=${accessibilityServiceStatus()}",
            "sdkConfigured=${BuildConfig.DISCORD_SDK_ENABLED}",
            "applicationId=${BuildConfig.DISCORD_APP_ID}",
            "eventLog=${runtime.getString(PresenceEngineService.KEY_EVENT_LOG, "").orEmpty()}",
        ).joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Gecko Presence diagnostics", value))
        Toast.makeText(this, "Redacted diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun renderRuntimeStatus(intent: Intent) {
        val engine = intent.getStringExtra(PresenceEngineService.EXTRA_ENGINE_STATE)
        val sdk = intent.getStringExtra(PresenceEngineService.EXTRA_SDK_STATE)
        val message = intent.getStringExtra(PresenceEngineService.EXTRA_STATUS_MESSAGE)
        val preview = intent.getStringExtra(PresenceEngineService.EXTRA_PRESENCE_PREVIEW)
        if (activePage == PAGE_DASHBOARD) {
            if (::engineStatus.isInitialized) engineStatus.text = "ENGINE / ${engine ?: "UNKNOWN"}"
            if (::discordStatus.isInitialized) discordStatus.text = "DISCORD / ${sdk ?: "UNKNOWN"}"
            if (::statusMessage.isInitialized && !message.isNullOrBlank()) statusMessage.text = message
            if (::currentActivity.isInitialized && !preview.isNullOrBlank()) currentActivity.text = preview
            if (::appSourceStatus.isInitialized) appSourceStatus.text = intent.getStringExtra(PresenceEngineService.EXTRA_APP_TRACKING_STATUS).orEmpty()
            if (::mediaSourceStatus.isInitialized) mediaSourceStatus.text = intent.getStringExtra(PresenceEngineService.EXTRA_MEDIA_TRACKING_STATUS).orEmpty()
        }
        if (activePage == PAGE_DEVELOPER) renderDeveloperStatus(intent)
    }

    private fun renderDeveloperStatus(intent: Intent? = null) {
        if (::developerEngineStatus.isInitialized) {
            developerEngineStatus.text = "ENGINE / ${intent?.getStringExtra(PresenceEngineService.EXTRA_ENGINE_STATE) ?: runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED").orEmpty()}"
            developerRpcStatus.text = "RPC / ${intent?.getStringExtra(PresenceEngineService.EXTRA_SDK_STATE) ?: runtime.getString(PresenceEngineService.KEY_SDK_STATE, "SDK_NOT_INITIALIZED").orEmpty()}"
            developerMessage.text = intent?.getStringExtra(PresenceEngineService.EXTRA_STATUS_MESSAGE)
                ?: runtime.getString(PresenceEngineService.KEY_STATUS_MESSAGE, "No status").orEmpty()
            developerActivity.text = intent?.getStringExtra(PresenceEngineService.EXTRA_PRESENCE_PREVIEW)
                ?: runtime.getString(PresenceEngineService.KEY_PRESENCE_PREVIEW, "No active source").orEmpty()
            developerEventLog.text = runtime.getString(PresenceEngineService.KEY_EVENT_LOG, "No events recorded").orEmpty().ifBlank { "No events recorded" }
        }
    }

    private fun renderDashboardStatus() {
        engineStatus.text = "ENGINE / ${runtime.getString(PresenceEngineService.KEY_ENGINE_STATE, "STOPPED")}" 
        discordStatus.text = "DISCORD / ${runtime.getString(PresenceEngineService.KEY_SDK_STATE, "SDK_MISSING")}" 
        statusMessage.text = runtime.getString(PresenceEngineService.KEY_STATUS_MESSAGE, "Start the engine to initialize Direct RPC.").orEmpty()
        currentActivity.text = runtime.getString(PresenceEngineService.KEY_PRESENCE_PREVIEW, "No active source").orEmpty()
        appSourceStatus.text = runtime.getString(PresenceEngineService.KEY_APP_TRACKER_STATUS, "App detector not started").orEmpty()
        mediaSourceStatus.text = runtime.getString(PresenceEngineService.KEY_MEDIA_TRACKER_STATUS, "Media detector not started").orEmpty()
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
        startServiceAction(action)
    }

    private fun startServiceAction(action: String): Boolean {
        val intent = Intent(this, PresenceEngineService::class.java).setAction(action)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            true
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Could not dispatch service action: $action", exception)
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
        background = outline(COLOR_LINE, COLOR_PANEL)
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
        cornerRadius = 0f
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
        private const val PAGE_MORE = "More"
        private const val PREFS_SETTINGS = "gpb_settings"
        private const val PREFS_RUNTIME = "gpb_runtime"
        private const val KEY_STUDIO_DRAFT_NAME = "studio_draft_name"
        private const val KEY_STUDIO_DRAFT_DETAILS = "studio_draft_details"
        private const val KEY_STUDIO_DRAFT_STATE = "studio_draft_state"
        private const val REQUEST_NOTIFICATIONS = 4401
        private const val TAG = "GeckoPresenceUI"

    }
}
