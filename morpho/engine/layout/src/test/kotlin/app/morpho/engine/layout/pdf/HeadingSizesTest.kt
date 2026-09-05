package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.ParagraphKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What counts as a heading when the file does not say.
 *
 * Every untagged PDF's outline comes out of this — and so does a tagged
 * one's whose structure tree turns out to name no headings at all — but
 * nothing named it directly: it was reached only through whole readings,
 * where a changed constant shows up as a corpus hash that moved and no
 * explanation of why. These are the decisions themselves, so that a
 * heading that stops being one says so.
 */
class HeadingSizesTest {

    @Test
    fun `a heading is short and set meaningfully larger than the body`() {
        val body = 12f
        assertTrue(HeadingSizes.isCandidate(size = 15f, length = 20, bodySize = body))
        // Larger, but not by enough: a paper sets its footnotes smaller and
        // its emphasis a shade larger, and neither is a heading.
        assertFalse(HeadingSizes.isCandidate(size = 13f, length = 20, bodySize = body))
        // Exactly the factor is enough; a hair under is not.
        assertTrue(HeadingSizes.isCandidate(body * HeadingSizes.SIZE_FACTOR, 20, body))
        assertFalse(HeadingSizes.isCandidate(body * HeadingSizes.SIZE_FACTOR - 0.1f, 20, body))
        // Long and large is a pull quote or a cover, not a heading.
        assertFalse(HeadingSizes.isCandidate(24f, HeadingSizes.MAX_CHARS + 1, body))
        assertTrue(HeadingSizes.isCandidate(24f, HeadingSizes.MAX_CHARS, body))
        // A document with no body text has nothing to be larger than.
        assertFalse(HeadingSizes.isCandidate(24f, 20, bodySize = 0f))
        assertFalse(HeadingSizes.isCandidate(24f, 20, bodySize = -1f))
    }

    @Test
    fun `sizes a hair apart are the same size`() {
        // A page's type is measured, not declared, so the same heading can
        // come back as 15.99 on one line and 16.01 on the next. Without
        // buckets that is two heading levels where the page has one.
        assertEquals(HeadingSizes.sizeKey(16f), HeadingSizes.sizeKey(16.2f))
        assertEquals(HeadingSizes.sizeKey(16f), HeadingSizes.sizeKey(15.8f))
        // Half a point apart is a real difference and stays one.
        assertTrue(HeadingSizes.sizeKey(16f) != HeadingSizes.sizeKey(16.5f))
    }

    @Test
    fun `the largest three sizes are the three levels, and the rest are body`() {
        val ranked = HeadingSizes.rank(listOf(14f, 20f, 16f, 13f))
        assertEquals(ParagraphKind.HEADING_1, ranked[HeadingSizes.sizeKey(20f)])
        assertEquals(ParagraphKind.HEADING_2, ranked[HeadingSizes.sizeKey(16f)])
        assertEquals(ParagraphKind.HEADING_3, ranked[HeadingSizes.sizeKey(14f)])
        // A fourth size is likelier decoration than a fourth level.
        assertEquals(null, ranked[HeadingSizes.sizeKey(13f)])
        assertEquals(3, ranked.size)
    }

    @Test
    fun `the same size named twice is one level`() {
        val ranked = HeadingSizes.rank(listOf(16f, 16.1f, 15.9f, 20f))
        assertEquals(2, ranked.size, "one heading size became two levels: $ranked")
        assertEquals(ParagraphKind.HEADING_1, ranked[HeadingSizes.sizeKey(20f)])
        assertEquals(ParagraphKind.HEADING_2, ranked[HeadingSizes.sizeKey(16f)])
    }

    @Test
    fun `a document that sets nothing large has no heading sizes at all`() {
        assertEquals(emptyMap<Int, ParagraphKind>(), HeadingSizes.rank(emptyList()))
    }

    @Test
    fun `a bold heading at body size sits under whatever larger type found`() {
        // The case this exists for: a hand-formatted paper whose sections
        // are bold at body size, under a title set larger. The title has
        // to come out above its sections rather than level with them.
        assertEquals(
            ParagraphKind.HEADING_1,
            HeadingSizes.boldLevel(emptyMap()),
            "with no larger type anywhere, bold is the top level",
        )
        assertEquals(
            ParagraphKind.HEADING_2,
            HeadingSizes.boldLevel(HeadingSizes.rank(listOf(18f))),
        )
        assertEquals(
            ParagraphKind.HEADING_3,
            HeadingSizes.boldLevel(HeadingSizes.rank(listOf(18f, 15f))),
        )
        // With all three levels already taken, bold joins the deepest
        // rather than falling off the end into body text.
        assertEquals(
            ParagraphKind.HEADING_3,
            HeadingSizes.boldLevel(HeadingSizes.rank(listOf(20f, 18f, 15f))),
        )
    }

    @Test
    fun `bold stops being evidence when most of the document is bold`() {
        assertTrue(HeadingSizes.boldIsMeaningful(boldParagraphs = 5, totalParagraphs = 100))
        assertFalse(HeadingSizes.boldIsMeaningful(boldParagraphs = 90, totalParagraphs = 100))
        // Exactly half is not a mark on the headings; it is a choice about
        // the text.
        assertFalse(HeadingSizes.boldIsMeaningful(50, 100))
        assertTrue(HeadingSizes.boldIsMeaningful(49, 100))
        // A document of nothing has no evidence either way.
        assertFalse(HeadingSizes.boldIsMeaningful(0, 0))
    }

    @Test
    fun `the body size is the middle of what the page sets, not its average`() {
        // A page whose footnotes are 8pt and whose title is 24pt has a body
        // of 12; an average would be pulled off it by both.
        assertEquals(12f, HeadingSizes.median(listOf(8f, 12f, 12f, 12f, 24f)))
        assertEquals(11f, HeadingSizes.median(listOf(10f, 12f)))
        assertEquals(0f, HeadingSizes.median(emptyList()))
        assertEquals(12f, HeadingSizes.median(listOf(12f)))
        // Order cannot matter.
        assertEquals(
            HeadingSizes.median(listOf(24f, 8f, 12f, 12f, 12f)),
            HeadingSizes.median(listOf(8f, 12f, 12f, 12f, 24f)),
        )
    }
}
