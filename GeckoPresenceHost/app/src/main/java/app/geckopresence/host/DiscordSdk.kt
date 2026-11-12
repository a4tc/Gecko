package app.geckopresence.host

import android.os.Handler
import android.os.Looper
import app.geckopresence.host.core.PresenceUpdate
import app.geckopresence.host.core.PresenceActivityMapper
import java.util.concurrent.atomic.AtomicBoolean

object DiscordSdk {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var statusListener: ((String, String?) -> Unit)? = null

    val isAvailable: Boolean
        get() = BuildConfig.DISCORD_SDK_ENABLED

    /** True once the native Direct Rich Presence client has been initialized. */
    val isInitialized: Boolean
        get() = initialized.get()

    fun setStatusListener(listener: ((String, String?) -> Unit)?) {
        statusListener = listener
    }

    fun initialize(applicationId: Long): Boolean {
        if (!isAvailable || applicationId <= 0L) return false
        if (initialized.get()) return true

        return try {
            System.loadLibrary("gecko_presence_native")
            val success = nativeInit(applicationId)
            initialized.set(success)
            success
        } catch (_: LinkageError) {
            initialized.set(false)
            false
        } catch (_: Exception) {
            false
        }
    }

    fun setPresence(update: PresenceUpdate): Boolean {
        if (!initialized.get()) return false
        return runCatching {
            val detailsLine = PresenceActivityMapper.detailsLine(update.name, update.details) ?: return false
            nativeSetActivity(
                detailsLine,
                PresenceActivityMapper.stateLine(update.state).orEmpty(),
                update.startTimestampSeconds,
            )
        }.getOrDefault(false)
    }

    fun clearPresence() {
        if (initialized.get()) runCatching { nativeClear() }
    }

    fun destroy() {
        if (initialized.compareAndSet(true, false)) {
            runCatching { nativeDestroy() }
        }
    }

    @JvmStatic
    fun onNativeStatus(status: String, error: String?) {
        mainHandler.post {
            statusListener?.invoke(status, error?.takeIf { it.isNotBlank() })
        }
    }

    private external fun nativeInit(applicationId: Long): Boolean
    private external fun nativeSetActivity(
        details: String,
        state: String,
        startTimestampSeconds: Long,
    ): Boolean
    private external fun nativeClear()
    private external fun nativeDestroy()
}
