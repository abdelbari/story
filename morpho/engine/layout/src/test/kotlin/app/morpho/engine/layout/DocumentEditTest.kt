package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a reader may do to a converted document, and what none of it may
 * do to the reader.
 *
 * These rules used to live in an Android view model and a Compose screen,
 * where nothing on this machine compiles them and no test touches them —
 * and the one about joining was written out twice, once in each, which is
 * how two copies of a rule come to disagree without anything failing.
 */
class DocumentEditTest {

    private fun body(text: String, confidence: Float = 0.5f) =
        Paragraph(listOf(TextRun(text)), confidence = confidence)

    private fun edit(vararg lines: String) = DocumentEdit(DocumentModel(lines.map { body(it) }))

    private fun texts(edit: DocumentEdit) =
        edit.asWritten.blocks.filterIsInstance<Paragraph>().map { it.text }

    @Test
    fun `taking a block out leaves every other block's position alone`() {
        // The invariant the whole design turns on. Marks are positions, so
        // a block that really left would carry every mark below it onto
        // somebody else's paragraph.
        val was = edit("a stray mark", "The first line", "The second line").retext(2, "The second line, fixed")
        val now = was.remove(0)

        assertEquals(3, now.document.blocks.size, "a removal removed a block from the document")
        assertEquals(setOf(2), now.corrected, "the mark moved off the block it was made on")
        assertEquals("The second line, fixed", now.textOf(2))
        assertEquals(listOf("The first line", "The second line, fixed"), texts(now))
    }

    @Test
    fun `a block taken out and put back is where it always was`() {
        val was = edit("one", "two", "three")
        val now = was.remove(1).restore(1)
        assertEquals(listOf("one", "two", "three"), texts(now))
        assertEquals(emptySet<Int>(), now.removed)
        assertFalse(now.touched, "putting it back left the document counted as edited")
    }

    @Test
    fun `a correction says nothing about how certain the reading was`() {
        val was = edit("recieved")
        val now = was.retext(0, "received")
        assertEquals(0.5f, now.document.blocks[0].confidence)
        assertEquals(0.5f, now.reclassify(0, ParagraphKind.HEADING_1).document.blocks[0].confidence)
    }

    @Test
    fun `a join is offered only where the block above is a paragraph still there`() {
        val document = DocumentModel(
            listOf(
                body("The committee found that the form"),
                body("had been received in time."),
                Table(rows = emptyList()),
                body("Below a table."),
            )
        )
        val was = DocumentEdit(document)
        assertFalse(was.canJoinUp(0), "the first block has nothing above it")
        assertTrue(was.canJoinUp(1))
        assertFalse(was.canJoinUp(2), "a table has no words to join")
        assertFalse(
            was.canJoinUp(3),
            "joining across a table would carry a sentence over what stands between its halves",
        )
        // A block taken out is passed over, because it is not there; the
        // block above it is what the join reaches.
        assertTrue(was.remove(2).canJoinUp(3))
    }

    @Test
    fun `joining marks the block kept and takes out the block joined from`() {
        val now = edit("The committee found that the form", "had been received in time.", "A separate finding.")
            .joinUp(1)
        assertEquals(setOf(0), now.corrected)
        assertEquals(setOf(1), now.removed)
        assertEquals(
            listOf("The committee found that the form had been received in time.", "A separate finding."),
            texts(now),
        )
        assertEquals(3, now.document.blocks.size, "joining moved a block")
    }

    @Test
    fun `a block that is gone cannot be edited, and one that is not there cannot either`() {
        val was = edit("one", "two").remove(1)
        assertSame(was, was.retext(1, "anything"), "a block on its way out took an edit")
        assertSame(was, was.reclassify(1, ParagraphKind.HEADING_1))
        assertSame(was, was.joinUp(1))
        assertEquals("", was.textOf(1))
        for (nowhere in listOf(-1, 2, 99)) {
            assertSame(was, was.retext(nowhere, "x"), "block $nowhere is not in the document")
            assertSame(was, was.remove(nowhere))
            assertSame(was, was.joinUp(nowhere))
            assertFalse(was.canJoinUp(nowhere))
        }
    }

    @Test
    fun `an edit that changes nothing is not an edit`() {
        val was = edit("one")
        assertSame(was, was.retext(0, "one"), "retyping the same words counted as a correction")
        assertSame(was, was.reclassify(0, ParagraphKind.BODY))
        assertSame(was, was.restore(0), "putting back a block that was never taken out")
        assertFalse(was.touched)
    }

    @Test
    fun `the count of fixes counts each block once`() {
        val now = edit("one", "two", "three")
            .retext(0, "ONE")
            .reclassify(0, ParagraphKind.HEADING_1)
            .remove(2)
        assertEquals(2, now.fixes, "one block corrected twice counted twice")
        // A join corrects the block above and removes the one joined from.
        // Here the block above is block 0, which was already counted, so
        // the join adds one and not two.
        assertEquals(3, now.joinUp(1).fixes)
    }

    @Test
    fun `a document a reader empties is still a document`() {
        val now = edit("«", "|", "—").remove(0).remove(1).remove(2)
        assertTrue(now.asWritten.blocks.isEmpty())
        assertEquals(3, now.document.blocks.size, "the blocks are still there to be put back")
        assertEquals(3, now.fixes)
    }

    @Test
    fun `whatever a reader does, the blocks stay where they are and the writing is a subset`() {
        val rng = kotlin.random.Random(20260906)
        val words = listOf("one", "two", "الاستمارة", "", "a longer line of words")
        repeat(2000) {
            val size = rng.nextInt(1, 7)
            val start = DocumentEdit(DocumentModel(List(size) { body(words.random(rng)) }))
            var now = start
            repeat(rng.nextInt(0, 8)) {
                val at = rng.nextInt(-1, size + 1)
                now = when (rng.nextInt(5)) {
                    0 -> now.retext(at, words.random(rng))
                    1 -> now.reclassify(at, ParagraphKind.entries.random(rng))
                    2 -> now.remove(at)
                    3 -> now.restore(at)
                    else -> now.joinUp(at)
                }
            }
            assertEquals(
                size, now.document.blocks.size,
                "an edit moved the blocks: removed=${now.removed} corrected=${now.corrected}",
            )
            assertTrue(
                now.corrected.all { it in 0 until size } && now.removed.all { it in 0 until size },
                "a mark landed outside the document: ${now.corrected} ${now.removed}",
            )
            assertEquals(
                size - now.removed.size, now.asWritten.blocks.size,
                "what is written is not the document less what was taken out",
            )
            assertTrue(now.fixes <= size, "more blocks were counted as fixed than there are blocks")
        }
    }
}
