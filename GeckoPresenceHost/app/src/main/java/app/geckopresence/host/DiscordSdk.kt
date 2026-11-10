package app.geckopresence.host

import android.app.Activity
import android.os.Handler
import android.os.Looper
import app.geckopresence.host.core.PresenceUpdate
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

object DiscordSdk {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var ready = false

    @Volatile
    private var statusListener: ((String, String?) -> Unit)? = null

    @Volatile
    private var sessionListener: ((String, Int) -> Unit)? = null

    val isAvailable: Boolean
        get() = BuildConfig.DISCORD_SDK_ENABLED

    val isReady: Boolean
        get() = ready

    fun setStatusListener(listener: ((String, String?) -> Unit)?) {
        statusListener = listener
    }

    fun setSessionListener(listener: ((String, Int) -> Unit)?) {
        sessionListener = listener
    }

    fun setEngineActivity(activity: Activity) {
        if (!isAvailable) return

        try {
            val sdkClass = Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
            val method = sdkClass.methods.firstOrNull { candidate ->
                candidate.name == "setEngineActivity" &&
                    candidate.parameterTypes.size == 1 &&
                    Activity::class.java.isAssignableFrom(candidate.parameterTypes[0])
            } ?: return

            val receiver = if (Modifier.isStatic(method.modifiers)) {
                null
            } else {
                runCatching { sdkClass.getField("INSTANCE").get(null) }.getOrNull()
            }
            method.invoke(receiver, activity)
        } catch (_: Exception) {
            statusListener?.invoke("sdk_error", "Discord Social SDK activity initialization failed")
        }
    }

    fun initialize(applicationId: Long): Boolean {
        if (!isAvailable || applicationId <= 0L) return false
        if (initialized.get()) return true

        return try {
            System.loadLibrary("gecko_presence_native")
            val success = nativeInit(applicationId)
            initialized.set(success)
            success
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun authorize(): Boolean {
        if (!initialized.get()) return false
        return runCatching { nativeAuthorize() }.getOrDefault(false)
    }

    fun connectWithAccessToken(accessToken: String): Boolean {
        if (!initialized.get() || accessToken.isBlank()) return false
        return runCatching { nativeConnectWithAccessToken(accessToken) }.getOrDefault(false)
    }

    fun refreshAccessToken(accessToken: String): Boolean {
        if (!initialized.get() || accessToken.isBlank()) return false
        return runCatching { nativeRefreshAccessToken(accessToken) }.getOrDefault(false)
    }

    fun setPresence(update: PresenceUpdate): Boolean {
        if (!initialized.get() || !ready) return false
        return runCatching {
            nativeSetActivity(
                update.name,
                update.details,
                update.state ?: "",
                update.startTimestampSeconds,
            )
        }.getOrDefault(false)
    }

    fun clearPresence() {
        if (initialized.get()) runCatching { nativeClear() }
    }

    fun disconnect() {
        if (initialized.get()) runCatching { nativeDisconnect() }
        ready = false
    }

    fun destroy() {
        if (initialized.compareAndSet(true, false)) {
            runCatching { nativeDestroy() }
        }
        ready = false
    }

    @JvmStatic
    fun onNativeStatus(status: String, error: String?) {
        mainHandler.post {
            ready = status == "ready"
            statusListener?.invoke(status, error?.takeIf { it.isNotBlank() })
        }
    }

    @JvmStatic
    fun onNativeSession(refreshToken: String, expiresInSeconds: Int) {
        mainHandler.post {
            if (refreshToken.isNotBlank()) {
                sessionListener?.invoke(refreshToken, expiresInSeconds.coerceAtLeast(60))
            }
        }
    }

    private external fun nativeInit(applicationId: Long): Boolean
    private external fun nativeAuthorize(): Boolean
    private external fun nativeConnectWithAccessToken(accessToken: String): Boolean
    private external fun nativeRefreshAccessToken(accessToken: String): Boolean
    private external fun nativeSetActivity(
        name: String,
        details: String,
        state: String,
        startTimestampSeconds: Long,
    ): Boolean
    private external fun nativeClear()
    private external fun nativeDisconnect()
    private external fun nativeDestroy()
}
