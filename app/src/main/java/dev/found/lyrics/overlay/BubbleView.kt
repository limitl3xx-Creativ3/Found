package dev.found.lyrics.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.found.lyrics.R
import dev.found.lyrics.lyrics.LyricWord
import dev.found.lyrics.util.AccentExtractor

/**
 * The floating bubble: cover art plus two lyric lines.
 *
 * It has two shapes — the expanded card while something is playing, and a thin
 * pill when paused or idle. The position is fixed (never draggable); only the
 * shape changes.
 */
class BubbleView(context: Context) : FrameLayout(context) {

    private val background = GradientDrawable()
    private val artwork: ImageView
    private val title: TextView
    private val currentLine: KaraokeTextView
    private val nextLine: KaraokeTextView
    private val column: LinearLayout

    private var expanded = true

    init {
        background.cornerRadius = 1000f
        background.setColor(Color.parseColor("#E612121A"))
        setBackground(background)
        setPadding(dp(10), dp(8), dp(12), dp(8))
        elevation = dp(6).toFloat()

        artwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(null)
            contentDescription = context.getString(R.string.album_art)
        }
        addView(
            artwork,
            LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_VERTICAL },
        )

        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        title = TextView(context).apply {
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        currentLine = KaraokeTextView(context).apply {
            // New lyrics are announced politely as they come in.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        nextLine = KaraokeTextView(context).apply {
            baseColor = Color.parseColor("#80FFFFFF")
        }

        column.addView(
            title,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        column.addView(
            currentLine,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        column.addView(
            nextLine,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        addView(
            column,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(10)
            },
        )
    }

    /** Applies the accent colour taken from the cover art. */
    fun setAccent(color: Int) {
        currentLine.accentColor = color
        background.setColor(AccentExtractor.blend(color, Color.parseColor("#12121A"), 0.22f))
        background.setStroke(dp(1), AccentExtractor.blend(color, Color.parseColor("#12121A"), 0.45f))
        invalidate()
    }

    /** Shows the album art, if there is any. */
    fun setArtwork(bitmap: Bitmap?) {
        artwork.setImageBitmap(bitmap)
        artwork.alpha = if (bitmap == null) 0f else 1f
    }

    /** Sets the title line (artist - title). */
    fun setTitle(value: String?) {
        title.text = value.orEmpty()
    }

    /** Sets the lyric text: the line being sung and the one after it. */
    fun setLyrics(current: String?, next: String?) {
        currentLine.setContent(current.orEmpty())
        nextLine.setContent(next.orEmpty())
        nextLine.visibility = if (next.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Sets how far through the current line the karaoke sweep has travelled. */
    fun setProgress(progress: Float) {
        currentLine.progress = progress
    }

    /** Word timings for the current line, when the source has them. */
    fun setWords(words: List<LyricWord>) {
        currentLine.setWords(words)
    }

    /** Moves the sweep inside the word that is being sung. */
    fun setWordProgress(index: Int, fraction: Float) {
        currentLine.setWordProgress(index, fraction)
    }

    /** Sets the maximum width the lyric lines may take. */
    fun setMaxTextWidth(px: Int) {
        currentLine.setMaxTextWidth(px)
        nextLine.setMaxTextWidth(px)
    }

    /** Applies the user's text-size preference. */
    fun setTextScale(scale: Float) {
        val size = 15f * scale * resources.displayMetrics.scaledDensity
        currentLine.setTextSizePx(size)
        nextLine.setTextSizePx(size)
    }

    /** Switches between the expanded card and the collapsed pill. */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        column.visibility = if (value) View.VISIBLE else View.GONE
        background.cornerRadius = 1000f
        invalidate()
    }

    fun isExpanded(): Boolean = expanded

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
