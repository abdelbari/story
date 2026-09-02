package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
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
    fun `a link annotation says where its words point, whatever they say`() {
        // The one thing text alone cannot give: "click here" leads
        // somewhere, and only the annotation over it knows where.
        val pdf = linkedPdf()
        val runs = PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        val linked = runs.filter { it.link != null }
        assertTrue(linked.isNotEmpty(), "no link was read: $runs")
        assertEquals("https://example.org/the-page", linked.first().link)
        assertTrue(linked.joinToString("") { it.text }.contains("click here"), linked.toString())
        // The words outside the rectangle are not part of it.
        assertTrue(runs.any { it.link == null && it.text.contains("Plain") }, runs.toString())
    }

    /** An untagged page with "click here" under a link annotation, and plain text beside it. */
    private fun linkedPdf(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("click here")
                content.endText()
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(300f, 700f)
                content.showText("Plain words")
                content.endText()
            }
            val link = PDAnnotationLink()
            link.rectangle = PDRectangle(70f, 694f, 60f, 18f)
            link.action = PDActionURI().apply { uri = "https://example.org/the-page" }
            page.annotations.add(link)
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `a page with no tags still gives up the colours it paints in`() {
        // The untagged reader is a text engine of its own, and a text
        // engine is not given the colour operators unless it asks for
        // them: without them a red heading in a scanned-then-typeset
        // paper reads as black, like everything else.
        val pdf = colouredPdf()
        val runs = PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        val heading = runs.first { it.text.contains("Heading") }
        assertEquals(0xB22222, heading.colorRgb, "the heading's red")
        val body = runs.first { it.text.contains("body") }
        assertNull(body.colorRgb, "black is the colour a page paints with")
    }

    /** An untagged page whose heading is painted red and whose body is black. */
    private fun colouredPdf(): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.setNonStrokingColor(Color(178, 34, 34))
                content.beginText()
                content.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                content.newLineAtOffset(72f, 720f)
                content.showText("A Heading In Red")
                content.endText()
                content.setNonStrokingColor(Color.BLACK)
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 690f)
                content.showText("Plain body text underneath it.")
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
