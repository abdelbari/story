package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A photograph of a page says how many pixels it is and nothing about how
 * big a thing it is a picture of, and the reading needs a page: the
 * running head is found against the foot of one, the margins are measured
 * against its edges, and a document with no page opens on whatever Word
 * opens with.
 */
class ImagePageTest {

    /** A4 in points, which is what a scan of A4 has to come back as. */
    private val a4Wide = 595.3f
    private val a4High = 841.9f

    @Test
    fun `a scan of A4 comes back as A4, at whatever it was scanned at`() {
        // 2480 by 3508 is A4 at 300 dpi, and 4960 by 7016 is the same page
        // at 600. Both are the same piece of paper and must measure the
        // same, which is the whole point of asking this question once.
        for ((wide, high) in listOf(2480 to 3508, 4960 to 7016, 1240 to 1754)) {
            val page = ImagePage.of(1, wide, high)!!
            assertEquals(a4Wide, page.sheet.widthPt, 3f, "$wide x $high came back the wrong width")
            assertEquals(a4High, page.sheet.heightPt, 1f, "$wide x $high came back the wrong height")
            assertEquals(1, page.sheet.page)
        }
    }

    @Test
    fun `a photograph taken on a phone is a page of ordinary size`() {
        // Three to four, which is what a phone camera gives. It is not A4
        // and pretending it were would stretch the page; what it is very
        // nearly is Letter, and that is a page a reader can use.
        val page = ImagePage.of(1, 3024, 4032)!!
        assertEquals(11.69f, page.sheet.heightPt / 72f, 0.01f, "the long side is a page's long side")
        assertEquals(8.77f, page.sheet.widthPt / 72f, 0.01f)
        assertTrue(page.dpi > 300f && page.dpi < 400f, "read at ${page.dpi} dpi")
    }

    @Test
    fun `a page turned on its side is a page on its side`() {
        val page = ImagePage.of(1, 3508, 2480)!!
        assertEquals(a4High, page.sheet.widthPt, 1f, "A4 on its side is A4's long side across")
        assertEquals(a4Wide, page.sheet.heightPt, 3f, "and A4's short side down")
        assertTrue(page.sheet.widthPt > page.sheet.heightPt, "a landscape picture is a landscape page")
    }

    @Test
    fun `a file that states a scanner's resolution is believed`() {
        // A page scanned at 600 and then cropped to one paragraph is not a
        // page-shaped thing, and its shape cannot say what it is — but the
        // file knows, because the scanner wrote it down.
        val page = ImagePage.of(1, 4960, 1200, statedDpi = 600f)!!
        assertEquals(600f, page.dpi)
        assertEquals(4960f / 600f * 72f, page.sheet.widthPt, 0.01f)
        assertEquals(1200f / 600f * 72f, page.sheet.heightPt, 0.01f)
    }

    @Test
    fun `a resolution no page could have is not believed`() {
        // A camera writes 72, or 1, or its sensor's own number. Believed,
        // 72 would make an ordinary photograph a wall chart four feet
        // high, with type to match.
        val fromCamera = ImagePage.of(1, 3024, 4032, statedDpi = 72f)!!
        assertEquals(11.69f, fromCamera.sheet.heightPt / 72f, 0.01f, "72 dpi was believed")
        for (nonsense in listOf(1f, 0f, -300f, Float.NaN, Float.POSITIVE_INFINITY, 1_000_000f)) {
            val page = ImagePage.of(1, 3024, 4032, statedDpi = nonsense)!!
            assertEquals(
                11.69f,
                page.sheet.heightPt / 72f,
                0.01f,
                "$nonsense dpi was believed",
            )
        }
    }

    @Test
    fun `a picture of nothing is no page at all`() {
        assertNull(ImagePage.of(1, 0, 1000))
        assertNull(ImagePage.of(1, 1000, 0))
        assertNull(ImagePage.of(1, -1, -1))
        assertNull(ImagePage.of(0, 100, 100), "pages are counted from one")
    }

    @Test
    fun `a panorama is a long page and not a broken one`() {
        val page = ImagePage.of(1, 8000, 1000)!!
        assertTrue(page.sheet.widthPt > page.sheet.heightPt * 7f)
        assertTrue(page.sheet.widthPt.isFinite() && page.sheet.heightPt > 0f)
        assertEquals(11.69f, page.sheet.widthPt / 72f, 0.01f)
    }

    @Test
    fun `the resolution and the sheet are one decision`() {
        // Everything recognition reports is in pixels of the image, and
        // the sheet is in points. A resolution that did not match the
        // sheet would put every word off the page it was recognised on.
        for ((wide, high) in listOf(2480 to 3508, 3024 to 4032, 800 to 600, 8000 to 1000)) {
            val page = ImagePage.of(1, wide, high)!!
            assertEquals(page.sheet.widthPt, wide / page.dpi * 72f, 0.01f)
            assertEquals(page.sheet.heightPt, high / page.dpi * 72f, 0.01f)
        }
    }
}
