package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A PDF carries no weight of its own. A producer with the bold cut of a
 * typeface names it, one with a made-up subset name declares it, and one
 * with no bold cut at all strokes round each letter to thicken it — and
 * only the first was ever read.
 */
class PdfWeightTest {

    @Test
    fun `a font that names itself bold is believed`() {
        assertTrue(PdfWeight.named("ABCDEE+Simplified Arabic,Bold"))
        assertTrue(PdfWeight.named("Times New Roman,Bold"))
        assertTrue(PdfWeight.named("TimesNewRomanPS-BoldMT"))
        assertTrue(PdfWeight.named("Helvetica-BoldOblique"))
    }

    @Test
    fun `the other words for a heavy cut are read too`() {
        assertTrue(PdfWeight.named("Roboto-Black"))
        assertTrue(PdfWeight.named("HelveticaNeue,Heavy"))
        assertTrue(PdfWeight.named("Open Sans,SemiBold"))
        assertTrue(PdfWeight.named("Frutiger-Demi"))
    }

    @Test
    fun `a family whose own name holds one of those words is not bold for it`() {
        // Blackadder is a typeface; Demi Bold is a weight. Only the part
        // after the name is read.
        assertFalse(PdfWeight.named("ABCDEE+Blackadder ITC"))
        assertFalse(PdfWeight.named("Heavyweight Display"))
        assertFalse(PdfWeight.named("ABCDEE+Simplified Arabic"))
        assertFalse(PdfWeight.named(null))
    }

    @Test
    fun `a subset with a made-up name says it in its descriptor`() {
        assertTrue(PdfWeight.declares(fontWeight = 700f, flags = 32))
        assertTrue(PdfWeight.declares(fontWeight = 600f, flags = 32))
        assertTrue(PdfWeight.declares(fontWeight = 0f, flags = 32 or (1 shl 18)))
        assertFalse(PdfWeight.declares(fontWeight = 400f, flags = 32))
        assertFalse(PdfWeight.declares(fontWeight = 0f, flags = 32))
        assertFalse(PdfWeight.declares(fontWeight = Float.NaN, flags = 4))
    }

    @Test
    fun `a page that strokes round its letters has thickened them`() {
        // Mode two fills and strokes; six does the same and clips.
        assertTrue(PdfWeight.strokes(renderingMode = 2, lineWidth = 1f))
        assertTrue(PdfWeight.strokes(renderingMode = 6, lineWidth = 0.4f))
    }

    @Test
    fun `filling alone, or outlining, or a pen of no width, is not bold`() {
        assertFalse(PdfWeight.strokes(renderingMode = 0, lineWidth = 1f))
        // Stroking alone is an outline, which a page does for a display
        // line and does not mean by it that the line is bold.
        assertFalse(PdfWeight.strokes(renderingMode = 1, lineWidth = 1f))
        // Invisible text, over a scan.
        assertFalse(PdfWeight.strokes(renderingMode = 3, lineWidth = 1f))
        assertFalse(PdfWeight.strokes(renderingMode = 2, lineWidth = 0f))
        assertFalse(PdfWeight.strokes(renderingMode = 2, lineWidth = Float.NaN))
    }
}
