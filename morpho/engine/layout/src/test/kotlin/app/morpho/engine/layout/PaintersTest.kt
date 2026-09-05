package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The reversal moves characters; what painted each has to move with it,
 * or a bold word ends up bold in the wrong place.
 */
class PaintersTest {

    @Test
    fun `reorder reports where each character of the result came from`() {
        // Visual "تبا" is logical "ابت": the last painted glyph is read first.
        val reordered = Bidi.reorder("تبا", TextDirection.RTL)
        assertEquals("ابت", reordered.text)
        assertEquals(listOf(2, 1, 0), reordered.sources.toList())
    }

    @Test
    fun `a left-to-right line maps onto itself`() {
        val reordered = Bidi.reorder("plain", TextDirection.LTR)
        assertEquals("plain", reordered.text)
        assertEquals(listOf(0, 1, 2, 3, 4), reordered.sources.toList())
    }

    @Test
    fun `a fenced date keeps its sources with the marks taken out`() {
        val visual = "2022-04-21" + "خ".reversed()
        val reordered = Bidi.reorder(visual, TextDirection.RTL)
        assertEquals("خ2022-04-21", reordered.text)
        assertEquals(reordered.text.length, reordered.sources.size)
        assertEquals(10, reordered.sources[0], "the letter came from the end of the visual line")
        assertEquals((0 until 10).toList(), reordered.sources.drop(1))
    }

    @Test
    fun `a painter follows its character through a ligature fold and a collapsed run of spaces`() {
        // Visual: x, two spaces, the lam-alef ligature. Logical, base
        // right-to-left: the ligature first, folded into its two letters,
        // one space, then x.
        val logical = ExtractedText.toLogical("x  ﻻ", listOf("X", "S1", "S2", "L"), TextDirection.RTL)
        assertEquals("لا x", logical.text)
        // The surviving space is the first in logical order — the second
        // one painted, once the line is reversed.
        assertEquals(listOf("L", "L", "S2", "X"), logical.painters)
    }

    @Test
    fun `an inserted space has no painter and everything else keeps its own`() {
        val logical = ExtractedText.toLogical("ab", listOf("A", null), TextDirection.LTR)
        assertEquals("ab", logical.text)
        assertEquals(listOf("A", null), logical.painters)
    }

    @Test
    fun `a date after arabic letters is one left-to-right run`() {
        // To the plain rules the hyphen between Arabic-context digits is a
        // right-to-left neutral, and a run of its own; marked so in a file,
        // Word displays the date reversed.
        val runs = Bidi.directionalRuns("تاريخ:2022-04-21", TextDirection.RTL)
        assertEquals(2, runs.size, "$runs")
        assertEquals(TextDirection.RTL, runs[0].direction)
        assertEquals(TextDirection.LTR, runs[1].direction)
        assertEquals("2022-04-21", "تاريخ:2022-04-21".substring(runs[1].start, runs[1].end))
    }
}
