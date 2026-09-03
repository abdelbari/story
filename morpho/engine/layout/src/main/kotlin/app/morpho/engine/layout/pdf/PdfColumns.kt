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
     * How many times a page may be cut apart: twice, so up to four
     * columns. A page set in more than four is a table drawn without rules
     * rather than a page of prose, and the check that each side fills the
     * measure it is set in is what tells the two apart.
     */
    const val DEEPEST_SPLIT = 2

    /**
     * The share of a band's lines that may run across a gutter and it
     * still be one: the headings set over some of the columns beside it.
     */
    private const val CROSSING_SHARE = 0.1f

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
        fun band(band: List<PdfLine>, gutters: List<Float>) {
            // Which column a line stands in is how many gutters it stands
            // past. A page read from the right takes its columns in the
            // other order, the rightmost first.
            val byColumn = band.groupBy { line -> gutters.count { line.x >= it } }
            val order = byColumn.keys.sorted().let { if (rightToLeft) it.reversed() else it }
            for (column in order) {
                for (line in byColumn.getValue(column)) flows[line] = next
                next++
            }
        }

        if (pageLines.size < LEAST_LINES) {
            for (line in pageLines) flows[line] = 0
            return
        }
        val gutters = guttersOf(pageLines, depth = 0)
        if (gutters.isEmpty()) {
            for (line in pageLines) flows[line] = 0
            return
        }
        // A line that crosses a gutter runs across the columns beside it,
        // and cuts the page into bands that are read one after the other.
        val current = mutableListOf<PdfLine>()
        for (line in pageLines) {
            if (gutters.any { line.x < it && line.xEnd > it }) {
                if (current.isNotEmpty()) {
                    band(current.toList(), gutters)
                    current.clear()
                }
                flows[line] = next
                next++
            } else {
                current += line
            }
        }
        if (current.isNotEmpty()) band(current.toList(), gutters)
    }

    /**
     * The x of a clear strip down the middle of a page's ink, or null when
     * the page is set in one column.
     *
     * [marks] is the horizontal extent of every mark on the page, word by
     * word or letter by letter — not line by line. A page whose columns
     * are set on the same grid paints both of them on the same baselines,
     * so every line of it reaches from the first column's margin to the
     * second's: read line by line, such a page has no clear strip at all,
     * and the columns can only be told apart by the marks themselves.
     *
     * The widest strip no mark crosses wins, and it is given back whole
     * rather than as its middle, because a line is only cut where the line
     * itself is clear right across it: a title, a heading over both
     * columns, a running head reaches into the strip and is one line.
     *
     * Both sides must hold a fair share of the marks, or a page with one
     * short column of notes beside a wide one would be cut in two.
     */
    fun gutterOfMarks(marksByLine: List<List<Pair<Float, Float>>>): Pair<Float, Float>? {
        val lines = marksByLine.filter { it.isNotEmpty() }
        val marks = lines.flatten()
        if (marks.size < LEAST_MARKS || lines.size < LEAST_LINES) return null
        val left = marks.minOf { it.first }
        val right = marks.maxOf { it.second }
        val width = right - left
        if (width <= 0f) return null
        val least = LEAST_GUTTER_SHARE * width
        val from = left + SEARCH_FROM * width
        val to = left + SEARCH_TO * width

        // Only a line that reaches across the middle of the page has
        // anything to say about a gutter: one set wholly in the left
        // column never meets it, and the foot of a page is often like
        // that, one column having run longer than the other.
        val middle = left + width / 2
        val reaching = lines.filter { line ->
            line.any { it.second <= middle } && line.any { it.first >= middle }
        }
        if (reaching.size < LEAST_LINES) return null

        // What each of them leaves clear across the middle. A line set in
        // columns leaves the whole gutter; a line that runs across the
        // page — a title, a heading over both columns, the running head —
        // leaves a word space at most.
        val gaps = reaching.mapNotNull { line -> widestMiddleGap(line, from, to) }
            .filter { it.second - it.first >= least }
        if (gaps.size < AGREEING_SHARE * reaching.size) return null

        // The strip the most of them agree on: where they overlap is where
        // the gutter is, and a line's own margins say where it stops.
        val best = gaps.maxByOrNull { candidate ->
            gaps.count { it.first < candidate.second && it.second > candidate.first }
        } ?: return null
        val agreeing = gaps.filter { it.first < best.second && it.second > best.first }
        if (agreeing.size < AGREEING_SHARE * reaching.size) return null
        val strip = agreeing.maxOf { it.first } to agreeing.minOf { it.second }
        if (strip.second - strip.first < least) return null

        val gutter = (strip.first + strip.second) / 2
        // The lines set in columns, which is every line but the few that
        // run across the page; they alone say whether this is two columns.
        val columned = lines.filterNot { line -> line.any { it.first < gutter && it.second > gutter } }
        if (columned.size < LEAST_LINES) return null
        // A column of prose fills its measure: its lines run to the far
        // margin and break where the words stop, and only the last line of
        // each paragraph falls short. A column of a table does not — its
        // cells hold what they hold. Without this a page that is mostly one
        // wide table of two columns would be cut into two columns of text
        // and stop being a table at all.
        if (!filled(columned, left, gutter) { it.second <= gutter }) return null
        if (!filled(columned, gutter, right) { it.first >= gutter }) return null
        return strip
    }

    /** The widest clear space a line leaves between [from] and [to], if any. */
    private fun widestMiddleGap(line: List<Pair<Float, Float>>, from: Float, to: Float): Pair<Float, Float>? {
        val sorted = line.sortedBy { it.first }
        var widest: Pair<Float, Float>? = null
        var reach = sorted.first().second
        for (mark in sorted.drop(1)) {
            if (mark.first > reach) {
                val gap = maxOf(reach, from) to minOf(mark.first, to)
                if (gap.second > gap.first &&
                    (widest == null || gap.second - gap.first > widest!!.second - widest!!.first)
                ) {
                    widest = gap
                }
            }
            reach = maxOf(reach, mark.second)
        }
        return widest
    }

    /** Of a page's lines, the share that must agree on a gap for it to be a gutter. */
    private const val AGREEING_SHARE = 0.5f

    /** Whether the lines on one side of a gutter fill the measure they are set in. */
    private fun filled(
        marksByLine: List<List<Pair<Float, Float>>>,
        from: Float,
        to: Float,
        onThisSide: (Pair<Float, Float>) -> Boolean,
    ): Boolean {
        val width = to - from
        if (width <= 0f) return false
        val widths = marksByLine
            .map { line -> line.filter(onThisSide) }
            .filter { it.isNotEmpty() }
            .map { it.maxOf { mark -> mark.second } - it.minOf { mark -> mark.first } }
            .sorted()
        if (widths.size < LEAST_LINES) return false
        val median = widths[widths.size / 2]
        return median >= FILLS_ITS_MEASURE * width
    }

    /** Below this many marks a page says too little about how it is set. */
    private const val LEAST_MARKS = 200

    /** Of the measure it is set in, the share a column of prose fills at the middle line. */
    private const val FILLS_ITS_MEASURE = 0.75f

    /**
     * The x of a strip down the middle of [lines] that few of them cross,
     * or null when the lines are set in one column. The widest such strip
     * wins, and its middle is the gutter.
     */
    /**
     * Every gutter of [lines], from the left of the page.
     *
     * A page of three columns is a page of two, one of which is a page of
     * two, so each side is asked the question again. Asked once, the two
     * columns on the far side of the one gutter it found were read as a
     * single column, line for line, so a sentence of one ran into a
     * sentence of the other.
     *
     * A line that crosses a gutter is not part of the columns it runs
     * across, so it is left out of what they are asked — it is the heading
     * over them, and it cuts the page into bands instead.
     */
    private fun guttersOf(lines: List<PdfLine>, depth: Int): List<Float> {
        if (depth >= DEEPEST_SPLIT) return emptyList()
        val gutter = gutterOf(lines) ?: return emptyList()
        return (
            guttersOf(lines.filter { it.xEnd <= gutter }, depth + 1) +
                listOf(gutter) +
                guttersOf(lines.filter { it.x >= gutter }, depth + 1)
            ).sorted()
    }

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
        // A heading set over two of a page's three columns crosses the
        // gutter between them and no other, so a gutter that no line at
        // all may cross is one such heading away from not being found —
        // and the columns under it are then read as one, a line of each in
        // turn. A few lines may cross; they are the headings, and they cut
        // the page into bands rather than belonging to a column.
        val mayCross = (CROSSING_SHARE * lines.size).toInt()
        // Where the page is clear: a probe almost no line's ink covers.
        var runStart: Float? = null
        var best: Pair<Float, Float>? = null
        var probe = from
        while (probe <= to) {
            val crossed = lines.count { it.x < probe && it.xEnd > probe } > mayCross
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
        if (before + after < lines.size - mayCross) return null
        if (before < LEAST_SIDE_SHARE * lines.size || after < LEAST_SIDE_SHARE * lines.size) return null
        return gutter
    }
}
