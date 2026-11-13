package dev.found.lyrics.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import dev.found.lyrics.FoundSettings
import dev.found.lyrics.lyrics.LrcParser
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

    override fun onCreate() {
        super.onCreate()
        settings = FoundSettings(this)
        repository = LyricsRepository(this)
        NotificationPublisher.ensureChannel(this)

        // The foreground notification has to exist before anything else can block.
        startForegroundCompat()

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
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        layoutParams = null
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (settings.corner == 0) {
                Gravity.TOP or Gravity.END
            } else {
                Gravity.TOP or Gravity.START
            }
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

    private fun toggleCollapsed() {
        manualCollapsed = !(bubble?.isExpanded() ?: false)
        applyCollapsedState()
    }

    private fun applyCollapsedState() {
        val view = bubble ?: return
        val collapsed = manualCollapsed ?: !(nowPlaying?.playing ?: false)
        view.setExpanded(!collapsed)
    }

    // --------------------------------------------------------------- playback

    private fun onNowPlaying(value: NowPlaying?) {
        nowPlaying = value
        val view = bubble ?: return

        if (value == null) {
            lyrics = null
            lastTrackKey = null
            view.setTitle(null)
            view.setLyrics(null, null)
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
            AccentExtractor.from(value.artwork)
        }
        view.setAccent(accent)

        val key = "${value.artist}|${value.title}|${value.durationMs}"
        if (key != lastTrackKey) {
            lastTrackKey = key
            lyrics = null
            view.setLyrics(null, null)
            loadLyrics(value)
        }
        applyCollapsedState()
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
            delay(33)
        }
    }

    private fun updateLyrics(positionMs: Long) {
        val view = bubble ?: return
        val current = lyrics ?: return
        if (!current.hasSynced) {
            val first = current.plainText?.lineSequence()?.firstOrNull { it.isNotBlank() }
            view.setLyrics(first, null)
            return
        }
        val index = LrcParser.indexAt(current.lines, positionMs)
        if (index < 0) {
            view.setLyrics(null, null)
            return
        }
        val line = current.lines[index]
        val next = current.lines.getOrNull(index + 1)
        val end = next?.timeMs ?: (line.timeMs + 4_000L)
        val span = (end - line.timeMs).coerceAtLeast(1L)
        val progress = ((positionMs - line.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)

        view.setLyrics(line.text, next?.text)
        view.setWords(line.words)
        view.setProgress(progress)
    }

    private fun updateNotification(force: Boolean) {
        if (!settings.showNotification) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdate < 500L) return
        lastNotificationUpdate = now

        val current = lyrics
        val position = nowPlaying?.livePosition() ?: 0L
        var line: String? = null
        var next: String? = null
        if (current != null && current.hasSynced) {
            val index = LrcParser.indexAt(current.lines, position)
            if (index >= 0) {
                line = current.lines[index].text
                next = current.lines.getOrNull(index + 1)?.text
            }
        } else if (current != null) {
            line = current.plainText?.lineSequence()?.firstOrNull { it.isNotBlank() }
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

    private fun startForegroundCompat() {
        val notification = NotificationPublisher.build(this, null, null, null, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationPublisher.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationPublisher.NOTIFICATION_ID, notification)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FoundOverlay"

        fun start(context: Context) {
            val intent = Intent(context, LyricOverlayService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LyricOverlayService::class.java)
            runCatching { context.stopService(intent) }
        }
    }
}
