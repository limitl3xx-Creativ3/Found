package dev.found.lyrics.util

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Pulls a single accent colour out of album artwork so the bubble can carry it.
 */
object AccentExtractor {

    /** Used when there is no artwork, or the artwork has no usable colour. */
    val FALLBACK: Int = Color.parseColor("#FF6B6B")

    private const val SAMPLE_SIZE = 24

    /** Returns an accent colour for the given artwork, never fully transparent. */
    fun from(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return FALLBACK
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
            val buckets = HashMap<Int, IntArray>()
            for (y in 0 until SAMPLE_SIZE) {
                for (x in 0 until SAMPLE_SIZE) {
                    val pixel = scaled.getPixel(x, y)
                    if (Color.alpha(pixel) < 128) continue
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    val entry = buckets.getOrPut(key) { intArrayOf(0, 0, 0, 0) }
                    entry[0] += 1
                    entry[1] += r
                    entry[2] += g
                    entry[3] += b
                }
            }
            if (buckets.isEmpty()) return FALLBACK

            var bestScore = -1f
            var best = FALLBACK
            for (entry in buckets.values) {
                val count = entry[0]
                val r = entry[1] / count
                val g = entry[2] / count
                val b = entry[3] / count
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val saturation = if (max == 0) 0f else (max - min).toFloat() / max
                val lightness = (max + min) / 2f / 255f
                // Favour colourful pixels, but keep near-white and near-black out.
                if (lightness > 0.88f || lightness < 0.08f) continue
                val score = count * (0.35f + saturation)
                if (score > bestScore) {
                    bestScore = score
                    best = Color.rgb(r, g, b)
                }
            }
            if (bestScore < 0f) FALLBACK else ensureReadable(best)
        } catch (e: Exception) {
            FALLBACK
        }
    }

    /** Blends [color] with a dark base so the bubble stays readable. */
    fun blend(color: Int, base: Int, ratio: Float): Int {
        val r = (Color.red(base) + (Color.red(color) - Color.red(base)) * ratio).toInt()
        val g = (Color.green(base) + (Color.green(color) - Color.green(base)) * ratio).toInt()
        val b = (Color.blue(base) + (Color.blue(color) - Color.blue(base)) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    /** Nudges a colour until it has enough contrast on a dark bubble. */
    private fun ensureReadable(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val saturation = if (max == 0) 0f else (max - min).toFloat() / max
        if (saturation < 0.15f) return FALLBACK
        val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        if (luminance < 0.35f) {
            // Lift the colour towards white until it is bright enough to read.
            val factor = (0.35f - luminance) / 0.35f
            return Color.rgb(
                (r + (255 - r) * factor).toInt().coerceIn(0, 255),
                (g + (255 - g) * factor).toInt().coerceIn(0, 255),
                (b + (255 - b) * factor).toInt().coerceIn(0, 255),
            )
        }
        return color
    }
}
