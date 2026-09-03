package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
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
 * A paragraph does not end because a page did.
 *
 * Every page of a book but the last ends in the middle of a paragraph.
 * Breaking there gave a converted document a broken sentence at every
 * page turn — hundreds of them in a book, each missing the space or the
 * hyphen that joined its two halves — and the reader had no way to put
 * them back, since by the time anyone saw the Word file they were two
 * paragraphs like any other two.
 *
 * What can be asked across a page is what a line looks like and where it
 * stops, not how far below the line before it it sits. So the question
 * is asked of a page that filled up, of a line that ran to its margin,
 * and of a line under it that begins the way a paragraph's middle does.
 */
class AcrossPagesTest {

    private val left = 72f
    private val step = 14f

    /** A line of the body: all of them the same width, so none of them ends short. */
    private fun body(number: Int) =
        "Line %02d of a paragraph that runs on and on across the page and does not stop".format(number)

    /**
     * A document of pages, each a list of lines given as text, the indent
     * they start at, and the size they are set in.
     */
    private fun document(pages: List<List<Triple<String, Float, Float>>>): ByteArray {
        PDDocument().use { doc ->
            for (lines in pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    var y = 770f
                    for ((text, indent, size) in lines) {
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, size)
                        content.newLineAtOffset(left + indent, y)
                        content.showText(text)
                        content.endText()
                        y -= step
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /**
     * A page whose text runs from its head to its foot — one the page
     * itself stopped. Fifty lines at this step leave as little below the
     * last as there is above the first, which is what a full page looks
     * like.
     */
    private fun full(from: Int, count: Int = 50) =
        (from until from + count).map { Triple(body(it), 0f, 11f) }

    private fun paragraphs(pages: List<List<Triple<String, Float, Float>>>): List<Paragraph> =
        PdfReader().extract(document(pages)).blocks.filterIsInstance<Paragraph>()

    @Test
    fun `a paragraph carries on over the page it filled`() {
        val read = paragraphs(listOf(full(1), full(51, count = 20)))
        assertEquals(1, read.size, read.map { it.text.take(40) }.toString())
        assertTrue(read.single().text.contains("Line 50 of a paragraph"), "the foot of the first page")
        assertTrue(read.single().text.contains("Line 51 of a paragraph"), "the head of the second")
    }

    @Test
    fun `a page that stopped short of its foot ends what stood on it`() {
        // A chapter's last page, a page ended on purpose: the writing
        // stopped, not the page.
        val read = paragraphs(listOf(full(1, count = 6), full(51, count = 20)))
        assertEquals(2, read.size, read.map { it.text.take(40) }.toString())
    }

    @Test
    fun `a page opening with an indented line opens a paragraph`() {
        val second = listOf(Triple(body(51), 24f, 11f)) + full(52, count = 19)
        val read = paragraphs(listOf(full(1), second))
        assertEquals(2, read.size, read.map { it.text.take(40) }.toString())
    }

    @Test
    fun `a page opening with a heading opens a paragraph`() {
        val second = listOf(Triple("A chapter of its own", 0f, 20f)) + full(52, count = 19)
        val read = paragraphs(listOf(full(1), second))
        assertTrue(read.size >= 2, read.map { it.text.take(40) }.toString())
        assertEquals("A chapter of its own", read[1].text)
        assertTrue(read[1].style.kind != ParagraphKind.BODY, "set larger, so a heading")
    }

    @Test
    fun `a page opening with a list item opens an item`() {
        val second = listOf(Triple("• An item of a list, opening the page", 0f, 11f)) +
            full(52, count = 19)
        val read = paragraphs(listOf(full(1), second))
        assertTrue(read.size >= 2, read.map { it.text.take(40) }.toString())
        assertTrue(read[1].text.startsWith("• An item"), read[1].text)
    }
}
