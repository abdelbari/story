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
}
