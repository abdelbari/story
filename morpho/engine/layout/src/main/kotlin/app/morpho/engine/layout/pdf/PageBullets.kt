package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.ListLabels
import kotlin.math.abs

/**
 * A list whose markers the page draws rather than writes.
 *
 * A browser printing a page draws its bullets: `list-style: disc` is a
 * filled circle painted beside the item, not a character in the text, so
 * nothing a reader extracts says the item is an item at all. Its lines are
 * then evenly spaced and evenly set, which is what a paragraph looks like,
 * and a printed web page's list comes back as one paragraph of run-on
 * sentences.
 *
 * What the page did draw is a small mark, the same size in the same place,
 * beside line after line. That is a list, and the honest thing to do with
 * it is to put back the bullet the page shows — after which everything
 * downstream reads the line as what it is: the label ends one item and
 * begins the next, the writers set the marker the page set, and a reader
 * comparing the two files sees the same thing on both.
 */
object PageBullets {

    /** A mark bigger than this either way is a picture, not a bullet. */
    private const val LARGEST_PT = 9f

    /** A mark longer than this many times its other side is a rule, not a bullet. */
    private const val SQUARENESS = 2.5f

    /** How far in front of a line a marker may stand, in type sizes. */
    private const val REACH = 3f

    /** How far off a line's middle a marker may sit, in type sizes. */
    private const val ON_THE_LINE = 0.7f

    /** Marks in the same place beside this many lines are a list. */
    private const val ITEMS_OF_A_LIST = 2

    /** Markers within this of each other stand in the same place. */
    private const val SAME_PLACE_PT = 3f

    /** What is written back for a mark the page drew. */
    private const val BULLET = "• "

    /**
     * [lines] with the bullet put back on every line a marker was drawn
     * beside, where the markers repeat as a list's do.
     *
     * The lines come back untouched where the page wrote its own bullets,
     * which is what a word processor does, and where what it drew beside
     * them was not a list.
     */
    fun marked(lines: List<PdfLine>, drawings: List<PdfDrawing>): List<PdfLine> {
        if (lines.isEmpty() || drawings.isEmpty()) return lines
        val marks = drawings.filter {
            val long = maxOf(it.widthPt, it.heightPt)
            val short = minOf(it.widthPt, it.heightPt)
            long in 0.5f..LARGEST_PT && short > 0f && long / short <= SQUARENESS
        }
        if (marks.isEmpty()) return lines
        val beside = HashMap<Int, Float>()
        for ((at, line) in lines.withIndex()) {
            if (line.text.isEmpty() || ListLabels.opensWithLabel(line.text)) continue
            markerFor(line, marks)?.let { beside[at] = it }
        }
        if (beside.size < ITEMS_OF_A_LIST) return lines
        // A marker on its own is a mark on the page; markers standing in
        // the same place beside line after line are a list.
        val repeats = beside.values
            .groupBy { (it / SAME_PLACE_PT).toInt() }
            .filterValues { it.size >= ITEMS_OF_A_LIST }
            .keys
        if (repeats.isEmpty()) return lines
        return lines.mapIndexed { at, line ->
            val edge = beside[at] ?: return@mapIndexed line
            if ((edge / SAME_PLACE_PT).toInt() !in repeats) return@mapIndexed line
            val rtl = line.xEnd < edge
            line.copy(
                text = BULLET + line.text,
                runs = if (line.runs.isEmpty()) line.runs else {
                    listOf(PdfRun(BULLET, line.runs.first().look)) + line.runs
                },
                x = if (rtl) line.x else minOf(line.x, edge),
                xEnd = if (rtl) maxOf(line.xEnd, edge) else line.xEnd,
            )
        }
    }

    /**
     * Where the marker beside [line] stands — its outer edge — or null
     * where nothing was drawn beside it.
     *
     * The marker of a right-to-left item stands at the right of its line
     * and of a left-to-right one at the left, so both ends are asked and
     * whichever answers is the one the page used.
     */
    private fun markerFor(line: PdfLine, marks: List<PdfDrawing>): Float? {
        val size = line.maxFontSize
        if (size <= 0f) return null
        val middle = line.baselineY - size / 3f
        return marks.firstOrNull { mark ->
            mark.page == line.page &&
                abs((mark.top + mark.bottom) / 2 - middle) <= ON_THE_LINE * size &&
                (line.x - mark.right in 0f..REACH * size || mark.left - line.xEnd in 0f..REACH * size)
        }?.let { mark -> if (mark.right <= line.x) mark.left else mark.right }
    }
}
