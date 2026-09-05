package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.RunField
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
 * A book, set the way books are set.
 *
 * The right-hand page carries the title of the chapter and the left the
 * title of the book, and each numbers itself at its outer edge. Both
 * repeat, so both are the page's furniture and both leave the text — and
 * a reader that keeps one page's worth of it keeps one of the two and
 * loses the other outright, then prints the survivor on every page of the
 * converted book.
 */
class MirroredHeadTest {

    private val book = "A History of the Sciences"
    private val chapter = "Chapter Three: Instruments"

    /** Six pages, the odd ones headed by the chapter and the even by the book. */
    private fun opening(mirrored: Boolean): ByteArray {
        PDDocument().use { doc ->
            for (page in 0 until 6) {
                val sheet = PDPage(PDRectangle.A4)
                doc.addPage(sheet)
                val onTheRight = page % 2 == 0
                PDPageContentStream(doc, sheet).use { content ->
                    fun show(size: Float, x: Float, y: Float, text: String, font: PDType1Font = PDType1Font.HELVETICA) {
                        content.beginText()
                        content.setFont(font, size)
                        content.newLineAtOffset(x, y)
                        content.showText(text)
                        content.endText()
                    }
                    // Set for both sides of an opening, or for one side
                    // used throughout: what it says and where it sits both
                    // belong to the side, and a book alternates both.
                    val head = if (mirrored && !onTheRight) book else chapter
                    val headX = if (mirrored && !onTheRight) 72f else 300f
                    show(9f, headX, 800f, head, PDType1Font.HELVETICA_OBLIQUE)
                    var y = 740f
                    for (piece in 1..5) {
                        show(11f, 72f, y, "Paragraph $piece of page ${page + 1}, set in the measure of the page.")
                        y -= 28f
                    }
                    show(9f, if (mirrored && !onTheRight) 72f else 500f, 50f, (page + 1).toString())
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `both sides of an opening keep their own head`() {
        val model = PdfReader().extract(opening(mirrored = true))
        assertEquals(chapter, (model.header.single() as Paragraph).text.trim(), "the right-hand pages'")
        assertEquals(book, (model.evenHeader.single() as Paragraph).text.trim(), "and the left-hand pages'")
    }

    @Test
    fun `neither head is left in the middle of the reading`() {
        val model = PdfReader().extract(opening(mirrored = true))
        val text = model.blocks.filterIsInstance<Paragraph>().joinToString("\n") { it.text }
        assertTrue(!text.contains("History of the Sciences"), "the book's title stayed in the text")
        assertTrue(!text.contains("Chapter Three"), "the chapter's title stayed in the text")
    }

    @Test
    fun `a book still numbers its own pages`() {
        val model = PdfReader().extract(opening(mirrored = true))
        val fields = (model.footer + model.evenFooter)
            .filterIsInstance<Paragraph>()
            .flatMap { it.runs }
            .mapNotNull { it.field }
        assertEquals(listOf(RunField.PAGE_NUMBER, RunField.PAGE_NUMBER), fields)
    }

    @Test
    fun `a document whose pages all read alike keeps one head, not two`() {
        val model = PdfReader().extract(opening(mirrored = false))
        assertEquals(chapter, (model.header.single() as Paragraph).text.trim())
        assertTrue(model.evenHeader.isEmpty(), "there is nothing different about the left-hand pages")
    }

    @Test
    fun `a foot that reads alike but sits at each outer edge is two feet`() {
        // A book numbers its pages at the outer edge. The two feet read
        // the same — a number, and every number reads as a number — and
        // the side is the whole of the difference between them.
        val model = PdfReader().extract(opening(mirrored = true))
        assertTrue(model.evenFooter.isNotEmpty(), "the left-hand pages number themselves on the left")
        assertTrue(model.footer.isNotEmpty(), "and the right-hand pages on the right")
    }
}
