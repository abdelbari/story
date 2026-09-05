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
import java.io.ByteArrayOutputStream

/**
 * The lines a page draws under its words, and through them.
 *
 * A PDF has no underline and no strike. A document that underlines a term
 * or strikes out a clause has its producer draw a hair of a rule where the
 * words are, and nothing in the file joins the two — so every underlined
 * heading and every struck-out price came back plain, while the same
 * document converted from Word kept both.
 *
 * What has to stay out of it is everything else a page rules: the border
 * under a paragraph, the line across a table, the bar of colour behind a
 * highlighted word.
 */
class MarkedWordsTest {

    private val font: PDFont = PDType1Font.HELVETICA
    private val size = 12f

    private fun width(text: String) = font.getStringWidth(text) / 1000f * size

    /** A page built by hand: words at a place, and rules where asked. */
    private class Sheet {
        val words = mutableListOf<Triple<String, Float, Float>>()
        val rules = mutableListOf<FloatArray>()
    }

    private fun read(build: Sheet.() -> Unit): List<Paragraph> {
        val sheet = Sheet().apply(build)
        val bytes = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                for ((text, x, y) in sheet.words) {
                    content.beginText()
                    content.setFont(font, size)
                    content.newLineAtOffset(x, y)
                    content.showText(text)
                    content.endText()
                }
                for (rule in sheet.rules) {
                    content.addRect(rule[0], rule[1], rule[2] - rule[0], rule[3])
                    content.fill()
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }
        return PdfReader().extract(bytes).blocks.filterIsInstance<Paragraph>()
    }

    private fun underlined(paragraphs: List<Paragraph>) =
        paragraphs.flatMap { it.runs }.filter { it.underline }.joinToString("|") { it.text.trim() }

    private fun struck(paragraphs: List<Paragraph>) =
        paragraphs.flatMap { it.runs }.filter { it.strikethrough }.joinToString("|") { it.text.trim() }

    @Test
    fun `a term the page underlines comes back underlined`() {
        val read = read {
            words += Triple("The term ", 72f, 700f)
            val at = 72f + width("The term ")
            words += Triple("consideration", at, 700f)
            words += Triple(" is defined below.", at + width("consideration"), 700f)
            rules += floatArrayOf(at, 698f, at + width("consideration"), 0.8f)
        }
        assertEquals("consideration", underlined(read))
        assertEquals("", struck(read))
    }

    @Test
    fun `a clause the page strikes out comes back struck`() {
        val read = read {
            words += Triple("The price is ", 72f, 700f)
            val at = 72f + width("The price is ")
            words += Triple("40 dollars", at, 700f)
            rules += floatArrayOf(at, 703.6f, at + width("40 dollars"), 0.8f)
        }
        assertEquals("40 dollars", struck(read))
        assertEquals("", underlined(read))
    }

    @Test
    fun `a border under the paragraph marks nothing`() {
        // The rule a paper draws under its dates: margin to margin, and
        // well clear of the descenders.
        val read = read {
            words += Triple("Received 21 April, accepted 19 May", 72f, 700f)
            rules += floatArrayOf(56f, 692f, 540f, 1f)
        }
        assertEquals("", underlined(read))
        assertEquals("", struck(read))
        assertTrue(read.single().style.ruleBelow, "the rule under the line was lost altogether")
    }

    @Test
    fun `a bar of colour behind the words is not a strike`() {
        // A highlight: as deep as the type and centred on it, which is
        // where a strike would sit.
        val read = read {
            words += Triple("Highlighted words here", 72f, 700f)
            rules += floatArrayOf(70f, 697f, 200f, 12f)
        }
        assertEquals("", struck(read))
        assertEquals("", underlined(read))
    }

    @Test
    fun `a rule across a table's column marks none of its words`() {
        val read = read {
            words += Triple("Total", 100f, 700f)
            words += Triple("41.00", 300f, 700f)
            // The line under the row: it runs the width of the table,
            // far past either cell's words.
            rules += floatArrayOf(72f, 697.5f, 480f, 0.8f)
        }
        assertEquals("", underlined(read))
        assertEquals("", struck(read))
    }

    @Test
    fun `both marks on one line each keep to their own words`() {
        val read = read {
            words += Triple("Was ", 72f, 700f)
            val old = 72f + width("Was ")
            words += Triple("50", old, 700f)
            val middle = old + width("50")
            words += Triple(" now ", middle, 700f)
            val new = middle + width(" now ")
            words += Triple("40", new, 700f)
            rules += floatArrayOf(old, 703.6f, old + width("50"), 0.8f)
            rules += floatArrayOf(new, 698f, new + width("40"), 0.8f)
        }
        assertEquals("50", struck(read))
        assertEquals("40", underlined(read))
    }
}
