package dev.found.lyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesTimestampedLines() {
        val lrc = """
            [ar:Coldplay]
            [00:12.34]And dreamed of para-para-paradise
            [00:15.50]Para-para-paradise
        """.trimIndent()

        val lines = LrcParser.parse(lrc)

        assertEquals(2, lines.size)
        assertEquals(12_340L, lines[0].timeMs)
        assertEquals("And dreamed of para-para-paradise", lines[0].text)
        assertEquals(15_500L, lines[1].timeMs)
    }

    @Test
    fun parsesFractionsWithDifferentPrecision() {
        val lines = LrcParser.parse("[00:01.5]one\n[00:02.25]two\n[00:03.125]three")

        assertEquals(1_500L, lines[0].timeMs)
        assertEquals(2_250L, lines[1].timeMs)
        assertEquals(3_125L, lines[2].timeMs)
    }

    @Test
    fun parsesWordLevelTimings() {
        val lrc = "[00:10.00]Hello <00:10.50>world <00:11.00>again"
        val lines = LrcParser.parse(lrc)

        assertEquals(1, lines.size)
        assertEquals(3, lines[0].words.size)
        assertEquals(10_000L, lines[0].words[0].timeMs)
        assertEquals("world", lines[0].words[1].text)
        assertTrue(lines[0].wordSynced)
    }

    @Test
    fun ignoresMetadataTagsAndBlankLines() {
        val lines = LrcParser.parse("[ti:Paradise]\n\n[00:05.00]real line\n")

        assertEquals(1, lines.size)
        assertEquals("real line", lines[0].text)
    }

    @Test
    fun findsTheLineThatIsPlaying() {
        val lines = LrcParser.parse("[00:00.00]first\n[00:10.00]second\n[00:20.00]third")

        assertEquals(0, LrcParser.indexAt(lines, 0L))
        assertEquals(0, LrcParser.indexAt(lines, 9_999L))
        assertEquals(1, LrcParser.indexAt(lines, 10_000L))
        assertEquals(2, LrcParser.indexAt(lines, 25_000L))
    }

    @Test
    fun returnsNothingForEmptyInput() {
        assertTrue(LrcParser.parse("").isEmpty())
        assertEquals(-1, LrcParser.indexAt(emptyList(), 100L))
    }
}
