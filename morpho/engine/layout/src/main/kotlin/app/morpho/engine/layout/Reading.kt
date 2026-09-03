package app.morpho.engine.layout

/**
 * How a long reading says where it has reached, and how it is stopped.
 *
 * A book of two hundred pages takes seconds to read on a desktop and the
 * better part of a minute on a phone, and until now the reader who asked
 * for it saw a spinner that said nothing and offered nothing: no page
 * count, and no way to change their mind about a file they picked by
 * mistake. Recognition had both — it is slower still, so it was given
 * them first — and this is the same two, for the reading every conversion
 * does.
 *
 * The channel is optional at every step and does nothing by default, so a
 * caller that does not care is a caller whose reading is unchanged.
 */
class Reading(
    /**
     * Called as each page is reached, with the page's number and how many
     * there are. Called from the reading's own thread; a caller that
     * shows it somewhere else is the one to say so.
     */
    private val onPage: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
    /** Asked before each page; false stops the reading where it stands. */
    private val shouldContinue: () -> Boolean = { true },
) {

    /**
     * The reading was stopped by whoever asked for it.
     *
     * Not a failure: a document that was not read because nobody wanted
     * it any more is not a document that could not be read, and the two
     * must not come back looking the same.
     */
    class Cancelled : Exception("the reading was stopped")

    /** Reports [page] of [pageCount], having first stopped if asked to. */
    fun reached(page: Int, pageCount: Int) {
        carryOn()
        onPage(page, pageCount)
    }

    /** Stops if asked to, without saying where the reading has reached. */
    fun carryOn() {
        if (!shouldContinue()) throw Cancelled()
    }

    companion object {
        /** A reading nobody is watching and nobody is going to stop. */
        val UNWATCHED = Reading()
    }
}
