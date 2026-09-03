package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.color.PDColor
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A marked-up PDF is the one people most want kept: the highlighting is
 * the reader's own reading of the document, and every converter throws it
 * away. A marking is an annotation with a colour and the quadrilaterals
 * it covers, joined to the words underneath by geometry.
 *
 * There are three kinds and only the first was kept: a highlight painted
 * over the words, a line drawn under them, and a line struck through
 * them — the last two being the ones that change what the document says.
 */
class HighlightTest {

    @Test
    fun `words a reader marked come back marked`() {
        val model = PdfReader().extract(markedPdf())
        val runs = model.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        val marked = runs.filter { it.highlightRgb != null }
        assertTrue(marked.isNotEmpty(), "nothing came back marked")
        assertTrue(
            marked.any { it.text.contains("important") },
            "the marked words were: " + marked.map { it.text },
        )
        assertEquals(0xFFFF00, marked.first().highlightRgb)
    }

    @Test
    fun `words beside the marking are left alone`() {
        val model = PdfReader().extract(markedPdf())
        val runs = model.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        val plain = runs.filter { it.highlightRgb == null }
        assertTrue(
            plain.any { it.text.contains("ordinary") },
            "the unmarked words were: " + plain.map { it.text },
        )
    }

    @Test
    fun `a document nobody marked has nothing marked`() {
        val model = PdfReader().extract(markedPdf(marked = false))
        val runs = model.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        assertNull(runs.firstOrNull { it.highlightRgb != null })
    }

    @Test
    fun `words a reader underlined come back underlined`() {
        val runs = runsOf(markedPdf(subtype = PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE))
        val marked = runs.filter { it.underline }
        assertTrue(
            marked.any { it.text.contains("important") },
            "the underlined words were: " + marked.map { it.text },
        )
        assertTrue(runs.none { it.strikethrough }, "an underline was read as a strike")
        assertTrue(
            runs.filter { it.text.contains("ordinary") }.none { it.underline },
            "the words beside the marking were underlined too",
        )
    }

    @Test
    fun `words a reader struck out come back struck out`() {
        val runs = runsOf(markedPdf(subtype = PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT))
        val marked = runs.filter { it.strikethrough }
        assertTrue(
            marked.any { it.text.contains("important") },
            "the struck words were: " + marked.map { it.text },
        )
        assertTrue(runs.none { it.underline }, "a strike was read as an underline")
    }

    @Test
    fun `a wavy line under the words is a line under the words`() {
        val runs = runsOf(markedPdf(subtype = PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY))
        assertTrue(runs.filter { it.underline }.any { it.text.contains("important") })
    }

    @Test
    fun `a marking with no colour of its own is a marking still`() {
        // A highlight has to be some colour to be seen; a line drawn under
        // the words is drawn in whatever colour the reader was using, and
        // a file that does not say which has still marked the words.
        val runs = runsOf(
            markedPdf(subtype = PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE, coloured = false)
        )
        assertTrue(runs.filter { it.underline }.any { it.text.contains("important") })
    }

    @Test
    fun `a document nobody marked has nothing underlined or struck`() {
        val runs = runsOf(markedPdf(marked = false))
        assertTrue(runs.none { it.underline || it.strikethrough })
    }

    private fun runsOf(pdf: ByteArray) =
        PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>().flatMap { it.runs }

    /**
     * One line of text with the middle words marked in yellow, drawn the
     * way a PDF reader writes a highlight: an annotation with the quads it
     * covers, over text that knows nothing about it.
     */
    private fun markedPdf(
        marked: Boolean = true,
        subtype: String = PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT,
        coloured: Boolean = true,
    ): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText("ordinary words")
                content.endText()
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("important words")
                content.endText()
            }
            if (marked) {
                val highlight = PDAnnotationTextMarkup(subtype)
                if (coloured) highlight.color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                highlight.rectangle = PDRectangle(70f, 696f, 200f, 16f)
                // Upper-left, upper-right, lower-left, lower-right, as a
                // reader's highlight is written.
                highlight.quadPoints = floatArrayOf(
                    70f, 712f, 270f, 712f, 70f, 696f, 270f, 696f,
                )
                page.annotations.add(highlight)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }
}
