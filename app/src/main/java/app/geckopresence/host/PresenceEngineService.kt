package app.geckopresence.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import app.geckopresence.host.core.PresenceActivityMapper
import app.geckopresence.host.core.PresenceSourceArbiter
import app.geckopresence.host.core.PresenceUpdate
import app.geckopresence.host.core.PresenceActivityType
import app.geckopresence.host.core.DiscordArtworkPolicy
import app.geckopresence.host.core.PresenceAsset
import app.geckopresence.host.core.PresenceButton
import app.geckopresence.host.core.RpcCommand
import app.geckopresence.host.core.RpcStartupPolicy

/** Owns Android source lifecycles, presence arbitration, and the Discord Gateway publisher. */
class PresenceEngineService : Service() {
    private val presenceCandidates = linkedMapOf<String, PresenceUpdate>()
    private val presenceLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var latestPresence: PresenceUpdate? = null
    @Volatile private var engineState = "STARTING"
    @Volatile private var gatewayState = DiscordGatewayPublisher.STATE_DISCONNECTED
    @Volatile private var appTrackerStatus = "App detector not started"
    @Volatile private var mediaTrackerStatus = "Media detector not started"
    @Volatile private var lastStatusMessage = ""

    private var usageAppTracker: UsageAppTracker? = null
    private var mediaSessionTracker: MediaSessionPresenceTracker? = null
    private var gatewayPublisher: DiscordGatewayPublisher? = null
    private var receiverRegistered = false
    private var firstStartCommand = true

    private val sourceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    ACTION_BROWSER_PRESENCE -> receiveBrowserPresence(intent)
                    ACTION_BROWSER_CLEAR -> {
                        if (intent.getStringExtra(EXTRA_SOURCE_TYPE) == SOURCE_WEBSITE) {
                            clearSourceType(SOURCE_WEBSITE)
                        }
                    }
                    ACTION_REFRESH_MEDIA -> updateMediaTracking()
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Source broadcast failed (${exception.javaClass.simpleName}): ${app.geckopresence.host.core.SensitiveDataRedactor.redact(exception.message.orEmpty())}")
                CrashDiagnostics.recordNonFatal(this@PresenceEngineService, "Presence source receiver", exception)
                lastStatusMessage = "A source event failed: ${exception.javaClass.simpleName}"
                recordEvent("Source event failed: ${exception.javaClass.simpleName}")
                publishStatus(lastStatusMessage)
            }
        }
    }

    private val expiryTask = object : Runnable {
        override fun run() {
            val changed = synchronized(presenceLock) {
                val active = PresenceSourceArbiter.active(presenceCandidates)
                val expired = active.size != presenceCandidates.size
                if (expired) {
                    presenceCandidates.clear()
                    presenceCandidates.putAll(active)
                }
                expired
            }
            if (changed) applyPresenceWinner(currentWinner())
            if (engineState == STATE_RUNNING) mainHandler.postDelayed(this, EXPIRY_SWEEP_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engineState = STATE_STARTING
        try {
            createNotificationChannel()
            startPresenceNotification("Starting Gecko presence engine")
            registerSourceReceiver()
            gatewayPublisher = DiscordGatewayPublisher(
                applicationId = BuildConfig.DISCORD_APP_ID,
                onSessionRejected = {
                    runCatching { DiscordCredentialVault.clear(this@PresenceEngineService) }
                },
                onDiagnostic = { message ->
                    mainHandler.post {
                        if (engineState != STATE_STOPPED) recordEvent(message)
                    }
                },
            ) { state, message ->
                mainHandler.post {
                    if (engineState == STATE_STOPPED) return@post
                    gatewayState = state
                    lastStatusMessage = message
                    if (state == DiscordGatewayPublisher.STATE_CONNECTED) {
                        recordEvent("Discord Gateway session ready")
                        updateNotification("Discord Gateway connected")
                    } else if (state == DiscordGatewayPublisher.STATE_ERROR) {
                        recordEvent("Discord Gateway reported an error")
                        updateNotification("Discord Gateway needs attention")
                    }
                    publishStatus(message)
                }
            }
            engineState = STATE_RUNNING
            lastStatusMessage = "Local engine ready. Discord Gateway will connect from the Home Turn On action."
            restoreManualPresence()
            updateAppTracking()
            updateMediaTracking()
            mainHandler.removeCallbacks(expiryTask)
            mainHandler.post(expiryTask)
            recordEvent("Local source engine started; Discord Gateway remains disconnected")
            publishStatus(lastStatusMessage)
            updateNotification("Presence engine running · Gateway disconnected")
        } catch (exception: Exception) {
            engineState = STATE_ERROR
            lastStatusMessage = "Engine start failed: ${exception.javaClass.simpleName}"
            Log.e(TAG, "Presence engine startup failed (${exception.javaClass.simpleName}): ${app.geckopresence.host.core.SensitiveDataRedactor.redact(exception.message.orEmpty())}")
            recordEvent(lastStatusMessage)
            publishStatus(lastStatusMessage)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (firstStartCommand) {
            firstStartCommand = false
            if (action == null || action == ACTION_START || action == ACTION_UPDATE_APP_TRACKING ||
                action == ACTION_UPDATE_MEDIA_TRACKING || action == ACTION_REFRESH_MEDIA) {
                publishStatus()
                return START_STICKY
            }
        }
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            when (RpcStartupPolicy.commandFor(action)) {
                RpcCommand.CONNECT -> connectDiscordGateway()
                RpcCommand.DISCONNECT -> disconnectDiscordGateway()
                RpcCommand.NONE -> when (action) {
                    null, ACTION_START -> {
                        engineState = STATE_RUNNING
                        updateAppTracking()
                        updateMediaTracking()
                        publishStatus()
                    }
                    ACTION_UPDATE_APP_TRACKING -> updateAppTracking()
                    ACTION_UPDATE_MEDIA_TRACKING, ACTION_REFRESH_MEDIA -> updateMediaTracking()
                    ACTION_APPLY_MANUAL -> applyManualPresence()
                    ACTION_CLEAR_MANUAL -> clearManualPresence()
                }
            }
        } catch (exception: Exception) {
            lastStatusMessage = "Engine action failed: ${exception.javaClass.simpleName}"
            Log.e(TAG, "Engine action failed: $action (${exception.javaClass.simpleName}): ${app.geckopresence.host.core.SensitiveDataRedactor.redact(exception.message.orEmpty())}")
            CrashDiagnostics.recordNonFatal(this, "Presence engine action ${action ?: "restart"}", exception)
            recordEvent("Action failed (${action ?: "restart"}): ${exception.javaClass.simpleName}")
            publishStatus(lastStatusMessage)
        }
        return START_STICKY
    }

    private fun connectDiscordGateway() {
        if (gatewayState in setOf(
                DiscordGatewayPublisher.STATE_CONNECTING,
                DiscordGatewayPublisher.STATE_IDENTIFYING,
                DiscordGatewayPublisher.STATE_CONNECTED,
                DiscordGatewayPublisher.STATE_RECONNECTING,
            )) {
            lastStatusMessage = "Discord Gateway is already connected or connecting"
            publishStatus(lastStatusMessage)
            return
        }
        if (!DiscordCredentialVault.hasCredential(this)) {
            gatewayState = DiscordGatewayPublisher.STATE_ERROR
            lastStatusMessage = "No Discord session is stored. Sign in from Settings first."
            recordEvent("Gateway connect blocked: no stored credential")
            publishStatus(lastStatusMessage)
            return
        }
        try {
            val token = DiscordCredentialVault.loadForConnection(this)
            if (token.isNullOrBlank()) {
                gatewayState = DiscordGatewayPublisher.STATE_ERROR
                lastStatusMessage = "Stored Discord session is empty. Sign in again from Settings."
                recordEvent("Gateway connect blocked: stored credential unavailable")
                publishStatus(lastStatusMessage)
                return
            }
            CrashDiagnostics.recordCheckpoint(this, "gateway-connect", "explicit user connect action")
            gatewayState = DiscordGatewayPublisher.STATE_CONNECTING
            lastStatusMessage = "Starting Discord Gateway connection"
            recordEvent("Discord Gateway connection requested by user")
            publishStatus(lastStatusMessage)
            gatewayPublisher?.connect(token)
        } catch (exception: Exception) {
            gatewayState = DiscordGatewayPublisher.STATE_ERROR
            lastStatusMessage = "Could not unlock stored Discord session (${exception.javaClass.simpleName})"
            Log.e(TAG, "Gateway credential access failed (${exception.javaClass.simpleName})")
            recordEvent("Gateway credential access failed (${exception.javaClass.simpleName})")
            publishStatus(lastStatusMessage)
        }
    }

    private fun disconnectDiscordGateway() {
        gatewayPublisher?.disconnect()
        gatewayState = DiscordGatewayPublisher.STATE_DISCONNECTED
        lastStatusMessage = "Discord Gateway disconnect requested; local sources remain available"
        recordEvent("Discord Gateway disconnect requested by user")
        publishStatus(lastStatusMessage)
        updateNotification("Presence engine running · Gateway disconnected")
    }

    private fun recordEvent(message: String) {
        val preferences = getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE)
        val existing = preferences.getString(KEY_EVENT_LOG, "").orEmpty()
        val safeMessage = app.geckopresence.host.core.SensitiveDataRedactor.redact(message)
        val next = (existing.split('\n').filter { it.isNotBlank() } +
            "${System.currentTimeMillis()} $safeMessage").takeLast(MAX_EVENT_LOG_LINES)
        preferences.edit().putString(KEY_EVENT_LOG, next.joinToString("\n")).apply()
    }

    private fun registerSourceReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_BROWSER_PRESENCE)
            addAction(ACTION_BROWSER_CLEAR)
            addAction(ACTION_REFRESH_MEDIA)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                sourceReceiver,
                filter,
                INTERNAL_BROADCAST_PERMISSION,
                null,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(sourceReceiver, filter, INTERNAL_BROADCAST_PERMISSION, null)
        }
        receiverRegistered = true
    }

    private fun updateAppTracking() {
        usageAppTracker?.stop()
        usageAppTracker = null
        clearSourceType(SOURCE_ANDROID_APP)
        if (!BuildConfig.AUTOMATION_SOURCES_ENABLED) {
            setAppTrackerStatus("App tracking excluded from this build")
            return
        }

        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        val enabled = settings.getBoolean(AppPresencePolicy.KEY_TRACK_APPS, false) &&
            settings.getBoolean(KEY_AUTO_SOURCES_ENABLED, true)
        val selected = settings.getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet()).orEmpty()
        when {
            !enabled -> setAppTrackerStatus("App tracking disabled")
            selected.isEmpty() -> setAppTrackerStatus("No applications selected")
            !UsageAppTracker.hasUsageAccess(this) -> setAppTrackerStatus("Usage Access permission required")
            else -> {
                setAppTrackerStatus("Monitoring ${selected.size} selected applications")
                usageAppTracker = UsageAppTracker(
                    context = this,
                    allowedPackages = selected.toSet(),
                    onForegroundPackageChanged = ::handleForegroundPackageChanged,
                    onDiagnosticsChanged = ::setAppTrackerStatus,
                ).also { it.start() }
            }
        }
    }

    private fun updateMediaTracking() {
        mediaSessionTracker?.stop()
        mediaSessionTracker = null
        clearSourceType(MediaSessionPresenceTracker.SOURCE_TYPE)
        if (!BuildConfig.AUTOMATION_SOURCES_ENABLED) {
            setMediaTrackerStatus("Media tracking excluded from this build")
            return
        }

        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        val enabled = settings.getBoolean(MediaSessionPresenceTracker.KEY_TRACK_MEDIA, false) &&
            settings.getBoolean(KEY_AUTO_SOURCES_ENABLED, true)
        if (!enabled) {
            setMediaTrackerStatus("Media session tracking disabled")
            return
        }
        if (!MediaSessionPresenceTracker.hasNotificationAccess(this)) {
            setMediaTrackerStatus("Notification Access permission required")
            return
        }
        mediaSessionTracker = MediaSessionPresenceTracker(
            context = this,
            onPresence = ::handleIncomingPresence,
            onClear = { clearSourceType(MediaSessionPresenceTracker.SOURCE_TYPE) },
            onStatus = ::setMediaTrackerStatus,
        ).also { it.start() }
    }

    private fun receiveBrowserPresence(intent: Intent) {
        if (!BuildConfig.AUTOMATION_SOURCES_ENABLED) return
        val source = intent.getStringExtra(EXTRA_SOURCE)?.take(253) ?: return
        val host = app.geckopresence.host.core.PresenceRules.sanitizeHost(source) ?: return
        if (host != source || app.geckopresence.host.core.PresenceRules.isSensitiveHost(host)) return
        val name = app.geckopresence.host.core.PresenceRules.cleanText(intent.getStringExtra(EXTRA_NAME), 80) ?: host
        val details = app.geckopresence.host.core.PresenceRules.cleanText(intent.getStringExtra(EXTRA_DETAILS), 120) ?: "Browsing"
        val state = app.geckopresence.host.core.PresenceRules.cleanText(intent.getStringExtra(EXTRA_STATE), 120)
        if (intent.getStringExtra(EXTRA_SOURCE_TYPE) != SOURCE_WEBSITE) return
        val sourceSettings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        if (!sourceSettings.getBoolean(BrowserAccessibilityService.KEY_TRACK_BROWSER, false) ||
            !sourceSettings.getBoolean(KEY_AUTO_SOURCES_ENABLED, true)) return
        val now = System.currentTimeMillis()
        val expiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, now + WEBSITE_TTL_MS)
        if (expiresAt <= now || expiresAt > now + MAX_BROWSER_TTL_MS) return
        handleIncomingPresence(
            PresenceUpdate(
                source = host,
                name = name,
                details = details,
                state = state,
                mediaState = intent.getStringExtra(EXTRA_MEDIA_STATE) ?: "browsing",
                startTimestampSeconds = intent.getLongExtra(EXTRA_START_TIMESTAMP, now / 1_000L),
                sourceType = SOURCE_WEBSITE,
                priority = intent.getIntExtra(EXTRA_PRIORITY, 85).coerceIn(1, 99),
                expiresAtEpochMillis = expiresAt,
            ),
        )
    }

    private fun setAppTrackerStatus(status: String) {
        appTrackerStatus = status
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
            .putString(KEY_APP_TRACKER_STATUS, status).apply()
        publishStatus()
    }

    private fun setMediaTrackerStatus(status: String) {
        mediaTrackerStatus = status
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
            .putString(KEY_MEDIA_TRACKER_STATUS, status).apply()
        publishStatus()
    }

    private fun handleForegroundPackageChanged(packageName: String?) {
        val update = packageName?.let { AppPresencePolicy.buildUpdate(this, it) }
        if (update == null) {
            clearSourceType(SOURCE_ANDROID_APP)
            return
        }

        handleIncomingPresence(update)
        if (update.largeImage != null) return
        if (!ArtworkAutoPublisher.isEnabled(this)) return

        ArtworkAutoPublisher.resolveApplicationIcon(this, packageName.orEmpty(), update.name) { asset, artworkStatus ->
            mainHandler.post {
                if (engineState != STATE_RUNNING || asset == null) {
                    artworkStatus?.let(::recordEvent)
                    return@post
                }
                val current = synchronized(presenceLock) { presenceCandidates[update.source] }
                if (current != null && current.sourceType == SOURCE_ANDROID_APP &&
                    current.name == update.name && current.details == update.details &&
                    current.startTimestampSeconds == update.startTimestampSeconds && current.largeImage == null
                ) {
                    handleIncomingPresence(current.copy(largeImage = asset))
                    recordEvent("Application artwork resolved for ${update.name}")
                }
            }
        }
    }

    private fun restoreManualPresence() {
        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        if (settings.getBoolean(KEY_MANUAL_ACTIVE, false)) applyManualPresence()
    }

    private fun applyManualPresence() {
        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        if (!settings.getBoolean(KEY_MANUAL_ACTIVE, false)) return
        val name = app.geckopresence.host.core.PresenceRules.cleanText(settings.getString(KEY_MANUAL_NAME, null), 80) ?: "Gecko Presence"
        val details = app.geckopresence.host.core.PresenceRules.cleanText(settings.getString(KEY_MANUAL_DETAILS, null), 120).orEmpty()
        val state = app.geckopresence.host.core.PresenceRules.cleanText(settings.getString(KEY_MANUAL_STATE, null), 120)
        val activityType = PresenceActivityType.fromWireValue(settings.getInt(KEY_MANUAL_ACTIVITY_TYPE, 0))
            ?: PresenceActivityType.PLAYING
        fun asset(imageKey: String, tooltipKey: String): PresenceAsset? {
            val image = settings.getString(imageKey, null)?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val tooltip = settings.getString(tooltipKey, null)?.trim()?.takeIf(String::isNotEmpty)
            return PresenceAsset(image, tooltip)
        }
        val buttons = buildList {
            val label1 = settings.getString(KEY_MANUAL_BUTTON1_LABEL, null)?.trim().orEmpty()
            val url1 = settings.getString(KEY_MANUAL_BUTTON1_URL, null)?.trim().orEmpty()
            if (label1.isNotEmpty() || url1.isNotEmpty()) add(PresenceButton(label1, url1))
            val label2 = settings.getString(KEY_MANUAL_BUTTON2_LABEL, null)?.trim().orEmpty()
            val url2 = settings.getString(KEY_MANUAL_BUTTON2_URL, null)?.trim().orEmpty()
            if (label2.isNotEmpty() || url2.isNotEmpty()) add(PresenceButton(label2, url2))
        }
        handleIncomingPresence(
            PresenceUpdate(
                source = SOURCE_MANUAL,
                name = name.take(80),
                details = details.take(120),
                state = state?.take(120),
                mediaState = "playing",
                startTimestampSeconds = settings.getLong(KEY_MANUAL_START, System.currentTimeMillis() / 1_000L),
                sourceType = SOURCE_MANUAL,
                priority = 100,
                activityType = activityType,
                endsAtEpochSeconds = settings.getString(KEY_MANUAL_END, null)?.toLongOrNull(),
                timestampDisplayMode = app.geckopresence.host.core.PresenceTimestampDisplayMode.fromStorage(
                    settings.getString(KEY_MANUAL_TIMESTAMP_DISPLAY_MODE, null),
                ),
                platform = app.geckopresence.host.core.PresencePlatform.fromWireValue(
                    settings.getString(KEY_MANUAL_PLATFORM, null),
                ),
                largeImage = asset(KEY_MANUAL_LARGE_IMAGE, KEY_MANUAL_LARGE_TOOLTIP),
                smallImage = asset(KEY_MANUAL_SMALL_IMAGE, KEY_MANUAL_SMALL_TOOLTIP),
                buttons = buttons,
                streamUrl = settings.getString(KEY_MANUAL_STREAM_URL, null)?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
    }

    private fun clearManualPresence() {
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
            .putBoolean(KEY_MANUAL_ACTIVE, false)
            .remove(KEY_MANUAL_START)
            .apply()
        clearSourceType(SOURCE_MANUAL)
    }

    private fun handleIncomingPresence(update: PresenceUpdate) {
        val localLarge = update.largeImage?.image?.let(DiscordArtworkPolicy::localId)
        val localSmall = update.smallImage?.image?.let(DiscordArtworkPolicy::localId)
        if (localLarge == null && localSmall == null) {
            updatePresenceCandidates { PresenceSourceArbiter.upsert(it, update) }
            return
        }

        // Stable "gecko:<id>" references never reach Discord. Publish the text immediately without the
        // unresolved image, then re-publish once the artwork has been hosted and registered.
        val interim = update.copy(
            largeImage = update.largeImage?.takeIf { localLarge == null },
            smallImage = update.smallImage?.takeIf { localSmall == null },
        )
        updatePresenceCandidates { PresenceSourceArbiter.upsert(it, interim) }

        fun resolve(galleryId: String?, apply: (PresenceUpdate, String) -> PresenceUpdate) {
            if (galleryId == null) return
            ArtworkAutoPublisher.resolveGalleryReference(this, galleryId) { reference, status ->
                mainHandler.post {
                    if (engineState != STATE_RUNNING || reference == null) {
                        status?.let(::recordEvent)
                        return@post
                    }
                    val current = synchronized(presenceLock) { presenceCandidates[update.source] }
                    if (current != null && current.sourceType == update.sourceType &&
                        current.startTimestampSeconds == update.startTimestampSeconds
                    ) {
                        updatePresenceCandidates { PresenceSourceArbiter.upsert(it, apply(current, reference)) }
                    }
                }
            }
        }
        resolve(localLarge) { current, ref -> current.copy(largeImage = update.largeImage?.copy(image = ref)) }
        resolve(localSmall) { current, ref -> current.copy(smallImage = update.smallImage?.copy(image = ref)) }
    }

    private fun clearSourceType(sourceType: String) {
        updatePresenceCandidates { PresenceSourceArbiter.removeSourceType(it, sourceType) }
    }

    private fun updatePresenceCandidates(transform: (Map<String, PresenceUpdate>) -> Map<String, PresenceUpdate>) {
        val winner = synchronized(presenceLock) {
            val updated = transform(PresenceSourceArbiter.active(presenceCandidates))
            presenceCandidates.clear()
            presenceCandidates.putAll(updated)
            PresenceSourceArbiter.winner(presenceCandidates)
        }
        applyPresenceWinner(winner)
    }

    private fun currentWinner(): PresenceUpdate? = synchronized(presenceLock) {
        PresenceSourceArbiter.winner(presenceCandidates)
    }

    private fun applyPresenceWinner(winner: PresenceUpdate?) {
        latestPresence = winner
        recordEvent("Presence selected: source=${winner?.sourceType ?: "none"}")
        val globalStatus = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
            .getString(KEY_PRESENCE_STATUS, "online")
            .orEmpty()
        val selectedStatus = winner?.accountStatus ?: globalStatus
        gatewayPublisher?.publish(winner?.let(PresenceActivityMapper::fromUpdate), selectedStatus)
        publishStatus()
    }

    private fun publishStatus(message: String? = null) {
        val statusMessage = message ?: lastStatusMessage
        val preview = latestPresence?.let(::previewText) ?: "No active source"
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
            .putString(KEY_ENGINE_STATE, engineState)
            .putString(KEY_GATEWAY_STATE, gatewayState)
            .putString(KEY_STATUS_MESSAGE, statusMessage)
            .putString(KEY_PRESENCE_PREVIEW, preview)
            .putString(KEY_APP_TRACKER_STATUS, appTrackerStatus)
            .putString(KEY_MEDIA_TRACKER_STATUS, mediaTrackerStatus)
            .apply()
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_ENGINE_STATE, engineState)
                .putExtra(EXTRA_GATEWAY_STATE, gatewayState)
                .putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
                .putExtra(EXTRA_PRESENCE_PREVIEW, preview)
                .putExtra(EXTRA_APP_TRACKING_STATUS, appTrackerStatus)
                .putExtra(EXTRA_MEDIA_TRACKING_STATUS, mediaTrackerStatus),
        )
    }

    private fun previewText(update: PresenceUpdate): String = buildString {
        append(update.name).append('\n').append(update.details)
        update.state?.let { append('\n').append(it) }
        append("\nSOURCE / ").append(update.sourceType.uppercase())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Gecko Presence Engine",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "User-started Rich Presence source monitoring and publishing"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Gecko Presence")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startPresenceNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        engineState = STATE_STOPPED
        mainHandler.removeCallbacks(expiryTask)
        usageAppTracker?.stop()
        usageAppTracker = null
        mediaSessionTracker?.stop()
        mediaSessionTracker = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(sourceReceiver) }
            receiverRegistered = false
        }
        gatewayPublisher?.shutdown()
        gatewayPublisher = null
        synchronized(presenceLock) {
            presenceCandidates.clear()
            latestPresence = null
        }
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
            .putString(KEY_ENGINE_STATE, STATE_STOPPED)
            .putString(KEY_GATEWAY_STATE, DiscordGatewayPublisher.STATE_DISCONNECTED)
            .putString(KEY_APP_TRACKER_STATUS, "App detector stopped")
            .putString(KEY_MEDIA_TRACKER_STATUS, "Media detector stopped")
            .putString(KEY_PRESENCE_PREVIEW, "No active source")
            .apply()
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED).setPackage(packageName)
                .putExtra(EXTRA_ENGINE_STATE, STATE_STOPPED)
                .putExtra(EXTRA_GATEWAY_STATE, DiscordGatewayPublisher.STATE_DISCONNECTED)
                .putExtra(EXTRA_PRESENCE_PREVIEW, "No active source"),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.geckopresence.host.action.START"
        const val ACTION_UPDATE_APP_TRACKING = "app.geckopresence.host.action.UPDATE_APP_TRACKING"
        const val ACTION_UPDATE_MEDIA_TRACKING = "app.geckopresence.host.action.UPDATE_MEDIA_TRACKING"
        const val ACTION_REFRESH_MEDIA = "app.geckopresence.host.action.REFRESH_MEDIA"
        const val ACTION_APPLY_MANUAL = "app.geckopresence.host.action.APPLY_MANUAL"
        const val ACTION_CLEAR_MANUAL = "app.geckopresence.host.action.CLEAR_MANUAL"
        const val ACTION_BROWSER_PRESENCE = "app.geckopresence.host.action.BROWSER_PRESENCE"
        const val ACTION_BROWSER_CLEAR = "app.geckopresence.host.action.BROWSER_CLEAR"
        const val ACTION_STOP = "app.geckopresence.host.action.STOP"
        const val ACTION_TURN_ON = RpcStartupPolicy.ACTION_TURN_ON
        const val ACTION_CONNECT_DISCORD = RpcStartupPolicy.ACTION_CONNECT_DISCORD
        const val ACTION_DISCONNECT_DISCORD = RpcStartupPolicy.ACTION_DISCONNECT_DISCORD
        const val ACTION_STATUS_CHANGED = "app.geckopresence.host.action.STATUS_CHANGED"

        const val EXTRA_ENGINE_STATE = "engine_state"
        const val EXTRA_GATEWAY_STATE = "gateway_state"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_PRESENCE_PREVIEW = "presence_preview"
        const val EXTRA_APP_TRACKING_STATUS = "app_tracking_status"
        const val EXTRA_MEDIA_TRACKING_STATUS = "media_tracking_status"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_NAME = "name"
        const val EXTRA_DETAILS = "details"
        const val EXTRA_STATE = "state"
        const val EXTRA_MEDIA_STATE = "media_state"
        const val EXTRA_START_TIMESTAMP = "start_timestamp"
        const val EXTRA_SOURCE_TYPE = "source_type"
        const val EXTRA_PRIORITY = "priority"
        const val EXTRA_EXPIRES_AT = "expires_at"

        const val INTERNAL_BROADCAST_PERMISSION = "app.geckopresence.host.permission.INTERNAL_BROADCAST"
        const val PREFS_SETTINGS = "gpb_settings"
        const val PREFS_RUNTIME = "gpb_runtime"
        const val KEY_ENGINE_STATE = "engine_state"
        const val KEY_GATEWAY_STATE = "gateway_state"
        const val KEY_STATUS_MESSAGE = "status_message"
        const val KEY_PRESENCE_PREVIEW = "presence_preview"
        const val KEY_APP_TRACKER_STATUS = "app_tracker_status"
        const val KEY_MEDIA_TRACKER_STATUS = "media_tracker_status"
        const val KEY_EVENT_LOG = "event_log"
        const val KEY_TRACK_MEDIA_SESSIONS = "track_media_sessions"
        const val KEY_AUTO_SOURCES_ENABLED = "auto_sources_enabled"
        const val KEY_PRESENCE_STATUS = "presence_status"
        const val KEY_MANUAL_ACTIVITY_TYPE = "manual_presence_activity_type"
        const val KEY_MANUAL_STREAM_URL = "manual_presence_stream_url"
        const val KEY_MANUAL_LARGE_IMAGE = "manual_presence_large_image"
        const val KEY_MANUAL_LARGE_TOOLTIP = "manual_presence_large_tooltip"
        const val KEY_MANUAL_SMALL_IMAGE = "manual_presence_small_image"
        const val KEY_MANUAL_SMALL_TOOLTIP = "manual_presence_small_tooltip"
        const val KEY_MANUAL_BUTTON1_LABEL = "manual_presence_button1_label"
        const val KEY_MANUAL_BUTTON1_URL = "manual_presence_button1_url"
        const val KEY_MANUAL_BUTTON2_LABEL = "manual_presence_button2_label"
        const val KEY_MANUAL_BUTTON2_URL = "manual_presence_button2_url"
        const val KEY_MANUAL_END = "manual_presence_end"
        const val KEY_MANUAL_TIMESTAMP_DISPLAY_MODE = "manual_presence_timestamp_display_mode"
        const val KEY_MANUAL_PLATFORM = "manual_presence_platform"
        const val KEY_MANUAL_ACTIVE = "manual_presence_active"
        const val KEY_MANUAL_NAME = "manual_presence_name"
        const val KEY_MANUAL_DETAILS = "manual_presence_details"
        const val KEY_MANUAL_STATE = "manual_presence_state"
        const val KEY_MANUAL_START = "manual_presence_start"

        const val STATE_STARTING = "STARTING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_STOPPED = "STOPPED"
        const val STATE_ERROR = "ERROR"
        const val SOURCE_WEBSITE = "website"
        const val SOURCE_ANDROID_APP = "android-app"
        const val SOURCE_MANUAL = "manual"

        private const val WEBSITE_TTL_MS = 75_000L
        private const val MAX_BROWSER_TTL_MS = 5 * 60_000L
        private const val EXPIRY_SWEEP_INTERVAL_MS = 10_000L
        private const val NOTIFICATION_CHANNEL_ID = "gpb_presence_engine"
        private const val NOTIFICATION_ID = 32145
        private const val MAX_EVENT_LOG_LINES = 40
        private const val TAG = "GeckoPresenceEngine"
    }
}
