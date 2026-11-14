package dev.found.lyrics.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import dev.found.lyrics.FoundSettings
import dev.found.lyrics.R
import dev.found.lyrics.lyrics.KaraokeSweep
import dev.found.lyrics.lyrics.Lyrics
import dev.found.lyrics.lyrics.LyricsRepository
import dev.found.lyrics.notify.NotificationPublisher
import dev.found.lyrics.nowplaying.MediaSessionListenerService
import dev.found.lyrics.nowplaying.NowPlaying
import dev.found.lyrics.nowplaying.NowPlayingBus
import dev.found.lyrics.util.AccentExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the floating lyric bubble on screen and in step with the audio.
 *
 * The bubble sits in a fixed top corner (it is never draggable) and changes
 * shape: the expanded card while something plays, a thin pill when it does not.
 */
class LyricOverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private lateinit var settings: FoundSettings
    private lateinit var windowManager: WindowManager
    private lateinit var repository: LyricsRepository

    private var bubble: BubbleView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var nowPlaying: NowPlaying? = null
    private var lyrics: Lyrics? = null
    private var lyricsJob: Job? = null
    private var lastTrackKey: String? = null
    private var manualCollapsed: Boolean? = null
    private var lastNotificationUpdate = 0L

    /** Identifies the line currently on screen, so it is only rebuilt on a change. */
    private var renderedLineKey: String? = null

    /** The artwork the cached accent was taken from. */
    private var accentSource: Any? = null
    private var accentValue: Int = AccentExtractor.FALLBACK

    override fun onCreate() {
        super.onCreate()
        settings = FoundSettings(this)
        repository = LyricsRepository(this)
        NotificationPublisher.ensureChannel(this)

        // The foreground notification has to exist before anything else can block.
        if (!startForegroundCompat()) {
            stopSelf()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission missing, stopping")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()

        scope.launch {
            ticker()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            NotificationPublisher.ACTION_PLAY_PAUSE -> togglePlayback()
            NotificationPublisher.ACTION_NEXT ->
                MediaSessionListenerService.activeController?.transportControls?.skipToNext()
            NotificationPublisher.ACTION_PREVIOUS ->
                MediaSessionListenerService.activeController?.transportControls?.skipToPrevious()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        removeBubble()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ bubble

    private fun showBubble() {
        if (bubble != null) return
        val view = BubbleView(this)
        view.setMaxTextWidth(dp(260))
        view.setTextScale(settings.textScale)
        view.setOnClickListener { toggleCollapsed() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = cornerGravity()
            x = dp(12)
            y = dp(56)
        }

        runCatching {
            windowManager.addView(view, params)
            bubble = view
            layoutParams = params
            view.setAccent(AccentExtractor.FALLBACK)
        }.onFailure {
            Log.e(TAG, "could not add overlay view: ${it.message}")
        }
    }

    private fun removeBubble() {
        val view = bubble ?: return
        bubble = null
        layoutParams = null
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "could not remove overlay view: ${it.message}") }
    }

    private fun cornerGravity(): Int =
        if (settings.corner == 0) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START

    private fun toggleCollapsed() {
        manualCollapsed = !(bubble?.isExpanded() ?: false)
        applyCollapsedState()
    }

    private fun applyCollapsedState() {
        val view = bubble ?: return
        val collapsed = when {
            manualCollapsed != null -> manualCollapsed == true
            settings.autoCollapse -> !(nowPlaying?.playing ?: false)
            else -> false
        }
        view.setExpanded(!collapsed)
    }

    /**
     * Makes sure the bubble is still on screen.
     *
     * The user can revoke the overlay permission while the service runs, and
     * the system drops the window on its own when that happens, so both cases
     * are checked here.
     */
    private fun ensureOverlayAlive() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay permission was revoked, stopping")
            removeBubble()
            stopSelf()
            return
        }
        val view = bubble
        when {
            view == null -> showBubble()
            !view.isAttachedToWindow -> {
                Log.i(TAG, "overlay window was dropped, adding it again")
                removeBubble()
                showBubble()
            }
        }
    }

    /** Applies the settings that can change while the service runs. */
    private fun applyLiveSettings() {
        val view = bubble ?: return
        view.setTextScale(settings.textScale)
        val params = layoutParams ?: return
        val gravity = cornerGravity()
        if (params.gravity != gravity) {
            params.gravity = gravity
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure { Log.w(TAG, "could not move overlay view: ${it.message}") }
        }
    }

    // --------------------------------------------------------------- playback

    private fun onNowPlaying(value: NowPlaying?) {
        nowPlaying = value
        val view = bubble ?: return

        if (value == null) {
            lyrics = null
            lastTrackKey = null
            renderedLineKey = null
            view.setTitle(null)
            view.setLyrics(null, null)
            view.setWords(emptyList())
            view.setArtwork(null)
            applyCollapsedState()
            updateNotification(force = true)
            return
        }

        view.setTitle(listOf(value.artist, value.title).filter { it.isNotBlank() }.joinToString(" — "))
        view.setArtwork(value.artwork)

        val accent = if (settings.accentOverride != 0) {
            settings.accentOverride
        } else {
            accentFor(value.artwork)
        }
        view.setAccent(accent)

        val key = "${value.artist}|${value.title}|${value.durationMs}"
        if (key != lastTrackKey) {
            lastTrackKey = key
            renderedLineKey = null
            lyrics = null
            view.setLyrics(null, null)
            view.setWords(emptyList())
            loadLyrics(value)
        }
        applyCollapsedState()
    }

    /** The accent colour of the artwork, worked out only when the art changes. */
    private fun accentFor(artwork: Bitmap?): Int {
        if (artwork != null && artwork === accentSource) return accentValue
        accentSource = artwork
        accentValue = AccentExtractor.from(artwork)
        return accentValue
    }

    private fun loadLyrics(track: NowPlaying) {
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            val result = runCatching {
                repository.lyricsFor(track.artist, track.title, track.durationMs)
            }.getOrNull()
            if (result != null) {
                lyrics = result
                Log.i(TAG, "lyrics for ${track.title} (${result.source}, ${result.lines.size} lines)")
            } else {
                Log.i(TAG, "no lyrics found for ${track.title}")
            }
        }
    }

    // ----------------------------------------------------------------- ticker

    private suspend fun ticker() {
        var lastCheck = 0L
        while (scope.isActive) {
            val latest = NowPlayingBus.state.value
            if (latest !== nowPlaying) {
                onNowPlaying(latest)
            }
            val track = nowPlaying
            if (track != null) {
                val position = track.livePosition()
                updateLyrics(position)
                updateNotification(force = false)
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheck >= CHECK_INTERVAL_MS) {
                lastCheck = now
                ensureOverlayAlive()
                applyLiveSettings()
            }
            delay(TICK_MS)
        }
    }

    private fun updateLyrics(positionMs: Long) {
        val view = bubble ?: return
        val current = lyrics ?: return

        if (!current.hasSynced) {
            val text = when {
                current.instrumental -> getString(R.string.instrumental)
                else -> current.plainText?.lineSequence()?.firstOrNull { it.isNotBlank() }
            }
            if (renderedLineKey != "plain") {
                renderedLineKey = "plain"
                view.setLyrics(text, null)
                view.setWords(emptyList())
            }
            return
        }

        val sweep = KaraokeSweep.at(current.lines, positionMs)
        if (sweep == null) {
            // Before the first line there is nothing to show.
            if (renderedLineKey != null) {
                renderedLineKey = null
                view.setLyrics(null, null)
                view.setWords(emptyList())
            }
            return
        }

        val lineKey = "${sweep.line.timeMs}|${sweep.line.text}"
        if (lineKey != renderedLineKey) {
            renderedLineKey = lineKey
            view.setLyrics(sweep.line.text, sweep.next?.text)
            view.setWords(sweep.line.words)
        }
        view.setProgress(sweep.progress)
        view.setWordProgress(sweep.wordIndex, sweep.wordProgress)
    }

    /**
     * Rebuilds the foreground notification.
     *
     * The foreground notification cannot be hidden, so the "show the lyrics in
     * the shade" setting only decides whether the lyric lines are part of it.
     */
    private fun updateNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdate < NOTIFICATION_INTERVAL_MS) return
        lastNotificationUpdate = now

        val showLyrics = settings.showNotification
        val current = lyrics
        val position = nowPlaying?.livePosition() ?: 0L
        var line: String? = null
        var next: String? = null
        if (showLyrics && current != null) {
            if (current.hasSynced) {
                val sweep = KaraokeSweep.at(current.lines, position)
                if (sweep != null) {
                    line = sweep.line.text
                    next = sweep.next?.text
                }
            } else {
                line = current.plainText?.lineSequence()?.firstOrNull { it.isNotBlank() }
            }
        }

        val notification = NotificationPublisher.build(
            this,
            nowPlaying,
            line,
            next,
            nowPlaying?.playing ?: false,
        )
        runCatching {
            NotificationManagerCompat.from(this).notify(NotificationPublisher.NOTIFICATION_ID, notification)
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun togglePlayback() {
        val controller = MediaSessionListenerService.activeController ?: return
        val state = controller.playbackState
        if (state != null && state.state == android.media.session.PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    /**
     * Puts the service in the foreground. Returns false when the system
     * refused, in which case the service has to stop.
     */
    private fun startForegroundCompat(): Boolean {
        val notification = NotificationPublisher.build(this, null, null, null, false)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationPublisher.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NotificationPublisher.NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not start in the foreground: ${e.message}")
            false
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FoundOverlay"

        /** How often the bubble is redrawn, in milliseconds. */
        private const val TICK_MS = 33L

        /** How often the overlay permission and the live settings are checked. */
        private const val CHECK_INTERVAL_MS = 1_000L

        /** How often the notification is rebuilt. */
        private const val NOTIFICATION_INTERVAL_MS = 500L

        fun start(context: Context) {
            val intent = Intent(context, LyricOverlayService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "could not start the overlay service: ${it.message}") }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LyricOverlayService::class.java)
            runCatching { context.stopService(intent) }
        }
    }
}
