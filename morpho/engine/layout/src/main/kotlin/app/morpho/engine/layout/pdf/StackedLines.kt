package app.morpho.engine.layout.pdf

/**
 * Where a column of lines can be cut so that what does not fit on this
 * page carries on to the next.
 *
 * A page exporter draws a table row into a band of the page and moves on;
 * a row taller than the page is then drawn into a band taller than the
 * page, and everything past the bottom edge is simply gone — a contract's
 * notes column, a syllabus, the one long cell a CV puts its history in.
 * The rest of the row has to continue on the next page instead, and the
 * cut has to fall between lines: half a line of Arabic at the foot of a
 * page is worse than none.
 *
 * Kept here, apart from the drawing, because it is the part that can be
 * wrong in ways nobody sees until a document is printed — and the part a
 * loop that never ends would be written into.
 */
object StackedLines {

    /**
     * How much of a column of lines can be drawn now: the bottom edge of
     * the last whole line that fits in [room], starting from [from] — the
     * height already drawn on the pages before this one.
     *
     * [lineBottoms] is the bottom edge of every line, from the top of the
     * column, in ascending order.
     *
     * The answer is always past [from] while any line is left, even when
     * the line does not fit at all: a line taller than a whole page is
     * drawn and clipped rather than left to be tried again on page after
     * page for ever. When nothing is left, the answer is [from] itself.
     */
    fun cut(lineBottoms: List<Float>, from: Float, room: Float): Float {
        var cut = from
        for (bottom in lineBottoms) {
            if (bottom <= from) continue
            if (bottom - from > room) break
            cut = bottom
        }
        if (cut > from) return cut
        return lineBottoms.firstOrNull { it > from } ?: from
    }

    /** Whether any of [lineBottoms] is still to be drawn past [from]. */
    fun more(lineBottoms: List<Float>, from: Float): Boolean =
        (lineBottoms.lastOrNull() ?: 0f) > from
}
