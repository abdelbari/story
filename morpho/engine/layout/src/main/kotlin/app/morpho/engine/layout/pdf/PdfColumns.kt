package app.morpho.engine.layout.pdf

/**
 * The columns a page is set in.
 *
 * A journal sets its text in two columns; a PDF paints it in whatever
 * order it likes and says nothing about the arrangement. A reader that
 * takes the lines down the page reads across the gutter — the first line
 * of the left column, then the first of the right — and hands back a
 * sentence that was never written.
 *
 * The gutter is what gives the page away: a strip down the middle of the
 * text that no line of the body crosses. Lines that do cross it are
 * full-width — a title, a heading over both columns, a caption — and they
 * cut the page into bands, each of which is read column by column.
 *
 * Every line comes back with the number of the run of text it belongs to:
 * lines of one run are read in the order they sit, and runs in the order
 * they are numbered. A page in one column has one run and nothing changes.
 */
object PdfColumns {

    /** A gutter narrower than this share of the text's width is a word space, not a column break. */
    private const val LEAST_GUTTER_SHARE = 0.035f

    /** Each side of a gutter must hold this share of the band's lines for it to be a gutter at all. */
    private const val LEAST_SIDE_SHARE = 0.25f

    /** Below this many lines a page says too little about how it is set. */
    private const val LEAST_LINES = 8

    /** The strip is looked for across this middle part of the text's width. */
    private const val SEARCH_FROM = 0.25f
    private const val SEARCH_TO = 0.75f

    /** The x positions the gutter is tried at, across the search range. */
    private const val PROBES = 48

    /**
     * The run of text each line belongs to, by the order it should be read
     * in. Lines of a page in one column all answer 0.
     */
    fun flows(lines: List<PdfLine>, rightToLeft: Boolean): Map<PdfLine, Int> {
        val flows = HashMap<PdfLine, Int>()
        for ((_, pageLines) in lines.groupBy { it.page }) {
            assign(pageLines.sortedBy { it.baselineY }, rightToLeft, flows)
        }
        return flows
    }

    /**
     * The run of text something drawn at [topY] on [page] belongs to: the
     * run of the last line above it, which is the text it follows. A
     * picture drawn before any text on its page belongs to the first run.
     */
    fun flowOf(flows: Map<PdfLine, Int>, page: Int, topY: Float): Int {
        val pageLines = flows.keys.filter { it.page == page }
        if (pageLines.isEmpty()) return 0
        val above = pageLines.filter { it.baselineY <= topY }.maxByOrNull { it.baselineY }
        val line = above ?: pageLines.minByOrNull { it.baselineY }
        return line?.let { flows[it] } ?: 0
    }

    private fun assign(pageLines: List<PdfLine>, rightToLeft: Boolean, flows: MutableMap<PdfLine, Int>) {
        var next = 0
        fun band(band: List<PdfLine>) {
            val gutter = gutterOf(band)
            if (gutter == null) {
                for (line in band) flows[line] = next
                next++
                return
            }
            val first = next
            val second = next + 1
            for (line in band) {
                val nearer = if (line.xEnd <= gutter) first else second
                flows[line] = if (rightToLeft) (if (nearer == first) second else first) else nearer
            }
            next += 2
        }

        if (pageLines.size < LEAST_LINES) {
            for (line in pageLines) flows[line] = 0
            return
        }
        val gutter = gutterOf(pageLines)
        if (gutter == null) {
            for (line in pageLines) flows[line] = 0
            return
        }
        // A line that crosses the gutter is full-width, and cuts the page
        // into bands that are read one after the other.
        val current = mutableListOf<PdfLine>()
        for (line in pageLines) {
            if (line.x < gutter && line.xEnd > gutter) {
                if (current.isNotEmpty()) {
                    band(current.toList())
                    current.clear()
                }
                flows[line] = next
                next++
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) band(current.toList())
    }

    /**
     * The x of a strip down the middle of [lines] that few of them cross,
     * or null when the lines are set in one column. The widest such strip
     * wins, and its middle is the gutter.
     */
    private fun gutterOf(lines: List<PdfLine>): Float? {
        if (lines.size < LEAST_LINES) return null
        val left = lines.minOf { it.x }
        val right = lines.maxOf { it.xEnd }
        val width = right - left
        if (width <= 0f) return null
        val from = left + SEARCH_FROM * width
        val to = left + SEARCH_TO * width
        val step = (to - from) / PROBES
        if (step <= 0f) return null
        // Where the page is clear: a probe no line's ink covers.
        var runStart: Float? = null
        var best: Pair<Float, Float>? = null
        var probe = from
        while (probe <= to) {
            val crossed = lines.any { it.x < probe && it.xEnd > probe }
            if (!crossed) {
                if (runStart == null) runStart = probe
            } else if (runStart != null) {
                val run = runStart to probe
                if (best == null || run.second - run.first > best!!.second - best!!.first) best = run
                runStart = null
            }
            probe += step
        }
        if (runStart != null) {
            val run = runStart to to
            if (best == null || run.second - run.first > best!!.second - best!!.first) best = run
        }
        val strip = best ?: return null
        if (strip.second - strip.first < LEAST_GUTTER_SHARE * width) return null
        val gutter = (strip.first + strip.second) / 2
        val before = lines.count { it.xEnd <= gutter }
        val after = lines.count { it.x >= gutter }
        if (before + after < lines.size) return null
        if (before < LEAST_SIDE_SHARE * lines.size || after < LEAST_SIDE_SHARE * lines.size) return null
        return gutter
    }
}
