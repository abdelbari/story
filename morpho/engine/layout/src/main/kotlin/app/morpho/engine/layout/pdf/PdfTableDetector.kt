package app.morpho.engine.layout.pdf

import kotlin.math.abs
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
    )

    /** A line's segments merged into cells by gap analysis. */
    fun cellsOf(line: PdfLine): List<PdfSegment> {
        if (line.segments.isEmpty()) {
            return listOf(PdfSegment(line.text, line.x, line.x))
        }
        val mergeGap = (MERGE_GAP_FONT_FACTOR * line.maxFontSize)
            .coerceIn(MERGE_GAP_MIN_PT, MERGE_GAP_MAX_PT)
        val cells = mutableListOf<PdfSegment>()
        for (segment in line.segments.sortedBy { it.xStart }) {
            val last = cells.lastOrNull()
            if (last != null && segment.xStart - last.xEnd <= mergeGap) {
                cells[cells.size - 1] = PdfSegment(
                    text = last.text + " " + segment.text,
                    xStart = last.xStart,
                    xEnd = max(last.xEnd, segment.xEnd),
                )
            } else {
                cells += segment
            }
        }
        return cells
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
                regions += Region(start = i, end = j, rows = cells.subList(i, j).toList())
                i = j
            } else {
                i++
            }
        }
        return regions
    }

    private fun aligns(template: List<PdfSegment>, candidate: List<PdfSegment>): Boolean {
        if (candidate.size != template.size) return false
        return template.indices.all { column ->
            abs(template[column].xStart - candidate[column].xStart) <= START_TOLERANCE_PT
        }
    }
}
