package app.morpho.engine.pdf

import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.io.ByteArrayOutputStream

/**
 * A running head drawn rather than written.
 *
 * Some pages carry a head no reader can read: a banner of shapes, or
 * words in a font whose file names none of its characters, which PDFBox
 * drops before a stripper is ever shown them. There is no line in the
 * margin to find and nothing else in the file that says the head is
 * there — and a converter working from lines alone loses it without ever
 * knowing it existed.
 *
 * What settles it is that a running head is the same drawing on every
 * page, so the margin is photographed on two pages and compared.
 */
class ArtworkHeadTest {

    /** Five pages of prose, each under the same drawn device. */
    private fun paper(headOnEveryPage: Boolean): ByteArray {
        PDDocument().use { doc ->
            for (page in 0 until 5) {
                val sheet = PDPage(PDRectangle.A4)
                doc.addPage(sheet)
                PDPageContentStream(doc, sheet).use { content ->
                    if (headOnEveryPage || page == 2) {
                        // A device of three filled discs, well above the
                        // text: not a rule, not a line, not a picture the
                        // file holds — just marks on the page.
                        content.setNonStrokingColor(Color(120, 20, 20))
                        for (disc in 0 until 3) {
                            val x = 72f + disc * 40f
                            val y = PDRectangle.A4.height - 44f
                            circle(content, x, y, 9f)
                        }
                    }
                    var y = 700f
                    for (piece in 1..6) {
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 11f)
                        content.newLineAtOffset(72f, y)
                        content.showText("Paragraph $piece of page ${page + 1}, set in the measure of the page.")
                        content.endText()
                        y -= 30f
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /** A filled disc, drawn as four bezier arcs. */
    private fun circle(content: PDPageContentStream, cx: Float, cy: Float, r: Float) {
        val k = 0.5523f * r
        content.moveTo(cx - r, cy)
        content.curveTo(cx - r, cy + k, cx - k, cy + r, cx, cy + r)
        content.curveTo(cx + k, cy + r, cx + r, cy + k, cx + r, cy)
        content.curveTo(cx + r, cy - k, cx + k, cy - r, cx, cy - r)
        content.curveTo(cx - k, cy - r, cx - r, cy - k, cx - r, cy)
        content.fill()
    }

    @Test
    fun `a head the page draws and no reader can read is still the head`() {
        val model = PdfReader().extract(paper(headOnEveryPage = true))
        assertTrue(model.header.isNotEmpty(), "the margin draws something on page after page")
        assertTrue(
            model.header.single() is ImageBlock,
            "and the page itself is the only honest account of what it says",
        )
        val distance = model.pageSetup?.headerDistancePt
        assertTrue(
            distance != null && distance > 10f && distance < 60f,
            "measured to where the ink actually sits: $distance",
        )
    }

    @Test
    fun `a device drawn on one page only is not a running head`() {
        val model = PdfReader().extract(paper(headOnEveryPage = false))
        assertTrue(model.header.isEmpty(), "furniture is what repeats; one page's device is not")
    }

    @Test
    fun `finding a drawn head takes nothing away from the text`() {
        val withHead = PdfReader().extract(paper(headOnEveryPage = true))
        val without = PdfReader().extract(paper(headOnEveryPage = false))
        assertEquals(
            without.blocks.filterIsInstance<Paragraph>().map { it.text },
            withHead.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }
}
