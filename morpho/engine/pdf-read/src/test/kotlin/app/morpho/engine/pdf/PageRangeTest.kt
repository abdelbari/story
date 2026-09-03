package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A reader who wants one chapter of a book, or one part of a document too
 * big for the phone to hold whole, should be able to convert what they
 * need rather than all of it.
 */
class PageRangeTest {

    @Test
    fun `only the pages asked for are read`() {
        assertEquals(listOf("Page two", "Page three"), textOf(book(), 2..3))
    }

    @Test
    fun `asking for one page reads one page`() {
        assertEquals(listOf("Page four"), textOf(book(), 4..4))
    }

    @Test
    fun `asking for no range reads the whole document`() {
        assertEquals(
            listOf("Page one", "Page two", "Page three", "Page four", "Page five"),
            textOf(book(), null),
        )
    }

    @Test
    fun `a range that runs past the end reads what is there`() {
        assertEquals(listOf("Page four", "Page five"), textOf(book(), 4..40))
    }

    @Test
    fun `a range of pages the document does not have reads its first`() {
        // Asking for nothing is not an answer; the first page beats an
        // empty document handed back as though that were the file.
        assertEquals(listOf("Page one"), textOf(book(), 40..50))
    }

    @Test
    fun `the part is read as a document of its own`() {
        // The pages of the part are numbered from one, so anything measured
        // per page — the running head, the page a line sits on — counts
        // from the start of what was asked for.
        val model = PdfReader().extract(book(), "", 3..5)
        assertTrue(model.pageSetup != null, "the part was not measured at all")
        assertEquals(3, model.blocks.filterIsInstance<Paragraph>().size)
    }

    private fun textOf(pdf: ByteArray, pages: IntRange?): List<String> =
        PdfReader().extract(pdf, "", pages).blocks
            .filterIsInstance<Paragraph>()
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }

    /** Five pages, each saying which it is. */
    private fun book(): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            for (name in listOf("one", "two", "three", "four", "five")) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 12f)
                    content.newLineAtOffset(72f, 700f)
                    content.showText("Page " + name)
                    content.endText()
                }
            }
            document.save(out)
        }
        return out.toByteArray()
    }
}
