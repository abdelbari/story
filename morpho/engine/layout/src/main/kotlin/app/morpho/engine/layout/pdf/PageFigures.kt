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

    /**
     * Smaller than this in either direction and a mark is not a figure: a
     * rule, a tick, a bullet, the underline of a heading. A page draws a
     * great many of those and none of them is a picture — the paper this
     * was measured on tags the rule under its dates as a Figure, and
     * photographed it came out as a strip of ink one point tall.
     */
    const val LEAST_SIDE_PT = 24f

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
                .filterNot { figure -> drawnBehindWords(figure, linesByPage[page].orEmpty()) }
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
     * Whether [figure] was drawn behind the document's own words rather
     * than as a thing of its own — in which case photographing it would
     * put those words in the document twice, once as text and once inside
     * a picture of them.
     *
     * A chart holds words too: the years under its bars, the counts up its
     * axis, the names in its key. What tells the two apart is what the
     * words are doing there. Shading behind a table's head, a highlight
     * over a phrase and a box round a paragraph are each a single painted
     * shape with the document's prose lying across them, line by line,
     * each line as wide as the shape. A chart is dozens of strokes with a
     * few short labels among them.
     *
     * So: a single shape holding any word at all was drawn behind it. A
     * drawing of many strokes is a figure unless what stands in it reads
     * as prose — a line reaching across it, or more lines than a figure
     * labels itself with.
     */
    private fun drawnBehindWords(figure: PdfDrawing, lines: List<PdfLine>): Boolean {
        val held = lines.filter { line ->
            val centre = (line.x + line.xEnd) / 2
            line.baselineY > figure.top && line.baselineY < figure.bottom &&
                centre > figure.left && centre < figure.right
        }
        if (held.isEmpty()) return false
        if (figure.paths < STROKES_OF_A_FIGURE) return true
        if (held.size > LABELS_OF_A_FIGURE) return true
        return held.any { it.xEnd - it.x > LABEL_SHARE * figure.widthPt }
    }

    /** Fewer painted shapes than this and a drawing holding words is a backdrop to them. */
    private const val STROKES_OF_A_FIGURE = 4

    /** More lines than this inside a drawing and they are its subject, not its labels. */
    private const val LABELS_OF_A_FIGURE = 8

    /** A line reaching more of a drawing's width than this is prose across it, not a label in it. */
    private const val LABEL_SHARE = 0.55f

    private fun coversThePage(figure: PdfDrawing, sheet: PdfPageSheet): Boolean =
        figure.widthPt > MOST_OF_THE_PAGE * sheet.widthPt &&
            figure.heightPt > MOST_OF_THE_PAGE * sheet.heightPt
}
