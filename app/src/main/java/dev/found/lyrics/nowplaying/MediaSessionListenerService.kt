package dev.found.lyrics.nowplaying

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Watches the media session of whatever app is playing (Spotify, YouTube Music,
 * VLC, ...) and publishes it to [NowPlayingBus].
 *
 * Reading another app's active session requires notification-listener access,
 * which the user grants from system settings.
 */
class MediaSessionListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    private val artworks = mutableMapOf<String, Bitmap>()
    private var activeKey: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Pick up whatever is already playing. The sessions of the running
        // players are asked for through the media session manager; without
        // notification access this throws and is simply ignored.
        runCatching {
            val component = ComponentName(this, MediaSessionListenerService::class.java)
            val manager = getSystemService(MediaSessionManager::class.java)
            manager?.getActiveSessions(component)?.forEach { session ->
                onSessionToken(session.sessionToken)
            }
        }.onFailure {
            Log.w(TAG, "cannot read the active sessions: ${it.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val token = extractToken(sbn) ?: return
        onSessionToken(token, artworkOf(sbn))
    }

    /**
     * The album art of the player.
     *
     * It is taken from the media notification, which is where the player put
     * it in the first place. The picture inside the media metadata is not
     * part of the public API, the notification is.
     */
    private fun artworkOf(sbn: StatusBarNotification?): Bitmap? {
        val icon = sbn?.notification?.largeIcon ?: return null
        return runCatching { icon.bitmap }.getOrNull()
    }

    /** Keeps a controller for [token] and makes it the one on screen. */
    private fun onSessionToken(token: MediaSession.Token, artwork: Bitmap? = null) {
        val key = token.toString()
        if (!controllers.containsKey(key)) {
            val controller = runCatching { MediaController(this, token) }.getOrNull() ?: return
            val callback = Callback()
            controllers[key] = controller
            callbacks[key] = callback
            controller.registerCallback(callback, handler)
        }
        if (artwork != null) artworks[key] = artwork
        activeKey = key
        publishCurrent()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val token = extractToken(sbn) ?: return
        val key = token.toString()
        if (activeKey == key) {
            activeKey = null
        }
        // The session is gone: drop the controller so it does not leak.
        releaseController(key)
        publishCurrent()
    }

    override fun onDestroy() {
        for (key in controllers.keys.toList()) {
            releaseController(key)
        }
        activeController = null
        NowPlayingBus.publish(null)
        super.onDestroy()
    }

    /** Unregisters the controller for [key], if there is one. */
    private fun releaseController(key: String) {
        artworks.remove(key)
        val controller = controllers.remove(key) ?: return
        val callback = callbacks.remove(key) ?: return
        runCatching { controller.unregisterCallback(callback) }
    }

    private inner class Callback : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishCurrent()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishCurrent()
        }
    }

    private fun extractToken(sbn: StatusBarNotification?): MediaSession.Token? {
        val extras = sbn?.notification?.extras ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Picks the controller that is actually playing.
     *
     * A media notification is posted for all sorts of things, so the most
     * recently posted one is not necessarily the audio the user is listening
     * to: a playing session wins, and the last known one is only the fallback.
     */
    private fun currentController(): MediaController? {
        val preferred = activeKey?.let { key -> controllers[key] }
        if (preferred != null && preferred.playbackState?.state == PlaybackState.STATE_PLAYING) {
            return preferred
        }
        var playing: MediaController? = null
        var fallback: MediaController? = null
        for (controller in controllers.values) {
            val state = controller.playbackState
            if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                if (playing == null) playing = controller
            }
            if (fallback == null) fallback = controller
        }
        return playing ?: preferred ?: fallback
    }

    private fun publishCurrent() {
        val controller = currentController() ?: return
        val metadata = controller.metadata
        val state = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (title.isEmpty() && artist.isEmpty()) return

        val playing = state?.state == PlaybackState.STATE_PLAYING
        val speed = state?.playbackSpeed ?: 0f

        activeController = controller
        NowPlayingBus.publish(
            NowPlaying(
                title = title,
                artist = artist,
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                artwork = activeKey?.let { artworks[it] },
                playing = playing,
                positionMs = currentPosition(state, playing, speed),
                positionUpdatedAt = SystemClock.elapsedRealtime(),
                playbackSpeed = speed,
                sessionToken = controller.sessionToken,
            )
        )
    }

    /**
     * The playback position of [state], extrapolated to now.
     *
     * A session only reports its position when it publishes a new state, so the
     * position has to be carried forward with the playback speed. The update
     * time is on the `SystemClock.elapsedRealtime()` base, the same one used
     * here.
     */
    private fun currentPosition(state: PlaybackState?, playing: Boolean, speed: Float): Long {
        if (state == null) return 0L
        val position = state.position
        if (position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return 0L
        if (!playing || speed <= 0f) return position.coerceAtLeast(0L)

        val updatedAt = state.lastPositionUpdateTime
        if (updatedAt <= 0L) return position.coerceAtLeast(0L)

        val delta = SystemClock.elapsedRealtime() - updatedAt
        if (delta <= 0L) return position.coerceAtLeast(0L)
        return (position + (delta * speed).toLong()).coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "FoundMedia"

        /** The controller of whatever is playing, for transport controls. */
        @Volatile
        var activeController: MediaController? = null
            private set

        /** True when the user has granted notification-listener access. */
        fun isEnabled(context: Context): Boolean {
            return try {
                NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            } catch (e: Exception) {
                false
            }
        }

        /** Opens the system screen where notification access is granted. */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
