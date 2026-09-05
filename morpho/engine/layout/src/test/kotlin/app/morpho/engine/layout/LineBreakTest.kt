package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A line break inside a paragraph, written out four ways and read back.
 *
 * Every format spells it differently and three of the four treat a bare
 * newline as ordinary whitespace, so a document set on two lines came out
 * on one — in the preview, in the Markdown, and in the Word file — while
 * the drawn page broke it, because the platform's text layout honours a
 * newline and nobody had told it not to. One document, four answers.
 */
class LineBreakTest {

    private fun paragraph(text: String, kind: ParagraphKind = ParagraphKind.BODY) =
        Paragraph(listOf(TextRun(text)), style = ParagraphStyle(kind = kind))

    @Test
    fun `the preview breaks the line rather than folding it into a space`() {
        val html = HtmlWriter.write(DocumentModel(listOf(paragraph("Faculty\nof Letters"))), "x.docx")
        assertTrue(html.contains("Faculty<br>of Letters"), html.substringAfter("<body>"))
    }

    @Test
    fun `what is broken is still escaped on both sides of the break`() {
        val html = HtmlWriter.write(DocumentModel(listOf(paragraph("a<b\nc&d"))), "x.docx")
        assertTrue(html.contains("a&lt;b<br>c&amp;d"), html.substringAfter("<body>"))
    }

    @Test
    fun `Markdown breaks a line the way that survives an editor`() {
        // Two trailing spaces are Markdown's other hard break and the one
        // to avoid: every editor that trims trailing whitespace throws it
        // away silently, turning the break back into the space this exists
        // to prevent.
        val md = MarkdownWriter.write(DocumentModel(listOf(paragraph("Faculty\nof Letters"))))
        assertTrue(md.contains("Faculty\\\nof Letters"), "[$md]")
        assertFalse(md.contains("Faculty  \n"), "[$md]")
    }

    @Test
    fun `a heading is set on one line because Markdown has no other kind`() {
        val md = MarkdownWriter.write(
            DocumentModel(listOf(paragraph("A title\nof two lines", ParagraphKind.HEADING_1)))
        )
        assertEquals("# A title of two lines", md.trim())
    }

    @Test
    fun `a broken line comes back off the Markdown as the same broken line`() {
        val was = DocumentModel(
            listOf(
                paragraph("Faculty of Letters\nUniversity of Algiers\nAlgiers"),
                paragraph("An ordinary paragraph, soft-wrapped by whoever wrote it."),
            )
        )
        val now = PlainTextImporter.import(MarkdownWriter.write(was))
        assertEquals(
            was.blocks.filterIsInstance<Paragraph>().map { it.text },
            now.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a backslash the document's own words end a line on is not a break`() {
        // The trap in choosing the backslash: a document that ends a line
        // with one of its own writes it escaped, as two, and two is not a
        // break. Counted rather than looked at, or a file path at a line
        // ending would split the paragraph.
        val was = DocumentModel(listOf(paragraph("C:\\Users\\ and then some more words about it")))
        val md = MarkdownWriter.write(was)
        val now = PlainTextImporter.import(md)
        assertEquals(
            "C:\\Users\\ and then some more words about it",
            now.blocks.filterIsInstance<Paragraph>().single().text,
            "[$md]",
        )
    }

    @Test
    fun `a table cell is set on one line, since Markdown's tables hold one`() {
        val table = Table(
            rows = listOf(
                TableRow(listOf(TableCell(listOf(paragraph("Name\nand rank"))), TableCell(listOf(paragraph("1377"))))),
            )
        )
        val md = MarkdownWriter.write(DocumentModel(listOf(table)))
        assertTrue(md.contains("Name and rank"), "[$md]")
        // A break left in would end the row where the cell was meant to
        // continue, and everything after it would be read as ordinary text.
        assertFalse(md.contains("Name\\\n"), "[$md]")
        assertFalse(md.contains("Name\n"), "[$md]")
    }

    @Test
    fun `a carriage return is the same break, wherever it came from`() {
        for (written in listOf("one\r\ntwo", "one\rtwo", "one\ntwo")) {
            assertEquals(listOf("one", "two"), LineBreaks.split(written), "[$written]")
            assertEquals("one two", LineBreaks.flattened(written))
        }
        assertEquals(listOf("no break here"), LineBreaks.split("no break here"))
        assertEquals(listOf("", ""), LineBreaks.split("\n"), "a break with nothing either side is still a break")
        assertEquals(listOf("", "a", ""), LineBreaks.split("\na\n"))
    }

    @Test
    fun `whatever a paragraph holds, breaking it and joining it is what it was`() {
        val pieces = listOf("", "a", "\n", "\r", "\r\n", "الاستمارة", " ", "\t", "\\", "word")
        val rng = kotlin.random.Random(20260904)
        repeat(3000) {
            val text = (1..rng.nextInt(0, 7)).joinToString("") { pieces.random(rng) }
            val lines = LineBreaks.split(text)
            assertTrue(lines.isNotEmpty(), "[$text] split into nothing")
            assertTrue(lines.none { LineBreaks.breaks(it) }, "[$text] left a break inside a line")
            assertEquals(
                LineBreaks.normalized(text), lines.joinToString("\n"),
                "[$text] did not come back from its own pieces",
            )
        }
    }
}
