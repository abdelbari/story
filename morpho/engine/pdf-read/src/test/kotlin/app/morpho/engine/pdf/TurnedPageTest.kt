package app.morpho.engine.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * A wide table or a plan is often written on a portrait page and turned a
 * quarter turn to be read. The words are measured in the frame they are
 * read in, so the sheet has to be the one the reader sees: a page read
 * landscape that comes back portrait sets every line to the wrong width.
 */
class TurnedPageTest {

    @Test
    fun `a page turned to be read landscape is measured landscape`() {
        val setup = PdfReader().extract(turnedPdf(rotation = 90)).pageSetup
        assertTrue(setup != null, "the page was not measured at all")
        assertTrue(
            setup!!.widthPt > setup.heightPt,
            "a page read landscape came back " + setup.widthPt.roundToInt() + " by " + setup.heightPt.roundToInt(),
        )
    }

    @Test
    fun `a page nobody turned is measured as it was written`() {
        val setup = PdfReader().extract(turnedPdf(rotation = 0)).pageSetup
        assertTrue(setup != null && setup.heightPt > setup.widthPt, "the page came back turned")
    }

    @Test
    fun `a page turned twice is still upright`() {
        val setup = PdfReader().extract(turnedPdf(rotation = 180)).pageSetup
        assertTrue(setup != null && setup.heightPt > setup.widthPt, "a half turn turned the sheet")
    }

    @Test
    fun `the words on a turned page are read all the same`() {
        val model = PdfReader().extract(turnedPdf(rotation = 90))
        val text = model.blocks.filterIsInstance<app.morpho.engine.layout.Paragraph>()
            .joinToString(" ") { it.text }
        assertEquals("Read this sideways", text.trim())
    }

    private fun turnedPdf(rotation: Int): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            page.rotation = rotation
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("Read this sideways")
                content.endText()
            }
            document.save(out)
        }
        return out.toByteArray()
    }
}
