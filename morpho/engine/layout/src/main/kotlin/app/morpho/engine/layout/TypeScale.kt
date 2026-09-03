package app.morpho.engine.layout

/**
 * How big each kind of paragraph is set, and how much air is left under it.
 *
 * The app makes a PDF two ways: it draws one itself, and it hands the
 * preview to the system print sheet, which prints the same HTML the reader
 * has been looking at. Both are "the document as a PDF", and they were set
 * to different scales — a first-level heading at 21 points drawn and 20
 * printed, a third-level one at 13 and 13.5, a body paragraph followed by
 * six points of air drawn and nine printed. Nobody chose either
 * difference; each side was given numbers of its own and neither knew
 * about the other.
 *
 * A reader who previews a document, saves it, prints it, and finds three
 * documents has been told something untrue by the preview. So the scale is
 * one thing, here, and a test holds the writers to it.
 *
 * What this does not settle is how two paragraphs' spacing meets in the
 * middle: a browser collapses the space under one paragraph into the space
 * over the next and takes the larger, while a page drawn line by line adds
 * them. That is a difference between the two media rather than two sets of
 * numbers, and it is left as it is — the numbers below are what each side
 * uses, not a claim that both compose them the same way.
 */
object TypeScale {

    /** The size [kind] is set at, in points. */
    fun sizePt(kind: ParagraphKind): Float = when (kind) {
        ParagraphKind.TITLE -> 26f
        ParagraphKind.HEADING_1 -> 20f
        ParagraphKind.HEADING_2 -> 16f
        ParagraphKind.HEADING_3 -> 13.5f
        ParagraphKind.BODY -> 12f
    }

    /**
     * The air under a [kind] where the document itself measured none.
     *
     * A document that says how much space it wants is obeyed; this is for
     * one that does not, which is most of what a PDF is read as.
     */
    fun spaceAfterPt(kind: ParagraphKind): Float =
        if (kind == ParagraphKind.BODY) 9f else 6f

    /**
     * The air over a [kind] where the document measured none — a heading
     * wants room above it to belong to what follows rather than to what it
     * ends.
     */
    fun spaceBeforePt(kind: ParagraphKind): Float =
        if (kind == ParagraphKind.BODY) 0f else 18f

    /** Whether [kind] is set bold. Every heading is; a title is not. */
    fun bold(kind: ParagraphKind): Boolean = when (kind) {
        ParagraphKind.HEADING_1, ParagraphKind.HEADING_2, ParagraphKind.HEADING_3 -> true
        ParagraphKind.TITLE, ParagraphKind.BODY -> false
    }

    /**
     * A raised or lowered run, as a share of the size it is set in.
     *
     * A footnote mark is on nearly every page of a paper, and there were
     * three sizes of it: the drawn page set one at two-thirds, the preview
     * set a note's mark at three-quarters, and an ordinary superscript in
     * the preview was left to the browser, whose `smaller` is about
     * five-sixths. One size.
     */
    const val RAISED_SHARE = 0.66f

    /** A note at the foot of a page, as a share of the body it belongs to. */
    const val NOTE_SHARE = 0.85f

    /** The rule over a page's notes: this thick, in points. */
    const val NOTE_RULE_PT = 0.75f

    /**
     * How far across the text the rule over the notes reaches.
     *
     * A word processor draws a short rule — about a third of the measure —
     * rather than one from margin to margin, so that the notes are set
     * apart from the text without a line across the page. The drawn page
     * did that and the preview drew the full width.
     */
    const val NOTE_RULE_SHARE = 0.33f

    /** Clear space between that rule and the first note under it. */
    const val NOTE_GAP_PT = 6f

    /** [value] with no more decimals than it needs, for a stylesheet. */
    fun pt(value: Float): String =
        if (value == value.toInt().toFloat()) "${value.toInt()}pt" else "${value}pt"
}
