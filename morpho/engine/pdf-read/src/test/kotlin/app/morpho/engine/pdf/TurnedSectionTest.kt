package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A report of portrait pages turns one page sideways for a wide table,
 * and a document has one shape only in the sense that most of it does.
 * Measured by the shape most of it has — which is right for the document
 * as a whole — the wide page came back upright, and every line of it was
 * set to the wrong width.
 */
class TurnedSectionTest {

    /** Pages of the given shapes, each with a line of its own to be found by. */
    private fun report(shapes: List<PDRectangle>): ByteArray {
        PDDocument().use { doc ->
            for ((index, shape) in shapes.withIndex()) {
                val page = PDPage(shape)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    var y = shape.height - 80f
                    for (line in 1..6) {
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 11f)
                        content.newLineAtOffset(72f, y)
                        content.showText("Page ${index + 1}, line $line of the report it belongs to.")
                        content.endText()
                        y -= 16f
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private val landscape = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)

    @Test
    fun `the page a report turns sideways starts a section of its own`() {
        val model = PdfReader().extract(
            report(listOf(PDRectangle.A4, PDRectangle.A4, landscape, PDRectangle.A4, PDRectangle.A4))
        )
        val paragraphs = model.blocks.filterIsInstance<Paragraph>()
        // The document as a whole keeps the shape most of it has.
        assertTrue(model.pageSetup!!.widthPt < model.pageSetup!!.heightPt, "the report is portrait")

        val turned = paragraphs.first { it.text.contains("Page 3") }
        val turnedSetup = turned.style.sectionSetup
        assertNotNull(turnedSetup, "the turned page said nothing about its shape")
        assertTrue(turnedSetup!!.widthPt > turnedSetup.heightPt, "the turned page is wide")

        // And the page after it turns back.
        val back = paragraphs.first { it.text.contains("Page 4") }
        assertNotNull(back.style.sectionSetup, "the page after it said nothing about turning back")
        assertTrue(back.style.sectionSetup!!.widthPt < back.style.sectionSetup!!.heightPt)
    }

    @Test
    fun `a report of one shape says nothing about sections`() {
        val model = PdfReader().extract(report(List(4) { PDRectangle.A4 }))
        assertTrue(
            model.blocks.filterIsInstance<Paragraph>().all { it.style.sectionSetup == null },
            "a document of one shape has one section",
        )
    }

    @Test
    fun `a document that opens on a page of another shape says so`() {
        // A cover, a wide table at the front: the document is measured at
        // the shape most of its pages have, so the page it opens on is a
        // section of its own or it is written at a shape it never had.
        val model = PdfReader().extract(report(listOf(landscape, PDRectangle.A4, PDRectangle.A4, PDRectangle.A4)))
        val paragraphs = model.blocks.filterIsInstance<Paragraph>()
        assertTrue(model.pageSetup!!.widthPt < model.pageSetup!!.heightPt, "the report is portrait")
        val opening = paragraphs.first().style.sectionSetup
        assertNotNull(opening, "the page it opens on said nothing about its shape")
        assertTrue(opening!!.widthPt > opening.heightPt, "the page it opens on is wide")
        // And the page after it turns to the shape the rest of it has.
        val second = paragraphs.first { it.text.contains("Page 2") }.style.sectionSetup
        assertNotNull(second)
        assertTrue(second!!.widthPt < second.heightPt)
    }

    @Test
    fun `a document that opens on its own shape says nothing at its start`() {
        val model = PdfReader().extract(report(listOf(PDRectangle.A4, PDRectangle.A4, landscape, PDRectangle.A4)))
        assertNull(model.blocks.filterIsInstance<Paragraph>().first().style.sectionSetup)
    }
}
