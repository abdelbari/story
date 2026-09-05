package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Rows and columns put into a table with merged cells and taken out of it, read off the grid. */
class TableEditsTest {

    private fun cell(text: String, columnSpan: Int = 1, rowSpan: Int = 1) =
        TableCell(listOf(Paragraph(listOf(TextRun(text)))), columnSpan, rowSpan)

    /** Each row as its cells: the text, then the columns and rows it covers where more than one. */
    private fun shape(table: Table): List<String> = table.rows.map { row ->
        row.cells.joinToString(" ") { c ->
            val text = (c.blocks.first() as Paragraph).text.ifEmpty { "_" }
            text + (if (c.columnSpan > 1) "+${c.columnSpan}" else "") + (if (c.rowSpan > 1) "v${c.rowSpan}" else "")
        }
    }

    // a covers two columns over c and d; e beside it covers two rows, over nothing.
    private val table = Table(
        listOf(
            TableRow(listOf(cell("a", columnSpan = 2), cell("e", rowSpan = 2))),
            TableRow(listOf(cell("c"), cell("d"))),
        ),
    )

    @Test
    fun `a row put in is shaped like the row beside it, and a cell crossing the place grows`() {
        assertEquals(listOf("a+2 ev2", "c d", "_ _ _"), shape(TableEdits.insertRow(table, at = 2, template = 1)!!), "below the last row")
        assertEquals(listOf("a+2 ev3", "_ _", "c d"), shape(TableEdits.insertRow(table, at = 1, template = 1)!!), "above c: e crosses and grows, a does not")
        assertEquals(listOf("a+2 ev3", "_+2", "c d"), shape(TableEdits.insertRow(table, at = 1, template = 0)!!), "below a: as wide as a, e grows")
        assertEquals(listOf("_+2 _", "a+2 ev2", "c d"), shape(TableEdits.insertRow(table, at = 0, template = 0)!!), "above everything")
        val heads = Table(listOf(TableRow(listOf(cell("h")), repeatsAsHeader = true), TableRow(listOf(cell("b")))))
        assertEquals(listOf(true, false, false), TableEdits.insertRow(heads, at = 1, template = 0)!!.rows.map { it.repeatsAsHeader }, "a new row is not a head")
    }

    @Test
    fun `rows taken out shorten the cells crossing them and take the cells inside them`() {
        assertEquals(listOf("a+2 e"), shape(TableEdits.deleteRows(table, 1..1)!!), "c's row: e shortens to one")
        assertEquals(listOf("c d e"), shape(TableEdits.deleteRows(table, 0..0)!!), "a's row: a goes, e shortens and now begins beside c and d")
    }

    @Test
    fun `a cell that begins in a row taken out and continues below moves down`() {
        val tall = Table(
            listOf(
                TableRow(listOf(cell("x", rowSpan = 3), cell("p"))),
                TableRow(listOf(cell("q"))),
                TableRow(listOf(cell("r"))),
            ),
        )
        assertEquals(listOf("xv2 q", "r"), shape(TableEdits.deleteRows(tall, 0..0)!!), "x begins in the row taken, so it now begins in the next")
        assertEquals(listOf("xv2 p", "r"), shape(TableEdits.deleteRows(tall, 1..1)!!))
        assertEquals(listOf("x p"), shape(TableEdits.deleteRows(tall, 1..2)!!))
        assertEquals(emptyList<String>(), shape(TableEdits.deleteRows(tall, 0..2)!!), "every row taken leaves no row")
    }

    @Test
    fun `a row left with no cell and nothing over it goes too`() {
        val ragged = Table(listOf(TableRow(listOf(cell("a"), cell("b"))), TableRow(listOf(cell("c")))))
        assertEquals(listOf("b"), shape(TableEdits.deleteColumns(ragged, 0..0)!!), "c's row had nothing else")
        assertEquals(listOf("a", "c"), shape(TableEdits.deleteColumns(ragged, 1..1)!!))
    }

    @Test
    fun `a column put in widens the cell crossing it and gives every other row a cell`() {
        assertEquals(listOf("a+3 ev2", "c _ d"), shape(TableEdits.insertColumn(table, at = 1, width = null)!!), "inside a")
        assertEquals(listOf("a+2 _ ev2", "c d _"), shape(TableEdits.insertColumn(table, at = 2, width = null)!!), "between a and e, under e too")
        assertEquals(listOf("_ a+2 ev2", "_ c d"), shape(TableEdits.insertColumn(table, at = 0, width = null)!!), "before everything")
        assertEquals(listOf("a+2 ev2 _", "c d _"), shape(TableEdits.insertColumn(table, at = 3, width = null)!!), "after everything, where the second row has no place: at its end")
        val widths = table.copy(columnWidthsPt = listOf(10f, 20f, 30f))
        assertEquals(listOf(10f, 20f, 25f, 30f), TableEdits.insertColumn(widths, at = 2, width = 25f)!!.columnWidthsPt)
    }

    @Test
    fun `columns taken out narrow the cells crossing them and take the cells inside them`() {
        assertEquals(listOf("a ev2", "d"), shape(TableEdits.deleteColumns(table, 0..0)!!), "c's column: a narrows")
        assertEquals(listOf("ev2", ""), shape(TableEdits.deleteColumns(table, 0..1)!!), "a's columns: c and d go; their row stays, since e still covers it")
        assertEquals(listOf("a+2", "c d"), shape(TableEdits.deleteColumns(table, 2..2)!!), "e's column")
        assertEquals(emptyList<String>(), shape(TableEdits.deleteColumns(table, 0..2)!!))
        assertEquals(listOf(30f), TableEdits.deleteColumns(table.copy(columnWidthsPt = listOf(10f, 20f, 30f)), 0..1)!!.columnWidthsPt)
    }

    @Test
    fun `a ragged table is laid out as wide as its widest row, covers and all`() {
        val off = Table(listOf(TableRow(listOf(cell("wide", columnSpan = 2))), TableRow(listOf(cell("a"), cell("b"), cell("c")))))
        assertTrue(TableEdits.placed(off) != null, "three cells under a two-wide one is a grid of three")
        val covered = Table(listOf(TableRow(listOf(cell("tall", columnSpan = 2, rowSpan = 2))), TableRow(listOf(cell("under"), cell("more"), cell("still")))))
        assertEquals(Cell(0, 0), TableEdits.cellAt(covered, 1, 1), "tall covers the row under it")
        assertEquals(Cell(1, 0), TableEdits.cellAt(covered, 1, 2), "and the cells of that row begin after it")
        val rowed = TableEdits.insertRow(covered, 0, 0)!!
        assertEquals(listOf("_+2", "tall+2v2", "under more still"), shape(rowed), "a row above it, shaped like it: as wide as tall, and nothing where the row reaches no cell")
        assertEquals(shape(covered), shape(TableEdits.deleteRows(rowed, 0..0)!!), "and it comes back as it went")
    }

    @Test
    fun `where a cell is stored is found from any place it covers`() {
        assertEquals(Cell(0, 0), TableEdits.cellAt(table, 0, 1), "a, from its second column")
        assertEquals(Cell(0, 1), TableEdits.cellAt(table, 1, 2), "e, from the row under it")
        assertEquals(Cell(1, 1), TableEdits.cellAt(table, 1, 1))
        assertNull(TableEdits.cellAt(Table(listOf(TableRow(listOf(cell("a"))), TableRow(listOf(cell("b"), cell("c"))))), 0, 1), "a place nothing reaches")
    }

    @Test
    fun `a row or a column put in and taken out again leaves a table as it was, whatever its merges`() {
        for (seed in 1..600) {
            val random = Random(seed)
            val rows = random.nextInt(1, 5)
            val columns = random.nextInt(1, 5)
            // A rectangular table with random merges: each place either
            // starts a cell reaching some way right and down, or is covered.
            val taken = Array(rows) { BooleanArray(columns) }
            val made = List(rows) { mutableListOf<TableCell>() }
            for (r in 0 until rows) for (c in 0 until columns) {
                if (taken[r][c]) continue
                var right = c
                while (right + 1 < columns && !taken[r][right + 1] && random.nextInt(3) == 0) right++
                var bottom = r
                while (bottom + 1 < rows && (c..right).all { !taken[bottom + 1][it] } && random.nextInt(3) == 0) bottom++
                for (rr in r..bottom) for (cc in c..right) taken[rr][cc] = true
                made[r] += cell("$r$c", right - c + 1, bottom - r + 1)
            }
            val table = Table(made.map { TableRow(it) }, columnWidthsPt = if (random.nextBoolean()) List(columns) { 10f * it } else null)
            val where = "seed $seed ${shape(table)}"
            assertTrue(TableEdits.placed(table) != null, where)
            val at = random.nextInt(rows + 1)
            val rowed = TableEdits.insertRow(table, at, template = if (at == 0) 0 else at - 1)!!
            assertTrue(TableEdits.placed(rowed) != null, "$where: the row put in left a cell without a place")
            assertEquals(rows + 1, rowed.rows.size, where)
            assertEquals(table, TableEdits.deleteRows(rowed, at..at), "$where: the row taken out again")
            val column = random.nextInt(columns + 1)
            val columned = TableEdits.insertColumn(table, column, 5f)!!
            assertTrue(TableEdits.placed(columned) != null, "$where: the column put in left a cell without a place")
            assertEquals(columns + 1, TableGrid.of(columned).columns, where)
            assertEquals(table, TableEdits.deleteColumns(columned, column..column), "$where: the column taken out again")
        }
    }
}
