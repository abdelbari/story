package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What folding a page's drawn shapes back into letters touches, and what
 * it leaves alone.
 *
 * A PDF may map a glyph to the shape that was drawn rather than to the
 * letter — an initial or final form, a lam-alef ligature — and those code
 * points are not what anyone types or searches for, and Word will not
 * join them into words. Folding them is what makes an Arabic conversion
 * a document rather than a picture of one.
 *
 * The reordering path is tested elsewhere, including that the fold
 * happens after a line is put back in order rather than before. What
 * nothing asked is where the fold stops. It stops at the presentation
 * blocks on purpose: normalising everything would reach much further —
 * a micro sign into Greek mu, fullwidth Latin into ASCII, a superscript
 * two into a plain one — and none of that belongs in a faithful
 * conversion. That is one line of code away from being "simplified" into
 * a document whose ² and µ quietly changed, so it is asked here, by code
 * point, with the values measured rather than assumed.
 */
class PresentationFormsTest {

    @Test
    fun `a drawn shape folds to the letters it stands for`() {
        // The lam-alef ligature is one glyph and two letters, in reading
        // order: every لا of a document depends on this.
        assertEquals("لا", ExtractedText.foldPresentationForms("ﻻ"))
        assertEquals("لا", ExtractedText.foldPresentationForms("ﻼ"))
        // A letter drawn in the shape its place in the word gave it.
        assertEquals("ا", ExtractedText.foldPresentationForms("ﺎ"))
        assertEquals("ب", ExtractedText.foldPresentationForms("ﺑ"))
        // The other Arabic block, at its first code point.
        assertEquals("ٱ", ExtractedText.foldPresentationForms("ﭐ"))
        // Latin has them too, and Word will not join those either.
        assertEquals("fi", ExtractedText.foldPresentationForms("ﬁ"))
    }

    @Test
    fun `the fold stops where the letters do`() {
        // FEFC is the last assigned form; FEFD and FEFE are unassigned and
        // FEFF is the byte order mark, not a letter. A fold that ran to
        // the end of the block would be folding a mark that means "this
        // file is big-endian" into the middle of somebody's sentence.
        for (past in listOf("﻽", "﻾", "﻿")) {
            assertEquals(past, ExtractedText.foldPresentationForms(past))
        }
        // And nothing below the first block is touched.
        assertEquals("﫿", ExtractedText.foldPresentationForms("﫿"))
    }

    @Test
    fun `what a document meant by a character it did not draw is left alone`() {
        // The reason the fold is not simply NFKC over the whole string.
        // Each of these is a character a document chose, and each is a
        // different character after a normalisation that reached too far.
        val kept = mapOf(
            "the micro sign" to "µ",
            "a superscript two" to "²",
            "fullwidth Latin" to "Ａ",
            "a Roman numeral" to "Ⅳ",
            "a no-break space" to " ",
            "an ordinary ligature spelled out" to "لا",
            "a fraction" to "½",
            "an ordinal indicator" to "ª",
        )
        for ((what, text) in kept) {
            assertEquals(text, ExtractedText.foldPresentationForms(text), "$what was changed")
        }
    }

    @Test
    fun `a sentence keeps everything that was not a drawn shape`() {
        // The three together, in the order a page would hand them over.
        val page = "الماء ﻻ ² ﬁ x"
        assertEquals(
            "الماء لا ² fi x",
            ExtractedText.foldPresentationForms(page),
        )
    }

    @Test
    fun `text with no drawn shapes in it is returned as it was`() {
        for (text in listOf("", "plain english", "البحث", "123 !?")) {
            assertEquals(text, ExtractedText.foldPresentationForms(text))
        }
    }
}
