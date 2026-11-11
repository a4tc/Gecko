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
import app.geckopresence.host.core.PresenceSourceArbiter
import app.geckopresence.host.core.PresenceUpdate

/** Owns native source lifecycles, candidate arbitration, and Discord Direct RPC publishing. */
class PresenceEngineService : Service() {
    private val presenceCandidates = linkedMapOf<String, PresenceUpdate>()
    private val presenceLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var latestPresence: PresenceUpdate? = null
    @Volatile private var engineState = "STARTING"
    @Volatile private var sdkState = "SDK_NOT_INITIALIZED"
    @Volatile private var appTrackerStatus = "App detector not started"
    @Volatile private var mediaTrackerStatus = "Media detector not started"
    @Volatile private var lastStatusMessage = ""

    private var usageAppTracker: UsageAppTracker? = null
    private var mediaSessionTracker: MediaSessionPresenceTracker? = null
    private var receiverRegistered = false

    private val sourceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_BROWSER_PRESENCE -> receiveBrowserPresence(intent)
                ACTION_BROWSER_CLEAR -> {
                    if (intent.getStringExtra(EXTRA_SOURCE_TYPE) == SOURCE_WEBSITE) {
                        clearSourceType(SOURCE_WEBSITE)
                    }
                }
                ACTION_REFRESH_MEDIA -> updateMediaTracking()
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
        createNotificationChannel()
        startPresenceNotification("Starting native presence engine")
        registerSourceReceiver()
        DiscordSdk.setStatusListener(::handleSdkStatus)

        if (BuildConfig.DISCORD_SDK_ENABLED && BuildConfig.DISCORD_APP_ID > 0L) {
            if (DiscordSdk.initialize(BuildConfig.DISCORD_APP_ID)) {
                sdkState = SDK_INITIALIZED
                lastStatusMessage = "Discord Direct RPC initialized"
            } else {
                sdkState = SDK_ERROR
                lastStatusMessage = "Discord Social SDK could not be initialized"
            }
        } else {
            sdkState = SDK_MISSING
            lastStatusMessage = "Official Discord SDK AAR or Application ID is missing"
        }

        engineState = STATE_RUNNING
        restoreManualPresence()
        updateAppTracking()
        updateMediaTracking()
        mainHandler.post(expiryTask)
        publishStatus(lastStatusMessage)
        updateNotification("Native presence engine running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                engineState = STATE_RUNNING
                updateAppTracking()
                updateMediaTracking()
                publishStatus()
            }
            ACTION_UPDATE_APP_TRACKING -> updateAppTracking()
            ACTION_UPDATE_MEDIA_TRACKING, ACTION_REFRESH_MEDIA -> updateMediaTracking()
            ACTION_APPLY_MANUAL -> applyManualPresence()
            ACTION_CLEAR_MANUAL -> clearManualPresence()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
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
        if (update == null) clearSourceType(SOURCE_ANDROID_APP) else handleIncomingPresence(update)
    }

    private fun restoreManualPresence() {
        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        if (settings.getBoolean(KEY_MANUAL_ACTIVE, false)) applyManualPresence()
    }

    private fun applyManualPresence() {
        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        if (!settings.getBoolean(KEY_MANUAL_ACTIVE, false)) return
        val name = app.geckopresence.host.core.PresenceRules.cleanText(settings.getString(KEY_MANUAL_NAME, null), 80) ?: "Gecko Presence"
        val details = app.geckopresence.host.core.PresenceRules.cleanText(settings.getString(KEY_MANUAL_DETAILS, null), 120) ?: "Custom activity"
        val state = app.geckopresence.host.core.PresenceRules.cleanText(settings.getString(KEY_MANUAL_STATE, null), 120)
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

    private fun handleSdkStatus(status: String, error: String?) {
        when (status) {
            "initialized", "ready" -> {
                sdkState = SDK_INITIALIZED
                lastStatusMessage = error ?: "Discord Direct RPC initialized"
                latestPresence?.let(DiscordSdk::setPresence)
                updateNotification("Discord Direct RPC ready")
            }
            "presence_updated" -> {
                sdkState = SDK_INITIALIZED
                lastStatusMessage = "Discord accepted the Rich Presence update"
            }
            "presence_cleared" -> {
                sdkState = SDK_INITIALIZED
                lastStatusMessage = "Discord accepted the clear request"
            }
            "presence_error" -> lastStatusMessage = error ?: "Discord rejected a presence update"
            "error", "sdk_error" -> {
                sdkState = SDK_ERROR
                lastStatusMessage = error ?: "Discord Social SDK error"
                updateNotification("Discord SDK needs attention")
            }
            else -> {
                sdkState = if (DiscordSdk.isInitialized) SDK_INITIALIZED else SDK_NOT_INITIALIZED
                if (!error.isNullOrBlank()) lastStatusMessage = error
            }
        }
        publishStatus(lastStatusMessage)
    }

    private fun handleIncomingPresence(update: PresenceUpdate) {
        updatePresenceCandidates { PresenceSourceArbiter.upsert(it, update) }
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
        if (DiscordSdk.isInitialized) {
            if (winner == null) DiscordSdk.clearPresence() else DiscordSdk.setPresence(winner)
        }
        publishStatus()
    }

    private fun publishStatus(message: String? = null) {
        val statusMessage = message ?: lastStatusMessage
        val preview = latestPresence?.let(::previewText) ?: "No active source"
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
            .putString(KEY_ENGINE_STATE, engineState)
            .putString(KEY_SDK_STATE, sdkState)
            .putString(KEY_STATUS_MESSAGE, statusMessage)
            .putString(KEY_PRESENCE_PREVIEW, preview)
            .putString(KEY_APP_TRACKER_STATUS, appTrackerStatus)
            .putString(KEY_MEDIA_TRACKER_STATUS, mediaTrackerStatus)
            .apply()
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_ENGINE_STATE, engineState)
                .putExtra(EXTRA_SDK_STATE, sdkState)
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
        DiscordSdk.setStatusListener(null)
        if (DiscordSdk.isInitialized) DiscordSdk.clearPresence()
        synchronized(presenceLock) {
            presenceCandidates.clear()
            latestPresence = null
        }
        DiscordSdk.destroy()
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
            .putString(KEY_ENGINE_STATE, STATE_STOPPED)
            .putString(KEY_SDK_STATE, SDK_NOT_INITIALIZED)
            .putString(KEY_APP_TRACKER_STATUS, "App detector stopped")
            .putString(KEY_MEDIA_TRACKER_STATUS, "Media detector stopped")
            .putString(KEY_PRESENCE_PREVIEW, "No active source")
            .apply()
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED).setPackage(packageName)
                .putExtra(EXTRA_ENGINE_STATE, STATE_STOPPED)
                .putExtra(EXTRA_SDK_STATE, SDK_NOT_INITIALIZED)
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
        const val ACTION_STATUS_CHANGED = "app.geckopresence.host.action.STATUS_CHANGED"

        const val EXTRA_ENGINE_STATE = "engine_state"
        const val EXTRA_SDK_STATE = "sdk_state"
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
        const val KEY_SDK_STATE = "sdk_state"
        const val KEY_STATUS_MESSAGE = "status_message"
        const val KEY_PRESENCE_PREVIEW = "presence_preview"
        const val KEY_APP_TRACKER_STATUS = "app_tracker_status"
        const val KEY_MEDIA_TRACKER_STATUS = "media_tracker_status"
        const val KEY_TRACK_MEDIA_SESSIONS = "track_media_sessions"
        const val KEY_AUTO_SOURCES_ENABLED = "auto_sources_enabled"
        const val KEY_MANUAL_ACTIVE = "manual_presence_active"
        const val KEY_MANUAL_NAME = "manual_presence_name"
        const val KEY_MANUAL_DETAILS = "manual_presence_details"
        const val KEY_MANUAL_STATE = "manual_presence_state"
        const val KEY_MANUAL_START = "manual_presence_start"

        const val STATE_STARTING = "STARTING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_STOPPED = "STOPPED"
        const val SDK_INITIALIZED = "SDK_INITIALIZED"
        const val SDK_MISSING = "SDK_MISSING"
        const val SDK_ERROR = "SDK_ERROR"
        const val SDK_NOT_INITIALIZED = "SDK_NOT_INITIALIZED"
        const val SOURCE_WEBSITE = "website"
        const val SOURCE_ANDROID_APP = "android-app"
        const val SOURCE_MANUAL = "manual"

        private const val WEBSITE_TTL_MS = 75_000L
        private const val MAX_BROWSER_TTL_MS = 5 * 60_000L
        private const val EXPIRY_SWEEP_INTERVAL_MS = 10_000L
        private const val NOTIFICATION_CHANNEL_ID = "gpb_presence_engine"
        private const val NOTIFICATION_ID = 32145
    }
}
