package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** A document as text, exactly, and back again — and a session with it. */
class DocumentJsonTest {

    private val words = listOf("form", "بحث", "the", "استمارة", "2022", "x\ty", "a\"b", "🙂", "line\nbreak")

    private fun picture(random: Random) = ImageBlock(
        bytes = ByteArray(random.nextInt(0, 40)) { random.nextInt().toByte() },
        mimeType = listOf("image/png", "image/jpeg")[random.nextInt(2)],
        widthPx = random.nextInt(1, 2000),
        heightPx = random.nextInt(1, 2000),
        confidence = random.nextFloat(),
        widthPt = if (random.nextBoolean()) random.nextFloat() * 500 else null,
        heightPt = if (random.nextBoolean()) random.nextFloat() * 500 else null,
        description = if (random.nextBoolean()) words[random.nextInt(words.size)] else null,
    )

    private fun page(random: Random) = PageSetup(
        widthPt = 595.3f, heightPt = random.nextFloat() * 1000, marginTopPt = 72f, marginBottomPt = 71.9f,
        marginLeftPt = random.nextFloat() * 100, marginRightPt = 72f,
        headerDistancePt = if (random.nextBoolean()) 35.4f else null,
        footerDistancePt = if (random.nextBoolean()) random.nextFloat() * 50 else null,
        firstPageNumber = random.nextInt(1, 5),
        differentFirstPage = random.nextBoolean(),
    )

    /** Everything the model holds, at random, deep enough to matter. */
    private inner class Documents(private val random: Random) {
        private fun <T> sometimes(one: Int, of: Int, make: () -> T): T? = if (random.nextInt(of) < one) make() else null
        private fun word() = words[random.nextInt(words.size)]

        private fun run(depth: Int): TextRun = when (random.nextInt(10)) {
            0 -> TextRun("", image = picture(random))
            1 -> TextRun("7", field = RunField.PAGE_NUMBER)
            2 -> TextRun("1", superscript = true, note = blocks(depth + 1, 2))
            else -> TextRun(
                text = word(),
                bold = random.nextBoolean(), italic = random.nextBoolean(), underline = random.nextBoolean(), strikethrough = random.nextBoolean(),
                language = sometimes(1, 4) { listOf("ar", "fr-FR")[random.nextInt(2)] },
                direction = sometimes(1, 4) { TextDirection.entries[random.nextInt(2)] },
                fontFamily = sometimes(1, 4) { "Simplified Arabic" },
                fontSizePt = sometimes(1, 4) { random.nextFloat() * 30 },
                superscript = random.nextInt(8) == 0, subscript = random.nextInt(8) == 0,
                colorRgb = sometimes(1, 4) { random.nextInt(0x1000000) },
                highlightRgb = sometimes(1, 6) { random.nextInt(0x1000000) },
                link = sometimes(1, 5) { "https://example.org/" + random.nextInt(99) },
                commentIds = (0 until random.nextInt(0, 3)).map { random.nextInt(1, 4) },
            )
        }

        private fun style(): ParagraphStyle = ParagraphStyle(
            kind = ParagraphKind.entries[random.nextInt(ParagraphKind.entries.size)],
            direction = sometimes(1, 4) { TextDirection.entries[random.nextInt(2)] },
            listMarker = sometimes(1, 3) { ListMarker.entries[random.nextInt(2)] },
            listLevel = random.nextInt(3),
            listFormat = sometimes(1, 4) { listOf("decimal", "arabicAlpha")[random.nextInt(2)] },
            alignment = sometimes(1, 3) { Alignment.entries[random.nextInt(4)] },
            firstLineIndentPt = sometimes(1, 4) { random.nextFloat() * 40 },
            startIndentPt = sometimes(1, 4) { 36f },
            hangingIndentPt = sometimes(1, 5) { 18f },
            spaceBeforePt = sometimes(1, 4) { 6f },
            spaceAfterPt = sometimes(1, 4) { random.nextFloat() * 20 },
            linePitchPt = sometimes(1, 6) { 15f },
            tabStopsPt = sometimes(1, 5) { listOf(72f, 144f, random.nextFloat() * 400) },
            ruleAbove = random.nextInt(8) == 0, ruleBelow = random.nextInt(8) == 0,
            pageBreakBefore = random.nextInt(8) == 0,
            sectionSetup = sometimes(1, 8) { page(random) },
        )

        private fun paragraph(depth: Int) = Paragraph(
            runs = (0 until random.nextInt(0, 4)).map { run(depth) },
            style = style(),
            confidence = random.nextFloat(),
            bookmarks = (0 until random.nextInt(0, 3)).map { "bm$it" },
        )

        private fun table(depth: Int): Table = Table(
            rows = (1..random.nextInt(1, 4)).map { row ->
                TableRow(
                    cells = (1..random.nextInt(1, 4)).map {
                        TableCell(
                            blocks = blocks(depth + 1, 2),
                            columnSpan = if (random.nextInt(5) == 0) 2 else 1,
                            rowSpan = if (random.nextInt(5) == 0) 2 else 1,
                            shadingRgb = sometimes(1, 4) { random.nextInt(0x1000000) },
                        )
                    },
                    repeatsAsHeader = row == 1 && random.nextBoolean(),
                )
            },
            confidence = random.nextFloat(),
            columnWidthsPt = sometimes(1, 2) { (1..3).map { random.nextFloat() * 200 } },
            ruled = random.nextBoolean(),
            direction = sometimes(1, 3) { TextDirection.entries[random.nextInt(2)] },
        )

        fun blocks(depth: Int, most: Int): List<Block> = (0 until random.nextInt(0, most + 1)).map {
            when {
                depth < 2 && random.nextInt(6) == 0 -> table(depth)
                random.nextInt(8) == 0 -> picture(random)
                else -> paragraph(depth)
            }
        }

        fun document() = DocumentModel(
            blocks = blocks(0, 6),
            defaultLanguage = sometimes(1, 2) { "ar" },
            defaultDirection = TextDirection.entries[random.nextInt(2)],
            pageSetup = sometimes(1, 2) { page(random) },
            header = blocks(1, 1), footer = blocks(1, 1), evenHeader = blocks(1, 1), evenFooter = blocks(1, 1),
            properties = DocumentProperties(
                title = sometimes(1, 2) { word() }, author = sometimes(1, 2) { word() },
                subject = sometimes(1, 3) { word() }, keywords = sometimes(1, 3) { word() },
            ),
            comments = (0 until random.nextInt(0, 3)).map {
                Comment(id = it + 1, text = word(), author = sometimes(1, 2) { "A" }, initials = sometimes(1, 2) { "AB" }, dateIso = sometimes(1, 2) { "2026-09-04T12:00:00Z" })
            },
        )
    }

    @Test
    fun `whatever a document holds, it reads back as itself`() {
        for (seed in 1..1000) {
            val document = Documents(Random(seed)).document()
            val text = DocumentJson.write(document)
            assertEquals(document, DocumentJson.read(text), "seed $seed")
            // And the text says what shape it is in.
            assertTrue(text.startsWith("{\"morpho\":1,"), text.take(30))
        }
    }

    @Test
    fun `a picture's bytes come back to the byte`() {
        val bytes = ByteArray(256) { it.toByte() }
        val document = DocumentModel(listOf(ImageBlock(bytes, "image/png", 16, 16), Paragraph(listOf(TextRun("", image = ImageBlock(bytes.reversedArray(), "image/jpeg", 2, 2, description = "a\"b"))))))
        val back = DocumentJson.read(DocumentJson.write(document))
        assertTrue((back.blocks[0] as ImageBlock).bytes.contentEquals(bytes))
        assertTrue((back.blocks[1] as Paragraph).runs[0].image!!.bytes.contentEquals(bytes.reversedArray()))
        assertEquals(document, back)
    }

    @Test
    fun `what is not a document is refused and nothing else`() {
        val document = DocumentJson.write(DocumentModel(listOf(Paragraph(listOf(TextRun("a", link = "x"))))))
        val bad = listOf(
            "", "[]", "7", "{}", """{"morpho":2,"blocks":[]}""",
            """{"morpho":1,"blocks":[{"kind":"song"}]}""",
            """{"morpho":1,"blocks":[{"kind":"paragraph","runs":[{"text":"a","bold":"yes"}]}]}""",
            """{"morpho":1,"blocks":[{"kind":"paragraph","runs":[{"bold":true}]}]}""",
            """{"morpho":1,"blocks":[{"kind":"image","bytes":"not base64!!","mimeType":"x","widthPx":1,"heightPx":1}]}""",
            """{"morpho":1,"blocks":[{"kind":"paragraph","runs":[{"text":"a","colorRgb":1e10}]}]}""",
            """{"morpho":1,"blocks":[{"kind":"paragraph","runs":[{"text":"a","colorRgb":1.5}]}]}""",
            """{"morpho":1,"blocks":[{"kind":"table","rows":[{"cells":[{"blocks":[],"columnSpan":0}]}]}]}""",
            """{"morpho":1,"blocks":[{"kind":"paragraph","runs":[],"style":{"kind":"HEADING_9"}}]}""",
            """{"morpho":1,"blocks":[],"pageSetup":{"widthPt":1}}""",
            """{"morpho":1,"blocks":[],"comments":[{"text":"no id"}]}""",
            document.replace("\"link\":\"x\"", "\"link\":7"),
            "{\"morpho\":1,\"blocks\":[" + "{\"kind\":\"table\",\"rows\":[{\"cells\":[{\"blocks\":[".repeat(40),
        )
        for (text in bad) {
            assertThrows(Json.Malformed::class.java, { DocumentJson.read(text) }, "read: ${text.take(80)}")
        }
        // A field left out is what a document that never had it says —
        // down to a document with nothing in it at all.
        val spare = DocumentJson.read("""{"morpho":1,"blocks":[{"kind":"paragraph","runs":[{"text":"a"}]}]}""")
        assertEquals(DocumentModel(listOf(Paragraph(listOf(TextRun("a"))))), spare)
        assertEquals(DocumentModel(emptyList()), DocumentJson.read("""{"morpho":1}"""))
    }

    @Test
    fun `a session saved and restored is the session, less its history`() {
        val opened = EditorState.open(DocumentModel(listOf(Paragraph(listOf(TextRun("one"))), Paragraph(listOf(TextRun("two", bold = true))))))
        val worked = opened.select(Selection.at(1, 3)).type("!").select(Selection.at(0, 1)).splitParagraph().select(Selection(Caret(0, 0), Caret(2, 2)))
        val back = EditorState.restored(worked.saved())
        assertEquals(worked.document, back.document)
        assertEquals(worked.selection, back.selection)
        assertEquals(worked.modified, back.modified, "which blocks changed is kept, by origin")
        assertFalse(back.canUndo, "the history is what a reader would least miss, and is not kept")
        // Work goes on from where it was.
        assertEquals(listOf("o", "neX", "two!"), back.select(Selection.at(1, 2)).type("X").document.blocks.map { (it as Paragraph).text })
        // And what is not a session is refused.
        for (bad in listOf("", "{}", """{"morpho":1}""", worked.saved().replace("\"origins\":", "\"origins\":[9,9,9],\"x\":"))) {
            assertThrows(Json.Malformed::class.java, { EditorState.restored(bad) }, bad.take(40))
        }
    }
}
