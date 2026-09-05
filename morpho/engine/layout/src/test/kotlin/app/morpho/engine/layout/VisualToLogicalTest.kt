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

    @Test
    fun `a ligature glyph keeps its letters in order through the reversal`() {
        // "الاستمارة" painted: the لا is one glyph whose text is two letters
        // in logical order; entered backwards, the line's reversal rights it.
        val painted = "ةرامتس" + ExtractedText.paintedForm("لا") + "ا"
        assertEquals("الاستمارة", ExtractedText.toLogical(painted, TextDirection.RTL))
    }

    @Test
    fun `a presentation-form ligature folds after the reversal, not before`() {
        // ﻻ (U+FEFB) is one character while the line is put back in order.
        assertEquals("الاستمارة", ExtractedText.toLogical("ةرامتس\uFEFBا", TextDirection.RTL))
    }

    @Test
    fun `the marks on a letter come back in the order a keyboard types them`() {
        // A dot below is written before a dot above whatever order a page
        // paints them in — Unicode's canonical order, and the order every
        // keyboard produces and every search box holds. The two render
        // identically, which is what makes the wrong one so easy to ship
        // and so baffling to a reader whose search finds nothing in a
        // document that plainly says the words. A vowelled Arabic page —
        // a verse, a line of poetry, a school book — is the case that
        // matters, and it is read end to end in ArabicPdfTest.
        assertEquals("q\u0323\u0307", ExtractedText.toLogical("q\u0307\u0323"))
    }

    @Test
    fun `a mark written over a letter becomes the letter Unicode has for it`() {
        // The one letter, not a letter and a mark: what a document holds,
        // what a reader types, and what every other file in the world has.
        assertEquals("\u00E9t\u00E9", ExtractedText.toLogical("e\u0301te\u0301"))
    }

    @Test
    fun `a letter with no marks on it is left exactly as it was`() {
        assertEquals("الاستمارة", ExtractedText.toLogical("ةرامتسالا", TextDirection.RTL))
        assertEquals("The form in scientific research", ExtractedText.toLogical("The form in scientific research"))
    }

    @Test
    fun `what painted a letter still paints its marks`() {
        // A vowelled word set in bold stays bold all the way through, mark
        // and letter alike: every character of the result carries a
        // painter, and the cluster's marks carry the cluster's.
        val painted = "q\u0307\u0323e\u0301"
        val logical = ExtractedText.toLogical(painted, List(painted.length) { "bold" })
        assertEquals(logical.text.length, logical.painters.size)
        assertEquals(List(logical.text.length) { "bold" }, logical.painters)
    }

    @Test
    fun `runs of spaces collapse to one`() {
        assertEquals("تاريخ القبول: 2022", ExtractedText.toLogical("2022      :لوبقلا خيرات", TextDirection.RTL))
    }


    @Test
    fun `a date after arabic letters keeps its components in order`() {
        // As painted: the date at the left, then the label's words reversed.
        // The bidi rules alone return 21-04-2022 here, while the same date
        // at the start of a line comes back whole.
        val visual = "2022-04-21:" + "الاستلام".reversed() + " " + "تاريخ".reversed()
        assertEquals("تاريخ الاستلام:2022-04-21", Bidi.visualToLogical(visual, TextDirection.RTL))
    }

    @Test
    fun `a page range inside arabic keeps its order too`() {
        val visual = "12-15 " + "الصفحات".reversed()
        assertEquals("الصفحات 12-15", Bidi.visualToLogical(visual, TextDirection.RTL))
    }


    @Test
    fun `an arabic comma between two numbers keeps them in reading order`() {
        // A citation reads (author، year، page). Painted, the page number is
        // leftmost. Classed as a number separator, the comma fused the two
        // into one left-to-right unit and the page came before the year.
        val visual = ")186،2000،" + "الرشيدي".reversed() + "("
        assertEquals("(الرشيدي،2000،186)", Bidi.visualToLogical(visual, TextDirection.RTL))
    }

}
