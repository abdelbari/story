package app.morpho.engine.layout.pdf

/**
 * What a page draws rather than places.
 *
 * A chart, a diagram, an organisation tree, a signature: a spreadsheet, a
 * word processor and every drawing tool export one as paths, not as a
 * picture the file holds. A reader that gathers only the pictures finds
 * none of it, so the text of a report converts and every figure in it
 * vanishes — the one loss a reader cannot see, because what is missing
 * leaves no gap in the words.
 *
 * Not every painted path is a figure. A page draws rules between the rows
 * of a table, shading behind its head, a highlight over a word, a border
 * round the whole sheet — and all of those are drawn *behind or beside*
 * the document's own words. A figure is not: it stands in the reading
 * with nothing of the text inside it. That is the whole of the test here,
 * and it is what keeps a table from being photographed as a picture of
 * itself.
 */
object PageFigures {

    /** Paths this close together are one drawing: a chart is many strokes, not one. */
    private const val SAME_FIGURE_PT = 12f

    /** Smaller than this in either direction and a mark is an ornament, not a figure. */
    private const val LEAST_SIDE_PT = 24f

    /** A drawing covering more of the sheet than this is the page itself, not a figure on it. */
    private const val MOST_OF_THE_PAGE = 0.7f

    /** Clear space kept around a figure, so no stroke of it is cut. */
    private const val MARGIN_PT = 2f

    /**
     * The figures [drawings] amount to on each page: near-touching paths
     * gathered into one, anything holding a word of [lines] thrown out,
     * and what is left grown by a hair so no stroke sits on the edge.
     *
     * [sheets] says how big each page is, which is how a border round the
     * whole of one is told from a box drawn on it.
     */
    fun of(
        drawings: List<PdfDrawing>,
        lines: List<PdfLine>,
        sheets: List<PdfPageSheet>,
    ): List<PdfDrawing> {
        if (drawings.isEmpty()) return emptyList()
        val linesByPage = lines.groupBy { it.page }
        val sheetByPage = sheets.associateBy { it.page }
        return drawings.groupBy { it.page }.toSortedMap().flatMap { (page, onPage) ->
            val sheet = sheetByPage[page]
            gathered(onPage)
                .filter { it.widthPt >= LEAST_SIDE_PT && it.heightPt >= LEAST_SIDE_PT }
                .filter { figure -> sheet == null || !coversThePage(figure, sheet) }
                .filterNot { figure -> holdsAWord(figure, linesByPage[page].orEmpty()) }
                .map {
                    PdfDrawing(
                        page = it.page,
                        left = it.left - MARGIN_PT,
                        top = it.top - MARGIN_PT,
                        right = it.right + MARGIN_PT,
                        bottom = it.bottom + MARGIN_PT,
                    )
                }
        }
    }

    /**
     * The drawings of one page, with every group of near-touching boxes
     * become the one box that holds them. A bar chart is thirty paths and
     * one figure.
     */
    private fun gathered(drawings: List<PdfDrawing>): List<PdfDrawing> {
        val out = drawings.toMutableList()
        var joined = true
        while (joined) {
            joined = false
            outer@ for (i in out.indices) {
                for (j in i + 1 until out.size) {
                    if (!out[i].near(out[j], SAME_FIGURE_PT)) continue
                    out[i] = out[i].with(out[j])
                    out.removeAt(j)
                    joined = true
                    break@outer
                }
            }
        }
        return out
    }

    /**
     * Whether the document's own words stand inside [figure]. A rule under
     * a heading holds none and is thrown out for its thinness; shading
     * behind a table's head holds the head; a border round a page holds
     * the page. What holds words was drawn to sit behind or beside them,
     * and photographing it would put the words in the document twice.
     */
    private fun holdsAWord(figure: PdfDrawing, lines: List<PdfLine>): Boolean = lines.any { line ->
        val middle = line.baselineY
        val centre = (line.x + line.xEnd) / 2
        middle > figure.top && middle < figure.bottom && centre > figure.left && centre < figure.right
    }

    private fun coversThePage(figure: PdfDrawing, sheet: PdfPageSheet): Boolean =
        figure.widthPt > MOST_OF_THE_PAGE * sheet.widthPt &&
            figure.heightPt > MOST_OF_THE_PAGE * sheet.heightPt
}
