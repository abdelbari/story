package app.morpho.port

import app.morpho.pdf.AndroidOcrReader
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Recognition has to be asked to look at the page before it reads it.
 *
 * Its own default is to take the whole image as one block of text and
 * work nothing out: no columns, no blocks, no classification of what a
 * line is. That is stated in the Android library's documentation for
 * `setPageSegMode` — "Defaults to PSM_SINGLE_BLOCK" — and it is set in
 * Tesseract's own source, where the parameter is declared
 * `INT_MEMBER(tessedit_pageseg_mode, PSM_SINGLE_BLOCK, ...)`. The command
 * line tool overrides it and every library caller inherits it, which is
 * why it is so easy to leave alone.
 *
 * Left alone here it is quietly ruinous. A two-column paper reads
 * straight across the gutter, half a sentence of each column at a time.
 * Nothing is classified, so the heading, the caption and the line beside
 * the text all arrive as ordinary lines, and the whole point of asking
 * for hOCR — that recognition says what it worked out about the page — is
 * that there is nothing worked out to say.
 *
 * Nothing here can run recognition, so this holds the one number that
 * decides it. A test that could only be written by reading the reader's
 * source would be worth less; a constant can be compared.
 */
class OcrSegmentationTest {

    @Test
    fun `the page is worked out before it is read`() {
        assertEquals(
            TessBaseAPI.PageSegMode.PSM_AUTO,
            AndroidOcrReader.PAGE_SEGMENTATION,
            "recognition must be asked to find the columns, the blocks and the lines",
        )
    }

    @Test
    fun `the mode recognition falls back to is not the mode this asks for`() {
        // The one that matters: a change back to the default would not
        // fail a build, a lint or a review. It would fail on the phone,
        // silently, as text that reads like nonsense on any page set in
        // more than one column.
        assertNotEquals(
            TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
            AndroidOcrReader.PAGE_SEGMENTATION,
            "this is recognition's own default, and it does no layout analysis at all",
        )
    }
}
