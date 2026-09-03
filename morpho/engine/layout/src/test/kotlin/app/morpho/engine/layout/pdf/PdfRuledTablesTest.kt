package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A table read from the lines the page drew round it, rather than from the
 * alignment of its cells.
 *
 * The alignment says nothing about a table whose cells wrap: a column of
 * short labels beside a column of sentences has one line in the first cell
 * and three in the second, so nothing lines up, and an ordinary bordered
 * report table came back as a wall of prose with its head read as a
 * section heading.
 */
class PdfRuledTablesTest {

    /** The lines across, at these heights, and the lines down, at these places. */
    private val across = listOf(90f, 120f, 190f, 260f)
    private val down = listOf(60f, 180f, 520f)

    /** The grid, drawn a cell at a time as a page draws one. */
    private fun grid(): List<PdfDrawing> {
        val out = mutableListOf<PdfDrawing>()
        for (y in across) {
            for (column in 0 until down.size - 1) {
                out += PdfDrawing(1, down[column], y - 0.4f, down[column + 1], y + 0.4f)
            }
        }
        for (x in down) {
            for (row in 0 until across.size - 1) {
                out += PdfDrawing(1, x - 0.4f, across[row], x + 0.4f, across[row + 1])
            }
        }
        return out
    }

    private fun row(label: String, wrapped: List<String>, top: Float): List<PdfLine> =
        wrapped.mapIndexed { at, piece ->
            val segments = mutableListOf<PdfSegment>()
            if (at == 0) segments += PdfSegment(label, 66f, 66f + label.length * 6f)
            segments += PdfSegment(piece, 186f, 186f + piece.length * 6f)
            PdfLine(
                text = segments.joinToString(" ") { it.text },
                x = segments.first().xStart,
                baselineY = top + at * 16f,
                maxFontSize = 11f,
                page = 1,
                xEnd = segments.last().xEnd,
                segments = segments,
            )
        }

    private fun page(): List<PdfLine> =
        row("Item", listOf("What the pilot found"), 108f) +
            row(
                "Clarity",
                listOf(
                    "most of the vagueness gathered in the",
                    "longest questions, which forced them to",
                    "be written again before the forms went",
                ),
                138f,
            ) +
            row(
                "Reach",
                listOf(
                    "a quarter of those asked had no way of",
                    "answering the third question at all",
                    "because it assumed a record they keep",
                ),
                208f,
            )

    @Test
    fun `a table the page ruled is read from its rules, wrapped cells and all`() {
        val model = PdfLayout.reconstruct(page(), confidence = 0.6f, drawings = grid())
        val table = model.blocks.filterIsInstance<Table>().single()
        assertEquals(3, table.rows.size, "rows: ${table.rows.map { r -> r.cells.size }}")
        assertTrue(table.rows.all { it.cells.size == 2 }, "columns: ${table.rows.map { it.cells.size }}")
        fun cell(row: Int, column: Int) = table.rows[row].cells[column]
            .blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        assertEquals("Item", cell(0, 0))
        assertEquals("What the pilot found", cell(0, 1))
        assertEquals("Clarity", cell(1, 0))
        assertTrue(
            cell(1, 1).startsWith("most of the vagueness") && cell(1, 1).endsWith("the forms went"),
            "the wrapped cell was not gathered: ${cell(1, 1)}",
        )
        assertTrue(table.ruled, "the page ruled it")
    }

    @Test
    fun `the paragraphs either side of it are still their own`() {
        val before = PdfLine("a sentence before the table", 60f, 70f, 11f, 1, 300f)
        val after = PdfLine("a sentence after the table", 60f, 300f, 11f, 1, 300f)
        val model = PdfLayout.reconstruct(
            listOf(before) + page() + listOf(after),
            confidence = 0.6f,
            drawings = grid(),
        )
        val paragraphs = model.blocks.filterIsInstance<Paragraph>().map { it.text }
        assertEquals(listOf("a sentence before the table", "a sentence after the table"), paragraphs)
    }

    @Test
    fun `a cell covers the columns the page drew no side between`() {
        // A head written across the whole table. Kept as three cells with
        // the words in the middle one, a converted table has two blanks
        // where the document has none.
        val bands = listOf(70f, 100f, 130f, 160f)
        val sides = listOf(60f, 180f, 520f)
        val drawn = mutableListOf<PdfDrawing>()
        for (y in bands) drawn += PdfDrawing(1, sides.first(), y - 0.4f, sides.last(), y + 0.4f)
        // The outer sides run the whole height; the one in the middle stops
        // short of the head, which is what makes the head one cell.
        for (x in listOf(sides.first(), sides.last())) {
            drawn += PdfDrawing(1, x - 0.4f, bands.first(), x + 0.4f, bands.last())
        }
        drawn += PdfDrawing(1, sides[1] - 0.4f, bands[1], sides[1] + 0.4f, bands.last())
        fun row(pieces: List<Pair<String, Float>>, y: Float): PdfLine {
            val segments = pieces.map { (text, x) -> PdfSegment(text, x, x + text.length * 6f) }
            return PdfLine(
                text = segments.joinToString(" ") { it.text },
                x = segments.first().xStart,
                baselineY = y,
                maxFontSize = 11f,
                page = 1,
                xEnd = segments.last().xEnd,
                segments = segments,
            )
        }
        val model = PdfLayout.reconstruct(
            listOf(
                row(listOf("Results of the pilot" to 200f), 90f),
                row(listOf("Item" to 66f, "Respondents" to 186f), 120f),
                row(listOf("Clear" to 66f, "48" to 186f), 150f),
            ),
            confidence = 0.6f,
            drawings = drawn,
        )
        val table = model.blocks.filterIsInstance<Table>().single()
        assertEquals(listOf(1, 2, 2), table.rows.map { it.cells.size }, "the head was cut into cells")
        assertEquals(2, table.rows.first().cells.single().columnSpan, "the head covers both columns")
        assertEquals(
            "Results of the pilot",
            table.rows.first().cells.single().blocks.filterIsInstance<Paragraph>().single().text,
        )
        // And the columns are still counted through the merge.
        assertEquals(2, table.columnWidthsPt?.size, "widths: ${table.columnWidthsPt}")
    }

    @Test
    fun `a box drawn round something is not a table of one cell`() {
        // Four lines make one cell, not a grid, and a border round a figure
        // or a frame round the sheet would otherwise be read as a table of
        // the whole page.
        val box = listOf(
            PdfDrawing(1, 60f, 89f, 520f, 91f),
            PdfDrawing(1, 60f, 259f, 520f, 261f),
            PdfDrawing(1, 59f, 90f, 61f, 260f),
            PdfDrawing(1, 519f, 90f, 521f, 260f),
        )
        val model = PdfLayout.reconstruct(page(), confidence = 0.6f, drawings = box)
        assertTrue(
            model.blocks.filterIsInstance<Table>().none { it.rows.size == 1 },
            "a box became a table: ${model.blocks.filterIsInstance<Table>().map { it.rows.size }}",
        )
    }

    @Test
    fun `a grid with next to nothing in it is not a table of words`() {
        val model = PdfLayout.reconstruct(
            listOf(PdfLine("one", 66f, 108f, 11f, 1, 90f, segments = listOf(PdfSegment("one", 66f, 90f)))),
            confidence = 0.6f,
            drawings = grid(),
        )
        assertTrue(model.blocks.filterIsInstance<Table>().isEmpty(), "one word in six cells is not a table")
    }
}
