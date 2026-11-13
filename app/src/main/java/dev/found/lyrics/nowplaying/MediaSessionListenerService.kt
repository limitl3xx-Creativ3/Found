package dev.found.lyrics.nowplaying

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
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
    private var activeKey: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeSessions?.forEach { onNotificationPosted(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot read active sessions: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val token = extractToken(sbn) ?: return
        val key = token.toString()
        if (!controllers.containsKey(key)) {
            val controller = runCatching { MediaController(this, token) }.getOrNull() ?: return
            val callback = Callback()
            controllers[key] = controller
            callbacks[key] = callback
            controller.registerCallback(callback, handler)
        }
        activeKey = key
        publishCurrent()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val token = extractToken(sbn) ?: return
        val key = token.toString()
        if (activeKey == key) {
            activeKey = null
            publishCurrent()
        }
    }

    override fun onDestroy() {
        for ((key, controller) in controllers) {
            callbacks[key]?.let { runCatching { controller.unregisterCallback(it) } }
        }
        controllers.clear()
        callbacks.clear()
        activeController = null
        NowPlayingBus.publish(null)
        super.onDestroy()
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

    /** Picks the controller that is actually playing, falling back to the last known one. */
    private fun currentController(): MediaController? {
        activeKey?.let { key -> controllers[key]?.let { return it } }
        var best: MediaController? = null
        for (controller in controllers.values) {
            val state = controller.playbackState
            if (state != null && state.state == PlaybackState.STATE_PLAYING) return controller
            if (best == null) best = controller
        }
        return best
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
        val position = when {
            state == null -> 0L
            playing && speed > 0f ->
                state.position + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * speed).toLong()
            else -> state.position
        }

        activeController = controller
        NowPlayingBus.publish(
            NowPlaying(
                title = title,
                artist = artist,
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                artwork = metadata?.artwork,
                playing = playing,
                positionMs = position,
                positionUpdatedAt = SystemClock.elapsedRealtime(),
                playbackSpeed = speed,
                sessionToken = controller.sessionToken,
            )
        )
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
