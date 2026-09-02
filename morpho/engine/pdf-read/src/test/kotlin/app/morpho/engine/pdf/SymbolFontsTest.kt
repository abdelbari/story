package app.morpho.engine.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The bullet Word draws before a list item comes to a reader as U+F0B7, a
 * private-use code point that means nothing outside the Symbol font — a
 * blank box in a word processor, and, being a left-to-right character, a
 * marker that drags a right-to-left item to the wrong side of the page.
 * Symbol's own published table says what the glyph is.
 */
class SymbolFontsTest {

    @Test
    fun `the symbol font's private codes are the characters its table names`() {
        assertEquals("•", SymbolFonts.character("Symbol", ""), "bullet")
        assertEquals("−", SymbolFonts.character("Symbol", ""), "minus")
        // A subset font is named for the font it was cut from.
        assertEquals("•", SymbolFonts.character("ABCDEE+Symbol", ""))
    }

    @Test
    fun `a font whose codes are its own business is left alone`() {
        // Wingdings assigns its bytes itself; there is no table to read
        // them by, and a guess would put a character where a picture is.
        assertNull(SymbolFonts.character("Wingdings", ""))
        assertNull(SymbolFonts.character("ABCDEE+Simplified Arabic", ""))
        assertNull(SymbolFonts.character(null, ""))
    }

    @Test
    fun `characters that are not a symbol font's own codes are left alone`() {
        assertNull(SymbolFonts.character("Symbol", "a"), "a real character")
        assertNull(SymbolFonts.character("Symbol", "•"), "already a bullet")
        assertNull(SymbolFonts.character("Symbol", ""), "outside the symbol area")
        assertNull(SymbolFonts.character("Symbol", ""), "nothing at all")
        assertNull(SymbolFonts.character("Symbol", ""), "two glyphs")
    }

    @Test
    fun `a code the table does not name stays as it is`() {
        // Symbol has no glyph at 00, so there is nothing better to say.
        assertNull(SymbolFonts.character("Symbol", ""))
    }
}
