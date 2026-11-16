package app.geckopresence.host

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import app.geckopresence.host.core.SensitiveDataRedactor
import android.os.Build
import android.os.Process
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local-only diagnostics for application crashes and unexpected process exits.
 * Reports remain in the application's private files directory and are never uploaded.
 */
object CrashDiagnostics {
    private const val PREFS = "gpb_diagnostics"
    private const val KEY_LAST_STAGE = "last_stage"
    private const val KEY_LAST_STAGE_AT = "last_stage_at"
    private const val KEY_LAST_EXIT_AT = "last_exit_at"
    private const val REPORT_PREFIX = "gecko_crash_"
    private const val EXIT_PREFIX = "gecko_exit_"
    private const val LEGACY_TRACE_PREFIX = "gecko_tombstone_"
    private const val MAX_REPORTS = 12

    fun install(context: Context) {
        val appContext = context.applicationContext
        purgeLegacyMemorySnapshots(appContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeReport(
                    appContext,
                    "$REPORT_PREFIX${System.currentTimeMillis()}.txt",
                    buildString {
                        appendLine("Gecko Presence uncaught exception")
                        appendLine("Thread: ${thread.name}")
                        appendLine("Exception: ${throwable.javaClass.name}")
                        appendLine("Message: ${SensitiveDataRedactor.redact(throwable.message.orEmpty())}")
                        appendLine(deviceHeader(appContext))
                        appendLine(checkpointHeader(appContext))
                        appendLine("Stack trace:")
                        append(SensitiveDataRedactor.redact(stackTrace(throwable)))
                    },
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** Synchronously persist a coarse application lifecycle checkpoint. */
    fun recordCheckpoint(context: Context, stage: String, detail: String? = null) {
        val safeStage = SensitiveDataRedactor.redact(stage.trim().take(100)).ifBlank { "unknown" }
        val safeDetail = SensitiveDataRedactor.redact(detail?.replace(Regex("[\\r\\n\\u0000-\\u001f]"), " ")?.take(180).orEmpty())
        val value = if (safeDetail.isBlank()) safeStage else "$safeStage · $safeDetail"
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STAGE, value)
            .putLong(KEY_LAST_STAGE_AT, System.currentTimeMillis())
            .commit()
    }

    fun recordNonFatal(context: Context, tag: String, throwable: Throwable) {
        runCatching {
            writeReport(
                context.applicationContext,
                "$REPORT_PREFIX${System.currentTimeMillis()}.txt",
                buildString {
                    appendLine("Gecko Presence non-fatal diagnostic")
                    appendLine("Tag: ${SensitiveDataRedactor.redact(tag.take(100))}")
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine("Message: ${SensitiveDataRedactor.redact(throwable.message.orEmpty())}")
                    appendLine(deviceHeader(context))
                    appendLine(checkpointHeader(context))
                    appendLine("Stack trace:")
                    append(SensitiveDataRedactor.redact(stackTrace(throwable)))
                },
            )
        }
    }

    /** Capture the previous app process exit after Android restarts this application. */
    fun capturePreviousProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext
        try {
            val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = preferences.getLong(KEY_LAST_EXIT_AT, 0L)
            val exits = activityManager.getHistoricalProcessExitReasons(appContext.packageName, 0, 12)
                .filter { it.timestamp > lastSeen }
                .sortedBy(ApplicationExitInfo::getTimestamp)
            if (exits.isEmpty()) return

            var newestTimestamp = lastSeen
            exits.forEach { exitInfo ->
                val timestamp = exitInfo.timestamp
                newestTimestamp = maxOf(newestTimestamp, timestamp)
                val baseName = "$EXIT_PREFIX${timestamp}_${exitInfo.pid}"
                val report = buildString {
                    appendLine("Gecko Presence previous process exit")
                    appendLine("Time: ${Date(timestamp)} ($timestamp)")
                    appendLine("Process: ${exitInfo.processName.orEmpty()}")
                    appendLine("PID: ${exitInfo.pid}")
                    appendLine("Reason: ${reasonName(exitInfo.reason)} (${exitInfo.reason})")
                    appendLine("Status / signal: ${exitInfo.status}")
                    appendLine("Importance: ${exitInfo.importance}")
                    appendLine("Description: ${SensitiveDataRedactor.redact(exitInfo.description.orEmpty())}")
                    appendLine("PSS KB: ${exitInfo.pss}")
                    appendLine("RSS KB: ${exitInfo.rss}")
                    appendLine(deviceHeader(appContext))
                    appendLine(checkpointHeader(appContext))
                    appendLine("Native memory snapshots are not collected or stored by Gecko.")
                }
                writeReport(appContext, "$baseName.txt", report)
            }
            preferences.edit().putLong(KEY_LAST_EXIT_AT, newestTimestamp).commit()
        } catch (exception: Exception) {
            recordNonFatal(appContext, "ApplicationExitInfo capture", exception)
        }
    }

    fun checkpointSummary(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stage = SensitiveDataRedactor.redact(prefs.getString(KEY_LAST_STAGE, null) ?: "No native checkpoint recorded")
        val at = prefs.getLong(KEY_LAST_STAGE_AT, 0L)
        return if (at <= 0L) stage else "$stage · ${Date(at)}"
    }

    fun latestReportText(context: Context): String? = latestReportFile(context)?.let { file ->
        runCatching { SensitiveDataRedactor.redact(file.readText()).take(MAX_EXPORT_CHARS) }.getOrNull()
    }

    fun exportableFileCount(context: Context): Int = diagnosticFiles(context).size

    /** Export redacted text reports only. Gecko never persists native memory snapshots. */
    fun exportBundle(context: Context, output: OutputStream): Int {
        val files = diagnosticFiles(context)
        ZipOutputStream(output.buffered()).use { zip ->
            val summary = buildString {
                appendLine("Gecko Presence diagnostics export")
                appendLine(checkpointHeader(context))
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
            zip.putNextEntry(ZipEntry("diagnostics-summary.txt"))
            zip.write(summary.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                val report = runCatching { file.readText() }.getOrDefault("[Report unavailable]")
                zip.write(SensitiveDataRedactor.redact(report).take(MAX_EXPORT_CHARS).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return files.size
    }

    fun summary(context: Context): String {
        val latest = latestReportFile(context)
        val latestText = latest?.name ?: "No crash report recorded"
        return "LAST EXIT / $latestText\nLAST CHECKPOINT / ${checkpointSummary(context)}"
    }

    private fun latestReportFile(context: Context): File? =
        diagnosticFiles(context)
            .filter { it.name.endsWith(".txt") }
            .maxByOrNull(File::lastModified)

    private fun diagnosticFiles(context: Context): List<File> =
        reportDirectory(context).listFiles()
            ?.filter {
                it.isFile && (it.name.startsWith(REPORT_PREFIX) || it.name.startsWith(EXIT_PREFIX))
            }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()

    private fun reportDirectory(context: Context): File =
        File(context.applicationContext.filesDir, "diagnostics/crashes").apply { mkdirs() }

    private fun writeReport(context: Context, fileName: String, content: String) {
        val directory = reportDirectory(context)
        File(directory, fileName).writeText(content.take(MAX_REPORT_CHARS))
        val reports = directory.listFiles()
            ?.filter { it.isFile && (it.name.startsWith(REPORT_PREFIX) || it.name.startsWith(EXIT_PREFIX)) }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        reports.drop(MAX_REPORTS).forEach { runCatching { it.delete() } }
    }

    private fun purgeLegacyMemorySnapshots(context: Context) {
        reportDirectory(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith(LEGACY_TRACE_PREFIX) }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_$reason"
    }

    private fun checkpointHeader(context: Context): String = "Last checkpoint: ${checkpointSummary(context)}"

    private fun deviceHeader(context: Context): String = buildString {
        appendLine("App: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    private fun stackTrace(throwable: Throwable): String = StringWriter().also {
        throwable.printStackTrace(PrintWriter(it))
    }.toString()

    private const val MAX_REPORT_CHARS = 48_000
    private const val MAX_EXPORT_CHARS = 48_000
}

class GeckoPresenceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiscordWebViewIsolation.initialize()
        DiscordWebSessionCleanup.clearStaleSessionAtStartup(this)
        CrashDiagnostics.install(this)
        CrashDiagnostics.capturePreviousProcessExits(this)
    }
}
