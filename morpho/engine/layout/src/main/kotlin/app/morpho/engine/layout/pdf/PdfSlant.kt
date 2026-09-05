package app.morpho.engine.layout.pdf

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Whether a page drew its text leaning, and so meant it to read as italic.
 *
 * A PDF carries no italics. A producer with an italic cut of the typeface
 * to hand switches to it and names it — "Times New Roman,Italic" — and
 * that name is all a reader has ever needed. But a producer with no italic
 * cut fakes one, by skewing the matrix it draws with until the letters
 * lean; the font it names is the upright one, and a reader that only reads
 * names sees nothing at all. This is not an edge: no Arabic typeface Word
 * ships has an italic cut, so *every* italic word of an Arabic document is
 * faked this way — a paper's book titles, a thesis's Latin terms, the
 * emphasis in a report — and every one of them was being converted plain.
 *
 * The lean is read from the matrix rather than from any one number in it,
 * because the matrix also holds the size the text is set at and any turn
 * the page was given. Upright text, however it is turned or scaled, is
 * drawn with its baseline and its up-stroke square to one another; leaning
 * text is not, and how far out of square they are is how far it leans.
 */
object PdfSlant {

    /**
     * How far out of square counts as a lean: about eight degrees. A
     * synthetic italic is set at fifteen to twenty — Word's is eighteen
     * and a half — and upright text is at nought, so the two are nowhere
     * near the line. What sits just above nought is rounding in a
     * producer's arithmetic, and is not a lean.
     */
    private const val LEANING = 0.14f

    /**
     * How far from upright a font descriptor's own angle counts, in
     * degrees. A face is called italic at ten or twelve; the odd font
     * declares half a degree and means nothing by it.
     */
    private const val DECLARED_DEGREES = 4f

    /**
     * True when text drawn with this matrix leans the way an italic leans.
     *
     * [a] and [b] are where the baseline runs, [c] and [d] where the
     * up-stroke of a letter goes. Square to one another is upright; the
     * up-stroke tipped towards the direction of writing is a forward lean,
     * which is what italic is. Tipped the other way is not italic — a
     * back-slant is a thing a designer does on purpose and means the
     * opposite — so only the forward tip counts.
     */
    fun leansIn(a: Float, b: Float, c: Float, d: Float): Boolean {
        val along = sqrt(a * a + b * b)
        val up = sqrt(c * c + d * d)
        if (along <= 0f || up <= 0f || !along.isFinite() || !up.isFinite()) return false
        val outOfSquare = (a * c + b * d) / (along * up)
        return outOfSquare.isFinite() && outOfSquare > LEANING
    }

    /**
     * True when a font descriptor's own italic angle says the face leans.
     *
     * A font names the angle it was drawn at whether or not its name says
     * "Italic", and a subset with a made-up name — "ABCDEE+Font1" — often
     * has nothing else left to say it with.
     */
    fun declares(italicAngle: Float): Boolean =
        italicAngle.isFinite() && abs(italicAngle) > DECLARED_DEGREES

    /** True when a font's name says outright that it is an italic face. */
    fun named(fontName: String?): Boolean {
        val name = fontName ?: return false
        return name.contains("Italic", ignoreCase = true) || name.contains("Oblique", ignoreCase = true)
    }
}
