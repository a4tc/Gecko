package app.geckopresence.host

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.geckopresence.host.core.AppActivityRules
import app.geckopresence.host.core.PresenceRules
import app.geckopresence.host.core.PresenceUpdate

/** Reads active media sessions only after the user grants Notification Access. */
class MediaSessionPresenceTracker(
    context: Context,
    private val onPresence: (PresenceUpdate) -> Unit,
    private val onClear: () -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private val listenerComponent = ComponentName(appContext, PresenceNotificationListener::class.java)
    private var running = false
    private var currentTrackKey: String? = null
    private var currentStartTimestamp = 0L
    private var lastStatus = "Media session detector stopped"

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                refresh()
            } catch (exception: RuntimeException) {
                report("Media session refresh failed · ${exception.javaClass.simpleName}")
            }
            if (running) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollTask)
        currentTrackKey = null
        currentStartTimestamp = 0L
        onClear()
        report("Media session detector stopped")
    }

    private fun refresh() {
        if (!hasNotificationAccess(appContext)) {
            onClear()
            report("Notification Access is not enabled")
            return
        }

        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            onClear()
            report("Media session access denied · recheck Notification Access")
            return
        } catch (exception: RuntimeException) {
            report("Media session query failed · ${exception.javaClass.simpleName}")
            return
        }

        val selected = controllers.firstNotNullOfOrNull(::toCandidate)
        if (selected == null) {
            currentTrackKey = null
            currentStartTimestamp = 0L
            onClear()
            report("No active media session")
            return
        }

        val (candidate, trackKey, positionMillis) = selected
        if (trackKey != currentTrackKey) {
            currentTrackKey = trackKey
            val positionSeconds = (positionMillis.coerceAtLeast(0L) / 1_000L)
            currentStartTimestamp = (System.currentTimeMillis() / 1_000L - positionSeconds).coerceAtLeast(1L)
        }
        onPresence(candidate.copy(
            startTimestampSeconds = currentStartTimestamp,
            expiresAtEpochMillis = System.currentTimeMillis() + EXPIRY_MS,
        ))
        report("Media session active · ${candidate.name}")
    }

    private fun toCandidate(controller: MediaController): Triple<PresenceUpdate, String, Long>? {
        val playback = controller.playbackState ?: return null
        val mediaState = when (playback.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            else -> return null
        }

        val packageName = controller.packageName ?: return null
        if (packageName == appContext.packageName) return null
        val appInfo = runCatching { appContext.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        val label = runCatching { appContext.packageManager.getApplicationLabel(appInfo).toString() }
            .getOrDefault(packageName)
        if (AppActivityRules.isSensitiveApp(packageName, label)) return null

        val metadata = controller.metadata
        val settings = appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val shareTitles = settings.getBoolean(KEY_SHARE_MEDIA_TITLES, false)
        val title = if (shareTitles) PresenceRules.cleanText(
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE), 120,
        ) else null
        val artist = if (shareTitles) PresenceRules.cleanText(
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            120,
        ) else null

        val details = title ?: AppActivityRules.defaultActivityDetails(packageName, label)
        val trackKey = listOf(packageName, title.orEmpty(), artist.orEmpty()).joinToString("|")
        val displayState = when {
            artist != null && mediaState == "paused" -> "$artist · Paused"
            artist != null -> artist
            mediaState == "paused" -> "Paused"
            else -> null
        }
        val update = PresenceUpdate(
            source = "media:$packageName",
            name = PresenceRules.cleanText(label, 80) ?: "Media player",
            details = details,
            state = displayState,
            mediaState = mediaState,
            startTimestampSeconds = 0L,
            sourceType = SOURCE_TYPE,
            priority = PresencePriorityPolicy.get(appContext, SOURCE_TYPE),
        )
        return Triple(update, trackKey, playback.position)
    }

    private fun report(message: String) {
        if (message == lastStatus) return
        lastStatus = message
        onStatus(message)
    }

    companion object {
        const val PREFS_SETTINGS = "gpb_settings"
        const val KEY_SHARE_MEDIA_TITLES = "share_media_titles"
        const val KEY_TRACK_MEDIA = "track_media_sessions"
        const val SOURCE_TYPE = "media-session"
        const val PRIORITY = 98
        private const val POLL_INTERVAL_MS = 5_000L
        private const val EXPIRY_MS = 16_000L

        fun hasNotificationAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            return enabled.split(':').any { value ->
                ComponentName.unflattenFromString(value)?.packageName == context.packageName &&
                    ComponentName.unflattenFromString(value)?.className == PresenceNotificationListener::class.java.name
            }
        }
    }
}
