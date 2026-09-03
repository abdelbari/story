package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The markers a page has to draw for itself, since a page has no numbering. */
class ListLabelsTest {

    @Test
    fun `each level counts the way an outline counts`() {
        assertEquals("1.", ListLabels.number(0, 1))
        assertEquals("b)", ListLabels.number(1, 2))
        assertEquals("iv.", ListLabels.number(2, 4))
        // Deeper than the ladder is long, it starts over.
        assertEquals("3.", ListLabels.number(3, 3))
    }

    @Test
    fun `a list outlasting the alphabet keeps counting`() {
        assertEquals("a", ListLabels.letter(1))
        assertEquals("z", ListLabels.letter(26))
        assertEquals("aa", ListLabels.letter(27))
        assertEquals("bb", ListLabels.letter(28))
    }

    @Test
    fun `roman numerals count as a list counts them`() {
        assertEquals(
            listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"),
            (1..10).map(ListLabels::roman),
        )
        assertEquals("xiv", ListLabels.roman(14))
        assertEquals("xl", ListLabels.roman(40))
    }

    @Test
    fun `each level of a bulleted list takes a bullet of its own`() {
        assertEquals(listOf("•", "◦", "▪", "•"), (0..3).map(ListLabels::bullet))
    }

    @Test
    fun `an item counts at its own level and starts again where its list does`() {
        val counts = ListCounts()
        fun at(level: Int) = counts.next(ParagraphStyle(listMarker = ListMarker.NUMBERED, listLevel = level))
        assertEquals(1, at(0))
        assertEquals(1, at(1))
        assertEquals(2, at(1))
        // Back out to the level above: its own count carries on.
        assertEquals(2, at(0))
        // And into the level below again, which starts over.
        assertEquals(1, at(1))
    }

    @Test
    fun `a paragraph that is no list item ends the count`() {
        val counts = ListCounts()
        assertEquals(1, counts.next(ParagraphStyle(listMarker = ListMarker.NUMBERED)))
        assertEquals(0, counts.next(ParagraphStyle()))
        assertEquals(1, counts.next(ParagraphStyle(listMarker = ListMarker.NUMBERED)))
    }

    @Test
    fun `an item sits a quarter inch in for every list it is inside`() {
        val outer = ParagraphStyle(listMarker = ListMarker.BULLET, listLevel = 0)
        val inner = ParagraphStyle(listMarker = ListMarker.BULLET, listLevel = 2)
        assertEquals(0f, ListLabels.indentPt(outer))
        assertEquals(36f, ListLabels.indentPt(inner))
        // A paragraph that is no list item is not indented for being one.
        assertEquals(0f, ListLabels.indentPt(ParagraphStyle(listLevel = 3)))
    }

    @Test
    fun `the marker before an item is the whole of what is drawn`() {
        assertEquals(
            "◦ ",
            ListLabels.markerFor(ParagraphStyle(listMarker = ListMarker.BULLET, listLevel = 1), 1),
        )
        assertEquals(
            "c) ",
            ListLabels.markerFor(ParagraphStyle(listMarker = ListMarker.NUMBERED, listLevel = 1), 3),
        )
        assertEquals("", ListLabels.markerFor(ParagraphStyle(), 1))
    }

    @Test
    fun `a label is a marker or a short enumerator, and a space after it`() {
        // What both readers ask before deciding that a line is an item of
        // a list: the one with tags so as not to draw a second marker over
        // the page's own, the one without because a label is where one
        // item ends and the next begins.
        for (label in listOf("• item", "- item", "* item", "– item", "» item", "▪ item")) {
            assertTrue(ListLabels.opensWithLabel(label), label)
        }
        for (label in listOf("1. item", "2) item", "a. item", "أ- item", "12. item")) {
            assertTrue(ListLabels.opensWithLabel(label), label)
        }
    }

    @Test
    fun `prose that merely opens like a label is prose`() {
        for (text in listOf(
            "-and on with no space after the dash",
            "The ordinary opening of a sentence",
            "1984. was a year, written out in full",
            "",
            "Introduction: the opening of a paper",
        )) {
            assertFalse(ListLabels.opensWithLabel(text), text)
        }
    }
}
