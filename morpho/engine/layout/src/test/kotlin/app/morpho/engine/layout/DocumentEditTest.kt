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

    @Test
    fun `putting back a block a join took undoes the join`() {
        // Without this the row's one offer is a trap: a join takes a
        // block's words into the paragraph above and takes the block out,
        // so putting the block back on its own would write its words
        // twice, once above and once alone, with nothing in the file to
        // say which was meant.
        val was = edit("The committee found that the form", "had been received in time.", "A separate finding.")
        val joined = was.joinUp(1)
        val now = joined.restore(1)

        assertEquals(
            listOf("The committee found that the form", "had been received in time.", "A separate finding."),
            texts(now),
        )
        assertFalse(now.touched, "undoing a join left the document counted as edited")
        assertEquals(emptyMap<Int, DocumentEdit.Join>(), now.joins)
    }

    @Test
    fun `undoing a join keeps a correction the block above already had`() {
        val was = edit("The frst half", "and the second.").retext(0, "The first half")
        val now = was.joinUp(1).restore(1)
        assertEquals(listOf("The first half", "and the second."), texts(now))
        assertEquals(setOf(0), now.corrected, "the correction made before the join was lost with it")
    }

    @Test
    fun `a block taken out by hand comes back without disturbing anything`() {
        val was = edit("one", "two", "three")
        assertEquals(listOf("one", "three"), texts(was.remove(1)))
        assertEquals(listOf("one", "two", "three"), texts(was.remove(1).restore(1)))
    }

    @Test
    fun `a join cannot be given back while its words have moved further up`() {
        // Found by the fuzz below, not by hand. Joining the third block
        // into the second and then the second into the first leaves the
        // third block's words in the first. Putting the third back on its
        // own would then say them twice, once where they ended up and once
        // alone, and nothing in the file would say which was meant.
        val was = edit("one", "two", "three")
        val joined = was.joinUp(2).joinUp(1)
        assertEquals(listOf("one two three"), texts(joined))
        assertSame(joined, joined.restore(2), "the inner join was given back out of order")
        // Undone from the outside in, both come back.
        val now = joined.restore(1).restore(2)
        assertEquals(listOf("one", "two", "three"), texts(now))
        assertFalse(now.touched)
    }

    @Test
    fun `a join cannot be given back once the same block has taken another`() {
        // The other way the fuzz found, and the one that loses text rather
        // than doubling it: two blocks joined into the same one, in the
        // order a reader mending a paragraph broken over two pages would.
        // Giving back the first would put back the paragraph as it was
        // before it, over the second join's words, and they would be gone.
        val was = edit("one", "two", "three")
        val joined = was.joinUp(1).joinUp(2)
        assertEquals(listOf("one two three"), texts(joined))
        assertSame(joined, joined.restore(1), "the first join was given back over the second")
        val now = joined.restore(2).restore(1)
        assertEquals(listOf("one", "two", "three"), texts(now))
        assertFalse(now.touched)
    }

    @Test
    fun `joining and undoing in any order leaves the words said once`() {
        // The property the whole thing exists for: however a reader joins
        // and puts back, no block's words appear twice in what is written.
        val rng = kotlin.random.Random(20260907)
        repeat(2000) {
            val size = rng.nextInt(2, 6)
            var now = DocumentEdit(DocumentModel(List(size) { body("block $it") }))
            repeat(rng.nextInt(1, 8)) {
                val at = rng.nextInt(0, size)
                now = if (rng.nextBoolean()) now.joinUp(at) else now.restore(at)
            }
            val written = now.asWritten.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
            for (block in 0 until size) {
                assertEquals(
                    1, Regex("block $block\\b").findAll(written).count(),
                    "\"block $block\" is not said exactly once in \"$written\"",
                )
            }
        }
    }

    @Test
    fun `what can be put back is exactly what putting back would change`() {
        val was = edit("one", "two", "three", "four")
        assertEquals(emptySet<Int>(), was.restorable, "nothing was taken out")
        assertEquals(setOf(2), was.remove(2).restorable)
        // Two joins into the same block: only the later one can be given
        // back, and once it is, the earlier one can.
        val joined = was.joinUp(1).joinUp(2)
        assertEquals(setOf(2), joined.restorable, "the screen would offer a button that does nothing")
        assertEquals(setOf(1), joined.restore(2).restorable)
        // And it never claims more than it can do.
        for (at in joined.removed) {
            assertEquals(
                at in joined.restorable, joined.restore(at) !== joined,
                "block $at is offered and refused, or refused and taken",
            )
        }
    }

    @Test
    fun `a paragraph run together is separated at the lines the reader put in`() {
        // Recognition breaks one paragraph at every column and page, which
        // joining mends, and runs two together wherever the space between
        // them was ambiguous. This is the way back.
        val was = edit("Report of the Committee The form was received.", "A separate finding.")
        val now = was
            .retext(0, "Report of the Committee\nThe form was received.")
            .splitLines(0)
        assertEquals(
            listOf("Report of the Committee", "The form was received.", "A separate finding."),
            texts(now),
        )
        assertEquals(2, now.document.blocks.size, "separating moved the blocks")
        assertEquals(setOf(0), now.splitBlocks)
    }

    @Test
    fun `each separated line keeps the formatting that was on it`() {
        val was = DocumentEdit(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(
                            TextRun("The committee", bold = true),
                            TextRun("\nwrote to "),
                            TextRun("the faculty", link = "https://example.org/faculty"),
                        )
                    )
                )
            )
        )
        val blocks = was.splitLines(0).asWritten.blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf("The committee", "wrote to the faculty"), blocks.map { it.text })
        assertTrue(blocks[0].runs.single().bold, "the first line lost its weight")
        assertEquals(
            "https://example.org/faculty",
            blocks[1].runs.first { it.link != null }.link,
            "the second line lost its link",
        )
    }

    @Test
    fun `a separated paragraph is still the paragraph it was`() {
        val was = DocumentEdit(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(TextRun("One\nTwo")),
                        style = ParagraphStyle(kind = ParagraphKind.HEADING_2, listMarker = ListMarker.BULLET),
                        confidence = 0.5f,
                    )
                )
            )
        )
        for (block in was.splitLines(0).asWritten.blocks.filterIsInstance<Paragraph>()) {
            assertEquals(ParagraphKind.HEADING_2, block.style.kind)
            assertEquals(ListMarker.BULLET, block.style.listMarker)
            assertEquals(0.5f, block.confidence, "breaking a block apart said something about the reading")
        }
    }

    @Test
    fun `only a block with a line break in it can be separated`() {
        val was = edit("one line only", "two\nlines")
        assertEquals(setOf(1), was.splittable)
        assertSame(was, was.splitLines(0), "a block with nothing to separate was separated")
        // Once separated it is not offered again; it is offered back instead.
        val now = was.splitLines(1)
        assertEquals(emptySet<Int>(), now.splittable)
        assertEquals(setOf(1), now.splitBlocks)
        assertSame(now, now.splitLines(1))
        assertEquals(was.splitBlocks, now.unsplitLines(1).splitBlocks)
        assertEquals(listOf("one line only", "two\nlines"), texts(now.unsplitLines(1)))
    }

    @Test
    fun `a block taken out is not offered to be separated`() {
        val was = edit("two\nlines").remove(0)
        assertEquals(emptySet<Int>(), was.splittable)
        assertSame(was, was.splitLines(0))
    }

    @Test
    fun `separating counts as one fix however many lines come out`() {
        val now = edit("a\nb\nc\nd").splitLines(0)
        assertEquals(4, texts(now).size)
        assertEquals(1, now.fixes)
        assertTrue(now.touched)
        assertFalse(now.unsplitLines(0).touched, "putting it back left the document counted as edited")
    }

    @Test
    fun `whatever a reader does, what is written says what the blocks say`() {
        // The property over every edit there is now, separating included:
        // the blocks never move, no mark lands outside the document, and
        // what is written holds the words of exactly the blocks left in.
        val rng = kotlin.random.Random(20260908)
        val words = listOf("one", "two\nthree", "الاستمارة", "", "a longer line\nof words")
        repeat(2000) {
            val size = rng.nextInt(1, 6)
            val start = DocumentEdit(DocumentModel(List(size) { body(words.random(rng)) }))
            var now = start
            repeat(rng.nextInt(0, 9)) {
                val at = rng.nextInt(-1, size + 1)
                now = when (rng.nextInt(7)) {
                    0 -> now.retext(at, words.random(rng))
                    1 -> now.reclassify(at, ParagraphKind.entries.random(rng))
                    2 -> now.remove(at)
                    3 -> now.restore(at)
                    4 -> now.splitLines(at)
                    5 -> now.unsplitLines(at)
                    else -> now.joinUp(at)
                }
            }
            assertEquals(size, now.document.blocks.size, "an edit moved the blocks")
            assertTrue(
                (now.corrected + now.removed + now.splitBlocks).all { it in 0 until size },
                "a mark landed outside the document",
            )
            // Every block still in says its words in what is written, and
            // separating one changes where its words sit and not what they are.
            val written = now.asWritten.blocks.filterIsInstance<Paragraph>().joinToString("\u0000") { it.text }
            for (at in 0 until size) {
                if (at in now.removed) continue
                val said = (now.document.blocks[at] as Paragraph).text
                assertTrue(
                    LineBreaks.split(said).all { it.isEmpty() || written.contains(it) },
                    "block $at says \"$said\" and it is not in \"$written\"",
                )
            }
            assertTrue(now.fixes <= size, "more blocks counted as fixed than there are blocks")
        }
    }
}
