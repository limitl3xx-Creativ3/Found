package dev.found.lyrics.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import dev.found.lyrics.lyrics.LyricWord

/**
 * A lyric line drawn with a karaoke sweep: the text is painted in a muted
 * colour and the part that has been sung is painted again in the accent colour
 * through a clip rectangle.
 *
 * When the source provides word-level timings the accent is clipped inside the
 * word currently being sung, which reads as word-by-word karaoke. Without them
 * the accent sweeps the line in step with the time.
 */
class KaraokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var layout: StaticLayout? = null
    private var content: CharSequence = ""

    /** Character range of every word of the current line, in the same order as [words]. */
    private var wordRanges: List<IntRange> = emptyList()

    /** Index of the word being sung, or -1 when the line is not word synced. */
    private var wordIndex = -1

    /** How far through the current word the audio is, 0 - 1. */
    private var wordProgress = 0f

    /** The widest the text may get, in pixels. 0 means no limit. */
    private var maxTextWidth = 0

    /** Colour of the text that has not been sung yet. */
    var baseColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** Colour of the text that has been sung. */
    var accentColor: Int = Color.parseColor("#FF6B6B")
        set(value) {
            field = value
            invalidate()
        }

    /** How far through the line the sweep has travelled (0 - 1). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Sets the text and lays it out for the available width. */
    fun setContent(value: CharSequence) {
        if (content == value) return
        content = value
        layout = null
        wordRanges = emptyList()
        wordIndex = -1
        // The text is painted by hand, so a screen reader would otherwise have
        // nothing to read: hand it the line as its description.
        contentDescription = value
        requestLayout()
        invalidate()
    }

    /** Changes the text size and re-lays out the line. */
    fun setTextSizePx(size: Float) {
        if (paint.textSize == size) return
        paint.textSize = size
        layout = null
        requestLayout()
        invalidate()
    }

    /** Limits how wide the line may get, in pixels. */
    fun setMaxTextWidth(px: Int) {
        if (maxTextWidth == px) return
        maxTextWidth = px
        layout = null
        requestLayout()
        invalidate()
    }

    /** Word timings for this line; empty when the source has none. */
    fun setWords(words: List<LyricWord>) {
        wordRanges = locateWords(words)
        wordIndex = -1
        wordProgress = 0f
        invalidate()
    }

    /**
     * Moves the sweep to [index] of the word list and [fraction] through that
     * word. An index below zero falls back to the time based [progress].
     */
    fun setWordProgress(index: Int, fraction: Float) {
        wordIndex = index
        wordProgress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * Finds where each word sits in the current text.
     *
     * Words that cannot be found (the source normalised them differently) give
     * up the word sweep, so the line falls back to the time based one instead
     * of highlighting the wrong words.
     */
    private fun locateWords(words: List<LyricWord>): List<IntRange> {
        if (words.isEmpty()) return emptyList()
        val text = content.toString()
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (word in words) {
            val index = text.indexOf(word.text, cursor)
            if (index < 0) return emptyList()
            cursor = index + word.text.length
            ranges.add(index until cursor)
        }
        return ranges
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val wrapWidth = if (maxTextWidth > 0) minOf(available, maxTextWidth) else available
        if (wrapWidth <= 0) {
            setMeasuredDimension(available, paddingTop + paddingBottom)
            return
        }
        val textLayout = layoutFor(wrapWidth)
        var used = 0f
        for (line in 0 until textLayout.lineCount) {
            used = maxOf(used, textLayout.getLineWidth(line))
        }
        val width = minOf(wrapWidth, kotlin.math.ceil(used.toDouble()).toInt().coerceAtLeast(1))
        setMeasuredDimension(width, textLayout.height + paddingTop + paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout ?: return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

        paint.color = baseColor
        textLayout.draw(canvas)

        val clipWidth = accentClipWidth(textLayout)
        if (clipWidth > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, clipWidth, textLayout.height.toFloat())
            paint.color = accentColor
            textLayout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    /** Where the accent should stop: inside the sung word, or at the sweep position. */
    private fun accentClipWidth(textLayout: StaticLayout): Float {
        val range = wordRanges.getOrNull(wordIndex)
        if (range != null && textLayout.lineCount == 1) {
            val text = content.toString()
            val prefix = paint.measureText(text.substring(0, range.first))
            val word = paint.measureText(text.substring(range.first, range.last + 1))
            return prefix + word * wordProgress
        }
        return textWidth(textLayout) * progress
    }

    /** The width the text actually occupies, which can be narrower than the layout. */
    private fun textWidth(textLayout: StaticLayout): Float {
        var width = 0f
        for (line in 0 until textLayout.lineCount) {
            width = maxOf(width, textLayout.getLineWidth(line))
        }
        return width
    }

    private fun layoutFor(width: Int): StaticLayout {
        val existing = layout
        if (existing != null && existing.width == width) return existing
        val created = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        layout = created
        return created
    }
}
