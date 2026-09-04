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

    /**
     * One row of an Arabic table, as a reader hands it over: the words in
     * the order they are read, their pieces in the order they sit, which
     * for Arabic is the other way round.
     */
    private fun arabicRow(label: String, words: List<String>, top: Float): PdfLine {
        var right = 512f
        val inCell = words.map { text ->
            val width = text.length * 6f
            PdfSegment(text, right - width, right).also { right -= width + 4f }
        }
        val pieces = (inCell + PdfSegment(label, 66f, 66f + label.length * 6f)).sortedBy { it.xStart }
        return PdfLine(
            text = (words + label).joinToString(" "),
            x = pieces.first().xStart,
            baselineY = top,
            maxFontSize = 11f,
            page = 1,
            xEnd = pieces.last().xEnd,
            segments = pieces,
        )
    }

    @Test
    fun `an Arabic cell says what it says, not the reverse of it`() {
        // A form an institution sends, scanned or not, is a ruled table
        // of Arabic, and the cells of one were coming back with their
        // words in reverse: every word right, the sentence not. The
        // pieces of a line are handed over left to right because what
        // they are for is the page — the gaps between them are the
        // columns — and a cell's words are rebuilt out of them.
        val rows = listOf(
            "العلمي" to listOf("الاستمارة", "في", "البحث"),
            "الميدانية" to listOf("أدوات", "جمع", "البيانات"),
            "الخاتمة" to listOf("عيوب", "الاستمارة", "ومميزاتها"),
        )
        val lines = rows.mapIndexed { at, (label, words) ->
            arabicRow(label, words, top = listOf(108f, 150f, 220f)[at])
        }
        val model = PdfLayout.reconstruct(lines, confidence = 0.6f, drawings = grid())
        val table = model.blocks.filterIsInstance<Table>().single()
        fun cell(row: Int, column: Int) = table.rows[row].cells[column]
            .blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        // The first cell of an Arabic row is the rightmost one, which is
        // where the table's own first column is.
        assertEquals("الاستمارة في البحث", cell(0, 0))
        assertEquals("أدوات جمع البيانات", cell(1, 0))
        assertEquals("عيوب الاستمارة ومميزاتها", cell(2, 0))
        assertEquals(listOf("العلمي", "الميدانية", "الخاتمة"), (0..2).map { cell(it, 1) })
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
    fun `a cell covers the rows the page drew no line across`() {
        // A label set beside the rows it belongs to. The rows it covers
        // hold only the cells that begin, as a document's own rows do.
        val bands = listOf(70f, 100f, 130f, 160f)
        val sides = listOf(60f, 180f, 520f)
        val drawn = mutableListOf<PdfDrawing>()
        // Every line across, except the one that would cut the label in
        // two: it runs only from the middle side to the right-hand one.
        for ((at, y) in bands.withIndex()) {
            val from = if (at == 2) sides[1] else sides.first()
            drawn += PdfDrawing(1, from, y - 0.4f, sides.last(), y + 0.4f)
        }
        for (x in sides) drawn += PdfDrawing(1, x - 0.4f, bands.first(), x + 0.4f, bands.last())
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
                row(listOf("Section" to 66f, "Item" to 186f), 90f),
                row(listOf("Design" to 66f, "Clear" to 186f), 120f),
                row(listOf("Vague" to 186f), 150f),
            ),
            confidence = 0.6f,
            drawings = drawn,
        )
        val table = model.blocks.filterIsInstance<Table>().single()
        assertEquals(listOf(2, 2, 1), table.rows.map { it.cells.size }, "the covered cell was kept")
        assertEquals(2, table.rows[1].cells.first().rowSpan, "the label covers both rows")
        assertEquals(
            "Vague",
            table.rows[2].cells.single().blocks.filterIsInstance<Paragraph>().single().text,
        )
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

    /** A two-column grid of [rows] rows, drawn whole, with its top at [top]. */
    private fun small(top: Float, rows: Int): List<PdfDrawing> {
        val out = mutableListOf<PdfDrawing>()
        val bottom = top + rows * 30f
        for (r in 0..rows) {
            val y = top + r * 30f
            out += PdfDrawing(1, 60f, y - 0.4f, 380f, y + 0.4f)
        }
        for (x in listOf(60f, 240f, 380f)) out += PdfDrawing(1, x - 0.4f, top, x + 0.4f, bottom)
        return out
    }

    /** A row of the small grid: a label and a figure, one in each column. */
    private fun pair(label: String, figure: String, y: Float): PdfLine {
        val segments = listOf(
            PdfSegment(label, 66f, 66f + label.length * 6f),
            PdfSegment(figure, 246f, 246f + figure.length * 6f),
        )
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

    @Test
    fun `two tables on one page are two tables, and what stands between them is not a row`() {
        // A page ruling two returns with a note between them. Every rule
        // the page drew was taken as one grid, so the rectangle reached
        // from the head of the first table to the foot of the second, the
        // note stood inside it, and it came back as a row of cells — the
        // sentence chopped into columns and the two tables run together.
        val lines = listOf(
            pair("Term", "Received", 108f),
            pair("Autumn", "148", 138f),
            PdfLine(
                text = "A paragraph between the two tables",
                x = 60f, baselineY = 210f, maxFontSize = 11f, page = 1, xEnd = 400f,
                segments = listOf(PdfSegment("A paragraph between the two tables", 60f, 400f)),
            ),
            pair("Term", "Withdrawn", 288f),
            pair("Autumn", "12", 318f),
        )
        val drawings = small(top = 90f, rows = 2) + small(top = 270f, rows = 2)
        val regions = PdfRuledTables.of(lines, drawings)
        assertEquals(
            listOf(0 to 2, 3 to 5),
            regions.map { it.start to it.end },
            "the two grids were not read apart: " + regions.map { "[${it.start},${it.end})" },
        )
        val model = PdfLayout.reconstruct(lines, confidence = 0.6f, drawings = drawings)
        assertEquals(
            listOf("Table", "Paragraph", "Table"),
            model.blocks.map { it::class.simpleName },
            "blocks: " + model.blocks.map { it::class.simpleName },
        )
        assertEquals(
            "A paragraph between the two tables",
            model.blocks.filterIsInstance<Paragraph>().single().text,
            "the note between the tables was cut into cells",
        )
    }

    @Test
    fun `a rule that stands in no grid is not a line of one`() {
        // The other half of reading grids apart: a rule under a heading, or
        // over a footer, belongs to no table. Counted into the one grid a
        // page was allowed, it added a line across above the table and the
        // heading became the table's first row.
        val heading = PdfLine(
            text = "Applications received",
            x = 60f, baselineY = 60f, maxFontSize = 16f, page = 1, xEnd = 300f,
            segments = listOf(PdfSegment("Applications received", 60f, 300f)),
        )
        val lines = listOf(heading, pair("Term", "Received", 108f), pair("Autumn", "148", 138f))
        val underHeading = PdfDrawing(1, 60f, 70f, 520f, 71f)
        val model = PdfLayout.reconstruct(
            lines,
            confidence = 0.6f,
            drawings = small(top = 90f, rows = 2) + underHeading,
        )
        assertEquals(
            "Applications received",
            model.blocks.filterIsInstance<Paragraph>().first().text,
            "the heading was pulled into the table under its rule",
        )
        assertEquals(
            2,
            model.blocks.filterIsInstance<Table>().single().rows.size,
            "the table gained a row from a rule that was not its own",
        )
    }
}
