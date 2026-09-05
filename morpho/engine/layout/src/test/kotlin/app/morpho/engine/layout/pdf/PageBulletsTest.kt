package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A list whose markers the page draws rather than writes.
 *
 * A browser draws its bullets: a filled circle painted beside the item,
 * not a character in the text. Nothing extracted says the item is one, its
 * lines are evenly spaced and evenly set — which is what a paragraph looks
 * like — and a printed web page's list came back as one paragraph of
 * run-on sentences.
 */
class PageBulletsTest {

    private fun item(text: String, y: Float, x: Float = 100f) =
        PdfLine(text = text, x = x, baselineY = y, maxFontSize = 12f, page = 1, xEnd = x + 300f)

    /** A filled circle beside a line, as a browser draws one. */
    private fun mark(y: Float, at: Float = 86f) =
        PdfDrawing(1, at, y - 8f, at + 5f, y - 3f)

    private fun paragraphs(lines: List<PdfLine>, drawings: List<PdfDrawing>) =
        PdfLayout.reconstruct(lines, confidence = 0.6f, drawings = drawings)
            .blocks.filterIsInstance<Paragraph>().map { it.text }

    @Test
    fun `marks drawn beside line after line are a list`() {
        val lines = listOf(
            item("it must be clear and admit of only one reading", 100f),
            item("it must be within reach of whoever answers it", 122f),
            item("it must measure what it was written to measure", 144f),
        )
        val read = paragraphs(lines, lines.map { mark(it.baselineY) })
        assertEquals(3, read.size, "read as ${read.size}: $read")
        assertTrue(read.all { it.startsWith("• ") }, "the bullet the page drew was not put back: $read")
    }

    @Test
    fun `one mark beside one line is a mark on the page, not a list`() {
        val lines = listOf(
            item("a sentence of ordinary prose running on a while", 100f),
            item("and carrying on to a second line of the same", 122f),
            item("and a third to make a paragraph of it", 144f),
        )
        val read = paragraphs(lines, listOf(mark(100f)))
        assertTrue(read.none { it.contains("•") }, "a mark became a bullet: $read")
    }

    @Test
    fun `a page that wrote its own bullets is left alone`() {
        // Otherwise the marker is drawn twice, once by the page and once
        // by whoever reads the file back.
        val lines = listOf(
            item("• it must be clear and admit of only one reading", 100f),
            item("• it must be within reach of whoever answers it", 122f),
        )
        val read = paragraphs(lines, lines.map { mark(it.baselineY) })
        assertTrue(read.none { it.startsWith("• •") }, "the bullet was written twice: $read")
    }

    @Test
    fun `marks in the same place at opposite ends of a page are two marks`() {
        // The items of a list follow one another. Marks that merely happen
        // to fall in the same place, pages apart in the reading, are a
        // coincidence — and a page that scatters enough of them has
        // coincidences everywhere.
        val lines = (0 until 20).map { item("a line of ordinary prose, number $it", 100f + it * 22f) }
        val read = paragraphs(lines, listOf(mark(lines.first().baselineY), mark(lines.last().baselineY)))
        assertTrue(read.none { it.contains("•") }, "two marks far apart became a list: $read")
    }

    @Test
    fun `a page scattered with small marks has no list on it`() {
        // A chart, a map, a page of points. Every line of one such page
        // was read as an item of a list, because with marks enough some of
        // them fall in the same place beside lines running.
        val lines = (0 until 20).map { item("a line of ordinary prose, number $it", 100f + it * 22f) }
        val scattered = (0 until 400).map { at ->
            PdfDrawing(1, 60f + (at * 7 % 460), 90f + (at * 11 % 430), 64f + (at * 7 % 460), 94f + (at * 11 % 430))
        }
        val read = paragraphs(lines, scattered)
        assertTrue(read.none { it.contains("•") }, "a scatter of marks became a list: ${read.take(3)}")
    }

    @Test
    fun `a right-to-left item takes its marker from the right of the line`() {
        val lines = listOf(
            item("أن يكون واضحا لا يحتمل أكثر من معنى واحد", 100f),
            item("أن يكون في متناول من يجيب عنه معرفة وذاكرة", 122f),
        )
        // The mark stands past the end of the line, which on a
        // right-to-left page is where the line begins.
        val read = paragraphs(lines, lines.map { mark(it.baselineY, at = it.xEnd + 6f) })
        assertEquals(2, read.size, "read as ${read.size}: $read")
        assertTrue(read.all { it.startsWith("• ") }, "the marker at the right was not seen: $read")
    }
}
