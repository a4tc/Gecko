package app.geckopresence.host

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import app.geckopresence.host.core.ForegroundEventKind
import app.geckopresence.host.core.ForegroundEventReducer

data class SelectableApp(val packageName: String, val label: String)

/**
 * Low-frequency foreground-package observer. UsageEvents are queried on a worker thread.
 * Only the current allow-listed package is retained; no usage history is written to disk.
 */
class UsageAppTracker(
    context: Context,
    private val allowedPackages: Set<String>,
    private val onForegroundPackageChanged: (String?) -> Unit,
    private val onDiagnosticsChanged: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val usageStats = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    @Volatile
    private var running = false
    @Volatile
    private var generation = 0

    private var lastQueryTime = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
    private var foregroundPackage: String? = null
    private var lastReportedPackage: String? = null
    private var pollCount = 0
    private var totalEventsRead = 0
    private var lastSelectedAppLabel: String? = null

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            pollForegroundPackage()
            if (running) workerHandler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        generation += 1
        lastQueryTime = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        pollCount = 0
        totalEventsRead = 0
        lastSelectedAppLabel = null
        val thread = HandlerThread("GPB-AppTracker").apply { start() }
        workerThread = thread
        workerHandler = Handler(thread.looper)
        emitDiagnostics("Detector starting · waiting for UsageEvents")
        workerHandler?.post(pollTask)
    }

    fun stop() {
        if (!running && workerThread == null) return
        running = false
        generation += 1
        workerHandler?.removeCallbacks(pollTask)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
        foregroundPackage = null
        val shouldClear = lastReportedPackage != null
        lastReportedPackage = null
        if (shouldClear) {
            val callbackGeneration = generation
            mainHandler.post {
                if (callbackGeneration == generation) onForegroundPackageChanged(null)
            }
        }
    }

    private fun pollForegroundPackage() {
        pollCount += 1
        if (!hasUsageAccess(appContext)) {
            foregroundPackage = null
            report(null)
            emitDiagnostics("Usage Access missing · grant it in Android Settings")
            return
        }

        val now = System.currentTimeMillis()
        val begin = maxOf(0L, lastQueryTime, now - MAX_LOOKBACK_MS)
        try {
            val events = usageStats.queryEvents(begin, now)
            val event = UsageEvents.Event()
            var eventsRead = 0
            var lastEventLabel = "none"

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                eventsRead += 1
                totalEventsRead += 1
                val packageName = event.packageName
                val kind = classifyEvent(event.eventType)
                lastEventLabel = eventTypeLabel(event.eventType)
                val allowed = kind == ForegroundEventKind.RESUMED &&
                    !packageName.isNullOrBlank() && isAllowedPackage(packageName)
                if (allowed && packageName != null) {
                    lastSelectedAppLabel = appLabel(packageName)
                }
                foregroundPackage = ForegroundEventReducer.reduce(
                    currentPackage = foregroundPackage,
                    eventPackage = packageName,
                    eventKind = kind,
                    isAllowedPackage = allowed,
                )
            }

            lastQueryTime = maxOf(begin, now - OVERLAP_MS)
            report(foregroundPackage)
            val activeLabel = foregroundPackage?.let(::appLabel)
            val eventSummary = if (eventsRead == 0) "no new events" else "$eventsRead events · last $lastEventLabel"
            val activeSummary = activeLabel?.let { "active $it" } ?: "no selected app active"
            val previousSummary = lastSelectedAppLabel?.let { " · last selected $it" }.orEmpty()
            emitDiagnostics("Detector polling · $pollCount polls · $totalEventsRead total events · $eventSummary · $activeSummary$previousSummary")
        } catch (_: SecurityException) {
            foregroundPackage = null
            report(null)
            emitDiagnostics("UsageEvents query denied · recheck Usage Access permission")
        } catch (exception: RuntimeException) {
            // Keep the last known foreground state during transient OEM query failures.
            emitDiagnostics("UsageEvents query error · ${exception.javaClass.simpleName} · retrying")
        }
    }

    private fun classifyEvent(type: Int): ForegroundEventKind = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            type == UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ForegroundEventKind.SCREEN_NON_INTERACTIVE
        isResumedEvent(type) -> ForegroundEventKind.RESUMED
        isPausedEvent(type) -> ForegroundEventKind.PAUSED
        else -> ForegroundEventKind.OTHER
    }

    private fun isAllowedPackage(packageName: String): Boolean {
        if (packageName !in allowedPackages || packageName == appContext.packageName) return false
        val appInfo = runCatching { appContext.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return false
        val label = runCatching { appContext.packageManager.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)
        return app.geckopresence.host.core.AppActivityRules.isSelectableApp(
            packageName,
            label,
            appContext.packageName,
        )
    }

    private fun appLabel(packageName: String): String {
        val appInfo = runCatching { appContext.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return "selected app"
        return runCatching { appContext.packageManager.getApplicationLabel(appInfo).toString() }
            .getOrDefault("selected app")
    }

    private fun report(packageName: String?) {
        if (packageName == lastReportedPackage) return
        lastReportedPackage = packageName
        val callbackGeneration = generation
        mainHandler.post {
            if (callbackGeneration == generation) onForegroundPackageChanged(packageName)
        }
    }

    private fun emitDiagnostics(message: String) {
        val callbackGeneration = generation
        mainHandler.post {
            if (callbackGeneration == generation) onDiagnosticsChanged(message)
        }
    }

    private fun isResumedEvent(type: Int): Boolean {
        @Suppress("DEPRECATION")
        val legacy = type == UsageEvents.Event.MOVE_TO_FOREGROUND
        return type == UsageEvents.Event.ACTIVITY_RESUMED || legacy
    }

    private fun isPausedEvent(type: Int): Boolean {
        @Suppress("DEPRECATION")
        val legacy = type == UsageEvents.Event.MOVE_TO_BACKGROUND
        // ACTIVITY_STOPPED can belong to an older activity in the same package after a
        // replacement activity has already resumed. PAUSED is the foreground transition.
        return type == UsageEvents.Event.ACTIVITY_PAUSED || legacy
    }

    private fun eventTypeLabel(type: Int): String = when {
        type == UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        type == UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        type == UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_OFF"
        else -> "EVENT_$type"
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
        private const val INITIAL_LOOKBACK_MS = 2 * 60_000L
        private const val MAX_LOOKBACK_MS = 5 * 60_000L
        private const val OVERLAP_MS = 3_000L

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            return appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }

        fun selectableApps(context: Context): List<SelectableApp> {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val packageManager = context.packageManager
            val resolved = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
            return resolved.mapNotNull { info ->
                val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                val label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(appInfo.packageName)
                if (!app.geckopresence.host.core.AppActivityRules.isSelectableApp(
                        appInfo.packageName,
                        label,
                        context.packageName,
                    )
                ) return@mapNotNull null
                SelectableApp(appInfo.packageName, label)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
    }
}
