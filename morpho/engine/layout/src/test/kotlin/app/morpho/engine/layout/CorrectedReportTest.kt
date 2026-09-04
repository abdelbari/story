package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a correction may and may not do to the report the reader is
 * correcting from.
 *
 * Review Mode lists the document a block at a time and remembers which
 * ones a reader has put right by their position in that list. Every one of
 * those marks is a claim about a block, and a correction that shifted the
 * list — dropped a block, split one, renumbered them — would move the
 * marks onto other people's paragraphs and the reader would be told the
 * app had fixed text it had never touched. So the rule is that a
 * correction changes one entry and leaves the rest identical, and that is
 * worth holding to rather than assuming.
 */
class CorrectedReportTest {

    private fun body(text: String, confidence: Float = 0.5f) =
        Paragraph(listOf(TextRun(text)), confidence = confidence)

    private fun scan(vararg lines: String) = DocumentModel(lines.map { body(it) })

    /** The one edit the app makes: [ParagraphEdit] over the block at [at]. */
    private fun corrected(model: DocumentModel, at: Int, text: String): DocumentModel {
        val blocks = model.blocks.toMutableList()
        blocks[at] = ParagraphEdit.retext(blocks[at] as Paragraph, text)
        return model.copy(blocks = blocks)
    }

    @Test
    fun `a correction moves one entry and no other`() {
        val was = scan("The frst line", "The second line", "The third line")
        val now = corrected(was, 0, "The first line")

        val before = FidelityReport.of(was)
        val after = FidelityReport.of(now)

        assertEquals(before.entries.size, after.entries.size, "a correction lost or gained a block")
        assertEquals(before.entries.map { it.index }, after.entries.map { it.index })
        for (at in 1 until before.entries.size) {
            assertEquals(
                before.entries[at], after.entries[at],
                "correcting one block changed the entry for block $at",
            )
        }
    }

    @Test
    fun `the corrected block shows the corrected words`() {
        // Without this the reader fixes a word, the list still shows the
        // old one, and the only honest conclusion available to them is
        // that the correction did not take.
        val now = corrected(scan("recieved by the office"), 0, "received by the office")
        assertEquals("received by the office", FidelityReport.of(now).entries[0].excerpt)
    }

    @Test
    fun `a correction says nothing about how certain the reading was`() {
        // The report is the app's account of what it guessed, not of what
        // it has since been told. A reader who corrects three words out of
        // a doubtful page has said nothing about the rest of it, and a
        // score that crept up every time somebody touched a block would
        // end up claiming a certainty nobody ever checked.
        val was = scan("frst", "second", "third")
        val now = corrected(was, 0, "first")
        assertEquals(FidelityReport.of(was).overall, FidelityReport.of(now).overall)
        assertEquals(FidelityReport.of(was).counts, FidelityReport.of(now).counts)
        assertEquals(FidelityReport.Band.LOW, FidelityReport.of(now).entries[0].band)
    }

    @Test
    fun `a block still in the list to check is still in the list to check`() {
        // Review Mode opens filtered to the doubtful blocks. If correcting
        // one dropped it out of that filter, the row the reader was
        // working in would vanish from under them mid-edit.
        val was = scan("frst", "second", "third")
        val now = corrected(was, 0, "first")
        assertEquals(
            FidelityReport.of(was).reviewables.map { it.index },
            FidelityReport.of(now).reviewables.map { it.index },
        )
    }

    @Test
    fun `what the list shows is not what an editor may be given`() {
        // The trap this exists to name. An entry's excerpt is a label: at
        // eighty code points it is cut and an ellipsis put on the end. An
        // editor seeded from one and saved back would cut the paragraph
        // there for real — a conversion silently losing most of a
        // paragraph while showing the reader a message about correcting
        // it. So the editor asks the model for the whole text, and this
        // test is here to say why that indirection is not redundant.
        val long = "الاستمارة في البحث العلمي ".repeat(20)
        val entry = FidelityReport.of(DocumentModel(listOf(body(long)))).entries[0]
        assertNotEquals(long, entry.excerpt, "the excerpt is short enough to be a label")
        assertTrue(entry.excerpt.endsWith("…"), "and says so where it is cut")
        assertTrue(entry.excerpt.length < long.length / 2)

        // And what happens if it is used anyway, so the cost is on record.
        val truncated = ParagraphEdit.retext(body(long), entry.excerpt)
        assertTrue(
            truncated.text.length < long.length / 2,
            "writing the excerpt back would throw most of the paragraph away",
        )
    }

    @Test
    fun `correcting every block in turn leaves a document of the same shape`() {
        val was = scan("one", "two", "three", "four", "five")
        var now = was
        for (at in was.blocks.indices) now = corrected(now, at, "block $at")
        assertEquals(was.blocks.size, now.blocks.size)
        assertEquals(
            List(was.blocks.size) { "block $it" },
            now.blocks.map { (it as Paragraph).text },
        )
        assertEquals(
            was.blocks.map { it.confidence },
            now.blocks.map { it.confidence },
            "correcting the words never touched the confidence",
        )
    }
}
