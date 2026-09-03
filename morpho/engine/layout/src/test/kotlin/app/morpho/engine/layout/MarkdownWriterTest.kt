package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownWriterTest {

    private fun body(vararg runs: TextRun) = Paragraph(runs.toList())

    private fun body(text: String) = Paragraph(listOf(TextRun(text)))

    @Test
    fun `a note goes where Markdown keeps one`() {
        // Dropped instead, the words of the note are gone from the file:
        // a paper's notes are not decoration.
        val model = DocumentModel(
            listOf(
                Paragraph(
                    listOf(
                        TextRun("Rabiha Nebbar"),
                        TextRun("*", superscript = true, note = listOf(body("The author's address."))),
                    )
                ),
                body("The body of the paper."),
            )
        )
        val markdown = MarkdownWriter.write(model)
        assertEquals("Rabiha Nebbar[^*]\n\nThe body of the paper.\n\n[^*]: The author's address.\n", markdown)
    }

    @Test
    fun `two notes marked alike are told apart`() {
        // A label names exactly one note, so where the document's own
        // marks do not, they are numbered instead.
        val model = DocumentModel(
            listOf(
                body(TextRun("One"), TextRun("*", note = listOf(body("First note.")))),
                body(TextRun("Two"), TextRun("*", note = listOf(body("Second note.")))),
            )
        )
        val markdown = MarkdownWriter.write(model)
        assertTrue(markdown.contains("One[^1]"), markdown)
        assertTrue(markdown.contains("Two[^2]"), markdown)
        assertTrue(markdown.contains("[^1]: First note."), markdown)
        assertTrue(markdown.contains("[^2]: Second note."), markdown)
    }

    @Test
    fun `a document with no notes gains nothing`() {
        assertEquals("Plain words.\n", MarkdownWriter.write(DocumentModel(listOf(body("Plain words.")))))
    }

    @Test
    fun `headings map to hash prefixes`() {
        val model = DocumentModel(
            listOf(
                Paragraph(listOf(TextRun("Top")), ParagraphStyle(kind = ParagraphKind.HEADING_1)),
                Paragraph(listOf(TextRun("Mid")), ParagraphStyle(kind = ParagraphKind.HEADING_2)),
                Paragraph(listOf(TextRun("Low")), ParagraphStyle(kind = ParagraphKind.HEADING_3)),
                body("Body."),
            )
        )
        assertEquals("# Top\n\n## Mid\n\n### Low\n\nBody.\n", MarkdownWriter.write(model))
    }

    @Test
    fun `styled runs become emphasis spans`() {
        val model = DocumentModel(
            listOf(
                body(
                    TextRun("plain "),
                    TextRun("bold", bold = true),
                    TextRun(" then "),
                    TextRun("italic", italic = true),
                    TextRun(" then "),
                    TextRun("both", bold = true, italic = true),
                )
            )
        )
        assertEquals("plain **bold** then *italic* then ***both***\n", MarkdownWriter.write(model))
    }

    @Test
    fun `literal asterisks and pipes are escaped`() {
        val model = DocumentModel(listOf(body("2 * 3 | done")))
        assertEquals("2 \\* 3 \\| done\n", MarkdownWriter.write(model))
    }

    @Test
    fun `lists renumber per contiguous list and bullets keep dashes`() {
        fun item(text: String, marker: ListMarker) =
            Paragraph(listOf(TextRun(text)), ParagraphStyle(listMarker = marker))
        val model = DocumentModel(
            listOf(
                item("a", ListMarker.NUMBERED),
                item("b", ListMarker.NUMBERED),
                body("interlude"),
                item("c", ListMarker.NUMBERED),
                item("d", ListMarker.BULLET),
            )
        )
        assertEquals(
            "1. a\n2. b\n\ninterlude\n\n1. c\n- d\n",
            MarkdownWriter.write(model),
        )
    }

    @Test
    fun `tables become pipe tables with a header separator`() {
        fun cell(text: String) = TableCell(listOf(body(text)))
        val model = DocumentModel(
            listOf(
                Table(
                    rows = listOf(
                        TableRow(listOf(cell("Language"), cell("Sample"))),
                        TableRow(listOf(cell("العربية"), cell("مرحبا"))),
                    )
                )
            )
        )
        assertEquals(
            "| Language | Sample |\n| --- | --- |\n| العربية | مرحبا |\n",
            MarkdownWriter.write(model),
        )
    }

    @Test
    fun `an empty document writes an empty string`() {
        assertEquals("", MarkdownWriter.write(DocumentModel(emptyList())))
    }

    @Test
    fun `images become self-contained data-uri image syntax`() {
        val model = DocumentModel(
            listOf(
                body("before"),
                ImageBlock(byteArrayOf(1, 2, 3), "image/png", 4, 4),
                body("after"),
            )
        )
        val markdown = MarkdownWriter.write(model)
        assertEquals("before\n\n![image](data:image/png;base64,AQID)\n\nafter\n", markdown)
    }

    @Test
    fun `write then re-import is a fixed point on the shared subset`() {
        val source = """
            # تقرير الأسبوع

            نصّ عربي فيه **كلمة غامقة** وكلمة *مائلة* أيضًا.

            ## Details

            plain paragraph with **bold** inside.

            1. first
            2. second

            - point one
            - point two
        """.trimIndent()

        val once = PlainTextImporter.import(source)
        val markdown = MarkdownWriter.write(once)
        val twice = PlainTextImporter.import(markdown)

        assertEquals(1.0, FidelityScorer.structureSimilarity(once, twice), 1e-9)
        val textOf = { m: DocumentModel ->
            m.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        }
        assertEquals(1.0, FidelityScorer.textSimilarity(textOf(once), textOf(twice)), 1e-9)
        assertTrue(MarkdownWriter.write(twice) == markdown, "second write drifted")
    }
}
