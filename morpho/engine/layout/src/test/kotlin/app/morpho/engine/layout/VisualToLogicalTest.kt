package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Reconstruction of logical order from the visual order a PDF content
 * stream paints in. The strings here are the shapes that came out of a
 * real Word-produced Arabic PDF before the reconstruction existed.
 */
class VisualToLogicalTest {

    @Test
    fun `latin text is untouched`() {
        val text = "The form in scientific research"
        assertEquals(text, Bidi.visualToLogical(text))
    }

    @Test
    fun `text with no strong direction is untouched`() {
        assertEquals("2025-01-31 (draft)", Bidi.visualToLogical("2025-01-31 (draft)"))
    }

    @Test
    fun `a reversed arabic word comes back in logical order`() {
        // "ربيحة" as painted: last letter first.
        assertEquals("ربيحة", Bidi.visualToLogical("ةحيبر"))
    }

    @Test
    fun `an embedded latin run keeps its own direction`() {
        // A latin address inside an Arabic line is painted left to right
        // already; only the Arabic around it is reversed.
        val logical = Bidi.visualToLogical("ةعماج nebbarrebih@gmail.com")
        assertEquals("nebbarrebih@gmail.com جامعة", logical)
    }

    @Test
    fun `paired punctuation lands at the right end without being mirrored`() {
        // Reversing the run alone moves "(" back to the opening end. Mirroring
        // it as well — as PDFBox does — would produce ")الجزائر(".
        assertEquals("(الجزائر)", Bidi.visualToLogical(")رئازجلا("))
    }

    @Test
    fun `reconstruction is stable when applied to logical text`() {
        val once = Bidi.visualToLogical("ةحيبر")
        assertEquals(once, Bidi.visualToLogical(Bidi.visualToLogical(once)))
    }

    @Test
    fun `an empty string is returned unchanged`() {
        assertEquals("", Bidi.visualToLogical(""))
    }
}
