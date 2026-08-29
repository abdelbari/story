package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BidiTest {

    @Test
    fun `latin text is LTR`() {
        assertEquals(TextDirection.LTR, Bidi.firstStrongDirection("Hello Morpho"))
    }

    @Test
    fun `arabic text is RTL`() {
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("مرحبا بالعالم"))
    }

    @Test
    fun `hebrew text is RTL`() {
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("שלום"))
    }

    @Test
    fun `leading digits and punctuation are skipped`() {
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("42 — مرحبا"))
        assertEquals(TextDirection.LTR, Bidi.firstStrongDirection("42 apples"))
    }

    @Test
    fun `text with no strong direction returns null`() {
        assertNull(Bidi.firstStrongDirection("123 + 456 = ..."))
        assertNull(Bidi.firstStrongDirection(""))
    }

    @Test
    fun `mixed text follows first strong character`() {
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("مرحبا Morpho"))
        assertEquals(TextDirection.LTR, Bidi.firstStrongDirection("Morpho مرحبا"))
    }
}
