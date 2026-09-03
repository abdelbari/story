package app.morpho.engine.layout.pdf

/**
 * The lines a page draws under its words, and through them.
 *
 * A PDF has no underline and no strike. Where a document underlines a
 * term or strikes out a clause, the producer draws a hair of a rule where
 * the words are — under the baseline for one, across the middle of the
 * letters for the other — and nothing in the file joins the line to the
 * text it marks. A reader that keeps only the words loses both, which is
 * why every underlined heading, every struck-out price and every crossed
 * clause came back plain from a PDF while the same document converted
 * from Word kept them.
 *
 * The join is geometry, the way a highlight's is. What makes a rule a
 * mark rather than a border, a table's line or a bar of colour is where
 * it sits against the baseline, how thick it is, and that it covers the
 * words rather than the page: a border runs margin to margin under a
 * paragraph, a table's rule runs the width of its column, and neither
 * hugs the ink the way a mark does.
 */
object PdfMarks {

    /** What a page's rule does to the words it lies over. */
    enum class Mark { UNDERLINE, STRIKE }

    /**
     * How near a baseline a rule has to be, as a share of the type size,
     * to belong to the words rather than to the paragraph.
     *
     * This is the one number the two readings share. Inside it a rule is
     * a mark on the line — an underline, a strike — and outside it a rule
     * is a border drawn above the paragraph or below it. Both bands below
     * lie strictly inside it, so no rule is ever read as a mark and as a
     * border at once, which would give a converted document a struck-out
     * line inside a boxed paragraph where the page drew one plain rule.
     */
    const val CLEARANCE = 0.4f

    /**
     * How far under the baseline an underline sits, as a share of the type
     * size. Word draws one about a sixth of the size down; a rule further
     * off is a border under the paragraph, not a line under the words.
     */
    private const val UNDER_HIGHEST = -0.04f
    private const val UNDER_LOWEST = 0.30f

    /**
     * How far above the baseline a strike crosses, as a share of the type
     * size: through the middle of the letters, which is about a third of
     * the size up. Higher than that and the rule is above the line rather
     * than through it — and past [CLEARANCE] it is the paragraph's own
     * border, which is read elsewhere.
     */
    private const val THROUGH_LOWEST = 0.18f
    private const val THROUGH_HIGHEST = 0.38f

    /**
     * How thick a mark can be, as a share of the type size. A rule as deep
     * as a fifth of the type is a bar of colour drawn behind the words,
     * and calling that a strike would have a document withdraw what it
     * had emphasised.
     */
    private const val THICKEST = 0.2f

    /**
     * How far past the ink it marks a rule may reach, as a share of the
     * type size. An underline runs on through the space at the end of the
     * words it marks; a paragraph's border runs to the margins, and a
     * table's rule to the sides of its column, which is what this tells
     * apart.
     */
    private const val OVERHANG = 1.0f

    /** How much of a glyph a rule must lie under for the glyph to be marked. */
    private const val COVERED = 0.5f

    /**
     * What [rule] does to a line of type set at [fontSizePt] on [baselineY],
     * whose ink runs from [inkLeft] to [inkRight] — or null where the rule
     * is not a mark on that line at all.
     *
     * [baselineY] and [rule]'s own y are measured down the page, the way
     * glyphs are, so a rule below the baseline has the larger y.
     */
    fun of(
        rule: PdfRule,
        baselineY: Float,
        fontSizePt: Float,
        inkLeft: Float,
        inkRight: Float,
    ): Mark? {
        if (fontSizePt <= 0f || inkRight <= inkLeft) return null
        if (rule.thicknessPt > THICKEST * fontSizePt) return null
        val reach = OVERHANG * fontSizePt
        if (rule.left < inkLeft - reach || rule.right > inkRight + reach) return null
        val below = (rule.y - baselineY) / fontSizePt
        return when {
            below in UNDER_HIGHEST..UNDER_LOWEST -> Mark.UNDERLINE
            -below in THROUGH_LOWEST..THROUGH_HIGHEST -> Mark.STRIKE
            else -> null
        }
    }

    /** Whether [rule] covers enough of the glyph running from [left] to [right]. */
    fun covers(rule: PdfRule, left: Float, right: Float): Boolean {
        val width = right - left
        if (width <= 0f) return rule.left <= left && left <= rule.right
        val over = minOf(rule.right, right) - maxOf(rule.left, left)
        return over >= COVERED * width
    }
}
