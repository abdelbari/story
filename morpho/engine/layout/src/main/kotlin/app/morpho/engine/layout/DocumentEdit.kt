package app.morpho.engine.layout

/**
 * A converted document and what a reader has since done to it.
 *
 * Every edit is a function from one of these to the next, so the rules
 * about what an edit does — and what it must not do — are here, where a
 * test can reach them, rather than in a view model and a screen that
 * compile only on a phone and that nothing checks. That is the same
 * reason [DocumentFormats] was moved out of the app: the version that
 * lived there was tested by nothing at all, and it was wrong.
 *
 * It was worth moving twice over, because the rule for which blocks can
 * be joined to the one above was written twice, once in each place, and
 * two copies of a rule drift apart without anything failing.
 *
 * The invariant everything here exists to keep: **an edit never moves a
 * block.** A reader's corrections are remembered by position, so a block
 * that left the list would move every block below it up one and carry
 * every mark onto somebody else's paragraph — and the reader would be
 * told the app had corrected text it never touched. So a block a reader
 * takes out stays exactly where it is and is left out of one thing only:
 * [asWritten], the document as they will save it.
 */
data class DocumentEdit(
    /** The document as it was read, with every correction made in place. */
    val document: DocumentModel,
    /** Blocks whose words or kind the reader changed. */
    val corrected: Set<Int> = emptySet(),
    /** Blocks the reader took out. Still here; left out of [asWritten]. */
    val removed: Set<Int> = emptySet(),
    /**
     * What each join took, kept so that a join can be undone.
     *
     * A join takes one block's words into the block above and takes the
     * first block out — so putting that block back, which is the one thing
     * the row offers, would put its words in the document twice, once in
     * the paragraph above and once on their own. Nothing in the file would
     * say which was meant. So a block a join consumed remembers what the
     * block above looked like before, and putting it back puts that back
     * too.
     */
    val joins: Map<Int, Join> = emptyMap(),
) {

    /** What a join took from the block above it, so it can be given back. */
    data class Join(
        /** The block the words went into. */
        val into: Int,
        /** That block as it was before they did. */
        val before: Paragraph,
        /** Whether it was already marked corrected, so undoing does not unmark it. */
        val wasCorrected: Boolean,
    )

    /** How many blocks the reader has changed, of either kind. */
    val fixes: Int get() = (corrected + removed).size

    /** Whether the reader has changed anything at all. */
    val touched: Boolean get() = corrected.isNotEmpty() || removed.isNotEmpty()

    /**
     * The document as the reader will save it: their corrections, less
     * what they took out.
     *
     * The one place a removal is applied, which is what keeps the drawn
     * preview and the saved file the same document.
     */
    val asWritten: DocumentModel
        get() =
            if (removed.isEmpty()) document
            else document.copy(blocks = document.blocks.filterIndexed { at, _ -> at !in removed })

    /**
     * The blocks that can be put back, which is not quite every block
     * taken out: a join can only be given back from the outside in.
     *
     * Defined by asking the operation itself, so a screen offering to put
     * a block back cannot come to disagree with what putting it back
     * actually does — and a button that does nothing when pressed is
     * worse than no button.
     */
    val restorable: Set<Int> get() = removed.filterTo(mutableSetOf()) { restore(it) !== this }

    /** The whole of what the block at [index] says, or "" where it says nothing. */
    fun textOf(index: Int): String = paragraphAt(index)?.text.orEmpty()

    /**
     * The block at [index] saying [text] instead of what it said, with the
     * formatting round the words that did not change; see [ParagraphEdit].
     *
     * Confidence is deliberately untouched. The reader has corrected these
     * characters and said nothing about the rest, and a report that grew
     * more certain whenever somebody touched a block would be worth
     * nothing.
     */
    fun retext(index: Int, text: String): DocumentEdit {
        val block = paragraphAt(index) ?: return this
        if (block.text == text) return this
        return replacing(index, ParagraphEdit.retext(block, text))
    }

    /**
     * The block at [index] recorded as a [kind] instead — a heading a
     * reading took for body text, or the other way about.
     */
    fun reclassify(index: Int, kind: ParagraphKind): DocumentEdit {
        val block = paragraphAt(index) ?: return this
        if (block.style.kind == kind) return this
        return replacing(index, block.copy(style = block.style.copy(kind = kind)))
    }

    /**
     * The document without the block at [index] — a scanner's edge read as
     * a mark, a running head that escaped the reading of the page's
     * furniture, a page number in the middle of the text.
     *
     * Taking one out is not a correction and is not counted as one: the
     * row says it is gone, and counting it twice would tell the reader
     * they had made more changes than they made.
     */
    fun remove(index: Int): DocumentEdit {
        if (index !in document.blocks.indices || index in removed) return this
        return copy(removed = removed + index)
    }

    /**
     * The block at [index] put back — and, where a join took it, the join
     * undone with it.
     *
     * Undoing rather than refusing, because the row offers to put it back
     * and a reader who joined the wrong two paragraphs has no other way
     * out. What comes back is what was there: the words on their own, and
     * the paragraph above as it stood before it took them.
     */
    fun restore(index: Int): DocumentEdit {
        if (index !in removed) return this
        val join = joins[index] ?: return copy(removed = removed - index)
        // A join can only be given back while it is still the last thing
        // that happened to the block it gave its words to. Both ways that
        // stops being true lose text, and the fuzz found both:
        //
        //  - the block it gave to has since been joined away itself, so
        //    the words are a level further up and putting this one back
        //    would say them twice, once there and once alone;
        //  - the block it gave to has since taken a second join, so the
        //    paragraph remembered from before this one would be put back
        //    over the later join's words and lose them outright.
        //
        // Undoing from the outside in is the order anybody undoes anything
        // in, and it is the only order that gives everything back. A later
        // join into the same block always comes from a higher block than
        // an earlier one, because everything between them has to be gone
        // for the join to have reached it — so "later" is "higher up the
        // document" and needs nothing counted.
        if (join.into in removed) return this
        if (joins.any { (at, other) -> other.into == join.into && at > index }) return this
        val blocks = document.blocks.toMutableList()
        blocks[join.into] = join.before
        return copy(
            document = document.copy(blocks = blocks),
            corrected = if (join.wasCorrected) corrected else corrected - join.into,
            removed = removed - index,
            joins = joins - index,
        )
    }

    /**
     * Whether the block at [index] has a paragraph directly above it to be
     * joined to.
     *
     * Directly: a block the reader has taken out is passed over, since it
     * is not there any more, but a table or a picture is not. Carrying a
     * sentence over something standing between its two halves is not what
     * anybody means by joining a paragraph to the one above it.
     */
    fun canJoinUp(index: Int): Boolean = above(index) != null

    /**
     * Every block that can be joined to the one above it.
     *
     * Asked of the whole document once, for a screen that lists blocks
     * and has to know which rows to offer it on — usually a screen
     * filtered to the doubtful blocks, where what sits above a row is not
     * what sits above it on the page.
     */
    val joinable: Set<Int> get() = document.blocks.indices.filterTo(mutableSetOf(), ::canJoinUp)

    /**
     * The block at [index] joined to the paragraph above it, which is
     * where it belonged before a page or a column broke them apart.
     *
     * The block joined into is marked as corrected; the one joined from is
     * taken out, which is what keeps every block where it was.
     */
    fun joinUp(index: Int): DocumentEdit {
        val second = paragraphAt(index) ?: return this
        val at = above(index) ?: return this
        val first = document.blocks[at] as Paragraph
        val blocks = document.blocks.toMutableList()
        blocks[at] = ParagraphEdit.join(first, second)
        return copy(
            document = document.copy(blocks = blocks),
            corrected = corrected + at,
            removed = removed + index,
            joins = joins + (index to Join(at, first, wasCorrected = at in corrected)),
        )
    }

    /** The index of the paragraph the block at [index] would join to. */
    private fun above(index: Int): Int? {
        if (paragraphAt(index) == null) return null
        val at = (index - 1 downTo 0).firstOrNull { it !in removed } ?: return null
        return if (document.blocks[at] is Paragraph) at else null
    }

    /** The block at [index], if it is a paragraph the reader can still see. */
    private fun paragraphAt(index: Int): Paragraph? =
        if (index in removed) null else document.blocks.getOrNull(index) as? Paragraph

    private fun replacing(index: Int, block: Paragraph): DocumentEdit {
        val blocks = document.blocks.toMutableList()
        blocks[index] = block
        return copy(document = document.copy(blocks = blocks), corrected = corrected + index)
    }
}
