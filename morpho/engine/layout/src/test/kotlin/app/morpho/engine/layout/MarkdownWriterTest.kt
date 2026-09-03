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

    private fun back(vararg blocks: Block): List<Block> =
        PlainTextImporter.import(MarkdownWriter.write(DocumentModel(blocks.toList()))).blocks

    @Test
    fun `runs a marker cannot tell apart are written as one span`() {
        // Word splits a sentence into runs wherever it likes. Closing a
        // marker only to open the same one again writes `~~a~~~~b~~`,
        // whose four tildes are four tildes on the page.
        val md = MarkdownWriter.write(
            DocumentModel(
                listOf(
                    body(
                        TextRun("struck ", strikethrough = true),
                        TextRun("through", strikethrough = true),
                        TextRun(" and "),
                        TextRun("bold ", bold = true),
                        TextRun("throughout", bold = true),
                    )
                )
            )
        )
        assertTrue(md.contains("~~struck through~~"), md)
        assertTrue(md.contains("**bold throughout**"), md)
        assertTrue(!md.contains("~~~~") && !md.contains("****"), md)
    }

    @Test
    fun `one pair of tildes covers a stretch that changes inside it`() {
        // The strike is outside the emphasis, so a struck word that turns
        // bold halfway used to close the tildes and open them again.
        val md = MarkdownWriter.write(
            DocumentModel(
                listOf(
                    body(
                        TextRun("gone", strikethrough = true),
                        TextRun("and gone", strikethrough = true, bold = true),
                    )
                )
            )
        )
        assertTrue(!md.contains("~~~~"), md)
        val runs = (back(body(
            TextRun("gone", strikethrough = true),
            TextRun("and gone", strikethrough = true, bold = true),
        )).single() as Paragraph).runs
        assertEquals("goneand gone", runs.joinToString("") { it.text })
        assertTrue(runs.all { it.strikethrough }, "all of it was struck through")
    }

    @Test
    fun `a link written across several runs keeps the emphasis inside it`() {
        val runs = (back(body(
            TextRun("the ", link = "https://example.org"),
            TextRun("big", link = "https://example.org", bold = true),
            TextRun(" page", link = "https://example.org"),
        )).single() as Paragraph).runs
        assertEquals("the big page", runs.joinToString("") { it.text })
        assertTrue(runs.all { it.link == "https://example.org" }, "all of it was the link")
        assertEquals("big", runs.single { it.bold }.text)
    }

    @Test
    fun `a note's words are written the way the document's are`() {
        // Written raw, a note holding a bracket or an asterisk came back
        // as something else, and a note's bold came back plain.
        val note = listOf<Block>(
            Paragraph(listOf(
                TextRun("See [note 3] and "),
                TextRun("Al-Muqaddima", italic = true),
                TextRun(", p. 4*."),
            ))
        )
        val runs = (back(body(TextRun("A claim."), TextRun("1", note = note)))
            .single() as Paragraph).runs
        val words = runs.single { it.note != null }.note!!.single() as Paragraph
        assertEquals("See [note 3] and Al-Muqaddima, p. 4*.", words.text)
        assertEquals("Al-Muqaddima", words.runs.single { it.italic }.text)
    }

    @Test
    fun `two notes never answer to one label`() {
        // A mark that can be its own label keeps it; a mark that cannot is
        // given a number — and that number must not be one another note
        // already answers to, or both marks lead to the first note's words
        // and the second note is lost outright.
        val first = listOf<Block>(Paragraph(listOf(TextRun("The first note."))))
        val second = listOf<Block>(Paragraph(listOf(TextRun("The second note."))))
        val third = listOf<Block>(Paragraph(listOf(TextRun("The third note."))))
        val runs = (back(body(
            TextRun("a"), TextRun("x", note = first),
            TextRun("b"), TextRun("1", note = second),
            TextRun("c"), TextRun("x", note = third),
        )).single() as Paragraph).runs
        val notes = runs.filter { it.note != null }
            .map { (it.note!!.single() as Paragraph).text }
        assertEquals(listOf("The first note.", "The second note.", "The third note."), notes)
    }

    @Test
    fun `a paragraph that only begins like a heading or a list stays a paragraph`() {
        // "1. Introduction" left over from a list a page drew, "- see the
        // appendix", "#3 in the series": read back as they stand, the
        // marker is eaten and the paragraph comes back a word short.
        for (text in listOf(
            "# not a heading", "## nor this one", "- not a list", "* nor this one",
            "1. not numbered", "2) nor this one", "| not a table |",
        )) {
            val paragraph = back(body(text)).single() as Paragraph
            assertEquals(text, paragraph.text, "text: $text")
            assertEquals(ParagraphKind.BODY, paragraph.style.kind, "kind: $text")
            assertEquals(null, paragraph.style.listMarker, "marker: $text")
        }
    }

    @Test
    fun `a heading and a list item still say what they are`() {
        // The escape is a paragraph's business only: a heading already
        // says what it is, and escaping its hash would leave the hash in
        // the words.
        val blocks = back(
            Paragraph(listOf(TextRun("Findings")), ParagraphStyle(kind = ParagraphKind.HEADING_1)),
            Paragraph(listOf(TextRun("First aim")), ParagraphStyle(listMarker = ListMarker.BULLET)),
        )
        assertEquals(ParagraphKind.HEADING_1, (blocks[0] as Paragraph).style.kind)
        assertEquals("Findings", (blocks[0] as Paragraph).text)
        assertEquals(ListMarker.BULLET, (blocks[1] as Paragraph).style.listMarker)
        assertEquals("First aim", (blocks[1] as Paragraph).text)
    }

    @Test
    fun `a page's head and foot are written once, not lost`() {
        val md = MarkdownWriter.write(
            DocumentModel(
                blocks = listOf(Paragraph(listOf(TextRun("The body of the paper.")))),
                header = listOf(Paragraph(listOf(TextRun("The Journal of Something")))),
                footer = listOf(Paragraph(listOf(TextRun("Volume 5, Issue 1")))),
            )
        )
        assertEquals(
            "The Journal of Something\n\nThe body of the paper.\n\nVolume 5, Issue 1\n",
            md,
        )
    }

    @Test
    fun `a table inside a cell keeps its words`() {
        // Markdown has no table inside a table and no way to invent one,
        // so the words of the inner one are given in the order they are
        // read — but they are given. Dropped, a form or an invoice laid
        // out as a table inside a table loses the half of itself that
        // carries the figures, and nothing in the file says so.
        fun para(text: String) = Paragraph(listOf(TextRun(text)))
        val inner = Table(
            rows = listOf(
                TableRow(listOf(TableCell(listOf(para("net"))), TableCell(listOf(para("120.00"))))),
                TableRow(listOf(TableCell(listOf(para("tax"))), TableCell(listOf(para("24.00"))))),
            ),
        )
        val markdown = MarkdownWriter.write(
            DocumentModel(
                listOf(
                    Table(
                        rows = listOf(
                            TableRow(listOf(TableCell(listOf(para("Item"))), TableCell(listOf(para("Amount"))))),
                            TableRow(listOf(TableCell(listOf(para("Survey"))), TableCell(listOf(inner)))),
                        ),
                    )
                )
            )
        )
        for (word in listOf("net", "120.00", "tax", "24.00")) {
            assertTrue(markdown.contains(word), "\"$word\" was dropped: $markdown")
        }
    }

    @Test
    fun `a picture among words is written where it stood`() {
        val md = MarkdownWriter.write(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(
                            TextRun("before "),
                            TextRun("", image = ImageBlock(byteArrayOf(1, 2, 3), "image/png", 4, 4, 8f, 8f)),
                            TextRun(" after"),
                        )
                    )
                )
            )
        )
        assertEquals("before ![image](data:image/png;base64,AQID) after\n", md)
    }
}
