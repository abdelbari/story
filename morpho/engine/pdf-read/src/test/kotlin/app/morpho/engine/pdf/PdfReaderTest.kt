package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PdfReaderTest {

    private fun samplePdf(vararg lines: String): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.setLeading(18f)
                content.newLineAtOffset(72f, 720f)
                for ((index, line) in lines.withIndex()) {
                    if (index > 0) content.newLine()
                    content.showText(line)
                }
                content.endText()
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `inspect reports page count and untagged status`() {
        val pdf = samplePdf("Hello Morpho engine")
        val info = PdfReader().inspect(pdf)
        assertEquals(1, info.pageCount)
        assertFalse(info.isTagged, "a bare generated PDF has no structure tree")
    }

    @Test
    fun `extract recovers the text`() {
        val pdf = samplePdf("Hello Morpho engine")
        val model = PdfReader().extract(pdf)
        val text = model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        assertTrue(text.contains("Hello Morpho engine"), "extracted: $text")
    }

    @Test
    fun `untagged extraction is marked with reduced confidence`() {
        val pdf = samplePdf("Hello Morpho engine")
        val model = PdfReader().extract(pdf)
        val paragraph = model.blocks.filterIsInstance<Paragraph>().first()
        assertEquals(0.6f, paragraph.confidence)
    }
}
