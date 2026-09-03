package app.morpho.engine.layout

import kotlin.math.floor

/**
 * Where a tab lands.
 *
 * A paragraph names the stops it was set with, and a tab goes to the first
 * one past where the text has reached. Past the last of them — and in a
 * paragraph that names none, which is most of them — it goes to the next
 * multiple of the document's default stop, measured from the start margin.
 * That default is half an inch in Word, which is where the 36 points come
 * from.
 *
 * The rule is here because the app had three of it and none was Word's:
 * the page's running head advanced a tab by 36 points from wherever the
 * text had reached, so a tab after a long word landed somewhere no
 * multiple of anything falls; the body handed the stops to the platform's
 * own text layout, which past the last declared one falls back to a
 * built-in increment of twenty — not points, not Word's, and nothing in
 * the app said so. A form is mostly tabs, and a form converted with its
 * tabs a third of an inch out is a form with nothing lined up.
 */
object TabStops {

    /** Word's own default tab stop: half an inch. */
    const val DEFAULT_PT = 36f

    /**
     * Where a tab lands from [at], given the [declared] stops.
     *
     * [at] is measured from the start of the text — the left edge of a
     * left-to-right paragraph, the right edge of a right-to-left one —
     * which is what the declared stops are measured from too.
     */
    fun next(at: Float, declared: List<Float> = emptyList(), defaultPt: Float = DEFAULT_PT): Float {
        val past = declared.filter { it > 0f }.sorted().firstOrNull { it > at }
        if (past != null) return past
        val step = if (defaultPt > 0f) defaultPt else DEFAULT_PT
        // The next multiple of the default, not the default added on: a
        // tab is a column, and a column does not move because the word
        // before it was long. A tab standing exactly on a stop goes to the
        // one after it, which is what a reader pressing tab twice means.
        val reached = if (at > 0f) at else 0f
        return (floor(reached / step) + 1) * step
    }

    /**
     * Every stop a line [widthPt] wide can reach: the ones [declared],
     * then the defaults past the last of them.
     *
     * A text layout that is given the stops rather than asked one at a
     * time needs them all in front of it, since what it does past the
     * last one it is given is its own business and not Word's.
     */
    fun through(
        widthPt: Float,
        declared: List<Float> = emptyList(),
        defaultPt: Float = DEFAULT_PT,
    ): List<Float> {
        if (widthPt <= 0f) return emptyList()
        val named = declared.filter { it > 0f && it <= widthPt }.distinct().sorted()
        val step = if (defaultPt > 0f) defaultPt else DEFAULT_PT
        val out = named.toMutableList()
        var at = named.lastOrNull() ?: 0f
        // Bounded by the width, so a wide sheet and a small default cannot
        // ask for more stops than a line has room for.
        while (true) {
            val stop = (floor(at / step) + 1) * step
            if (stop > widthPt) break
            out += stop
            at = stop
        }
        return out
    }
}
