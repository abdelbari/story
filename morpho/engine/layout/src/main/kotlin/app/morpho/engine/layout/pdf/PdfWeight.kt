package app.morpho.engine.layout.pdf

/**
 * Whether a page set its type bold, by any of the three ways it can say so.
 *
 * A PDF carries no weight of its own. A producer with the bold cut of the
 * typeface to hand switches to it and names it — "Times New Roman,Bold" —
 * and the name was the only evidence either reader looked for. Two other
 * kinds of document say it differently and were read light:
 *
 *  - a subset whose name the producer made up ("ABCDEE+Font1") says
 *    nothing in its name and everything in its descriptor, where the
 *    weight is written as a number the way a designer writes it;
 *  - a producer with no bold cut fakes one, by drawing each letter and
 *    then stroking round it so the strokes thicken. The font it names is
 *    the light one, exactly as with a faked italic.
 *
 * The last matters for the same documents [PdfSlant] does: a typeface
 * with no bold cut is common outside Latin type, and every bold word of
 * such a document was coming back light.
 */
object PdfWeight {

    /** At or above this, a designer's own number for the weight means bold. */
    private const val BOLD_WEIGHT = 600f

    /** The descriptor's ForceBold flag, bit nineteen. */
    private const val FORCE_BOLD = 1 shl 18

    /**
     * The words a producer puts after a typeface's name to say it is a
     * heavy cut of it. Only the suffix is read, never the family: a face
     * called Blackadder is not bold for being called Blackadder.
     */
    private val HEAVY = listOf("bold", "black", "heavy", "semibold", "demibold", "demi")

    /**
     * Rendering modes that fill a glyph and stroke round it. Stroking
     * alone (mode 1) is an outline, which is a thing a page does for a
     * display line and does not mean bold.
     */
    private val FILLED_AND_STROKED = setOf(2, 6)

    /** True when a font's name says outright that it is a heavy cut. */
    fun named(fontName: String?): Boolean {
        val name = fontName ?: return false
        if (name.contains("Bold", ignoreCase = true)) return true
        val family = name.substringAfter('+', name)
        val suffix = when {
            ',' in family -> family.substringAfter(',')
            '-' in family -> family.substringAfter('-')
            else -> return false
        }.lowercase()
        return HEAVY.any { suffix.contains(it) }
    }

    /**
     * True when a font descriptor's own numbers say the face is heavy:
     * the weight a designer gave it, or the flag a producer sets when it
     * wants the face drawn heavier than it was drawn.
     */
    fun declares(fontWeight: Float, flags: Int): Boolean =
        (fontWeight.isFinite() && fontWeight >= BOLD_WEIGHT) || (flags and FORCE_BOLD) != 0

    /**
     * True when the page thickened its letters by stroking round them.
     *
     * A pen of no width draws the thinnest line the device can, which is
     * not what somebody pressing bold asks for; it is how a producer
     * outlines text it means to look ordinary, so it does not count.
     */
    fun strokes(renderingMode: Int, lineWidth: Float): Boolean =
        renderingMode in FILLED_AND_STROKED && lineWidth.isFinite() && lineWidth > 0f
}
