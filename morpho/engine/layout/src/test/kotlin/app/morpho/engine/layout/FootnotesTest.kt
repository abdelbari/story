package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A PDF has no notes: it has a rule across the bottom of a page and some
 * small text under it, and a raised mark somewhere above. A reader that
 * keeps the words in the order they were painted drops the note into the
 * middle of the text; the mark is what puts it back where it belongs.
 */
class FootnotesTest {

    private fun paragraph(vararg runs: TextRun, rule: Boolean = false) =
        Paragraph(runs.toList(), ParagraphStyle(ruleAbove = rule))

    @Test
    fun `a note under a page's rule goes to the mark that calls it`() {
        val model = Footnotes.refine(
            DocumentModel(
                listOf(
                    paragraph(TextRun("ربيحة نبار "), TextRun("*", superscript = true, fontSizePt = 8f)),
                    paragraph(TextRun("من شروط البحث العلمي الالمام بجميع المعلومات")),
                    paragraph(
                        TextRun("*", superscript = true, fontSizePt = 8f),
                        TextRun(" المؤلف المرسل ."),
                        rule = true,
                    ),
                )
            )
        )
        assertEquals(2, model.blocks.size, "the note stayed in the text")
        val mark = (model.blocks[0] as Paragraph).runs.last()
        assertEquals("*", mark.text, "the mark itself stays where the page put it")
        val note = mark.note?.single() as Paragraph
        assertEquals("المؤلف المرسل .", note.text, "the note keeps its words, without its own mark")
    }

    @Test
    fun `a second note on the same page needs no rule of its own`() {
        val model = Footnotes.refine(
            DocumentModel(
                listOf(
                    paragraph(TextRun("body "), TextRun("1", superscript = true), TextRun(" and "), TextRun("2", superscript = true)),
                    paragraph(TextRun("1", superscript = true), TextRun(" the first note"), rule = true),
                    paragraph(TextRun("2", superscript = true), TextRun(" the second note")),
                )
            )
        )
        assertEquals(1, model.blocks.size)
        val runs = (model.blocks.single() as Paragraph).runs
        assertEquals("the first note", (runs[1].note?.single() as Paragraph).text)
        assertEquals("the second note", (runs[3].note?.single() as Paragraph).text)
    }

    @Test
    fun `a note with nothing to point at is left where it is`() {
        val blocks = listOf(
            paragraph(TextRun("body with no mark at all")),
            paragraph(TextRun("*", superscript = true), TextRun(" a note nobody called"), rule = true),
        )
        val model = Footnotes.refine(DocumentModel(blocks))
        assertEquals(2, model.blocks.size, "better an odd paragraph than a note that disappears")
        assertNull((model.blocks[0] as Paragraph).runs.single().note)
    }

    @Test
    fun `a raised mark in ordinary text is not a note`() {
        // A footnote mark in the middle of a sentence, and no note under a
        // rule: nothing to move.
        val blocks = listOf(
            paragraph(TextRun("the theorem"), TextRun("2", superscript = true), TextRun(" states that")),
            paragraph(TextRun("the next paragraph carries on")),
        )
        assertEquals(blocks, Footnotes.refine(DocumentModel(blocks)).blocks)
    }

    @Test
    fun `a paragraph that merely opens raised is not a note`() {
        // A whole line set as a superscript is not a note; a note has a
        // mark and then words.
        val blocks = listOf(
            paragraph(TextRun("body "), TextRun("*", superscript = true)),
            paragraph(TextRun("*", superscript = true), rule = true),
        )
        assertTrue(Footnotes.refine(DocumentModel(blocks)).blocks.size == 2)
    }
}
