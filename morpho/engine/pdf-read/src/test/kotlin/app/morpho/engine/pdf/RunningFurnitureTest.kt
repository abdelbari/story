package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.RunField
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A page with no tags says nothing about which of its lines are the
 * page's own furniture. Read as text, a running head arrives in the
 * middle of the reading once per page; dropped, it is gone from the
 * converted file and a paper that numbered its pages 48, 49, 50 comes
 * back numbering nothing.
 */
class RunningFurnitureTest {

    private val margin = 72f
    private val measure = 451f

    private fun wrap(text: String, font: PDFont, size: Float, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.getStringWidth(candidate) / 1000f * size > width && line.isNotEmpty()) {
                lines += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    /** Five pages of prose, each under a running head and over its number. */
    private fun paper(head: String?, firstNumber: Int?): ByteArray {
        PDDocument().use { doc ->
            for (page in 0 until 5) {
                val sheet = PDPage(PDRectangle.A4)
                doc.addPage(sheet)
                PDPageContentStream(doc, sheet).use { content ->
                    fun show(font: PDFont, size: Float, x: Float, y: Float, text: String) {
                        content.beginText()
                        content.setFont(font, size)
                        content.newLineAtOffset(x, y)
                        content.showText(text)
                        content.endText()
                    }
                    if (head != null) show(PDType1Font.HELVETICA_OBLIQUE, 9f, margin, 800f, head)
                    var y = 740f
                    for (piece in 1..4) {
                        val text = "Paragraph $piece of page ${page + 1}. It runs on for a sentence " +
                            "or two so that the line fills the measure it is set in and breaks where " +
                            "the words stop, as prose does on a printed page."
                        for (line in wrap(text, PDType1Font.HELVETICA, 11f, measure)) {
                            show(PDType1Font.HELVETICA, 11f, margin, y, line)
                            y -= 14f
                        }
                        y -= 10f
                    }
                    if (firstNumber != null) {
                        show(PDType1Font.HELVETICA, 9f, 290f, 50f, (firstNumber + page).toString())
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `a running head is the page's own, and leaves the text`() {
        val model = PdfReader().extract(paper("The Journal of Something, Volume 4", 48))
        assertEquals(
            "The Journal of Something, Volume 4",
            (model.header.single() as Paragraph).text.trim(),
        )
        assertTrue(
            model.blocks.filterIsInstance<Paragraph>().none { it.text.contains("Journal of Something") },
            "the head was left in the middle of the reading",
        )
        assertEquals(20, model.blocks.size, "every paragraph of every page is still there")
    }

    @Test
    fun `the number that counts the pages is a field, and the document starts where the paper does`() {
        val model = PdfReader().extract(paper("The Journal of Something, Volume 4", 48))
        val foot = model.footer.single() as Paragraph
        assertEquals(listOf(RunField.PAGE_NUMBER), foot.runs.mapNotNull { it.field })
        assertEquals("48", foot.runs.first { it.field != null }.text)
        assertEquals(48, model.pageSetup?.firstPageNumber)
    }

    @Test
    fun `a paper with nothing in its margins keeps its text and gains no furniture`() {
        val model = PdfReader().extract(paper(head = null, firstNumber = null))
        assertTrue(model.header.isEmpty() && model.footer.isEmpty())
        assertEquals(20, model.blocks.size)
    }
}
