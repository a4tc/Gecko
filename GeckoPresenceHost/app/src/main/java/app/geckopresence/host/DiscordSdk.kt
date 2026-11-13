package app.geckopresence.host

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.geckopresence.host.core.PresenceActivityMapper
import app.geckopresence.host.core.PresenceUpdate
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Main-looper owner for the Discord Social SDK JNI client and callback pump. */
object DiscordSdk {
    private const val CALLBACK_INTERVAL_MS = 50L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var engineActivityReady = false

    @Volatile
    private var engineActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var engineActivitySetter: Method? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var statusListener: ((String, String?) -> Unit)? = null

    private val callbackPump = object : Runnable {
        override fun run() {
            if (!initialized.get()) return
            try {
                requireMainThread()
                nativeRunCallbacks()
            } catch (error: LinkageError) {
                handleCallbackFailure(error)
                return
            } catch (exception: Exception) {
                handleCallbackFailure(exception)
                return
            }
            if (initialized.get()) mainHandler.postDelayed(this, CALLBACK_INTERVAL_MS)
        }
    }

    val isAvailable: Boolean
        get() = BuildConfig.DISCORD_SDK_ENABLED

    /** True once the native Direct Rich Presence client has been initialized. */
    val isInitialized: Boolean
        get() = initialized.get()

    fun setStatusListener(listener: ((String, String?) -> Unit)?) {
        statusListener = listener
    }

    /**
     * Register the foreground Activity required by Discord's Android C++ SDK bootstrap.
     * Reflection keeps the no-SDK preview build compilable because the proprietary AAR is
     * deliberately optional and is only added to the compile classpath in Direct RPC mode.
     */
    @Synchronized
    fun attachEngineActivity(activity: Activity): Boolean {
        if (!isAvailable) return false
        return try {
            CrashDiagnostics.recordCheckpoint(
                activity,
                "sdk-bootstrap",
                "before loading DiscordSocialSdkInit and its JNI bindings",
            )
            val sdkInitClass = Class.forName(
                "com.discord.socialsdk.DiscordSocialSdkInit",
                true,
                activity.classLoader,
            )
            val setter = sdkInitClass.methods.firstOrNull { method ->
                method.name == "setEngineActivity" &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(activity.javaClass)
            } ?: throw NoSuchMethodException(
                "DiscordSocialSdkInit.setEngineActivity(Activity) was not found",
            )

            CrashDiagnostics.recordCheckpoint(
                activity,
                "sdk-bootstrap",
                "before DiscordSocialSdkInit.setEngineActivity",
            )
            setter.invoke(null, activity)
            engineActivitySetter = setter
            engineActivityRef = WeakReference(activity)
            engineActivityReady = true
            CrashDiagnostics.recordCheckpoint(
                activity,
                "sdk-bootstrap",
                "DiscordSocialSdkInit.setEngineActivity completed",
            )
            true
        } catch (error: ReflectiveOperationException) {
            recordActivityBootstrapFailure(activity, error)
            false
        } catch (error: LinkageError) {
            recordActivityBootstrapFailure(activity, error)
            false
        } catch (error: RuntimeException) {
            recordActivityBootstrapFailure(activity, error)
            false
        }
    }

    /** Clear the SDK's Activity reference when the host Activity is actually finishing. */
    @Synchronized
    fun detachEngineActivity(activity: Activity) {
        if (engineActivityRef?.get() !== activity) return
        try {
            engineActivitySetter?.invoke(null, null)
            CrashDiagnostics.recordCheckpoint(
                activity,
                "sdk-bootstrap",
                "DiscordSocialSdkInit.setEngineActivity(null) completed",
            )
        } catch (error: ReflectiveOperationException) {
            CrashDiagnostics.recordNonFatal(activity, "Discord Android Activity detach", unwrapReflectionError(error))
        } catch (error: LinkageError) {
            CrashDiagnostics.recordNonFatal(activity, "Discord Android Activity detach", error)
        } catch (error: RuntimeException) {
            CrashDiagnostics.recordNonFatal(activity, "Discord Android Activity detach", error)
        } finally {
            engineActivityReady = false
            engineActivityRef = null
            engineActivitySetter = null
        }
    }

    private fun recordActivityBootstrapFailure(activity: Activity, error: Throwable) {
        engineActivityReady = false
        engineActivityRef = null
        engineActivitySetter = null
        val cause = unwrapReflectionError(error)
        CrashDiagnostics.recordNonFatal(activity, "Discord Android Activity bootstrap", cause)
        CrashDiagnostics.recordCheckpoint(
            activity,
            "sdk-bootstrap",
            "failed: ${cause.javaClass.simpleName}",
        )
        statusListener?.invoke(
            "error",
            "Discord Android Activity bootstrap failed: ${cause.javaClass.simpleName}",
        )
    }

    private fun unwrapReflectionError(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error

    @Synchronized
    fun initialize(context: Context, applicationId: Long): Boolean {
        requireMainThread()
        if (!isAvailable || applicationId <= 0L) return false
        if (initialized.get()) return true

        val app = context.applicationContext
        appContext = app
        if (!engineActivityReady || engineActivityRef?.get() == null) {
            CrashDiagnostics.recordCheckpoint(
                app,
                "sdk-init",
                "blocked: DiscordSocialSdkInit.setEngineActivity was not completed",
            )
            statusListener?.invoke(
                "error",
                "Discord Android Activity bootstrap has not completed; reopen the app and retry",
            )
            return false
        }
        CrashDiagnostics.recordCheckpoint(app, "sdk-init", "before System.loadLibrary")
        return try {
            System.loadLibrary("gecko_presence_native")
            CrashDiagnostics.recordCheckpoint(app, "sdk-init", "native library loaded; before nativeInit")
            val success = nativeInit(applicationId)
            initialized.set(success)
            CrashDiagnostics.recordCheckpoint(app, "sdk-init", if (success) "nativeInit returned true" else "nativeInit returned false")
            if (success) {
                mainHandler.removeCallbacks(callbackPump)
                mainHandler.post(callbackPump)
            }
            success
        } catch (error: LinkageError) {
            initialized.set(false)
            CrashDiagnostics.recordNonFatal(app, "Discord SDK native linkage", error)
            statusListener?.invoke("error", "Native Discord library could not be loaded: ${error.javaClass.simpleName}")
            false
        } catch (exception: Exception) {
            initialized.set(false)
            CrashDiagnostics.recordNonFatal(app, "Discord SDK initialization", exception)
            statusListener?.invoke("error", "Discord SDK initialization failed: ${exception.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun setPresence(update: PresenceUpdate): Boolean {
        requireMainThread()
        if (!initialized.get()) return false
        return runCatching {
            val detailsLine = PresenceActivityMapper.detailsLine(update.name, update.details) ?: return false
            appContext?.let { CrashDiagnostics.recordCheckpoint(it, "rpc-update", update.sourceType) }
            nativeSetActivity(
                detailsLine,
                PresenceActivityMapper.stateLine(update.state).orEmpty(),
                update.startTimestampSeconds,
            )
        }.getOrElse { error ->
            appContext?.let { CrashDiagnostics.recordNonFatal(it, "Discord RPC update", error) }
            statusListener?.invoke("error", "Rich Presence update failed: ${error.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun clearPresence() {
        requireMainThread()
        if (initialized.get()) {
            appContext?.let { CrashDiagnostics.recordCheckpoint(it, "rpc-clear", "before nativeClear") }
            runCatching { nativeClear() }.onFailure { error ->
                appContext?.let { CrashDiagnostics.recordNonFatal(it, "Discord RPC clear", error) }
            }
        }
    }

    @Synchronized
    fun destroy() {
        requireMainThread()
        mainHandler.removeCallbacks(callbackPump)
        if (initialized.compareAndSet(true, false)) {
            appContext?.let { CrashDiagnostics.recordCheckpoint(it, "sdk-destroy", "before nativeDestroy") }
            runCatching { nativeDestroy() }.onFailure { error ->
                appContext?.let { CrashDiagnostics.recordNonFatal(it, "Discord SDK destroy", error) }
            }
        }
    }

    @JvmStatic
    fun onNativeStatus(status: String, error: String?) {
        if (status.startsWith(CHECKPOINT_PREFIX)) {
            appContext?.let { context ->
                CrashDiagnostics.recordCheckpoint(context, status.removePrefix(CHECKPOINT_PREFIX), error)
            }
        }
        mainHandler.post {
            statusListener?.invoke(status, error?.takeIf { it.isNotBlank() })
        }
    }

    private fun handleCallbackFailure(error: Throwable) {
        val context = appContext
        if (context != null) {
            CrashDiagnostics.recordNonFatal(context, "Discord SDK callback pump", error)
            CrashDiagnostics.recordCheckpoint(context, "callback-pump", "stopped after ${error.javaClass.simpleName}")
        }
        mainHandler.removeCallbacks(callbackPump)
        initialized.set(false)
        runCatching { nativeDestroy() }
        statusListener?.invoke("error", "Discord SDK callback pump stopped: ${error.javaClass.simpleName}")
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Discord Social SDK calls must be serialized on the Android main looper"
        }
    }

    private external fun nativeInit(applicationId: Long): Boolean
    private external fun nativeRunCallbacks()
    private external fun nativeSetActivity(
        details: String,
        state: String,
        startTimestampSeconds: Long,
    ): Boolean
    private external fun nativeClear()
    private external fun nativeDestroy()

    private const val CHECKPOINT_PREFIX = "checkpoint:"
}
