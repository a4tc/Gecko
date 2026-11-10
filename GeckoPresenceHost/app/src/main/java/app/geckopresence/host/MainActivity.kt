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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.provider.Settings
import android.text.InputType
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var txtBridgeStatus: TextView
    private lateinit var txtDiscordStatus: TextView
    private lateinit var txtSdkInfo: TextView
    private lateinit var txtApplicationId: TextView
    private lateinit var txtPairingKey: TextView
    private lateinit var txtPresencePreview: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCopyKey: Button
    private lateinit var btnRevealKey: Button
    private lateinit var btnRotateKey: Button
    private lateinit var btnRestoreKey: Button
    private lateinit var btnLogout: Button
    private lateinit var shareTitlesSwitch: CompoundButton
    private lateinit var trackAppsSwitch: CompoundButton
    private lateinit var txtAppTrackingStatus: TextView
    private lateinit var btnChooseApps: Button
    private lateinit var btnCustomizeApps: Button
    private lateinit var btnCopyAppDiagnostics: Button
    private lateinit var btnExportExtension: Button

    private var keyVisible = false
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PresenceBridgeService.ACTION_STATUS_CHANGED) {
                renderStatus(
                    intent.getStringExtra(PresenceBridgeService.EXTRA_BRIDGE_STATE),
                    intent.getStringExtra(PresenceBridgeService.EXTRA_SDK_STATE),
                    intent.getStringExtra(PresenceBridgeService.EXTRA_STATUS_MESSAGE),
                )
                intent.getStringExtra(PresenceBridgeService.EXTRA_PRESENCE_PREVIEW)?.let {
                    txtPresencePreview.text = it
                }
                renderAppTrackingStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 11, 9)
        window.navigationBarColor = Color.rgb(11, 11, 9)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        setContentView(R.layout.activity_main)
        bindViews()
        DiscordSdk.setEngineActivity(this)
        configureActions()
        renderInitialState()
    }

    private fun bindViews() {
        txtBridgeStatus = findViewById(R.id.txtBridgeStatus)
        txtDiscordStatus = findViewById(R.id.txtDiscordStatus)
        txtSdkInfo = findViewById(R.id.txtSdkInfo)
        txtApplicationId = findViewById(R.id.txtApplicationId)
        txtPairingKey = findViewById(R.id.txtPairingKey)
        txtPresencePreview = findViewById(R.id.txtPresencePreview)
        btnConnect = findViewById(R.id.btnConnect)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnCopyKey = findViewById(R.id.btnCopyKey)
        btnRevealKey = findViewById(R.id.btnRevealKey)
        btnRotateKey = findViewById(R.id.btnRotateKey)
        btnRestoreKey = findViewById(R.id.btnRestoreKey)
        btnLogout = findViewById(R.id.btnLogout)
        shareTitlesSwitch = findViewById(R.id.switchShareTitles)
        trackAppsSwitch = findViewById(R.id.switchTrackApps)
        txtAppTrackingStatus = findViewById(R.id.txtAppTrackingStatus)
        btnChooseApps = findViewById(R.id.btnChooseApps)
        btnCustomizeApps = findViewById(R.id.btnCustomizeApps)
        btnCopyAppDiagnostics = findViewById(R.id.btnCopyAppDiagnostics)
        btnExportExtension = findViewById(R.id.btnExportExtension)
    }

    private fun configureActions() {
        btnStart.setOnClickListener {
            requestNotificationPermissionIfNeeded()
            startHostService(PresenceBridgeService.ACTION_START)
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, PresenceBridgeService::class.java))
            Toast.makeText(this, "Local bridge stopped", Toast.LENGTH_SHORT).show()
            renderInitialState()
        }

        btnConnect.setOnClickListener {
            if (!BuildConfig.DISCORD_SDK_ENABLED || BuildConfig.DISCORD_APP_ID <= 0L) {
                showSdkSetupDialog()
                return@setOnClickListener
            }
            DiscordSdk.setEngineActivity(this)
            requestNotificationPermissionIfNeeded()
            startHostService(PresenceBridgeService.ACTION_AUTHORIZE)
        }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Disconnect Discord?")
                .setMessage("This removes the saved encrypted refresh token from this app. Your Discord account is not deleted.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DISCONNECT") { _, _ ->
                    if (isServiceMarkedRunning()) {
                        startHostService(PresenceBridgeService.ACTION_LOGOUT)
                    } else {
                        SecureStore.clearRefreshToken(this)
                        renderInitialState()
                    }
                }
                .show()
        }

        btnCopyKey.setOnClickListener {
            val key = SecureStore.getOrCreateBridgeKey(this)
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Gecko Presence pairing key", key))
            Toast.makeText(this, "Pairing key copied", Toast.LENGTH_SHORT).show()
        }

        btnRevealKey.setOnClickListener {
            keyVisible = !keyVisible
            renderPairingKey()
        }

        btnRestoreKey.setOnClickListener { showRestoreKeyDialog() }

        btnRotateKey.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Rotate pairing key?")
                .setMessage("The current Firefox extension pairing key will stop working. You must copy the new key into the extension settings.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("ROTATE KEY") { _, _ ->
                    SecureStore.rotateBridgeKey(this)
                    keyVisible = true
                    renderPairingKey()
                    Toast.makeText(this, "New pairing key generated", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        shareTitlesSwitch.isChecked = getSharedPreferences("gpb_settings", MODE_PRIVATE)
            .getBoolean(PresenceBridgeService.KEY_SHARE_TITLES, true)
        shareTitlesSwitch.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("gpb_settings", MODE_PRIVATE)
                .edit()
                .putBoolean(PresenceBridgeService.KEY_SHARE_TITLES, checked)
                .apply()
            Toast.makeText(
                this,
                if (checked) "Page titles may appear in Rich Presence" else "Page titles are hidden",
                Toast.LENGTH_SHORT,
            ).show()
        }

        trackAppsSwitch.isChecked = getSharedPreferences("gpb_settings", MODE_PRIVATE)
            .getBoolean(AppPresencePolicy.KEY_TRACK_APPS, false)
        trackAppsSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !UsageAppTracker.hasUsageAccess(this)) {
                trackAppsSwitch.isChecked = false
                Toast.makeText(this, "Enable Usage Access for Gecko Presence Host, then return here.", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                return@setOnCheckedChangeListener
            }
            getSharedPreferences("gpb_settings", MODE_PRIVATE)
                .edit()
                .putBoolean(AppPresencePolicy.KEY_TRACK_APPS, checked)
                .apply()
            refreshAppTrackingService()
            renderAppTrackingStatus()
        }

        btnChooseApps.setOnClickListener { showAppPicker() }
        btnCustomizeApps.setOnClickListener { showAppCustomizationPicker() }
        btnCopyAppDiagnostics.setOnClickListener { copyAppTrackingDiagnostics() }
        btnExportExtension.setOnClickListener { requestExtensionExport() }
        renderAppTrackingStatus()
    }

    private fun startHostService(action: String) {
        val intent = Intent(this, PresenceBridgeService::class.java).setAction(action)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            renderInitialState()
        } catch (_: Exception) {
            Toast.makeText(this, "Android could not start the bridge service", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PresenceBridgeService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
        renderInitialState()
    }

    override fun onResume() {
        super.onResume()
        DiscordSdk.setEngineActivity(this)
        renderInitialState()
        renderAppTrackingStatus()
        refreshAppTrackingService()
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(statusReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }


    private fun refreshAppTrackingService() {
        if (isServiceMarkedRunning()) {
            startHostService(PresenceBridgeService.ACTION_UPDATE_APP_TRACKING)
        }
    }

    private fun renderAppTrackingStatus() {
        val settings = getSharedPreferences("gpb_settings", MODE_PRIVATE)
        val enabled = settings.getBoolean(AppPresencePolicy.KEY_TRACK_APPS, false)
        val selected = settings.getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet()).orEmpty()
        val access = UsageAppTracker.hasUsageAccess(this)
        val bridgeRunning = isServiceMarkedRunning()
        val baseStatus = when {
            !access -> "USAGE ACCESS: NOT GRANTED\nEnable Android Usage Access to activate foreground detection."
            !enabled -> "USAGE ACCESS: READY\nTracking is off. Choose apps, then enable tracking."
            selected.isEmpty() -> "USAGE ACCESS: READY\nNo apps selected. Choose at least one eligible app."
            !bridgeRunning -> "${selected.size} APPS ALLOW-LISTED\nStart the local bridge to activate the detector."
            else -> "USAGE ACCESS: READY\n${selected.size} apps allow-listed · detector should be active."
        }
        val diagnostic = getSharedPreferences("gpb_runtime", MODE_PRIVATE)
            .getString(PresenceBridgeService.KEY_APP_TRACKER_STATUS, null)
        txtAppTrackingStatus.text = if (access && enabled && selected.isNotEmpty() && bridgeRunning && !diagnostic.isNullOrBlank()) {
            "$baseStatus\n\n$diagnostic\nOnly selected app labels are shown; no usage history is stored."
        } else {
            baseStatus
        }
        btnChooseApps.isEnabled = true
        btnChooseApps.text = "CHOOSE APPS  /  ${selected.size}"
        btnCustomizeApps.isEnabled = selected.isNotEmpty()
        btnCustomizeApps.alpha = if (selected.isNotEmpty()) 1f else 0.45f
    }

    private fun copyAppTrackingDiagnostics() {
        val runtime = getSharedPreferences("gpb_runtime", MODE_PRIVATE)
        val settings = getSharedPreferences("gpb_settings", MODE_PRIVATE)
        val selectedCount = settings.getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet()).orEmpty().size
        val report = buildString {
            appendLine("Gecko Presence · Android App Tracker")
            appendLine("Usage Access: ${if (UsageAppTracker.hasUsageAccess(this@MainActivity)) "granted" else "not granted"}")
            appendLine("Tracking enabled: ${settings.getBoolean(AppPresencePolicy.KEY_TRACK_APPS, false)}")
            appendLine("Selected apps: $selectedCount")
            appendLine("Bridge: ${runtime.getString("bridge_state", "UNKNOWN")}")
            appendLine("Discord SDK: ${runtime.getString("sdk_state", "UNKNOWN")}")
            appendLine("Detector: ${runtime.getString(PresenceBridgeService.KEY_APP_TRACKER_STATUS, "No diagnostics yet")}")
            appendLine("Presence preview: ${runtime.getString("presence_preview", "none")}")
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Gecko Presence diagnostics", report))
        Toast.makeText(this, "Detector diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun showAppPicker() {
        val apps = UsageAppTracker.selectableApps(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "No eligible launchable apps found. Android did not return any apps for this device profile.", Toast.LENGTH_LONG).show()
            return
        }

        val settings = getSharedPreferences("gpb_settings", MODE_PRIVATE)
        val selected = settings.getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet())
            .orEmpty().toMutableSet()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val search = EditText(this).apply {
            setSingleLine(true)
            hint = "Search installed apps"
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(31, 31, 24))
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(48)))

        val count = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setTextColor(Color.rgb(255, 229, 0))
            setPadding(0, dp(10), 0, dp(6))
        }
        content.addView(count)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))

        fun refreshCount() {
            count.text = "${selected.size} SELECTED  /  ${apps.size} ELIGIBLE"
        }

        fun renderRows(query: String = "") {
            list.removeAllViews()
            val normalized = query.trim().lowercase()
            val visible = apps.filter {
                normalized.isEmpty() || it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized)
            }
            if (visible.isEmpty()) {
                val empty = TextView(this).apply {
                    text = "NO MATCHING APPS"
                    textSize = 12f
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                    setTextColor(Color.LTGRAY)
                    setPadding(dp(8), dp(20), dp(8), dp(20))
                }
                list.addView(empty)
            }
            visible.forEach { app ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(7), dp(4), dp(7))
                    setBackgroundColor(Color.rgb(22, 22, 18))
                }
                val icon = ImageView(this).apply {
                    val drawable = runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                    if (drawable != null) setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))

                val labelColumn = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(4), 0)
                }
                val label = TextView(this).apply {
                    text = app.label
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                val packageName = TextView(this).apply {
                    text = app.packageName
                    textSize = 9f
                    typeface = Typeface.create("monospace", Typeface.NORMAL)
                    setTextColor(Color.rgb(164, 160, 140))
                }
                labelColumn.addView(label)
                labelColumn.addView(packageName)
                row.addView(labelColumn, LinearLayout.LayoutParams(0, -2, 1f))

                val check = CheckBox(this).apply {
                    isChecked = app.packageName in selected
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 229, 0))
                    contentDescription = "Track ${app.label}"
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                        refreshCount()
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.setOnClickListener { check.isChecked = !check.isChecked }
                list.addView(row, LinearLayout.LayoutParams(-1, -2))
                val divider = View(this).apply { setBackgroundColor(Color.rgb(65, 61, 30)) }
                list.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }

        refreshCount()
        renderRows()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderRows(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("CHOOSE APPS TO TRACK")
            .setMessage("Only apps checked here can appear. Banking, payments, finance, health, authentication, password, identity and browser apps are excluded by policy.")
            .setView(content)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE ALLOWLIST") { _, _ ->
                settings.edit().putStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, selected.toSet()).apply()
                refreshAppTrackingService()
                renderAppTrackingStatus()
                Toast.makeText(this, "App allowlist saved.", Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(255, 229, 0))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.WHITE)
    }

    private fun showRestoreKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste the 43-character key copied from the previous app"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("RESTORE PAIRING KEY")
            .setMessage("Use this when you had to uninstall an older debug-signed build. The key is re-encrypted with this installation's Android Keystore.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RESTORE") { _, _ ->
                runCatching { SecureStore.restoreBridgeKey(this, input.text.toString()) }
                    .onSuccess {
                        keyVisible = true
                        renderPairingKey()
                        Toast.makeText(this, "Previous pairing key restored.", Toast.LENGTH_LONG).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(this, error.message ?: "Invalid pairing key", Toast.LENGTH_LONG).show()
                    }
            }
            .show()
    }

    private fun requestExtensionExport() {
        val files = runCatching { assets.list("gpb-extension").orEmpty() }.getOrDefault(emptyArray())
        if (files.isEmpty()) {
            Toast.makeText(this, "Browser module is not bundled in this APK. Rebuild with the suite workflow.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-xpinstall"
            putExtra(Intent.EXTRA_TITLE, "GeckoPresenceBridge-0.5.0-unsigned.xpi")
        }
        runCatching { startActivityForResult(intent, REQUEST_EXPORT_EXTENSION) }
            .onFailure { Toast.makeText(this, "Android could not open the file picker.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Uses the platform document picker for Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_EXTENSION || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { writeExtensionArchive(uri) }
            .onSuccess {
                AlertDialog.Builder(this)
                    .setTitle("BROWSER MODULE EXPORTED")
                    .setMessage("The XPI file was created. It is unsigned: upload it to addons.mozilla.org as an unlisted add-on and download the signed XPI before installing it in regular Firefox for Android. Debug/temporary installation is only for development and may be removed when Firefox restarts.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            .onFailure { error ->
                Toast.makeText(this, "XPI export failed: ${error.message ?: "file write error"}", Toast.LENGTH_LONG).show()
            }
    }

    private fun writeExtensionArchive(destination: Uri) {
        contentResolver.openOutputStream(destination)?.use { output ->
            ZipOutputStream(output).use { zip ->
                addAssetDirectoryToZip("gpb-extension", "", zip)
            }
        } ?: error("Could not open the destination file")
    }

    private fun addAssetDirectoryToZip(assetPath: String, entryPrefix: String, zip: ZipOutputStream) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            children.sorted().forEach { child ->
                val nextAssetPath = "$assetPath/$child"
                val nextEntryPath = if (entryPrefix.isEmpty()) child else "$entryPrefix/$child"
                addAssetDirectoryToZip(nextAssetPath, nextEntryPath, zip)
            }
            return
        }
        zip.putNextEntry(ZipEntry(entryPrefix))
        assets.open(assetPath).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun showAppCustomizationPicker() {
        val settings = getSharedPreferences("gpb_settings", MODE_PRIVATE)
        val selected = settings.getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet()).orEmpty()
        val apps = UsageAppTracker.selectableApps(this).filter { it.packageName in selected }
        if (apps.isEmpty()) {
            Toast.makeText(this, "Choose at least one app first.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("CUSTOM PRESENCE RULES")
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                showAppTemplateEditor(apps[which])
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showAppTemplateEditor(app: SelectableApp) {
        val settings = getSharedPreferences("gpb_settings", MODE_PRIVATE)
        val nameKey = "app_presence_name__${app.packageName}"
        val detailsKey = "app_presence_details__${app.packageName}"
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val nameInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Activity name / header"
            filters = arrayOf(InputFilter.LengthFilter(80))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(settings.getString(nameKey, AppPresencePolicy.defaultName(app.label)).orEmpty())
        }
        val detailsInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Details · what you're doing"
            filters = arrayOf(InputFilter.LengthFilter(120))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(settings.getString(detailsKey, AppPresencePolicy.defaultDetails(app.packageName, app.label)).orEmpty())
        }
        val stateInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Optional state / secondary line"
            filters = arrayOf(InputFilter.LengthFilter(120))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(settings.getString(AppPresencePolicy.KEY_CUSTOM_STATE_PREFIX + app.packageName, "").orEmpty())
        }
        val preview = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(Color.rgb(255, 229, 0))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(23, 23, 19))
        }
        fun refreshPreview() {
            val name = nameInput.text.toString().trim().ifBlank { AppPresencePolicy.defaultName(app.label) }
            val details = detailsInput.text.toString().trim().ifBlank {
                AppPresencePolicy.defaultDetails(app.packageName, app.label)
            }
            val state = stateInput.text.toString().trim()
            preview.text = buildString {
                appendLine("DISCORD ACTIVITY PREVIEW")
                appendLine(name)
                appendLine(details)
                if (state.isNotBlank()) append(state)
                else append("Optional state is empty")
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        nameInput.addTextChangedListener(watcher)
        detailsInput.addTextChangedListener(watcher)
        stateInput.addTextChangedListener(watcher)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(8), pad, dp(8))
            addView(nameInput, LinearLayout.LayoutParams(-1, -2))
            addView(detailsInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(stateInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        refreshPreview()
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("EDIT ${app.label.uppercase()}")
            .setMessage("These are automatic rules for this app only. Global Custom Presence will be configured separately.")
            .setView(scroll)
            .setNegativeButton("CANCEL", null)
            .setNeutralButton("RESET") { _, _ ->
                settings.edit().remove(nameKey).remove(detailsKey)
                    .remove(AppPresencePolicy.KEY_CUSTOM_STATE_PREFIX + app.packageName).apply()
                refreshAppTrackingService()
            }
            .setPositiveButton("SAVE RULE") { _, _ ->
                AppPresencePolicy.storeCustom(
                    this,
                    app.packageName,
                    nameInput.text.toString(),
                    detailsInput.text.toString(),
                    stateInput.text.toString(),
                )
                refreshAppTrackingService()
            }
            .show()
    }

    private fun renderInitialState() {
        val runtime = getSharedPreferences("gpb_runtime", MODE_PRIVATE)
        val bridge = runtime.getString("bridge_state", "STOPPED") ?: "STOPPED"
        val sdk = runtime.getString("sdk_state", "AUTH_REQUIRED") ?: "AUTH_REQUIRED"
        val message = runtime.getString("status_message", "")
        renderStatus(bridge, sdk, message)

        txtApplicationId.text = if (BuildConfig.DISCORD_APP_ID > 0L) {
            BuildConfig.DISCORD_APP_ID.toString()
        } else {
            "NOT CONFIGURED"
        }

        if (BuildConfig.DISCORD_SDK_ENABLED && BuildConfig.DISCORD_APP_ID > 0L) {
            txtSdkInfo.text = "Discord Social SDK package detected · OAuth transport available"
            btnConnect.isEnabled = true
            btnConnect.alpha = 1f
        } else {
            txtSdkInfo.text = "Preview build · add Discord's official SDK AAR and Application ID to enable Discord login and Rich Presence"
            btnConnect.isEnabled = true
            btnConnect.alpha = 0.75f
        }

        renderPairingKey()
        trackAppsSwitch.isChecked = getSharedPreferences("gpb_settings", MODE_PRIVATE)
            .getBoolean(AppPresencePolicy.KEY_TRACK_APPS, false)
        renderAppTrackingStatus()
        txtPresencePreview.text = getSharedPreferences("gpb_runtime", MODE_PRIVATE)
            .getString("presence_preview", "Waiting for browser or Android app activity…")
        btnStop.isEnabled = bridge != "STOPPED"
        btnStop.alpha = if (btnStop.isEnabled) 1f else 0.45f
        btnLogout.isEnabled = SecureStore.hasRefreshToken(this)
        btnLogout.alpha = if (btnLogout.isEnabled) 1f else 0.45f
    }

    private fun renderStatus(bridge: String?, sdk: String?, message: String?) {
        val bridgeValue = bridge ?: "STOPPED"
        val sdkValue = sdk ?: "AUTH_REQUIRED"
        txtBridgeStatus.text = bridgeValue.replace('_', ' ')
        txtDiscordStatus.text = sdkValue.replace('_', ' ')

        val bridgeColor = when (bridgeValue) {
            "LISTENING" -> getColorCompat(R.color.status_green)
            "PORT_IN_USE", "SERVER_ERROR" -> getColorCompat(R.color.status_red)
            else -> getColorCompat(R.color.text_muted)
        }
        val discordColor = when (sdkValue) {
            "CONNECTED" -> getColorCompat(R.color.status_green)
            "AUTH_ERROR", "NETWORK_ERROR", "SDK_ERROR" -> getColorCompat(R.color.status_red)
            "SDK_MISSING" -> getColorCompat(R.color.accent_pink)
            else -> getColorCompat(R.color.text_muted)
        }
        txtBridgeStatus.setTextColor(bridgeColor)
        txtDiscordStatus.setTextColor(discordColor)

        if (!message.isNullOrBlank()) {
            txtSdkInfo.text = message
        }
        btnStop.isEnabled = bridgeValue != "STOPPED"
        btnStop.alpha = if (btnStop.isEnabled) 1f else 0.45f
        btnLogout.isEnabled = SecureStore.hasRefreshToken(this)
        btnLogout.alpha = if (btnLogout.isEnabled) 1f else 0.45f
    }

    private fun renderPairingKey() {
        val key = SecureStore.getOrCreateBridgeKey(this)
        txtPairingKey.text = if (keyVisible) key else "••••••••••••••••••••••••••••••••••••••••••••"
        btnRevealKey.text = if (keyVisible) "HIDE" else "REVEAL"
    }

    private fun isServiceMarkedRunning(): Boolean {
        val status = getSharedPreferences("gpb_runtime", MODE_PRIVATE)
            .getString("bridge_state", "STOPPED")
        return status != null && status != "STOPPED"
    }

    private fun showSdkSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Discord SDK not configured")
            .setMessage(
                "The local bridge and Android app tracking can run in Preview mode. Discord publishing needs the official Discord Social SDK AAR, which is distributed from the Developer Portal after its onboarding/approval process, plus an Application ID from your own Discord application.\n\n" +
                    "Place the AAR at app/libs/discord_partner_sdk.aar in a private source repository and configure DISCORD_APP_ID as a GitHub Actions repository variable. The Social SDK is currently documented primarily as a game integration; confirm that Discord accepts this companion-app use before depending on it.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun getColorCompat(resourceId: Int): Int {
        return if (Build.VERSION.SDK_INT >= 23) getColor(resourceId) else resources.getColor(resourceId)
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 7101
        private const val REQUEST_EXPORT_EXTENSION = 0x4750
    }
}
