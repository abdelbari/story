package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A PDF has no underline and no strike: a producer draws a hair of a rule
 * where the words are. What has to be told apart from a mark is everything
 * else a page rules — a border under a paragraph, a table's line, a bar of
 * colour behind the words — because reading a bar as a strike would have a
 * document withdraw what it had emphasised.
 *
 * Twelve-point type on a baseline at 100, its ink running from 72 to 300.
 */
class PdfMarksTest {

    private val baseline = 100f
    private val size = 12f
    private val inkLeft = 72f
    private val inkRight = 300f

    private fun rule(y: Float, left: Float = 72f, right: Float = 300f, thick: Float = 0.8f) =
        PdfRule(page = 1, y = y, left = left, right = right, thicknessPt = thick)

    private fun mark(rule: PdfRule) = PdfMarks.of(rule, baseline, size, inkLeft, inkRight)

    @Test
    fun `a hair under the baseline is an underline`() {
        // Word draws one about a sixth of the type size down.
        assertEquals(PdfMarks.Mark.UNDERLINE, mark(rule(101.6f)))
        assertEquals(PdfMarks.Mark.UNDERLINE, mark(rule(100f)))
        assertEquals(PdfMarks.Mark.UNDERLINE, mark(rule(103f)))
    }

    @Test
    fun `a hair through the letters is a strike`() {
        assertEquals(PdfMarks.Mark.STRIKE, mark(rule(96f)))
        assertEquals(PdfMarks.Mark.STRIKE, mark(rule(97.5f)))
    }

    @Test
    fun `a border under the paragraph is not an underline`() {
        // Far enough below the baseline to clear the descenders, and run
        // to the margins rather than to the words.
        assertNull(mark(rule(106f)))
        assertNull(mark(rule(102f, left = 56f, right = 540f)))
    }

    @Test
    fun `a table's rule across the column is not a mark`() {
        // It reaches well past the words in the cell, on both sides.
        assertNull(mark(rule(101.5f, left = 40f, right = 400f)))
        assertNull(mark(rule(96f, left = 40f, right = 400f)))
    }

    @Test
    fun `a bar of colour behind the words is not a strike`() {
        // A highlight covers the line: as deep as the type, and centred on
        // it, which is exactly where a strike would be.
        assertNull(mark(rule(96f, thick = 4f)))
        assertNull(mark(rule(96f, thick = 2.5f)))
    }

    @Test
    fun `a rule belonging to another line is not this line's mark`() {
        assertNull(mark(rule(88f)))
        assertNull(mark(rule(115f)))
    }

    @Test
    fun `an underline may run on past the last word it marks`() {
        // Word underlines the space at the end of the words too.
        assertEquals(PdfMarks.Mark.UNDERLINE, mark(rule(101.6f, right = 308f)))
        assertNull(mark(rule(101.6f, right = 330f)))
    }

    @Test
    fun `a line with no type and no ink is never marked`() {
        assertNull(PdfMarks.of(rule(101.6f), baseline, 0f, inkLeft, inkRight))
        assertNull(PdfMarks.of(rule(101.6f), baseline, size, 300f, 72f))
    }

    @Test
    fun `a mark covers the glyphs it lies under and no others`() {
        val drawn = rule(101.6f, left = 100f, right = 200f)
        assertTrue(PdfMarks.covers(drawn, 120f, 130f))
        assertTrue(PdfMarks.covers(drawn, 195f, 203f), "a glyph mostly under the rule is under it")
        assertFalse(PdfMarks.covers(drawn, 80f, 95f))
        assertFalse(PdfMarks.covers(drawn, 198f, 220f), "a glyph mostly past the rule is past it")
    }

    @Test
    fun `a mark scales with the type it marks`() {
        // The same rule three points under the baseline: a mark on
        // twelve-point type, and too far off on eight.
        assertEquals(PdfMarks.Mark.UNDERLINE, PdfMarks.of(rule(103f), baseline, 12f, inkLeft, inkRight))
        assertNull(PdfMarks.of(rule(103f), baseline, 8f, inkLeft, inkRight))
    }
}
