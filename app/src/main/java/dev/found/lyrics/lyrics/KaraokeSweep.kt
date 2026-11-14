package dev.found.lyrics.lyrics

/**
 * Where the karaoke sweep currently is.
 *
 * [progress] is how far through the line the audio is, by time.
 * [wordIndex] is the word being sung (-1 when the line has no word timings),
 * and [wordProgress] is how far through that word the audio is.
 */
data class Sweep(
    val line: LyricLine,
    val next: LyricLine?,
    val progress: Float,
    val wordIndex: Int,
    val wordProgress: Float,
)

/**
 * The maths behind the karaoke sweep, kept free of Android types so it can be
 * unit tested.
 *
 * It answers one question: given the lyrics and a playback position, what is
 * on screen and how far through is it?
 */
object KaraokeSweep {

    /**
     * How long the last line of a track is assumed to last when no next line
     * follows it.
     */
    const val LAST_LINE_SPAN_MS = 4_000L

    /**
     * The state of the lyrics at [positionMs], or null when there is nothing to
     * show (no lyrics at all, or the audio is before the first line).
     */
    fun at(lines: List<LyricLine>, positionMs: Long): Sweep? {
        if (lines.isEmpty()) return null
        val index = LrcParser.indexAt(lines, positionMs)
        if (index < 0) return null

        val line = lines[index]
        val next = lines.getOrNull(index + 1)
        val end = next?.timeMs ?: (line.timeMs + LAST_LINE_SPAN_MS)
        val span = (end - line.timeMs).coerceAtLeast(1L)
        val progress = ((positionMs - line.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)

        var wordIndex = -1
        var wordProgress = 0f
        if (line.words.size > 1) {
            val words = line.words
            var i = words.size - 1
            while (i >= 0 && words[i].timeMs > positionMs) {
                i--
            }
            if (i >= 0) {
                wordIndex = i
                val wordEnd = if (i + 1 < words.size) words[i + 1].timeMs else end
                val wordSpan = (wordEnd - words[i].timeMs).coerceAtLeast(1L)
                wordProgress =
                    ((positionMs - words[i].timeMs).toFloat() / wordSpan.toFloat())
                        .coerceIn(0f, 1f)
            }
        }

        return Sweep(
            line = line,
            next = next,
            progress = progress,
            wordIndex = wordIndex,
            wordProgress = wordProgress,
        )
    }
}
