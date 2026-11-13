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
 * When the source provides word-level timings the accent is clipped at the end
 * of the word currently being sung, which reads as word-by-word karaoke.
 */
class KaraokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var layout: StaticLayout? = null
    private var content: CharSequence = ""
    private var wordEnds: List<Int> = emptyList()

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
        content = value
        layout = null
        requestLayout()
        invalidate()
    }

    /** Changes the text size and re-lays out the line. */
    fun setTextSizePx(size: Float) {
        paint.textSize = size
        layout = null
        requestLayout()
        invalidate()
    }

    /** Word timings for this line; empty when the source has none. */
    fun setWords(words: List<LyricWord>) {
        wordEnds = if (words.isEmpty()) emptyList() else buildWordEnds(words)
        invalidate()
    }

    /** Character offsets where each word ends, so the clip can follow the words. */
    private fun buildWordEnds(words: List<LyricWord>): List<Int> {
        val text = content.toString()
        val ends = mutableListOf<Int>()
        var cursor = 0
        for (word in words) {
            val index = text.indexOf(word.text, cursor)
            if (index < 0) {
                ends.add(text.length)
                continue
            }
            cursor = index + word.text.length
            ends.add(cursor)
        }
        return ends
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val wrapWidth = if (maxWidth > 0) minOf(available, maxWidth) else available
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

    /** Where the accent should stop: at the sung word, or at the sweep position. */
    private fun accentClipWidth(textLayout: StaticLayout): Float {
        if (wordEnds.isNotEmpty() && textLayout.lineCount == 1) {
            val sung = (progress * wordEnds.size).toInt().coerceIn(0, wordEnds.size)
            val end = if (sung == 0) 0 else wordEnds[sung - 1]
            if (end <= 0) return 0f
            return paint.measureText(content.subSequence(0, end).toString())
        }
        return textLayout.width * progress
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
