package app.morpho.engine.layout

/**
 * Rows and columns put into a table and taken out of it, merged cells
 * and all.
 *
 * The rows of a table store only the cells that begin, so a row put in
 * under a cell that covers three rows is not a row of cells: it is one
 * more row for that cell to cover, and cells only where nothing covers
 * them. Reasoned about row by row that is a case for every way a cell
 * can lie across the place; so it is not reasoned about that way. The
 * table is laid out on its grid, the row or the column is put into the
 * grid or taken out of it — a cell that crosses the place grows or
 * shrinks by it, a cell that lies wholly in what is taken out goes with
 * it — and the rows are read back off the grid.
 */
internal object TableEdits {

    /** A cell at the places it covers: rows [top] until [bottom], columns [left] until [right]. */
    class Placed(val cell: TableCell, val top: Int, val bottom: Int, val left: Int, val right: Int) {
        fun covers(row: Int, column: Int): Boolean = row in top until bottom && column in left until right
    }

    /**
     * [table] as its cells at their places — or null where a cell has no
     * place, which a table whose spans run off its grid can have; such a
     * table is left as it is.
     */
    fun placed(table: Table): List<Placed>? {
        val layout = TableGrid.of(table)
        val out = mutableListOf<Placed>()
        for ((row, places) in layout.rows.withIndex()) {
            val filled = places.filterIsInstance<TableGrid.Filled>()
            if (filled.size != table.rows[row].cells.size) return null
            for (place in filled) {
                out += Placed(place.cell, row, minOf(row + place.rowSpan, table.rows.size), place.column, place.column + place.span)
            }
        }
        return out
    }

    /**
     * Where in [table]'s rows the cell covering [row], [column] is
     * stored — the row it begins in and its index there — or null where
     * no cell covers that place.
     */
    fun cellAt(table: Table, row: Int, column: Int): Cell? {
        val placed = placed(table) ?: return null
        val found = placed.firstOrNull { it.covers(row, column) } ?: return null
        return Cell(found.top, placed.count { it.top == found.top && it.left < found.left })
    }

    /**
     * [table] with a row put in at [at], shaped like row [template] —
     * the row above the new one or the one below it: a cell of the
     * template that crosses the place grows by a row, and everywhere
     * else the new row has an empty cell as wide as the template's.
     */
    fun insertRow(table: Table, at: Int, template: Int): Table? {
        val placed = placed(table) ?: return null
        val moved = placed.map { p ->
            when {
                p.top >= at -> Placed(p.cell, p.top + 1, p.bottom + 1, p.left, p.right)
                p.bottom > at -> Placed(p.cell, p.top, p.bottom + 1, p.left, p.right)
                else -> p
            }
        }
        val added = placed
            .filter { template in it.top until it.bottom && !(it.top < at && at < it.bottom) }
            .map { Placed(emptyCell(), at, at + 1, it.left, it.right) }
        val heads = table.rows.map { it.repeatsAsHeader }.toMutableList().also { it.add(at, false) }
        return tableOf(table, moved + added, heads, table.columnWidthsPt)
    }

    /**
     * [table] with the rows [taken] out: a cell lying wholly in them goes,
     * one crossing into them is shortened by as much of it as they held,
     * and a row left with no cell and nothing covering it goes too. The
     * table that comes back may have no rows at all.
     */
    fun deleteRows(table: Table, taken: IntRange): Table? {
        val placed = placed(table) ?: return null
        fun moved(row: Int) = row - taken.count { it < row }
        val kept = placed.mapNotNull { p ->
            val top = moved(p.top)
            val bottom = moved(p.bottom)
            if (top == bottom) null else Placed(p.cell, top, bottom, p.left, p.right)
        }
        val heads = table.rows.filterIndexed { index, _ -> index !in taken }.map { it.repeatsAsHeader }
        return pruned(table, kept, heads, table.columnWidthsPt)
    }

    /**
     * [table] with a column put in at [at], [width] wide where the table
     * knows its widths: a cell crossing the place grows by a column, and
     * every row nothing crosses there gets an empty cell — at the end of
     * a row too short to reach the place, since a row cannot hold a gap.
     */
    fun insertColumn(table: Table, at: Int, width: Float?): Table? {
        val placed = placed(table) ?: return null
        val moved = placed.map { p ->
            when {
                p.left >= at -> Placed(p.cell, p.top, p.bottom, p.left + 1, p.right + 1)
                p.right > at -> Placed(p.cell, p.top, p.bottom, p.left, p.right + 1)
                else -> p
            }
        }
        val added = table.rows.indices.mapNotNull { row ->
            val inRow = moved.filter { row in it.top until it.bottom }
            val column = minOf(at, inRow.maxOfOrNull { it.right } ?: 0)
            if (inRow.any { it.covers(row, column) }) null else Placed(emptyCell(), row, row + 1, column, column + 1)
        }
        val widths = table.columnWidthsPt?.let { widths ->
            widths.toMutableList().also { it.add(at.coerceAtMost(it.size), width ?: widths.lastOrNull() ?: return@let null) }
        }
        return tableOf(table, moved + added, table.rows.map { it.repeatsAsHeader }, widths)
    }

    /**
     * [table] with the columns [taken] out: a cell lying wholly in them
     * goes, one crossing into them is narrowed by as much of it as they
     * held, and a row left with no cell and nothing covering it goes
     * too. The table that comes back may have no rows at all.
     */
    fun deleteColumns(table: Table, taken: IntRange): Table? {
        val placed = placed(table) ?: return null
        fun moved(column: Int) = column - taken.count { it < column }
        val kept = placed.mapNotNull { p ->
            val left = moved(p.left)
            val right = moved(p.right)
            if (left == right) null else Placed(p.cell, p.top, p.bottom, left, right)
        }
        val widths = table.columnWidthsPt?.filterIndexed { index, _ -> index !in taken }?.ifEmpty { null }
        return pruned(table, kept, table.rows.map { it.repeatsAsHeader }, widths)
    }

    /** The table of [placed], less every row no cell covers. */
    private fun pruned(table: Table, placed: List<Placed>, heads: List<Boolean>, widths: List<Float>?): Table {
        val gone = heads.indices.filter { row -> placed.none { row in it.top until it.bottom } }
        if (gone.isEmpty()) return tableOf(table, placed, heads, widths)
        fun moved(row: Int) = row - gone.count { it < row }
        val kept = placed.map { Placed(it.cell, moved(it.top), moved(it.bottom), it.left, it.right) }
        return tableOf(table, kept, heads.filterIndexed { index, _ -> index !in gone }, widths)
    }

    /** The table [placed] describes: each row the cells that begin in it, left to right, with [heads]' flags. */
    private fun tableOf(table: Table, placed: List<Placed>, heads: List<Boolean>, widths: List<Float>?): Table {
        val rows = heads.indices.map { row ->
            val cells = placed.filter { it.top == row }.sortedBy { it.left }
                .map { it.cell.copy(columnSpan = it.right - it.left, rowSpan = it.bottom - it.top) }
            TableRow(cells, heads[row])
        }
        return table.copy(rows = rows, columnWidthsPt = widths)
    }

    private fun emptyCell(): TableCell = TableCell(listOf(Paragraph(listOf(TextRun("")))))
}
