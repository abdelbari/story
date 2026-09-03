package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * A PDF has no italics: a producer either switches to the italic cut of
 * the typeface, or — having none, which is the case for every Arabic
 * typeface Word ships — skews what it draws with until the letters lean.
 * The second kind was read as upright, so a paper's every book title, a
 * thesis's every Latin term, came back plain.
 */
class PdfSlantTest {

    /** A matrix for text set at [size], turned [turn] degrees, leaning [lean] degrees. */
    private fun matrix(size: Float, turn: Float = 0f, lean: Float = 0f): FloatArray {
        val t = Math.toRadians(turn.toDouble())
        val skew = Math.tan(Math.toRadians(lean.toDouble())).toFloat()
        // Upright: the baseline runs along (cos, sin) and the up-stroke goes
        // square to it. A lean tips the up-stroke towards the writing.
        val alongX = (cos(t) * size).toFloat()
        val alongY = (sin(t) * size).toFloat()
        val upX = (-sin(t) * size).toFloat() + alongX * skew
        val upY = (cos(t) * size).toFloat() + alongY * skew
        return floatArrayOf(alongX, alongY, upX, upY)
    }

    private fun leans(m: FloatArray) = PdfSlant.leansIn(m[0], m[1], m[2], m[3])

    @Test
    fun `upright text does not lean, at any size`() {
        for (size in listOf(6f, 12f, 48f, 400f)) assertFalse(leans(matrix(size)))
    }

    @Test
    fun `text skewed the way word fakes an italic leans`() {
        // Word's synthetic oblique is a shear of one third — the paper this
        // was built for is drawn with exactly that, 4.32 against 12.96.
        assertTrue(PdfSlant.leansIn(12.96f, 0f, 4.32f, 12.96f))
        for (angle in listOf(10f, 12f, 15f, 18.43f, 20f, 30f)) {
            assertTrue(leans(matrix(12f, lean = angle)), "$angle° did not read as a lean")
        }
    }

    @Test
    fun `a page turned to be read is still upright text`() {
        // A landscape page, or one written portrait and turned: the whole
        // matrix rotates and nothing about the type has changed. Read as a
        // shear, a turned page would come back italic from top to bottom.
        for (turn in listOf(90f, 180f, 270f, 45f, -90f)) {
            assertFalse(leans(matrix(12f, turn = turn)), "${turn}° of turn read as a lean")
        }
    }

    @Test
    fun `a turned page's italics still lean`() {
        for (turn in listOf(90f, 180f, 270f)) {
            assertTrue(leans(matrix(12f, turn = turn, lean = 18.43f)))
        }
    }

    @Test
    fun `a back-slant is not an italic`() {
        // Leaning against the writing is a thing a designer does on
        // purpose and it means the opposite of emphasis.
        for (angle in listOf(-10f, -18.43f, -30f)) assertFalse(leans(matrix(12f, lean = angle)))
    }

    @Test
    fun `rounding in a producer's arithmetic is not a lean`() {
        for (angle in listOf(0.1f, 1f, 3f)) assertFalse(leans(matrix(12f, lean = angle)))
    }

    @Test
    fun `a matrix that says nothing is not a lean`() {
        assertFalse(PdfSlant.leansIn(0f, 0f, 0f, 0f))
        assertFalse(PdfSlant.leansIn(12f, 0f, 0f, 0f))
        assertFalse(PdfSlant.leansIn(Float.NaN, 0f, 4f, 12f))
        assertFalse(PdfSlant.leansIn(Float.POSITIVE_INFINITY, 0f, 4f, 12f))
    }

    @Test
    fun `a font's own declared angle says the face leans`() {
        // Fonts write the angle negative, leaning forward; a few write it
        // the other way round and mean the same thing.
        assertTrue(PdfSlant.declares(-12f))
        assertTrue(PdfSlant.declares(11f))
        assertFalse(PdfSlant.declares(0f))
        assertFalse(PdfSlant.declares(-0.5f))
        assertFalse(PdfSlant.declares(Float.NaN))
    }

    @Test
    fun `a font that names itself italic is believed`() {
        assertTrue(PdfSlant.named("ABCDEE+Times New Roman,Italic"))
        assertTrue(PdfSlant.named("Helvetica-Oblique"))
        assertFalse(PdfSlant.named("ABCDEE+Simplified Arabic"))
        assertFalse(PdfSlant.named(null))
    }
}
