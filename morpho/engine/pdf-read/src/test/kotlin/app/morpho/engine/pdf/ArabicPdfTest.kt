package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextDirection
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Arabic through the untagged reader, end to end.
 *
 * Every other test in this module is Latin, and that is exactly how a
 * reversal bug reached a user: the logic had unit tests, but nothing built
 * an Arabic PDF and read it back, so nobody noticed the words were being
 * assembled left to right. These build one the way a real right-to-left
 * producer does — words placed right to left across the page, glyphs inside
 * each word painted left to right — and assert the logical text comes back.
 */
class ArabicPdfTest {

    private val title = "الاستمارة"
    private val inWord = "في"
    private val research = "البحث"

    @Test
    fun `a right-to-left line comes back in the order it was written`() {
        val pdf = rtlPdf(listOf(listOf(title, inWord, research)))
        val text = paragraphText(pdf)
        assertEquals("$title $inWord $research", text)
    }

    @Test
    fun `the paragraph is marked right-to-left`() {
        val pdf = rtlPdf(listOf(listOf(title, inWord, research)))
        val paragraph = paragraphs(pdf).first()
        assertEquals(TextDirection.RTL, paragraph.style.direction)
    }

    @Test
    fun `a latin word inside an arabic line keeps its own direction`() {
        // Painted between two Arabic words but left to right itself, the way
        // a citation or an address sits inside an Arabic sentence.
        val pdf = rtlPdf(listOf(listOf(title, "Morpho", research)))
        val text = paragraphText(pdf)
        assertTrue(text.contains("Morpho"), "latin run lost: $text")
        assertEquals("$title Morpho $research", text)
    }

    @Test
    fun `arabic survives with no reversed word left in the output`() {
        val pdf = rtlPdf(listOf(listOf(title, inWord, research)))
        val text = paragraphText(pdf)
        assertTrue(
            !text.contains(title.reversed()) && !text.contains(research.reversed()),
            "text still holds a reversed word: $text",
        )
    }

    private fun paragraphs(pdf: ByteArray) =
        PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>()

    private fun paragraphText(pdf: ByteArray) =
        paragraphs(pdf).joinToString(separator = " ") { it.text }.trim()

    /**
     * An untagged PDF whose content stream is in painting order: each line's
     * words run right to left, and each word's glyphs are painted left to
     * right, which for right-to-left script means reversed.
     */
    private fun rtlPdf(lines: List<List<String>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val font = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")
                    ?: error("test font missing"),
            )
            PDPageContentStream(document, page).use { content ->
                var y = 700f
                for (words in lines) {
                    var x = RIGHT_EDGE
                    for (word in words) {
                        // Latin runs are painted in their own order; Arabic
                        // is painted in the order the glyphs appear on paper.
                        val painted = if (isRtl(word)) word.reversed() else word
                        val width = font.getStringWidth(painted) / 1000f * SIZE
                        x -= width
                        content.beginText()
                        content.setFont(font, SIZE)
                        content.newLineAtOffset(x, y)
                        content.showText(painted)
                        content.endText()
                        x -= WORD_GAP
                    }
                    y -= LINE_GAP
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }

    /** Hebrew through Arabic Extended-A, enough for what these tests draw. */
    private fun isRtl(word: String) = word.any { it in '\u0590'..'\u08FF' }

    private companion object {
        const val RIGHT_EDGE = 500f
        const val SIZE = 14f
        const val WORD_GAP = 6f
        const val LINE_GAP = 30f
    }
}
