package app.morpho.engine.layout

/**
 * Whether a stretch of text has finished saying something.
 *
 * Both readings ask this, for the same reason and about the same thing: a
 * line that stops mid-sentence has not reached the end of its paragraph,
 * whatever the page did after it. The reading of a laid-out page asks it
 * of a line that stopped short of its margin; the reading of a scanned
 * one asks it of the last words on a page, where there is no margin to
 * measure and nothing else to go on.
 *
 * Kept in one place so the two cannot drift: a full stop that ends a
 * sentence for one reading and not for the other would put a paragraph
 * break in a converted document that depends on which path read it.
 */
object Sentences {

    /** What a sentence stops on, in the scripts this app converts. */
    private const val ENDS = ".:!?\u061F\u06D4\u2026"

    /** What may be closed after the stop and still leave the sentence finished. */
    private const val CLOSERS = ")]}\u00BB\u203A\u0022\u0027\u201D\u2019"

    /** Whether [text] reaches the end of a sentence rather than stopping mid-way. */
    fun finishes(text: String): Boolean {
        val trimmed = text.trimEnd().trimEnd { it in CLOSERS }
        return trimmed.lastOrNull()?.let { it in ENDS } ?: false
    }
}
