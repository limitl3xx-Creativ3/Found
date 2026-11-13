package dev.found.lyrics.nowplaying

import android.graphics.Bitmap
import android.media.session.MediaSession
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A snapshot of whatever is playing right now, read from the active media session.
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artwork: Bitmap?,
    val playing: Boolean,
    /** Position of the track at [positionUpdatedAt]. */
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() when [positionMs] was sampled. */
    val positionUpdatedAt: Long,
    val playbackSpeed: Float,
    val sessionToken: MediaSession.Token?,
) {
    /** Position extrapolated to now, so the lyric line stays in step between updates. */
    fun livePosition(): Long {
        if (!playing || playbackSpeed <= 0f) return positionMs
        val delta = SystemClock.elapsedRealtime() - positionUpdatedAt
        if (delta <= 0L) return positionMs
        return positionMs + (delta * playbackSpeed).toLong()
    }
}

/** Single source of truth for "what is playing", shared between services. */
object NowPlayingBus {

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state

    fun publish(value: NowPlaying?) {
        _state.value = value
    }
}
