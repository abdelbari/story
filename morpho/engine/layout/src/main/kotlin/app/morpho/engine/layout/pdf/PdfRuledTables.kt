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

    /**
     * Thicker than this and a drawn thing is a box, not a line of a grid.
     *
     * Public because recognition has to know it. A rule a page drew is
     * measured here off the PDF's own path, where its thickness is what
     * the producer asked for; a rule recognition found is the ink it saw
     * on a rendered page, which anti-aliasing makes thicker than the rule
     * underneath. So the hOCR reading caps its own rules at this rather
     * than letting a number about drawn ink decide about seen ink.
     */
    const val THIN_PT = 3f

    /** Shorter than this and a drawn line is a tick, not a side of a cell. */
    private const val LEAST_SIDE_PT = 8f

    /** Lines within this of each other are the one line, drawn cell by cell. */
    private const val SAME_LINE_PT = 2f

    /** Sides this close, end to end, are sides of the one grid. */
    private const val SAME_GRID_PT = 6f

    /** A grid of fewer cells than this either way is a box round something, not a table. */
    private const val LEAST_BANDS = 2

    /** This share of a grid's cells must hold words before it is a table of them. */
    private const val FILLED_SHARE = 0.4f

    /**
     * The most cells a grid may have before it is taken for a drawing.
     *
     * A page ruled cell by cell draws about one line for every cell, and
     * every cell is then measured against every line, so the work grows as
     * the square of the count. A table nobody reads — a plan, a map, a
     * sheet of graph paper — would spend a phone's afternoon being read as
     * one, and a reader must never be the reason a conversion hangs.
     */
    private const val MOST_CELLS = 20_000

    /**
     * The tables [drawings] show the page ruled, as regions over [lines].
     *
     * Empty where the page ruled none, which is most pages: the grid has to
     * have cells on both sides of a line each way, and enough of them have
     * to hold words, or a border round a figure would be read as a table of
     * one cell and the sheet's own frame as a table of the whole page.
     */
    fun of(
        lines: List<PdfLine>,
        drawings: List<PdfDrawing>,
        spelling: LineJoiner.Vocabulary = LineJoiner.Vocabulary.NONE,
    ): List<PdfTableDetector.Region> {
        if (lines.isEmpty() || drawings.isEmpty()) return emptyList()
        val out = mutableListOf<PdfTableDetector.Region>()
        for ((page, drawn) in drawings.groupBy { it.page }) {
            val uprights = drawn.filter { it.widthPt <= THIN_PT && it.heightPt >= LEAST_SIDE_PT }
            val levels = drawn.filter { it.heightPt <= THIN_PT && it.widthPt >= LEAST_SIDE_PT }
            for (grid in gridsOf(uprights, levels)) {
                val across = merged(grid.levels) { (it.top + it.bottom) / 2 }
                val down = merged(grid.uprights) { (it.left + it.right) / 2 }
                if (across.size <= LEAST_BANDS || down.size <= LEAST_BANDS) continue
                gridOf(lines, page, across, down, grid.uprights, grid.levels, spelling)
                    ?.let { out += it }
            }
        }
        return out.sortedBy { it.start }
    }

    /** Where a grid stands, in points from the top left of its page. */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Where each grid the [drawings] of one page rule stands.
     *
     * The geometry on its own, with no words in it, because recognition
     * has to know where a grid is before there is anything to put in one:
     * a page that is nothing but a table is handed over column by column,
     * and the cells are gathered back into rows inside the grid they
     * belong to. Held to the same count of lines each way as a table read
     * from a grid, so what is not a grid here is not a grid there.
     */
    fun rectanglesOf(drawings: List<PdfDrawing>): List<Rect> {
        val uprights = drawings.filter { it.widthPt <= THIN_PT && it.heightPt >= LEAST_SIDE_PT }
        val levels = drawings.filter { it.heightPt <= THIN_PT && it.widthPt >= LEAST_SIDE_PT }
        return gridsOf(uprights, levels)
            .filter { grid ->
                merged(grid.levels) { (it.top + it.bottom) / 2 }.size > LEAST_BANDS &&
                    merged(grid.uprights) { (it.left + it.right) / 2 }.size > LEAST_BANDS
            }
            .map { grid ->
                Rect(
                    left = grid.uprights.minOf { it.left },
                    top = grid.uprights.minOf { it.top },
                    right = grid.uprights.maxOf { it.right },
                    bottom = grid.uprights.maxOf { it.bottom },
                )
            }
    }

    /** The sides and levels of one grid, apart from every other grid's. */
    private class Grid(val uprights: List<PdfDrawing>, val levels: List<PdfDrawing>)

    /**
     * The separate grids [uprights] and [levels] draw on the one page.
     *
     * A page may rule two tables with a paragraph between them, and the
     * two are not one table with the paragraph as a row of it: read as
     * one grid, the rectangle reaches from the head of the first to the
     * foot of the second, every line between stands inside it, and the
     * paragraph comes back as cells.
     *
     * A grid's sides are the ones that stand together: a table's sides
     * all run down the same part of the page, so they overlap each other
     * there, and the sides of a table further down do not reach them.
     * Sides drawn cell by cell meet end to end rather than overlapping,
     * so they are taken as together when they come within [SAME_GRID_PT],
     * which is far less than a table is ever set from its neighbour.
     * Each level then belongs to the grid it stands in and reaches
     * across; one that stands in none — a rule under a heading, the line
     * over a footer — belongs to no table and is left where it is.
     *
     * Tables set beside each other stand at the same height, so their
     * sides fall in the one band and [apart] divides them again.
     */
    private fun gridsOf(uprights: List<PdfDrawing>, levels: List<PdfDrawing>): List<Grid> {
        if (uprights.isEmpty() || levels.isEmpty()) return emptyList()
        val bands = mutableListOf<MutableList<PdfDrawing>>()
        for (upright in uprights.sortedBy { it.top }) {
            val last = bands.lastOrNull()
            if (last != null && upright.top - last.maxOf { it.bottom } <= SAME_GRID_PT) last += upright
            else bands += mutableListOf(upright)
        }
        return bands.flatMap { apart(it, levels) }
    }

    /**
     * The grids a [band] of sides at the one height holds.
     *
     * One, on nearly every page: a table's lines across run from one of
     * its sides to the next, so every side is tied to the side beside it
     * and the whole band is the one grid. Two tables set beside each
     * other rule their own lines and neither's reach the other's, so
     * nothing ties the last side of the first to the first side of the
     * second, and the band divides there.
     *
     * It is the reach of the lines across that says this, not the space
     * between the sides: a table may have a column wider than the gap
     * between two tables, and a page that rules cell by cell draws each
     * line across one cell wide, which ties each side to the next just
     * the same.
     */
    private fun apart(band: List<PdfDrawing>, levels: List<PdfDrawing>): List<Grid> {
        val top = band.minOf { it.top } - SAME_GRID_PT
        val bottom = band.maxOf { it.bottom } + SAME_GRID_PT
        val own = levels.filter { (it.top + it.bottom) / 2 in top..bottom }
        if (own.isEmpty()) return emptyList()
        val places = merged(band) { (it.left + it.right) / 2 }
        val cuts = mutableListOf(0)
        for (at in 1 until places.size) {
            val tied = own.any {
                it.left <= places[at - 1] + SAME_GRID_PT && it.right >= places[at] - SAME_GRID_PT
            }
            if (!tied) cuts += at
        }
        cuts += places.size
        return (0 until cuts.size - 1).mapNotNull { grid ->
            val from = places[cuts[grid]] - SAME_GRID_PT
            val to = places[cuts[grid + 1] - 1] + SAME_GRID_PT
            val sides = band.filter { (it.left + it.right) / 2 in from..to }
            val across = own.filter { it.right >= from && it.left <= to }
            if (sides.isEmpty() || across.isEmpty()) null else Grid(sides, across)
        }
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
        levels: List<PdfDrawing>,
        spelling: LineJoiner.Vocabulary,
    ): PdfTableDetector.Region? {
        val rows = across.size - 1
        val columns = down.size - 1
        if (rows < LEAST_BANDS || columns < LEAST_BANDS) return null
        if (rows.toLong() * columns > MOST_CELLS) return null
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
                // In the order the cell is read, not the order it is
                // painted: the pieces come across the page left to right
                // whatever the words do, and an Arabic cell left that way
                // is a sentence written backwards.
                cells[row][column] += at to PdfTableDetector
                    .inReadingOrder(own.sortedBy { it.xStart })
                    .joinToString(" ") { it.text }
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
        // A cell covers whatever the page drew no line between: a head
        // written across a whole table, a label set beside three rows.
        // Kept as separate cells, a converted table has blanks where the
        // document has none.
        val sides = Array(rows) { row ->
            BooleanArray(columns + 1) { at -> sideAt(uprights, down[at], across[row] to across[row + 1]) }
        }
        val levelsAt = Array(rows + 1) { row ->
            BooleanArray(columns) { at -> levelAt(levels, across[row], down[at] to down[at + 1]) }
        }
        val taken = Array(rows) { BooleanArray(columns) }
        val builtRows = mutableListOf<List<PdfSegment>>()
        val builtSpans = mutableListOf<List<PdfTableDetector.Span>>()
        var merged = false
        for (row in 0 until rows) {
            val cellsOfRow = mutableListOf<PdfSegment>()
            val spansOfRow = mutableListOf<PdfTableDetector.Span>()
            var column = 0
            while (column < columns) {
                if (taken[row][column]) {
                    column++
                    continue
                }
                var wide = 1
                while (column + wide < columns && !taken[row][column + wide] && !sides[row][column + wide]) wide++
                var tall = 1
                while (row + tall < rows &&
                    (column until column + wide).none { levelsAt[row + tall][it] } &&
                    (column until column + wide).none { taken[row + tall][it] }
                ) {
                    tall++
                }
                for (down1 in row until row + tall) {
                    for (across1 in column until column + wide) taken[down1][across1] = true
                }
                val own = (row until row + tall).flatMap { held ->
                    (column until column + wide).flatMap { cells[held][it] }
                }
                cellsOfRow += PdfSegment(
                    text = LineJoiner.join(joinedByLine(own), spelling),
                    xStart = down[column],
                    xEnd = down[column + wide],
                )
                spansOfRow += PdfTableDetector.Span(columns = wide, rows = tall)
                if (wide > 1 || tall > 1) merged = true
                column += wide
            }
            builtRows += cellsOfRow
            builtSpans += spansOfRow
        }
        return PdfTableDetector.Region(
            start = first,
            end = last + 1,
            rows = builtRows,
            spans = builtSpans.takeIf { merged },
        )
    }

    /** Whether the page drew a line at [y] across most of the column [band]. */
    private fun levelAt(levels: List<PdfDrawing>, y: Float, band: Pair<Float, Float>): Boolean {
        val width = band.second - band.first
        if (width <= 0f) return false
        return levels.any { level ->
            val middle = (level.top + level.bottom) / 2
            abs(middle - y) <= SAME_LINE_PT &&
                minOf(level.right, band.second) - maxOf(level.left, band.first) >= SIDE_SHARE * width
        }
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
