package app.morpho.engine.layout.pdf

import kotlin.math.abs
import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.TextDirection
import kotlin.math.max

/**
 * Detects table regions in untagged PDFs from line segment geometry: a table
 * is a run of at least [MIN_ROWS] consecutive lines on one page that each
 * split into the same [MIN_COLS]-or-more cells, with cell start positions
 * aligned across the run within [START_TOLERANCE_PT].
 *
 * Cells come from merging a line's segments: a gap narrower than roughly a
 * space (relative to the font size) joins segments into one cell; anything
 * wider starts a new cell. Prose survives this honestly — ordinary word gaps
 * merge into a single cell, and justified text's stretched gaps vary from
 * line to line, so their cell starts never align across consecutive lines.
 */
object PdfTableDetector {

    const val MIN_ROWS = 2
    const val MIN_COLS = 2
    private const val START_TOLERANCE_PT = 6f
    private const val MERGE_GAP_FONT_FACTOR = 1.0f
    private const val MERGE_GAP_MIN_PT = 3f
    private const val MERGE_GAP_MAX_PT = 14f

    /** A detected table: [start, end) indices into the line list, plus rows of cells. */
    data class Region(
        val start: Int,
        val end: Int,
        val rows: List<List<PdfSegment>>,
        /**
         * How much of the table each cell covers, where the page said so —
         * a head written across the whole table, a label set beside three
         * rows. Null for a table found by the alignment of its cells,
         * which cannot see a merge: every cell of it covers one place, and
         * [rows] holds them all.
         *
         * Where it is given, [rows] holds only the cells that begin, as a
         * document's own rows do, so a row every cell of which is covered
         * from above holds none.
         */
        val spans: List<List<Span>>? = null,
    )

    /** How many columns and how many rows one cell of a region covers. */
    data class Span(val columns: Int = 1, val rows: Int = 1)

    /** A line's segments merged into cells by gap analysis. */
    fun cellsOf(line: PdfLine): List<PdfSegment> {
        if (line.segments.isEmpty()) {
            return listOf(PdfSegment(line.text, line.x, line.x))
        }
        val mergeGap = (MERGE_GAP_FONT_FACTOR * line.maxFontSize)
            .coerceIn(MERGE_GAP_MIN_PT, MERGE_GAP_MAX_PT)
        // Which pieces make one cell is a question about the page, so it
        // is settled by walking across the page; what the cell then says
        // is a question about the words, and is settled separately.
        val held = mutableListOf<MutableList<PdfSegment>>()
        var reach = 0f
        for (segment in line.segments.sortedBy { it.xStart }) {
            val last = held.lastOrNull()
            if (last != null && segment.xStart - reach <= mergeGap) {
                last += segment
                reach = max(reach, segment.xEnd)
            } else {
                held += mutableListOf(segment)
                reach = segment.xEnd
            }
        }
        return held.map(::cellOf)
    }

    /** The one piece [pieces] make between them, saying what they say. */
    private fun cellOf(pieces: List<PdfSegment>) = PdfSegment(
        text = inReadingOrder(pieces).joinToString(" ") { it.text },
        xStart = pieces.first().xStart,
        xEnd = pieces.maxOf { it.xEnd },
    )

    /**
     * [pieces] of one cell in the order they are read, given in the order
     * they sit on the page.
     *
     * Every reader hands a line's pieces over left to right, because what
     * they are for is the page: the gaps between them are what a column is
     * found from and what tells one cell from the next. The line's own text
     * is a different thing, put back into the order it is read.
     *
     * A cell's text is built out of the pieces rather than cut out of the
     * line, and left as they came it is an Arabic cell written backwards:
     * "الاستمارة في البحث" — the form in research — comes back as "البحث في
     * الاستمارة", research in the form. Every word of it is right and the
     * sentence is not, which is the kind of wrong a reader has to read
     * twice to catch.
     *
     * The pieces of the cell decide, not the line they came from: a name
     * or an address set in Latin inside an Arabic table is read the way it
     * is written, and a cell of one piece has no order to get wrong.
     */
    fun inReadingOrder(pieces: List<PdfSegment>): List<PdfSegment> = when {
        pieces.size < 2 -> pieces
        Bidi.dominantDirection(pieces.joinToString(" ") { it.text }) != TextDirection.RTL -> pieces
        else -> pieces.asReversed()
    }

    /** Non-overlapping table regions, in line order. */
    fun detect(lines: List<PdfLine>): List<Region> {
        val regions = mutableListOf<Region>()
        val cells = lines.map(::cellsOf)
        var i = 0
        while (i < lines.size) {
            val template = cells[i]
            if (template.size < MIN_COLS) {
                i++
                continue
            }
            var j = i + 1
            while (j < lines.size &&
                lines[j].page == lines[i].page &&
                aligns(template, cells[j])
            ) {
                j++
            }
            if (j - i >= MIN_ROWS) {
                // The rows that stand in the same columns without being
                // set the same way: a head centred over them, a line of
                // totals ranged right under them. A table is never
                // founded on that reading — two lines with a wide gap
                // each are not a table because the gaps happen to
                // overlap — but a table already proved by rows that line
                // up exactly may take in the rows around it that share
                // its columns.
                val floor = regions.lastOrNull()?.end ?: 0
                var from = i
                while (from > floor && lines[from - 1].page == lines[i].page &&
                    sameColumns(cells[i], cells[from - 1])
                ) {
                    from--
                }
                var to = j
                while (to < lines.size && lines[to].page == lines[i].page &&
                    sameColumns(cells[i], cells[to])
                ) {
                    to++
                }
                regions += Region(start = from, end = to, rows = cells.subList(from, to).toList())
                i = to
            } else {
                i++
            }
        }
        return regions
    }

    private fun aligns(template: List<PdfSegment>, candidate: List<PdfSegment>): Boolean {
        if (candidate.size != template.size) return false
        if (template.indices.all { column ->
                abs(template[column].xStart - candidate[column].xStart) <= START_TOLERANCE_PT
            }
        ) {
            return true
        }
        return false
    }

    /**
     * Whether two rows stand in the same columns without being set the
     * same way — a head centred over figures ranged right, a line of
     * totals under columns of prose.
     *
     * Where the cells themselves are is the wrong question: a centred head
     * and a ranged-right figure need not overlap by a point. What the two
     * rows share is the clear space between their cells. The gutter of a
     * column is where neither row put any ink, and two rows stand in the
     * same columns when their gutters do — which reads the same whether a
     * cell is centred, ranged left or ranged right.
     *
     * This is a looser reading than [aligns] and is only ever used to take
     * in the rows around a table that rows lining up exactly have already
     * proved. On its own it would call any two lines with a wide gap in
     * them a table.
     */
    private fun sameColumns(template: List<PdfSegment>, candidate: List<PdfSegment>): Boolean {
        if (candidate.size != template.size) return false
        val here = gutters(template)
        val there = gutters(candidate)
        return here.indices.all { at ->
            here[at].first < there[at].second && there[at].first < here[at].second
        }
    }

    /** The clear space between each pair of neighbouring cells, left to right. */
    private fun gutters(cells: List<PdfSegment>): List<Pair<Float, Float>> =
        (0 until cells.size - 1).map { cells[it].xEnd to cells[it + 1].xStart }
}
