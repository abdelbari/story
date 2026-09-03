package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The walk that puts back the places a merged cell covers. */
class TableGridTest {

    private fun cell(text: String, columnSpan: Int = 1, rowSpan: Int = 1) =
        TableCell(listOf(Paragraph(listOf(TextRun(text)))), columnSpan, rowSpan)

    private fun shape(table: Table): List<String> =
        TableGrid.of(table).rows.map { row ->
            row.joinToString(" ") { place ->
                when (place) {
                    is TableGrid.Filled -> (place.cell.blocks.single() as Paragraph).text +
                        "@${place.column}+${place.span}" + if (place.rowSpan > 1) "v${place.rowSpan}" else ""
                    is TableGrid.Covered -> "covered@${place.column}"
                    is TableGrid.Empty -> "empty@${place.column}"
                }
            }
        }

    @Test
    fun `a plain table is a place per cell`() {
        val table = Table(listOf(TableRow(listOf(cell("a"), cell("b")))))
        assertEquals(2, TableGrid.of(table).columns)
        assertEquals(listOf("a@0+1 b@1+1"), shape(table))
    }

    @Test
    fun `a heading over two columns leaves no place beside it`() {
        val table = Table(
            listOf(
                TableRow(listOf(cell("wide", columnSpan = 2))),
                TableRow(listOf(cell("a"), cell("b"))),
            )
        )
        assertEquals(2, TableGrid.of(table).columns, "the wide cell decides the grid")
        assertEquals(listOf("wide@0+2", "a@0+1 b@1+1"), shape(table))
    }

    @Test
    fun `a cell beside two rows covers the place under it`() {
        val table = Table(
            listOf(
                TableRow(listOf(cell("side", rowSpan = 2), cell("first"))),
                TableRow(listOf(cell("second"))),
            )
        )
        assertEquals(listOf("side@0+1v2 first@1+1", "covered@0 second@1+1"), shape(table))
    }

    @Test
    fun `a row shorter than the table is filled out`() {
        val table = Table(
            listOf(
                TableRow(listOf(cell("a"), cell("b"), cell("c"))),
                TableRow(listOf(cell("only"))),
            )
        )
        assertEquals(listOf("a@0+1 b@1+1 c@2+1", "only@0+1 empty@1 empty@2"), shape(table))
    }

    @Test
    fun `a cell that claims more than the table has is held to it`() {
        val table = Table(
            listOf(
                TableRow(listOf(cell("a"), cell("b"))),
                TableRow(listOf(cell("greedy", columnSpan = 9))),
            )
        )
        val layout = TableGrid.of(table)
        assertEquals(9, layout.columns, "the widest row is the grid")
        assertEquals("greedy@0+9", shape(table)[1].substringBefore(' '))
    }

    private fun rows(vararg heads: Boolean) =
        Table(heads.map { TableRow(listOf(cell("x")), repeatsAsHeader = it) })

    @Test
    fun `the head is the run of rows from the top`() {
        assertEquals(0, TableGrid.headRows(rows(false, false, false)), "a table nobody marked")
        assertEquals(1, TableGrid.headRows(rows(true, false, false)))
        assertEquals(2, TableGrid.headRows(rows(true, true, false)))
    }

    @Test
    fun `a row marked in the middle of a table is not a head`() {
        // Word repeats the leading rows and ignores the mark further down:
        // a row cannot be repeated above the rows before it. A writer that
        // marked it would show a head the reader never sees.
        assertEquals(0, TableGrid.headRows(rows(false, true, false)))
        assertEquals(1, TableGrid.headRows(rows(true, false, true)), "only the run from the top")
    }

    @Test
    fun `a table that is all head has none`() {
        // There is nothing under it to head, and repeating it at the top of
        // every page it runs onto would be the table repeating itself.
        assertEquals(0, TableGrid.headRows(rows(true)))
        assertEquals(0, TableGrid.headRows(rows(true, true)))
        assertEquals(0, TableGrid.headRows(Table(emptyList())))
    }
}
