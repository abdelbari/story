package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A page set in two columns. A PDF paints its lines down the page, not
 * down each column, so a reader that takes them in the order they arrive
 * reads across the gutter and hands back sentences that were never
 * written: the first line of the left column, then the first of the right.
 */
class ColumnsTest {

    private fun line(text: String, x: Float, y: Float, width: Float, page: Int = 1) =
        PdfLine(text = text, x = x, baselineY = y, maxFontSize = 10f, page = page, xEnd = x + width)

    /** Two columns of five lines each, painted a row at a time as a producer paints them. */
    private fun twoColumnPage(): List<PdfLine> {
        val lines = mutableListOf<PdfLine>()
        for (row in 0 until 5) {
            val y = 100f + row * 14f
            lines += line("left line $row of the first column, which runs on", 56f, y, 210f)
            lines += line("right line $row of the second column, running on", 300f, y, 210f)
        }
        return lines
    }

    @Test
    fun `the left column is read before the right, not across the gutter`() {
        val model = PdfLayout.reconstruct(twoColumnPage(), confidence = 0.6f)
        val text = model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        val firstRight = text.indexOf("right line 0")
        val lastLeft = text.indexOf("left line 4")
        assertTrue(lastLeft in 0 until firstRight, "the columns were read across: $text")
    }

    @Test
    fun `a heading across both columns keeps its place between them`() {
        val lines = mutableListOf<PdfLine>()
        for (row in 0 until 5) {
            val y = 100f + row * 14f
            lines += line("above left $row and some more of it here", 56f, y, 210f)
            lines += line("above right $row and some more of it here", 300f, y, 210f)
        }
        // A full-width line: it crosses the gutter, so it ends the band.
        lines += line("A HEADING THAT CROSSES THE WHOLE PAGE FROM EDGE TO EDGE", 56f, 190f, 454f)
        for (row in 0 until 5) {
            val y = 220f + row * 14f
            lines += line("below left $row and some more of it here", 56f, y, 210f)
            lines += line("below right $row and some more of it here", 300f, y, 210f)
        }
        val text = PdfLayout.reconstruct(lines, confidence = 0.6f)
            .blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        val heading = text.indexOf("A HEADING")
        assertTrue(text.indexOf("above right 4") in 0 until heading, "the first band ran past the heading: $text")
        assertTrue(heading < text.indexOf("below left 0"), "the heading fell after the band below it: $text")
    }

    @Test
    fun `a page in one column is left exactly as it is`() {
        val lines = (0 until 10).map { row ->
            line("one wide line $row of an ordinary page of prose that fills it", 56f, 100f + row * 14f, 454f)
        }
        val text = PdfLayout.reconstruct(lines, confidence = 0.6f)
            .blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        for (row in 0 until 9) {
            assertTrue(
                text.indexOf("line $row ") < text.indexOf("line ${row + 1} "),
                "the lines were reordered: $text",
            )
        }
    }

    @Test
    fun `two words with a space between them are not two columns`() {
        // The gutter of a real page is a clear strip down the middle of
        // every line; the space between two words is not, because the
        // lines around it are covered.
        val lines = (0 until 10).map { row ->
            line("word ${"x".repeat(row + 1)} more text here to fill the line", 56f, 100f + row * 14f, 454f)
        }
        val flows = PdfColumns.flows(lines, rightToLeft = false)
        assertEquals(setOf(0), flows.values.toSet(), "an ordinary page was split into columns")
    }

    @Test
    fun `a right-to-left page is read from its right column first`() {
        val lines = mutableListOf<PdfLine>()
        for (row in 0 until 5) {
            val y = 100f + row * 14f
            lines += line("يسار السطر $row من العمود الأيسر وما بعده", 56f, y, 210f)
            lines += line("يمين السطر $row من العمود الأيمن وما بعده", 300f, y, 210f)
        }
        val flows = PdfColumns.flows(lines, rightToLeft = true)
        val right = lines.first { it.x == 300f }
        val left = lines.first { it.x == 56f }
        assertTrue(flows.getValue(right) < flows.getValue(left), "Arabic columns were read from the left")
    }
}
