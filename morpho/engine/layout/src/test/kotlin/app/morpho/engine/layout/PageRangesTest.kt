package app.morpho.engine.layout

import app.morpho.engine.layout.pdf.PageRanges
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What a reader means when they type which pages they want. */
class PageRangesTest {

    @Test
    fun `a range is the pages between its ends`() {
        assertEquals(5..20, PageRanges.parse("5-20"))
        assertEquals(5..20, PageRanges.parse(" 5 - 20 "))
    }

    @Test
    fun `a number alone is that page alone`() {
        assertEquals(7..7, PageRanges.parse("7"))
    }

    @Test
    fun `an open end means to the end, or from the start`() {
        assertEquals(5..Int.MAX_VALUE, PageRanges.parse("5-"))
        assertEquals(1..20, PageRanges.parse("-20"))
    }

    @Test
    fun `a reader who types it backwards means the pages between`() {
        assertEquals(5..20, PageRanges.parse("20-5"))
    }

    @Test
    fun `Arabic digits are the digits they are`() {
        assertEquals(5..20, PageRanges.parse("\u0665-\u0662\u0660"))
        assertEquals(7..7, PageRanges.parse("\u0667"))
    }

    @Test
    fun `every dash a keyboard offers is a dash`() {
        assertEquals(3..9, PageRanges.parse("3\u20139"))
        assertEquals(3..9, PageRanges.parse("3\u20149"))
        assertEquals(3..9, PageRanges.parse("3\u22129"))
    }

    @Test
    fun `what names no pages means the whole document`() {
        assertNull(PageRanges.parse(""))
        assertNull(PageRanges.parse("   "))
        assertNull(PageRanges.parse("all of it"))
        assertNull(PageRanges.parse("-"))
        assertNull(PageRanges.parse("0"))
    }

    @Test
    fun `a page before the first is the first`() {
        assertEquals(1..9, PageRanges.parse("0-9"))
    }
}
