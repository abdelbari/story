package app.morpho.engine.layout

import app.morpho.engine.layout.pdf.PdfOutline
import app.morpho.engine.layout.pdf.PdfOutlineEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What a document's own list of chapters says about the lines on its pages. */
class PdfOutlineTest {

    private val outline = listOf(
        PdfOutlineEntry("Getting started", 0, 3),
        PdfOutlineEntry("1. Turning it on", 1, 3),
        PdfOutlineEntry("Maintenance", 0, 9),
    )

    @Test
    fun `a line an entry names on its own page is that entry's heading`() {
        assertEquals(ParagraphKind.HEADING_1, PdfOutline.kindOf(outline, 3, "Getting started"))
        assertEquals(ParagraphKind.HEADING_1, PdfOutline.kindOf(outline, 9, "Maintenance"))
    }

    @Test
    fun `a section inside a chapter is a heading under it`() {
        assertEquals(ParagraphKind.HEADING_2, PdfOutline.kindOf(outline, 3, "Turning it on"))
    }

    @Test
    fun `a contents page naming every chapter is not the chapters`() {
        // The same words, on the page the contents are set on.
        assertNull(PdfOutline.kindOf(outline, 2, "Getting started"))
        // And the line as a contents page writes it, with what it points at.
        assertNull(PdfOutline.kindOf(outline, 3, "Getting started ......... 3"))
    }

    @Test
    fun `a paragraph that merely opens with a chapter's name is not a heading`() {
        assertNull(
            PdfOutline.kindOf(
                outline,
                3,
                "Getting started with the machine is a matter of reading what follows and doing it in order.",
            )
        )
    }

    @Test
    fun `spacing and case are not what tells two names apart`() {
        assertEquals(ParagraphKind.HEADING_1, PdfOutline.kindOf(outline, 9, "  MAINTENANCE  "))
        assertEquals(ParagraphKind.HEADING_1, PdfOutline.kindOf(outline, 3, "Getting\tstarted"))
    }

    @Test
    fun `an entry that leads nowhere still names its heading`() {
        val loose = listOf(PdfOutlineEntry("Appendix", 0, 0))
        assertEquals(ParagraphKind.HEADING_1, PdfOutline.kindOf(loose, 4, "Appendix"))
    }

    @Test
    fun `a document with no outline says nothing about its lines`() {
        assertNull(PdfOutline.kindOf(emptyList(), 1, "Getting started"))
    }

    @Test
    fun `anything deeper than a section is a heading of the third rank`() {
        assertEquals(ParagraphKind.HEADING_3, PdfOutline.kindFor(2))
        assertEquals(ParagraphKind.HEADING_3, PdfOutline.kindFor(7))
    }
}
