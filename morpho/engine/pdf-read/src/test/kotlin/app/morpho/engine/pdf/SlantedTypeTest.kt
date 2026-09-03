package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.util.Matrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Italics a page fakes by leaning its type.
 *
 * A producer that has the italic cut of a typeface switches to it and
 * names it, and the name was the only evidence either reader looked for.
 * A producer that has none — Word, for every Arabic typeface it ships —
 * skews the matrix it draws with instead and goes on naming the upright
 * font, and every italic word of such a document was converted plain: a
 * paper's book titles, a thesis's Latin terms, a report's emphasis.
 */
class SlantedTypeTest {

    /** One line drawn upright and one drawn with a lean, in the same font. */
    private fun page(lean: Float): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.setTextMatrix(Matrix(1f, 0f, 0f, 1f, 72f, 700f))
                content.showText("Upright words here")
                content.endText()
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.setTextMatrix(Matrix(1f, 0f, lean, 1f, 72f, 680f))
                content.showText("Leaning words here")
                content.endText()
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun paragraphs(bytes: ByteArray) =
        PdfReader().extract(bytes).blocks.filterIsInstance<Paragraph>()

    /** What of a page's text came back leaning, joined up. */
    private fun leaning(lean: Float): String =
        paragraphs(page(lean)).flatMap { it.runs }.filter { it.italic }.joinToString("") { it.text }.trim()

    @Test
    fun `a line drawn leaning comes back italic`() {
        // A third is the shear Word writes: 4.32 against a type size of
        // 12.96, which is what the paper this was built for is drawn with.
        val read = paragraphs(page(1f / 3f))
        assertEquals("Upright words here Leaning words here", read.single().text)
        assertEquals("Leaning words here", leaning(1f / 3f), "the leaning line came back upright")
    }

    @Test
    fun `text drawn upright stays upright`() {
        assertEquals("", leaning(0f), "upright text came back italic")
    }

    @Test
    fun `a lean too slight to be meant is not read as one`() {
        // Two degrees is a producer's rounding, not emphasis.
        assertEquals("", leaning(Math.tan(Math.toRadians(2.0)).toFloat()))
    }

    @Test
    fun `a leaning word inside an upright line is its own run`() {
        val bytes = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                var x = 72f
                for ((word, lean) in listOf("Read" to 0f, "Middlemarch" to 1f / 3f, "twice" to 0f)) {
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 12f)
                    content.setTextMatrix(Matrix(1f, 0f, lean, 1f, x, 700f))
                    content.showText("$word ")
                    content.endText()
                    x += PDType1Font.HELVETICA.getStringWidth("$word ") / 1000f * 12f
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }
        val line = paragraphs(bytes).single()
        assertEquals("Read Middlemarch twice", line.text)
        assertEquals(
            "Middlemarch",
            line.runs.filter { it.italic }.joinToString("") { it.text }.trim(),
            "the title in the middle of the line did not come back as the italic it is",
        )
    }

    @Test
    fun `a word the page thickened by stroking round it comes back bold`() {
        // A producer with no bold cut of the typeface draws each letter
        // and then strokes round it. The font it names is the light one,
        // exactly as with a faked italic.
        val bytes = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                var x = 72f
                for ((word, heavy) in listOf("A" to false, "thickened" to true, "word" to false)) {
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 12f)
                    content.setRenderingMode(
                        if (heavy) RenderingMode.FILL_STROKE else RenderingMode.FILL,
                    )
                    content.setLineWidth(if (heavy) 0.4f else 0f)
                    content.newLineAtOffset(x, 700f)
                    content.showText("$word ")
                    content.endText()
                    x += PDType1Font.HELVETICA.getStringWidth("$word ") / 1000f * 12f
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }
        val line = paragraphs(bytes).single()
        assertEquals("A thickened word", line.text)
        assertEquals(
            "thickened",
            line.runs.filter { it.bold }.joinToString("") { it.text }.trim(),
            "the word the page stroked round did not come back bold",
        )
        assertEquals("", line.runs.filter { it.italic }.joinToString("") { it.text })
    }
}
