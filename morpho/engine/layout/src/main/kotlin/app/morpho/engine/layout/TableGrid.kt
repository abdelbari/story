package app.morpho.engine.layout

/**
 * A table's places, worked out once.
 *
 * The rows of a table hold only the cells that begin: where a cell covers
 * several columns the places beside it hold nothing, and where one covers
 * several rows the places under it hold nothing either. Every writer needs
 * the same walk to put that back — Word wants each covered place written
 * as a continuation, a page wants to know how wide a cell is drawn — and a
 * walk written twice is a walk that goes wrong once.
 */
object TableGrid {

    /** One place of a row, left to right. */
    sealed interface Place {
        /** The column the place begins at. */
        val column: Int

        /** How many columns it covers. */
        val span: Int
    }

    /** A cell of the table, at the place it begins. */
    data class Filled(
        val cell: TableCell,
        override val column: Int,
        override val span: Int,
        /** How many rows it covers, itself included. */
        val rowSpan: Int,
    ) : Place

    /**
     * A place a cell in an earlier row covers — as wide as that cell,
     * since a cell over two columns and two rows covers two places under
     * it that are one continuation of it, not two.
     */
    data class Covered(override val column: Int, override val span: Int = 1) : Place

    /** A place no cell reaches: a row shorter than the table is wide. */
    data class Empty(override val column: Int) : Place {
        override val span: Int get() = 1
    }

    /** The table as places: every row a full sweep of the grid, left to right. */
    class Layout(val columns: Int, val rows: List<List<Place>>)

    fun of(table: Table): Layout {
        val columns = widthOf(table)
        // For each column, how many rows further down a cell above still
        // covers it, and — at the column a cover begins — how wide it is.
        val covered = IntArray(columns)
        val coverSpan = IntArray(columns)
        val rows = table.rows.map { row ->
            val places = mutableListOf<Place>()
            var column = 0
            fun fillCovered() {
                while (column < columns && covered[column] > 0) {
                    val span = coverSpan[column].coerceIn(1, columns - column)
                    for (c in column until column + span) covered[c]--
                    places += Covered(column, span)
                    column += span
                }
            }
            for (cell in row.cells) {
                fillCovered()
                if (column >= columns) break
                val span = cell.columnSpan.coerceIn(1, columns - column)
                val rowSpan = cell.rowSpan.coerceAtLeast(1)
                places += Filled(cell, column, span, rowSpan)
                if (rowSpan > 1) {
                    for (c in column until column + span) {
                        covered[c] = rowSpan - 1
                        coverSpan[c] = 0
                    }
                    coverSpan[column] = span
                }
                column += span
            }
            fillCovered()
            while (column < columns) {
                places += Empty(column)
                column++
            }
            places.toList()
        }
        return Layout(columns, rows)
    }

    /**
     * How many rows from the top of [table] are its head — the rows drawn
     * again at the top of every page a long table runs onto.
     *
     * A head is a run of rows from the top and nothing else. Word repeats
     * only the leading rows a table marks and ignores the mark further
     * down, since a row in the middle of a table cannot be repeated above
     * the rows before it; so a writer that marks such a row writes
     * something Word will not do, and every writer that believes it shows
     * a head where the reader will not see one.
     *
     * A table that is all head has no head: there is nothing under it to
     * head, and a head repeated at the top of every page the table runs
     * onto would be the table repeating itself.
     */
    fun headRows(table: Table): Int =
        table.rows.takeWhile { it.repeatsAsHeader }.size.takeIf { it < table.rows.size } ?: 0

    /**
     * How many columns the grid has: the widest row, counting each cell for
     * the columns it covers and each place a cell in an earlier row has
     * already taken.
     */
    private fun widthOf(table: Table): Int {
        var widest = 1
        val covered = HashMap<Int, Int>()
        fun skipCovered(from: Int): Int {
            var column = from
            while ((covered[column] ?: 0) > 0) {
                covered[column] = covered.getValue(column) - 1
                column++
            }
            return column
        }
        for (row in table.rows) {
            var column = 0
            for (cell in row.cells) {
                column = skipCovered(column)
                val span = cell.columnSpan.coerceAtLeast(1)
                if (cell.rowSpan > 1) for (c in column until column + span) covered[c] = cell.rowSpan - 1
                column += span
            }
            column = skipCovered(column)
            widest = maxOf(widest, column)
        }
        return widest
    }
}
