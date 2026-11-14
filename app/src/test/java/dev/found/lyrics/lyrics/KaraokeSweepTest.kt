package dev.found.lyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KaraokeSweepTest {

    private val lines = LrcParser.parse(
        """
        [00:00.00]first line
        [00:10.00]second line
        [00:20.00]last line
        """.trimIndent()
    )

    @Test
    fun thereIsNothingBeforeTheFirstLine() {
        assertNull(KaraokeSweep.at(lines, -1L))
    }

    @Test
    fun thereIsNothingWithoutLyrics() {
        assertNull(KaraokeSweep.at(emptyList(), 1_000L))
    }

    @Test
    fun theProgressRunsThroughTheLine() {
        val start = KaraokeSweep.at(lines, 10_000L)
        assertNotNull(start)
        assertEquals(0f, start!!.progress, 0.001f)
        assertEquals("second line", start.line.text)

        val middle = KaraokeSweep.at(lines, 15_000L)
        assertEquals(0.5f, middle!!.progress, 0.001f)

        val end = KaraokeSweep.at(lines, 19_999L)
        assertEquals(1f, end!!.progress, 0.001f)
    }

    @Test
    fun theNextLineIsTheOneAfter() {
        val sweep = KaraokeSweep.at(lines, 12_000L)

        assertEquals("second line", sweep?.line?.text)
        assertEquals("last line", sweep?.next?.text)
    }

    @Test
    fun theLastLineEndsOnItsOwn() {
        val sweep = KaraokeSweep.at(lines, 20_000L)

        assertNotNull(sweep)
        assertNull(sweep!!.next)
        assertEquals(0f, sweep.progress, 0.001f)
    }

    @Test
    fun withoutWordTimingsThereIsNoWord() {
        val sweep = KaraokeSweep.at(lines, 12_000L)

        assertEquals(-1, sweep?.wordIndex)
        assertEquals(0f, sweep?.wordProgress ?: -1f, 0.001f)
    }

    @Test
    fun theWordFollowsItsOwnTiming() {
        val worded = LrcParser.parse("[00:10.00]Hello <00:11.00>world <00:12.00>again")

        val first = KaraokeSweep.at(worded, 10_500L)
        assertEquals(0, first?.wordIndex)
        assertEquals(0.5f, first?.wordProgress ?: -1f, 0.001f)

        val second = KaraokeSweep.at(worded, 11_500L)
        assertEquals(1, second?.wordIndex)
        assertEquals(0.5f, second?.wordProgress ?: -1f, 0.001f)

        val third = KaraokeSweep.at(worded, 12_500L)
        assertEquals(2, third?.wordIndex)
        // The last word runs until the end of the line.
        assertEquals(0.25f, third?.wordProgress ?: -1f, 0.001f)
    }

    @Test
    fun beforeTheFirstWordThereIsNoWord() {
        val worded = LrcParser.parse("[00:10.00]<00:10.50>Hello <00:11.00>world")

        val sweep = KaraokeSweep.at(worded, 10_000L)

        assertEquals(-1, sweep?.wordIndex)
    }
}
