package app.geckopresence.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.geckopresence.host.core.PresenceUpdate
import app.geckopresence.host.core.PresenceSourceArbiter
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PresenceBridgeService : Service() {
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GPB-discord-auth").apply { isDaemon = true }
    }
    private val refreshInProgress = AtomicBoolean(false)

    @Volatile
    private var latestPresence: PresenceUpdate? = null
    private val presenceCandidates = linkedMapOf<String, PresenceUpdate>()
    private val presenceLock = Any()

    @Volatile
    private var bridgeState = "STARTING"

    @Volatile
    private var sdkState = "NOT_CONNECTED"

    private var bridgeServer: BridgeHttpServer? = null
    private var usageAppTracker: UsageAppTracker? = null
    private var refreshRunnable: Runnable? = null

    @Volatile
    private var appTrackerStatus = "Detector not started"

    inner class LocalBinder : Binder() {
        fun service(): PresenceBridgeService = this@PresenceBridgeService
    }

    override fun onCreate() {
        super.onCreate()
        SecureStore.getOrCreateBridgeKey(this)
        createNotificationChannel()
        startPresenceNotification("Starting local bridge")

        DiscordSdk.setStatusListener(::handleSdkStatus)
        DiscordSdk.setSessionListener(::handleSdkSession)

        bridgeServer = BridgeHttpServer(
            context = this,
            onPresence = ::handleIncomingPresence,
            onClear = ::handleClearPresence,
            onManualClear = ::handleManualOverrideClear,
            sharePageTitles = ::sharePageTitles,
            healthSnapshot = ::healthSnapshot,
            onServerState = ::handleServerState,
        ).also { it.start() }

        if (BuildConfig.DISCORD_SDK_ENABLED && BuildConfig.DISCORD_APP_ID > 0L) {
            val initialized = DiscordSdk.initialize(BuildConfig.DISCORD_APP_ID)
            if (initialized) {
                sdkState = "SDK_READY"
                publishStatus()
                resumeSavedSession()
            } else {
                setSdkState("SDK_ERROR", "Discord Social SDK could not be initialized")
            }
        } else {
            setSdkState("SDK_MISSING", "Add the official Discord Social SDK AAR and configure the Application ID")
        }
        updateAppTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> publishStatus()
            ACTION_AUTHORIZE -> beginAuthorization()
            ACTION_LOGOUT -> logoutDiscord()
            ACTION_UPDATE_APP_TRACKING -> updateAppTracking()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun isBridgeListening(): Boolean = bridgeState == "LISTENING"

    fun currentBridgeState(): String = bridgeState

    fun currentSdkState(): String = sdkState

    fun startAuthorization() {
        beginAuthorization()
    }

    fun stopBridge() {
        stopSelf()
    }

    private fun updateAppTracking() {
        handleClearAppPresence()
        usageAppTracker?.stop()
        usageAppTracker = null

        val settings = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        val enabled = settings.getBoolean(AppPresencePolicy.KEY_TRACK_APPS, false)
        val selected = settings.getStringSet(AppPresencePolicy.KEY_TRACKED_PACKAGES, emptySet()).orEmpty()
        when {
            !enabled -> {
                setAppTrackerStatus("Detector off · enable Track Selected Android Apps")
                handleClearAppPresence()
                return
            }
            selected.isEmpty() -> {
                setAppTrackerStatus("No apps selected · choose at least one eligible app")
                handleClearAppPresence()
                return
            }
            !UsageAppTracker.hasUsageAccess(this) -> {
                setAppTrackerStatus("Usage Access missing · grant it in Android Settings")
                handleClearAppPresence()
                return
            }
        }

        setAppTrackerStatus("Detector starting · ${selected.size} allow-listed apps")
        usageAppTracker = UsageAppTracker(
            context = this,
            allowedPackages = selected.toSet(),
            onForegroundPackageChanged = ::handleForegroundPackageChanged,
            onDiagnosticsChanged = ::setAppTrackerStatus,
        ).also { it.start() }
    }

    private fun setAppTrackerStatus(status: String) {
        appTrackerStatus = status
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_TRACKER_STATUS, status)
            .apply()
        publishStatus()
    }

    private fun handleForegroundPackageChanged(packageName: String?) {
        val update = packageName?.let { AppPresencePolicy.buildUpdate(this, it) }
        if (update == null) {
            handleClearAppPresence()
            return
        }
        handleIncomingPresence(update)
    }

    private fun beginAuthorization() {
        if (!BuildConfig.DISCORD_SDK_ENABLED || BuildConfig.DISCORD_APP_ID <= 0L) {
            setSdkState("SDK_MISSING", "Discord Social SDK is not configured in this build")
            return
        }
        if (!DiscordSdk.isReady && sdkState == "SDK_ERROR") {
            val initialized = DiscordSdk.initialize(BuildConfig.DISCORD_APP_ID)
            if (!initialized) return
        }
        sdkState = "AUTHORIZING"
        publishStatus()
        if (!DiscordSdk.authorize()) {
            setSdkState("AUTH_ERROR", "Discord authorization could not be started")
        }
    }

    private fun resumeSavedSession() {
        val refreshToken = SecureStore.getRefreshToken(this) ?: return
        refreshDiscordSession(refreshToken, connectAfterRefresh = true)
    }

    private fun handleSdkSession(refreshToken: String, expiresInSeconds: Int) {
        runCatching { SecureStore.storeRefreshToken(this, refreshToken) }
        scheduleRefresh(expiresInSeconds)
        if (sdkState != "CONNECTED") {
            sdkState = "AUTHORIZING"
            publishStatus()
        }
    }

    private fun handleSdkStatus(status: String, error: String?) {
        when (status) {
            "initialized" -> {
                sdkState = "SDK_READY"
                publishStatus()
            }
            "ready" -> {
                sdkState = "CONNECTED"
                publishStatus()
                latestPresence?.let { DiscordSdk.setPresence(it) }
                updateNotification("Discord connected · local bridge active")
            }
            "connecting", "connected", "reconnecting" -> {
                sdkState = "CONNECTING"
                publishStatus()
            }
            "disconnected", "disconnecting" -> {
                sdkState = if (SecureStore.hasRefreshToken(this)) "DISCONNECTED" else "AUTH_REQUIRED"
                publishStatus()
            }
            "presence_error" -> {
                publishStatus(error)
            }
            "error", "sdk_error" -> {
                sdkState = "AUTH_ERROR"
                publishStatus(error)
                updateNotification("Discord connection needs attention")
            }
            else -> {
                sdkState = status.uppercase()
                publishStatus(error)
            }
        }
    }

    private fun handleIncomingPresence(update: PresenceUpdate) {
        updatePresenceCandidates { current -> PresenceSourceArbiter.upsert(current, update) }
    }

    /** Browser-originated clear removes only website candidates. */
    private fun handleClearPresence() {
        updatePresenceCandidates { current -> PresenceSourceArbiter.removeSourceType(current, "website") }
    }

    private fun handleClearAppPresence() {
        updatePresenceCandidates { current -> PresenceSourceArbiter.removeSourceType(current, "android-app") }
    }

    private fun handleManualOverrideClear() {
        updatePresenceCandidates { current -> PresenceSourceArbiter.removeSourceType(current, "manual") }
    }

    private fun updatePresenceCandidates(transform: (Map<String, PresenceUpdate>) -> Map<String, PresenceUpdate>) {
        val winner = synchronized(presenceLock) {
            val updated = transform(presenceCandidates)
            presenceCandidates.clear()
            presenceCandidates.putAll(updated)
            PresenceSourceArbiter.winner(presenceCandidates)
        }
        applyPresenceWinner(winner)
    }

    private fun applyPresenceWinner(winner: PresenceUpdate?) {
        latestPresence = winner
        if (DiscordSdk.isReady) latestPresence?.let { DiscordSdk.setPresence(it) } ?: DiscordSdk.clearPresence()
        publishStatus()
    }

    private fun handleServerState(state: String, message: String?) {
        bridgeState = state
        publishStatus(message)
        updateNotification(
            when (state) {
                "LISTENING" -> "Local bridge listening on port ${BridgeHttpServer.PORT}"
                "PORT_IN_USE" -> "Local bridge port is already in use"
                "SERVER_ERROR" -> "Local bridge could not start"
                else -> "Local bridge stopped"
            },
        )
    }

    private fun setSdkState(state: String, message: String? = null) {
        sdkState = state
        publishStatus(message)
        updateNotification(message ?: state.replace('_', ' ').lowercase())
    }

    private fun publishStatus(message: String? = null) {
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE)
            .edit()
            .putString(KEY_BRIDGE_STATE, bridgeState)
            .putString(KEY_SDK_STATE, sdkState)
            .putString(KEY_STATUS_MESSAGE, message.orEmpty())
            .putString(KEY_PRESENCE_PREVIEW, latestPresence?.let { previewText(it) } ?: "Waiting for browser or Android app activity…")
            .putString(KEY_APP_TRACKER_STATUS, appTrackerStatus)
            .apply()

        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_BRIDGE_STATE, bridgeState)
                .putExtra(EXTRA_SDK_STATE, sdkState)
                .putExtra(EXTRA_STATUS_MESSAGE, message.orEmpty())
                .putExtra(EXTRA_PRESENCE_PREVIEW, latestPresence?.let { previewText(it) } ?: "Waiting for browser or Android app activity…")
                .putExtra(EXTRA_APP_TRACKING_STATUS, appTrackerStatus),
        )
    }

    private fun previewText(update: PresenceUpdate): String {
        val title = update.state?.let { "\n$it" }.orEmpty()
        return "${update.name}\n${update.details}$title\n${update.source}"
    }

    private fun healthSnapshot(): JSONObject {
        return JSONObject()
            .put("ok", true)
            .put("service", "Gecko Presence Host")
            .put("bridgeState", bridgeState.lowercase())
            .put("discordSdkEnabled", BuildConfig.DISCORD_SDK_ENABLED)
            .put("discordReady", DiscordSdk.isReady)
            .put("apiVersion", 1)
    }

    private fun sharePageTitles(): Boolean {
        return getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
            .getBoolean(KEY_SHARE_TITLES, true)
    }

    private fun refreshDiscordSession(refreshToken: String, connectAfterRefresh: Boolean) {
        if (!refreshInProgress.compareAndSet(false, true)) return
        networkExecutor.execute {
            try {
                val refreshed = DiscordTokenRefresher.refresh(
                    BuildConfig.DISCORD_APP_ID,
                    refreshToken,
                )
                mainHandler.post {
                    refreshInProgress.set(false)
                    SecureStore.storeRefreshToken(this, refreshed.refreshToken)
                    val accepted = if (connectAfterRefresh || !DiscordSdk.isReady) {
                        DiscordSdk.connectWithAccessToken(refreshed.accessToken)
                    } else {
                        DiscordSdk.refreshAccessToken(refreshed.accessToken)
                    }
                    if (!accepted) {
                        setSdkState("AUTH_ERROR", "The Discord SDK rejected the refreshed session")
                    } else {
                        sdkState = "CONNECTING"
                        publishStatus()
                        scheduleRefresh(refreshed.expiresInSeconds)
                    }
                }
            } catch (exception: Exception) {
                mainHandler.post {
                    refreshInProgress.set(false)
                    val error = exception.message.orEmpty()
                    if (error.contains("invalid_grant", ignoreCase = true) ||
                        error.contains("invalid_client", ignoreCase = true)
                    ) {
                        SecureStore.clearRefreshToken(this)
                        setSdkState("AUTH_REQUIRED", "Discord session expired. Sign in again.")
                    } else {
                        setSdkState("NETWORK_ERROR", "Discord session refresh failed. Check connectivity.")
                        scheduleRefresh(RETRY_REFRESH_SECONDS)
                    }
                }
            }
        }
    }

    private fun scheduleRefresh(expiresInSeconds: Int) {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        val delaySeconds = (expiresInSeconds - REFRESH_SKEW_SECONDS).coerceAtLeast(60)
        refreshRunnable = Runnable {
            val token = SecureStore.getRefreshToken(this)
            if (!token.isNullOrBlank()) {
                refreshDiscordSession(token, connectAfterRefresh = !DiscordSdk.isReady)
            }
        }
        mainHandler.postDelayed(refreshRunnable!!, delaySeconds * 1000L)
    }

    private fun logoutDiscord() {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null
        SecureStore.clearRefreshToken(this)
        latestPresence = null
        synchronized(presenceLock) { presenceCandidates.clear() }
        DiscordSdk.clearPresence()
        DiscordSdk.disconnect()
        setSdkState("AUTH_REQUIRED", "Discord account disconnected")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Gecko Presence Bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Visible status for the user-started local presence bridge"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Gecko Presence Host")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Gecko Presence Host")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    private fun startPresenceNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        refreshRunnable?.let(mainHandler::removeCallbacks)
        refreshRunnable = null
        usageAppTracker?.stop()
        usageAppTracker = null
        bridgeServer?.close()
        bridgeServer = null
        DiscordSdk.setStatusListener(null)
        DiscordSdk.setSessionListener(null)
        DiscordSdk.clearPresence()
        synchronized(presenceLock) { presenceCandidates.clear() }
        latestPresence = null
        DiscordSdk.disconnect()
        DiscordSdk.destroy()
        networkExecutor.shutdownNow()
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE)
            .edit()
            .putString(KEY_BRIDGE_STATE, "STOPPED")
            .putString(KEY_SDK_STATE, if (SecureStore.hasRefreshToken(this)) "AUTH_SAVED" else "AUTH_REQUIRED")
            .putString(KEY_APP_TRACKER_STATUS, "Detector stopped")
            .apply()
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_BRIDGE_STATE, "STOPPED")
                .putExtra(EXTRA_SDK_STATE, if (SecureStore.hasRefreshToken(this)) "AUTH_SAVED" else "AUTH_REQUIRED")
                .putExtra(EXTRA_PRESENCE_PREVIEW, "Waiting for browser or Android app activity…")
                .putExtra(EXTRA_APP_TRACKING_STATUS, "Detector stopped"),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.geckopresence.host.action.START"
        const val ACTION_AUTHORIZE = "app.geckopresence.host.action.AUTHORIZE"
        const val ACTION_LOGOUT = "app.geckopresence.host.action.LOGOUT"
        const val ACTION_UPDATE_APP_TRACKING = "app.geckopresence.host.action.UPDATE_APP_TRACKING"
        const val ACTION_STOP = "app.geckopresence.host.action.STOP"
        const val ACTION_STATUS_CHANGED = "app.geckopresence.host.action.STATUS_CHANGED"
        const val EXTRA_BRIDGE_STATE = "bridge_state"
        const val EXTRA_SDK_STATE = "sdk_state"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_PRESENCE_PREVIEW = "presence_preview"
        const val EXTRA_APP_TRACKING_STATUS = "app_tracking_status"
        const val KEY_APP_TRACKER_STATUS = "app_tracker_status"

        private const val PREFS_RUNTIME = "gpb_runtime"
        private const val PREFS_SETTINGS = "gpb_settings"
        private const val KEY_BRIDGE_STATE = "bridge_state"
        private const val KEY_SDK_STATE = "sdk_state"
        private const val KEY_STATUS_MESSAGE = "status_message"
        private const val KEY_PRESENCE_PREVIEW = "presence_preview"
        const val KEY_SHARE_TITLES = "share_page_titles"

        private const val NOTIFICATION_CHANNEL_ID = "gpb_bridge"
        private const val NOTIFICATION_ID = 32145
        private const val REFRESH_SKEW_SECONDS = 120
        private const val RETRY_REFRESH_SECONDS = 60
    }
}
