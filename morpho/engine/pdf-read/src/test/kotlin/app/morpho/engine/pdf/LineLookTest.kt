package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.io.ByteArrayOutputStream

/**
 * A paragraph is written line by line and read back as one, and the runs
 * measured off the page have to be walked in step with the joined text.
 * The joiner puts a space between two lines and drops the hyphen of a
 * word broken across them, so the two sequences do not agree character
 * for character — and a walk that gave up at the first disagreement gave
 * every line after the first the look of the line before it.
 *
 * A paper's emphasised term on its second line came back plain, a term
 * set in the journal's red came back black, and a link on any line but
 * the first led wherever the first one led.
 */
class LineLookTest {

    private class Piece(val text: String, val font: PDFont = PDType1Font.HELVETICA, val color: Color = Color.BLACK)

    /** Each line drawn piece by piece, every piece placed where the one before it ended. */
    private fun page(lines: List<List<Piece>>): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                var y = 700f
                for (line in lines) {
                    var x = 72f
                    for (piece in line) {
                        content.beginText()
                        content.setFont(piece.font, 12f)
                        content.setNonStrokingColor(piece.color)
                        content.newLineAtOffset(x, y)
                        content.showText(piece.text)
                        content.endText()
                        x += piece.font.getStringWidth(piece.text) / 1000f * 12f
                    }
                    y -= 14f
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun read(lines: List<List<Piece>>): Paragraph {
        val model = PdfReader().extract(page(lines))
        return model.blocks.filterIsInstance<Paragraph>().maxBy { it.text.length }
    }

    @Test
    fun `an emphasised word on the second line of a paragraph is still emphasised`() {
        val paragraph = read(
            listOf(
                listOf(Piece("The first line of a paragraph that runs on and on to ")),
                listOf(
                    Piece("a second line where the word "),
                    Piece("emphasis", PDType1Font.HELVETICA_BOLD),
                    Piece(" is set in bold, and then more of it."),
                ),
            )
        )
        assertTrue(paragraph.text.contains("The first line"), paragraph.text)
        assertTrue(paragraph.text.contains("more of it"), "the lines must be read as one paragraph")
        val bold = paragraph.runs.filter { it.bold }.joinToString("") { it.text }.trim()
        assertEquals("emphasis", bold, paragraph.runs.map { it.bold to it.text }.toString())
    }

    @Test
    fun `a word set in colour on the second line keeps its colour`() {
        val paragraph = read(
            listOf(
                listOf(Piece("The first line of a paragraph that runs on and on to ")),
                listOf(
                    Piece("a second line where the word "),
                    Piece("marked", color = Color.RED),
                    Piece(" is set in red, and then more of it."),
                ),
            )
        )
        val red = paragraph.runs.filter { it.colorRgb == 0xFF0000 }.joinToString("") { it.text }.trim()
        assertEquals("marked", red, paragraph.runs.map { it.colorRgb to it.text }.toString())
    }

    @Test
    fun `keeping step neither drops a word nor says one twice`() {
        val paragraph = read(
            listOf(
                listOf(Piece("One two three four five six seven eight nine ten")),
                listOf(Piece("eleven twelve thirteen fourteen fifteen sixteen.")),
            )
        )
        assertEquals(paragraph.text, paragraph.runs.joinToString("") { it.text })
        for (word in listOf("One", "ten", "eleven", "sixteen")) {
            assertEquals(1, Regex(Regex.escape(word)).findAll(paragraph.text).count(), "$word in '${paragraph.text}'")
        }
    }
}
