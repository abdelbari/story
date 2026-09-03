package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.LineJoiner
import kotlin.math.abs

/**
 * A table read from the lines the page drew round it.
 *
 * [PdfTableDetector] knows a table by the alignment of its cells on line
 * after line, which is all a page of plain text ever offers. It cannot see
 * a table whose cells wrap: a column of dates beside a column of sentences
 * has one line in the first cell and three in the second, nothing lines up,
 * and an ordinary bordered report table comes back as a wall of prose with
 * its head read as a section heading.
 *
 * A page that ruled its table said exactly where every cell is. The
 * operators that find a page's rules keep the box of every painted path,
 * so the grid is already in hand: the thin wide ones are the lines across,
 * the thin tall ones the lines down, and the cells are the spaces between
 * them. Each line of text belongs to the cell it stands in, and a cell of
 * three lines is three lines of one cell rather than three rows of a
 * table.
 *
 * This is exact where it applies, so it is asked first, and the alignment
 * of cells is left to say what it can about everything else.
 */
object PdfRuledTables {

    /** Thicker than this and a drawn thing is a box, not a line of a grid. */
    private const val THIN_PT = 3f

    /** Shorter than this and a drawn line is a tick, not a side of a cell. */
    private const val LEAST_SIDE_PT = 8f

    /** Lines within this of each other are the one line, drawn cell by cell. */
    private const val SAME_LINE_PT = 2f

    /** A grid of fewer cells than this either way is a box round something, not a table. */
    private const val LEAST_BANDS = 2

    /** This share of a grid's cells must hold words before it is a table of them. */
    private const val FILLED_SHARE = 0.4f

    /**
     * The tables [drawings] show the page ruled, as regions over [lines].
     *
     * Empty where the page ruled none, which is most pages: the grid has to
     * have cells on both sides of a line each way, and enough of them have
     * to hold words, or a border round a figure would be read as a table of
     * one cell and the sheet's own frame as a table of the whole page.
     */
    fun of(lines: List<PdfLine>, drawings: List<PdfDrawing>): List<PdfTableDetector.Region> {
        if (lines.isEmpty() || drawings.isEmpty()) return emptyList()
        val out = mutableListOf<PdfTableDetector.Region>()
        for ((page, drawn) in drawings.groupBy { it.page }) {
            val across = merged(drawn.filter { it.heightPt <= THIN_PT && it.widthPt >= LEAST_SIDE_PT }) {
                (it.top + it.bottom) / 2
            }
            val down = merged(drawn.filter { it.widthPt <= THIN_PT && it.heightPt >= LEAST_SIDE_PT }) {
                (it.left + it.right) / 2
            }
            if (across.size <= LEAST_BANDS || down.size <= LEAST_BANDS) continue
            val uprights = drawn.filter { it.widthPt <= THIN_PT && it.heightPt >= LEAST_SIDE_PT }
            gridOf(lines, page, across, down, uprights)?.let { out += it }
        }
        return out.sortedBy { it.start }
    }

    /** The distinct places [drawn] put a line, near-identical ones counted once. */
    private fun merged(drawn: List<PdfDrawing>, at: (PdfDrawing) -> Float): List<Float> {
        val places = drawn.map(at).sorted()
        if (places.isEmpty()) return emptyList()
        val out = mutableListOf(places.first())
        for (place in places) if (place - out.last() > SAME_LINE_PT) out += place
        return out
    }

    /**
     * The grid [across] and [down] draw on [page], filled with the lines of
     * text that stand in it, or null where what they draw is not a table of
     * words.
     */
    private fun gridOf(
        lines: List<PdfLine>,
        page: Int,
        across: List<Float>,
        down: List<Float>,
        uprights: List<PdfDrawing>,
    ): PdfTableDetector.Region? {
        val rows = across.size - 1
        val columns = down.size - 1
        if (rows < LEAST_BANDS || columns < LEAST_BANDS) return null
        // A row of a table is one line of text with a piece of it in each
        // cell, so it is the pieces that are placed, not the line: placed
        // by the line, every row of a two-column table lands in whichever
        // column its middle happens to fall in and the other comes back
        // empty.
        val cells = List(rows) { List(columns) { mutableListOf<Pair<Int, String>>() } }
        val held = mutableListOf<Int>()
        for ((at, line) in lines.withIndex()) {
            if (line.page != page) continue
            val row = across.indexOfLast { it < line.baselineY }
            if (row < 0 || row >= rows) continue
            val pieces = List(columns) { mutableListOf<PdfSegment>() }
            val segments = line.segments.ifEmpty { listOf(PdfSegment(line.text, line.x, line.xEnd)) }
            for (segment in segments) {
                val column = down.indexOfLast { it < (segment.xStart + segment.xEnd) / 2 }
                if (column in 0 until columns) pieces[column] += segment
            }
            var placed = false
            for (column in 0 until columns) {
                val own = pieces[column]
                if (own.isEmpty()) continue
                cells[row][column] += at to own.sortedBy { it.xStart }.joinToString(" ") { it.text }
                placed = true
            }
            if (placed) held += at
        }
        if (held.isEmpty()) return null
        // The lines of a grid are the lines between its first and its last:
        // anything else on the page between them was never inside it, and a
        // region that swallowed it would lose it.
        val first = held.min()
        val last = held.max()
        if (last - first + 1 != held.size) return null
        val filled = cells.sumOf { row -> row.count { it.isNotEmpty() } }
        if (filled < FILLED_SHARE * rows * columns) return null
        // A cell covers the columns beside it wherever the page drew no
        // side between them: a head written across a whole table, a label
        // set beside three rows. Kept as three cells with the words in the
        // middle one, a converted table has two blanks where a document
        // has none.
        val built = cells.mapIndexed { row, columnsOfRow ->
            val band = across[row] to across[row + 1]
            val cellsOfRow = mutableListOf<PdfSegment>()
            val spansOfRow = mutableListOf<Int>()
            var column = 0
            while (column < columns) {
                var span = 1
                while (column + span < columns && !sideAt(uprights, down[column + span], band)) span++
                val own = (column until column + span).flatMap { columnsOfRow[it] }
                cellsOfRow += PdfSegment(
                    text = LineJoiner.join(joinedByLine(own)),
                    xStart = down[column],
                    xEnd = down[column + span],
                )
                spansOfRow += span
                column += span
            }
            cellsOfRow to spansOfRow
        }
        return PdfTableDetector.Region(
            start = first,
            end = last + 1,
            rows = built.map { it.first },
            spans = built.map { it.second }.takeIf { spans -> spans.any { row -> row.any { it > 1 } } },
        )
    }

    /** The pieces of a cell, one entry per line of it, in the order they were read. */
    private fun joinedByLine(pieces: List<Pair<Int, String>>): List<String> =
        pieces.groupBy { it.first }.toSortedMap()
            .map { (_, own) -> own.joinToString(" ") { it.second } }

    /** Whether the page drew a side at [x] down most of the row [band]. */
    private fun sideAt(uprights: List<PdfDrawing>, x: Float, band: Pair<Float, Float>): Boolean {
        val height = band.second - band.first
        if (height <= 0f) return false
        return uprights.any { upright ->
            val middle = (upright.left + upright.right) / 2
            abs(middle - x) <= SAME_LINE_PT &&
                minOf(upright.bottom, band.second) - maxOf(upright.top, band.first) >= SIDE_SHARE * height
        }
    }

    /** A side must run this much of a row's height to be that row's side. */
    private const val SIDE_SHARE = 0.6f
}
