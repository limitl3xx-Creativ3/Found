package dev.found.lyrics.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import dev.found.lyrics.MainActivity
import dev.found.lyrics.R
import dev.found.lyrics.nowplaying.NowPlaying
import dev.found.lyrics.overlay.LyricOverlayService

/**
 * The notification side of Found: the same two lyric lines, shown in the shade
 * with transport controls, so the lyrics are there even when the bubble is hidden.
 *
 * This is also the notification of the foreground service, so it always exists
 * while the bubble is on screen.
 */
object NotificationPublisher {

    const val CHANNEL_ID = "found_lyrics"
    const val NOTIFICATION_ID = 4101

    const val ACTION_PLAY_PAUSE = "dev.found.lyrics.action.PLAY_PAUSE"
    const val ACTION_NEXT = "dev.found.lyrics.action.NEXT"
    const val ACTION_PREVIOUS = "dev.found.lyrics.action.PREVIOUS"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lyrics",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the lyrics of whatever is playing"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        nowPlaying: NowPlaying?,
        currentLine: String?,
        nextLine: String?,
        playing: Boolean,
    ): Notification {
        val title = when {
            nowPlaying == null -> "Found"
            nowPlaying.artist.isBlank() -> nowPlaying.title
            else -> "${nowPlaying.artist} — ${nowPlaying.title}"
        }
        val body = listOfNotNull(
            currentLine?.takeIf { it.isNotBlank() },
            nextLine?.takeIf { it.isNotBlank() },
        ).joinToString("\n").ifEmpty {
            if (nowPlaying == null) "Waiting for a track…" else "No lyrics for this track"
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(currentLine?.takeIf { it.isNotBlank() } ?: body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(Color.parseColor("#FF6B6B"))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )

        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_previous,
                "Previous",
                serviceIntent(context, ACTION_PREVIOUS, 1),
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                serviceIntent(context, ACTION_PLAY_PAUSE, 2),
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_next,
                "Next",
                serviceIntent(context, ACTION_NEXT, 3),
            ).build()
        )

        nowPlaying?.artwork?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    /**
     * The intent behind a transport button.
     *
     * It is a foreground service intent: the service may have been killed while
     * the notification was still on screen, and a plain service intent would
     * then not be allowed to start it.
     */
    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            Intent(context, LyricOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
