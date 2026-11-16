package app.geckopresence.host

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.geckopresence.host.core.AppActivityRules
import app.geckopresence.host.core.PresenceRules
import app.geckopresence.host.core.PresenceUpdate

/**
 * Observes active media sessions only after the user grants Notification Access.
 * Session-change and controller callbacks drive updates; polling is a recovery fallback.
 */
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
    private val registeredControllers = linkedMapOf<MediaSession.Token, MediaController>()
    private var running = false
    private var listenerRegistered = false
    private var currentTrackKey: String? = null
    private var artworkTrackKey: String? = null
    private var currentStartTimestamp = 0L
    private var currentPresence: PresenceUpdate? = null
    private var missingSinceEpochMillis = 0L
    private var artworkRetryAttempt = 0
    private var nextArtworkAttemptAtEpochMillis = 0L
    private var lastStatus = "Media session detector stopped"

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        scheduleRefresh(ACTIVE_CHANGE_DEBOUNCE_MS)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            scheduleRefresh(CONTROLLER_CHANGE_DEBOUNCE_MS)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            scheduleRefresh(CONTROLLER_CHANGE_DEBOUNCE_MS)
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            scheduleRefresh(0L)
        }
    }

    private val refreshTask = Runnable {
        if (running) runCatching { refresh() }
            .onFailure { report("Media session refresh failed · ${it.javaClass.simpleName}") }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            refreshTask.run()
            if (running) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(refreshTask)
        if (hasNotificationAccess(appContext)) {
            try {
                manager.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent, handler)
                listenerRegistered = true
            } catch (_: SecurityException) {
                report("Media session listener access denied · recheck Notification Access")
            } catch (exception: RuntimeException) {
                report("Media session listener unavailable · ${exception.javaClass.simpleName}")
            }
        }
        handler.post(refreshTask)
        handler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    fun stop() {
        if (!running && !listenerRegistered && registeredControllers.isEmpty()) return
        running = false
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(refreshTask)
        detachControllerCallbacks()
        if (listenerRegistered) {
            runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
            listenerRegistered = false
        }
        currentTrackKey = null
        artworkTrackKey = null
        currentStartTimestamp = 0L
        currentPresence = null
        missingSinceEpochMillis = 0L
        onClear()
        report("Media session detector stopped")
    }

    private fun scheduleRefresh(delayMillis: Long) {
        if (!running) return
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, delayMillis)
    }

    private fun refresh() {
        if (!hasNotificationAccess(appContext)) {
            detachControllerCallbacks()
            if (listenerRegistered) {
                runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
                listenerRegistered = false
            }
            resetCurrentTrack()
            onClear()
            report("Notification Access is not enabled")
            return
        }

        if (!listenerRegistered) {
            try {
                manager.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent, handler)
                listenerRegistered = true
            } catch (_: SecurityException) {
                report("Media session access denied · recheck Notification Access")
                return
            } catch (exception: RuntimeException) {
                report("Media session listener unavailable · ${exception.javaClass.simpleName}")
            }
        }

        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            if (!hasNotificationAccess(appContext)) {
                resetCurrentTrack()
                onClear()
                report("Media session access denied · recheck Notification Access")
            } else {
                report("Media session query denied · retaining the last known activity")
            }
            return
        } catch (exception: RuntimeException) {
            report("Media session query failed · ${exception.javaClass.simpleName} · retaining last activity")
            return
        }

        syncControllerCallbacks(controllers)
        val selected = controllers.firstNotNullOfOrNull(::toCandidate)
        if (selected == null) {
            handleMissingSession()
            return
        }

        missingSinceEpochMillis = 0L
        val (candidate, trackKey, positionMillis, controller, artworkLabel) = selected
        if (trackKey != currentTrackKey) {
            currentTrackKey = trackKey
            artworkTrackKey = null
            artworkRetryAttempt = 0
            nextArtworkAttemptAtEpochMillis = 0L
            currentStartTimestamp = (System.currentTimeMillis() / 1_000L -
                (positionMillis.coerceAtLeast(0L) / 1_000L)).coerceAtLeast(1L)
        }
        val freshPresence = candidate.copy(
            startTimestampSeconds = currentStartTimestamp,
            expiresAtEpochMillis = System.currentTimeMillis() + EXPIRY_MS,
        )
        currentPresence = freshPresence
        onPresence(freshPresence)
        report("Media session active · ${candidate.name}")

        if (artworkTrackKey != trackKey && System.currentTimeMillis() >= nextArtworkAttemptAtEpochMillis) {
            artworkTrackKey = trackKey
            ArtworkAutoPublisher.resolveMediaArtwork(
                context = appContext,
                controller = controller,
                displayName = artworkLabel,
            ) { asset, artworkStatus ->
                handler.post {
                    if (!running || currentTrackKey != trackKey) return@post
                    if (asset != null) {
                        artworkRetryAttempt = 0
                        nextArtworkAttemptAtEpochMillis = 0L
                        val updated = currentPresence?.copy(
                            largeImage = asset,
                            expiresAtEpochMillis = System.currentTimeMillis() + EXPIRY_MS,
                        ) ?: return@post
                        currentPresence = updated
                        onPresence(updated)
                    } else if (!artworkStatus.isNullOrBlank()) {
                        artworkTrackKey = null
                        artworkRetryAttempt = (artworkRetryAttempt + 1).coerceAtMost(MAX_ARTWORK_RETRY_ATTEMPTS)
                        val retryDelay = (INITIAL_ARTWORK_RETRY_DELAY_MS * (1L shl (artworkRetryAttempt - 1)))
                            .coerceAtMost(MAX_ARTWORK_RETRY_DELAY_MS)
                        nextArtworkAttemptAtEpochMillis = System.currentTimeMillis() + retryDelay
                        report("$artworkStatus · retry in ${retryDelay / 1_000L}s")
                    }
                }
            }
        }
    }

    private fun syncControllerCallbacks(controllers: List<MediaController>) {
        val activeTokens = controllers.mapTo(HashSet<MediaSession.Token>()) { it.sessionToken }
        val removedTokens = registeredControllers.keys.filterNot(activeTokens::contains)
        removedTokens.forEach { token ->
            registeredControllers.remove(token)?.unregisterCallback(controllerCallback)
        }
        controllers.forEach { controller ->
            val token = controller.sessionToken
            if (token !in registeredControllers) {
                runCatching { controller.registerCallback(controllerCallback, handler) }
                    .onSuccess { registeredControllers[token] = controller }
                    .onFailure { report("Media controller callback unavailable · ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun detachControllerCallbacks() {
        registeredControllers.values.forEach { controller ->
            runCatching { controller.unregisterCallback(controllerCallback) }
        }
        registeredControllers.clear()
    }

    private fun handleMissingSession() {
        if (missingSinceEpochMillis == 0L) missingSinceEpochMillis = System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - missingSinceEpochMillis
        if (elapsed >= MISSING_SESSION_GRACE_MS) {
            resetCurrentTrack()
            onClear()
            report("No active media session")
        } else {
            report("Waiting for media session state to settle")
        }
    }

    private fun resetCurrentTrack() {
        currentTrackKey = null
        artworkTrackKey = null
        currentStartTimestamp = 0L
        currentPresence = null
        missingSinceEpochMillis = 0L
        artworkRetryAttempt = 0
        nextArtworkAttemptAtEpochMillis = 0L
    }

    private fun toCandidate(controller: MediaController): Candidate? {
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
        val artworkUri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val trackKey = listOf(
            packageName,
            title.orEmpty(),
            artist.orEmpty(),
            artworkUri.orEmpty(),
            metadata?.hashCode()?.toString().orEmpty(),
        ).joinToString("|")
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
        val artworkLabel = listOfNotNull(title, artist, label).joinToString(" · ").take(80)
        return Candidate(update, trackKey, playback.position, controller, artworkLabel)
    }

    private fun report(message: String) {
        if (message == lastStatus) return
        lastStatus = message
        onStatus(message)
    }

    private data class Candidate(
        val update: PresenceUpdate,
        val trackKey: String,
        val positionMillis: Long,
        val controller: MediaController,
        val artworkLabel: String,
    )

    companion object {
        const val PREFS_SETTINGS = "gpb_settings"
        const val KEY_SHARE_MEDIA_TITLES = "share_media_titles"
        const val KEY_TRACK_MEDIA = "track_media_sessions"
        const val SOURCE_TYPE = "media-session"
        const val PRIORITY = 98
        private const val POLL_INTERVAL_MS = 10_000L
        private const val EXPIRY_MS = 30_000L
        private const val ACTIVE_CHANGE_DEBOUNCE_MS = 450L
        private const val CONTROLLER_CHANGE_DEBOUNCE_MS = 650L
        private const val MISSING_SESSION_GRACE_MS = 3_500L
        private const val INITIAL_ARTWORK_RETRY_DELAY_MS = 10_000L
        private const val MAX_ARTWORK_RETRY_DELAY_MS = 5 * 60_000L
        private const val MAX_ARTWORK_RETRY_ATTEMPTS = 8

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
