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

class PdfLayoutTest {

    private class Line(val text: String, val y: Float, val size: Float)

    /** Renders absolutely-positioned lines (PDF user space, y from the bottom). */
    private fun pdfOf(vararg lines: Line): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                for (line in lines) {
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, line.size)
                    content.newLineAtOffset(72f, line.y)
                    content.showText(line.text)
                    content.endText()
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun paragraphs(pdf: ByteArray): List<Paragraph> =
        PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>()

    @Test
    fun `a large vertical gap splits paragraphs and soft wraps are unwrapped`() {
        val pdf = pdfOf(
            Line("First paragraph starts here", 700f, 12f),
            Line("and wraps to a second line.", 685f, 12f),
            Line("Second paragraph after a gap", 600f, 12f),
            Line("also wraps once more here.", 585f, 12f),
        )
        val paras = paragraphs(pdf)
        assertEquals(2, paras.size, "paragraphs: ${paras.map { it.text }}")
        assertEquals("First paragraph starts here and wraps to a second line.", paras[0].text)
        assertEquals("Second paragraph after a gap also wraps once more here.", paras[1].text)
        assertTrue(paras.all { it.style.kind == ParagraphKind.BODY })
    }

    @Test
    fun `an oversized short line becomes a heading and body stays body`() {
        val pdf = pdfOf(
            Line("Quarterly Report", 720f, 18f),
            Line("The body text of the report begins with", 690f, 12f),
            Line("an ordinary paragraph set in twelve point.", 675f, 12f),
            Line("It continues with more ordinary lines so", 660f, 12f),
            Line("the median body size is clearly twelve.", 645f, 12f),
        )
        val paras = paragraphs(pdf)
        assertEquals(ParagraphKind.HEADING_1, paras[0].style.kind, "blocks: ${paras.map { it.style.kind }}")
        assertEquals("Quarterly Report", paras[0].text)
        assertTrue(paras.drop(1).all { it.style.kind == ParagraphKind.BODY })
    }

    @Test
    fun `two heading sizes rank into heading levels`() {
        val pdf = pdfOf(
            Line("Main Title", 740f, 20f),
            Line("Section One", 700f, 16f),
            Line("Body line one in the usual size here", 670f, 12f),
            Line("body line two in the usual size here", 655f, 12f),
            Line("body line three in the usual size too", 640f, 12f),
            Line("body line four keeps the median at twelve", 625f, 12f),
        )
        val paras = paragraphs(pdf)
        assertEquals(ParagraphKind.HEADING_1, paras[0].style.kind)
        assertEquals(ParagraphKind.HEADING_2, paras[1].style.kind)
    }

    @Test
    fun `a uniform-size document has no headings`() {
        val pdf = pdfOf(
            Line("Everything in this document", 700f, 12f),
            Line("is set in exactly one size,", 685f, 12f),
            Line("so nothing may become a heading.", 670f, 12f),
        )
        assertTrue(paragraphs(pdf).all { it.style.kind == ParagraphKind.BODY })
    }

    @Test
    fun `untagged position-aware extraction keeps the reduced confidence`() {
        val pdf = pdfOf(Line("Confidence check line", 700f, 12f))
        val paras = paragraphs(pdf)
        assertTrue(paras.isNotEmpty())
        assertTrue(paras.all { it.confidence == 0.6f })
    }
}
