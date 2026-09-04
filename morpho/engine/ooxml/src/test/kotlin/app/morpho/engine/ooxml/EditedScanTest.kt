package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentEdit
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.MarkdownWriter
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scan, put right by a reader, and written out.
 *
 * Everything here is tested on its own somewhere else: laying words over
 * runs, joining two halves, taking a block out, the line break, and the
 * Word writer. What none of those can catch is the way they meet — a
 * correction that survives being written and read again, a removal that
 * really leaves the file, a join whose second half keeps its link through
 * the writer. So this is the sequence a reader actually performs on a
 * page recognition got wrong, run through to the bytes and back.
 */
class EditedScanTest {

    /**
     * What recognition hands back for a page it read badly: a heading
     * taken for body text, a word misread, a paragraph broken where the
     * page ended, a scanner's edge as a picture of nothing, and a page
     * number that escaped the reading of the page's furniture.
     */
    private fun asRecognized() = DocumentModel(
        listOf(
            Paragraph(listOf(TextRun("Report of the Committee")), confidence = 0.5f),
            Paragraph(listOf(TextRun("The form was recieved by the")), confidence = 0.5f),
            Paragraph(
                listOf(
                    TextRun("office of "),
                    TextRun("the faculty", link = "https://example.org/faculty"),
                    TextRun(" in time."),
                ),
                confidence = 0.5f,
            ),
            ImageBlock(bytes = ByteArray(4), mimeType = "image/png", widthPx = 3, heightPx = 400, confidence = 0.5f),
            Paragraph(listOf(TextRun("١٤")), confidence = 0.5f),
        )
    )

    /** The reader's four corrections, in the order anybody would make them. */
    private fun putRight(model: DocumentModel): DocumentEdit =
        DocumentEdit(model)
            .reclassify(0, ParagraphKind.HEADING_1)
            .retext(1, "The form was received by the")
            .joinUp(2)
            .remove(3)
            .remove(4)

    @Test
    fun `a corrected scan comes out of the Word writer as the reader left it`() {
        val edit = putRight(asRecognized())
        val now = DocxReader.read(DocxWriter.toByteArray(edit.asWritten))
        val paragraphs = now.blocks.filterIsInstance<Paragraph>()

        assertEquals(
            listOf(
                "Report of the Committee",
                "The form was received by the office of the faculty in time.",
            ),
            paragraphs.map { it.text },
        )
        assertEquals(ParagraphKind.HEADING_1, paragraphs[0].style.kind, "the relabelling did not survive")
        assertEquals(
            "https://example.org/faculty",
            paragraphs[1].runs.first { it.link != null }.link,
            "the second half's link was lost in the join or in the writing",
        )
        assertTrue(now.blocks.none { it is ImageBlock }, "the scanner's edge is still in the file")
        assertFalse(paragraphs.any { it.text.contains("١٤") }, "the page number is still in the file")
    }

    @Test
    fun `the same corrections reach the Markdown as well`() {
        val md = MarkdownWriter.write(putRight(asRecognized()).asWritten)
        assertTrue(md.startsWith("# Report of the Committee"), "[$md]")
        assertTrue(md.contains("The form was received by the office of"), "[$md]")
        assertTrue(md.contains("[the faculty](https://example.org/faculty)"), "[$md]")
        assertFalse(md.contains("recieved"), "[$md]")
        assertFalse(md.contains("١٤"), "[$md]")
    }

    @Test
    fun `a reader who changes their mind gets the document back`() {
        // Every removal is undoable because nothing was ever removed. The
        // words corrected stay corrected; what was taken out comes back
        // where it was, not at the end.
        val edit = putRight(asRecognized()).restore(3).restore(4)
        // Four, not five: the half the join took its words from is still
        // out, because putting back what was removed by hand does not undo
        // a join. Its words are in the paragraph above, and restoring it
        // would write them twice.
        assertEquals(
            listOf("Paragraph", "Paragraph", "ImageBlock", "Paragraph"),
            edit.asWritten.blocks.map { it::class.simpleName },
            "a block came back in the wrong place",
        )
        assertEquals("١٤", (edit.asWritten.blocks[3] as Paragraph).text)
        assertEquals(
            "The form was received by the office of the faculty in time.",
            (edit.asWritten.blocks[1] as Paragraph).text,
        )
        // And it can still be put back, which un-joins it in the only sense
        // that matters: the words appear once above and once on their own.
        assertEquals(5, edit.restore(2).asWritten.blocks.size)
    }

    @Test
    fun `a line break a reader types survives the whole way to the file`() {
        val edit = DocumentEdit(DocumentModel(listOf(Paragraph(listOf(TextRun("University Faculty"))))))
            .retext(0, "University of Algiers\nFaculty of Letters")
        val now = DocxReader.read(DocxWriter.toByteArray(edit.asWritten))
        assertEquals(
            "University of Algiers\nFaculty of Letters",
            now.blocks.filterIsInstance<Paragraph>().single().text,
        )
        assertTrue(
            MarkdownWriter.write(edit.asWritten).contains("University of Algiers\\\nFaculty of Letters"),
        )
    }

    @Test
    fun `a reader who takes out everything gets a file, not a failure`() {
        var edit = DocumentEdit(asRecognized())
        for (at in asRecognized().blocks.indices) edit = edit.remove(at)
        assertTrue(edit.asWritten.blocks.isEmpty())
        val bytes = DocxWriter.toByteArray(edit.asWritten)
        assertTrue(bytes.size > 0)
        assertTrue(DocxReader.read(bytes).blocks.isEmpty())
        assertEquals(5, edit.fixes, "every block was taken out and the count says so")
    }
}
