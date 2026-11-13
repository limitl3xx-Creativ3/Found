package dev.found.lyrics.lyrics

/** A single word with the timestamp it is sung at (present in enhanced LRC). */
data class LyricWord(
    val timeMs: Long,
    val text: String,
)

/** One lyric line, starting at [timeMs]. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    /** Word-level timings when the source provides them; empty otherwise. */
    val words: List<LyricWord> = emptyList(),
) {
    val wordSynced: Boolean get() = words.size > 1
}

/**
 * Parser for LRC lyrics, including the enhanced `<mm:ss.xx>` word timings.
 */
object LrcParser {

    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /** Parses an LRC document into time-ordered lines. */
    fun parse(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val lines = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val stamps = TIME.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val body = line.substring(stamps.first().range.last + 1)
            val words = parseWords(body)
            val text = normalize(if (words.isNotEmpty()) body.replace(WORD, "") else body)
            if (text.isEmpty() && words.isEmpty()) continue
            for (match in stamps) {
                lines.add(LyricLine(timeMs(match), text, words))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * Index of the line that is playing at [positionMs], or -1 before the first line.
     */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun parseWords(body: String): List<LyricWord> {
        val marks = WORD.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()
        val words = mutableListOf<LyricWord>()
        for (i in marks.indices) {
            val start = marks[i].range.last + 1
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else body.length
            if (start >= end) continue
            val chunk = normalize(body.substring(start, end))
            if (chunk.isEmpty()) continue
            words.add(LyricWord(timeMs(marks[i]), chunk))
        }
        return words
    }

    private fun normalize(text: String): String =
        text.replace(' ', ' ').trim().replace(Regex("\\s+"), " ")

    private fun timeMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fractionText = match.groupValues[3]
        val fraction = when (fractionText.length) {
            0 -> 0L
            1 -> (fractionText.toLongOrNull() ?: 0L) * 100
            2 -> (fractionText.toLongOrNull() ?: 0L) * 10
            else -> fractionText.take(3).toLongOrNull() ?: 0L
        }
        return (minutes * 60_000L) + (seconds * 1_000L) + fraction
    }
}
