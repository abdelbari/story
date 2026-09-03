package app.morpho.engine.ooxml

import app.morpho.engine.layout.Comment
import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.TableGrid
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Documents nobody wrote, written as .docx and read back.
 *
 * The same idea as the Markdown gate, turned on the format the app exists
 * to produce. A numbered heading was found here — "2. Method", which is
 * how a report, a thesis and a standard number their chapters: told only
 * that the paragraph was an item of a list, the writer named it List
 * Paragraph, and every numbered chapter of every such document came back
 * as body text with the document's outline gone.
 *
 * Two things are asked of each document. Its shape: the kind of every
 * paragraph, the list it sits in and how deep, its alignment and its page
 * break, and the size, rules and head of every table, cells walked as the
 * document is. And its look, per character rather than per run, because a
 * reader may split a run wherever it likes and has lost nothing by doing
 * so — while a character that arrives less bold than it left has.
 *
 * A look the document did not name is not asked about: null means
 * "whatever the file says", and a reader that resolves the styles answers
 * with what the character actually looks like, which is the point of it.
 */
class DocxFuzzTest {

    private val words = listOf(
        "report", "annual", "الجزائر", "التقرير", "données", "2019",
        // The characters XML must escape, in the middle of ordinary words.
        "a<b&c", "\"quoted\"", "x'y",
    )

    /**
     * One document's worth of nonsense. [marks] counts the notes so each
     * has a mark of its own, as a document's notes do.
     */
    private inner class Documents(private val random: Random) {
        private var marks = 0

        private fun words(): String =
            (1..random.nextInt(1, 4)).joinToString(" ") { words[random.nextInt(words.size)] }

        private fun <T> sometimes(one: Int, of: Int, make: () -> T): T? =
            if (random.nextInt(of) < one) make() else null

        private fun run(): TextRun {
            val note = sometimes(1, 8) { listOf<Block>(Paragraph(listOf(TextRun(words())))) }
            val raised = random.nextInt(6) == 0
            return TextRun(
                text = if (note != null) (++marks).toString() else words(),
                bold = random.nextBoolean(),
                italic = random.nextBoolean(),
                underline = random.nextBoolean(),
                strikethrough = random.nextBoolean(),
                // Raised, or lowered, or neither — never both. The model
                // can hold both because they are two flags, but no format
                // can show a character in two places at once, and asking a
                // writer for it is asking it to fail.
                superscript = note != null || raised,
                subscript = note == null && !raised && random.nextInt(8) == 0,
                language = sometimes(1, 4) { listOf("ar", "fr-FR", "en-GB")[random.nextInt(3)] },
                fontFamily = sometimes(1, 4) { listOf("Simplified Arabic", "Georgia")[random.nextInt(2)] },
                fontSizePt = sometimes(1, 4) { listOf(9f, 10.5f, 14f)[random.nextInt(3)] },
                colorRgb = sometimes(1, 4) { listOf(0xC00000, 0x1F4E79, 0x00B050)[random.nextInt(3)] },
                highlightRgb = sometimes(1, 6) { listOf(0xFFFF00, 0x00FFFF)[random.nextInt(2)] },
                link = if (note == null) sometimes(1, 6) { "https://example.org/" + random.nextInt(99) } else null,
                note = note,
            )
        }

        private fun style() = ParagraphStyle(
            kind = ParagraphKind.entries[random.nextInt(ParagraphKind.entries.size)],
            listMarker = sometimes(1, 3) { ListMarker.entries[random.nextInt(2)] },
            listLevel = random.nextInt(3),
            alignment = sometimes(1, 3) { Alignment.entries[random.nextInt(4)] },
            direction = sometimes(1, 4) { TextDirection.entries[random.nextInt(2)] },
            firstLineIndentPt = sometimes(1, 4) { 18f },
            startIndentPt = sometimes(1, 4) { 36f },
            spaceBeforePt = sometimes(1, 4) { 6f },
            spaceAfterPt = sometimes(1, 4) { 12f },
            // The three a paragraph is measured by that nothing here used
            // to write: a hanging indent, an exact line, and the stops a
            // form's tabs land on.
            hangingIndentPt = sometimes(1, 5) { 18f },
            linePitchPt = sometimes(1, 6) { 15f },
            tabStopsPt = sometimes(1, 5) { listOf(72f, 144f, 216f) },
            pageBreakBefore = random.nextInt(8) == 0,
            ruleAbove = random.nextInt(8) == 0,
            ruleBelow = random.nextInt(8) == 0,
        )

        private fun paragraph() = Paragraph((1..random.nextInt(1, 4)).map { run() }, style())

        private fun table(): Table {
            val columns = random.nextInt(1, 4)
            return Table(
                rows = (1..random.nextInt(1, 4)).map { row ->
                    TableRow(
                        cells = (1..columns).map {
                            TableCell(listOf(paragraph()), shadingRgb = sometimes(1, 4) { 0xD9E2F3 })
                        },
                        repeatsAsHeader = row == 1 && random.nextBoolean(),
                    )
                },
                ruled = random.nextBoolean(),
                direction = sometimes(1, 3) { TextDirection.entries[random.nextInt(2)] },
            )
        }

        fun document(): DocumentModel {
            val blocks = (1..random.nextInt(1, 8)).map {
                if (random.nextInt(5) == 0) table() else paragraph()
            }
            val (marked, remarks) = remarked(blocks)
            return DocumentModel(
                blocks = marked,
                defaultDirection = if (random.nextBoolean()) TextDirection.RTL else TextDirection.LTR,
                comments = remarks,
            )
        }

        /**
         * [blocks] with somebody's notes left on them.
         *
         * A note covers an unbroken stretch of a paragraph's runs, because
         * that is what Word can anchor and what the writer therefore
         * writes: given a subject with a hole in it, it marks the stretch
         * from the first run to the last unbroken one and says so. Asking
         * a fuzz for holes would be asking it to fail on purpose.
         */
        private fun remarked(blocks: List<Block>): Pair<List<Block>, List<Comment>> {
            val remarks = mutableListOf<Comment>()
            fun mark(block: Block): Block = when (block) {
                is Paragraph -> {
                    if (block.runs.size < 2 || random.nextInt(4) != 0) block
                    else {
                        val from = random.nextInt(block.runs.size)
                        val to = (from + random.nextInt(1, 3)).coerceAtMost(block.runs.size)
                        val id = remarks.size + 1
                        remarks += Comment(
                            id = id,
                            text = words(),
                            author = if (random.nextBoolean()) "A Reader" else null,
                            initials = if (random.nextBoolean()) "AR" else null,
                        )
                        Paragraph(
                            block.runs.mapIndexed { at, run ->
                                if (at in from until to) run.copy(commentIds = listOf(id)) else run
                            },
                            block.style,
                        )
                    }
                }
                is Table -> Table(
                    block.rows.map { row -> TableRow(row.cells.map { cell ->
                        TableCell(cell.blocks.map(::mark), cell.columnSpan, cell.rowSpan, cell.shadingRgb)
                    }, row.repeatsAsHeader) },
                    block.confidence, block.columnWidthsPt, block.ruled, block.direction,
                )
                else -> block
            }
            return blocks.map(::mark) to remarks
        }
    }

    /** What the document is made of, cells walked as the document is. */
    private fun shape(blocks: List<Block>): List<String> {
        val out = mutableListOf<String>()
        fun walk(list: List<Block>, depth: Int) {
            for (block in list) when (block) {
                is Paragraph -> out += "paragraph@$depth ${block.style.kind}" +
                    (block.style.listMarker?.let { "/$it@${block.style.listLevel}" }.orEmpty()) +
                    // START is what a paragraph is set to when it says
                    // nothing, so saying it and not saying it are the same.
                    (block.style.alignment?.takeIf { it != Alignment.START }
                        ?.let { " align=$it" }.orEmpty()) +
                    (if (block.style.pageBreakBefore) " break" else "")
                is Table -> {
                    out += "table@$depth rows=${block.rows.size} columns=${block.rows[0].cells.size}" +
                        " ruled=${block.ruled}" +
                        // A table that is all head has none: there is no
                        // body under it to head, and it is written and read
                        // back that way on purpose.
                        " head=${TableGrid.headRows(block)}"
                    for (row in block.rows) for (cell in row.cells) walk(cell.blocks, depth + 1)
                }
                else -> out += "image@$depth"
            }
        }
        walk(blocks, 0)
        return out
    }

    private fun runsOf(blocks: List<Block>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        fun walk(list: List<Block>) {
            for (block in list) when (block) {
                is Paragraph -> out += block.runs
                is Table -> for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                else -> {}
            }
        }
        walk(blocks)
        return out
    }

    /**
     * How every character of the document is set. [said] is the document
     * as it was written, whose runs say which of the two measured looks —
     * the face and the size — were named rather than inherited.
     */
    private fun look(
        blocks: List<Block>,
        said: List<TextRun>,
        remarks: List<Comment> = emptyList(),
    ): List<String> {
        val named = said.flatMap { run -> run.text.map { run } }
        // A note is compared by what it says and who left it, and by
        // neither the number it is filed under nor the letters shown in
        // the margin. The number is a handle the writer assigns as it
        // writes the file; the letters a document leaves out are taken
        // from the author's name, which is what Word does and what
        // CommentTest holds this writer to. Asking for either back
        // unchanged would be asking a writer to keep somebody else's
        // bookkeeping rather than to keep the note.
        val saying = remarks.associate { it.id to "${it.author} said ${it.text}" }
        return runsOf(blocks)
            .flatMap { run -> run.text.map { it to run } }
            .mapIndexed { index, (character, run) ->
                val asWritten = named.getOrNull(index)
                "$character bold=${run.bold} italic=${run.italic} under=${run.underline}" +
                    " struck=${run.strikethrough} raised=${run.superscript}" +
                    " lowered=${run.subscript} tongue=${run.language}" +
                    " noted=${run.commentIds.map { saying[it] ?: "?" }}" +
                    " colour=${run.colorRgb} marked=${run.highlightRgb} link=${run.link}" +
                    (if (asWritten?.fontFamily != null) " face=${run.fontFamily}" else "") +
                    (if (asWritten?.fontSizePt != null) " size=${run.fontSizePt}" else "")
            }
    }

    @Test
    fun `a document nobody wrote survives being written as a docx and read back`() {
        for (seed in 1..300) {
            val document = Documents(Random(seed)).document()
            val back = DocxReader.read(DocxWriter.toByteArray(document))
            assertEquals(shape(document.blocks), shape(back.blocks), "seed $seed: shape")
            val said = runsOf(document.blocks)
            assertEquals(
                look(document.blocks, said, document.comments),
                look(back.blocks, said, back.comments),
                "seed $seed: look",
            )
            assertEquals(
                document.comments.map { "${it.author} said ${it.text}" }.sorted(),
                back.comments.map { "${it.author} said ${it.text}" }.sorted(),
                "seed $seed: the notes themselves",
            )
            // A note that names who left it gets letters for the margin,
            // its own or ones taken from that name. A note nobody signed
            // has no name to take them from and gets none, which is what
            // an unsigned note looks like in Word too.
            assertTrue(
                back.comments.filter { it.author != null }.all { !it.initials.isNullOrBlank() },
                "seed $seed: a signed note came back with no letters for the margin",
            )
        }
    }

    /**
     * A document made of pictures as much as words: one in a line of text,
     * one beside a tab stop the way a page's foot sets its number, one in
     * a cell, one standing alone, and the page's own head and foot.
     *
     * A picture is a run with no text of its own. Three writers have now
     * lost one to that — passed over because the paragraph holding it had
     * no words to write — so each is asked to account for every picture it
     * was given, in the places a picture actually turns up.
     */
    private inner class Pictured(private val random: Random) {

        private fun picture() = ImageBlock(
            bytes = PNG,
            mimeType = "image/png",
            widthPx = 8,
            heightPx = 4,
            widthPt = 16f,
            heightPt = 8f,
        )

        private fun paragraph() = Paragraph(
            runs = buildList {
                if (random.nextBoolean()) add(TextRun("before "))
                add(TextRun("", image = picture()))
                if (random.nextBoolean()) add(TextRun("\t"))
                if (random.nextBoolean()) add(TextRun("48", field = RunField.PAGE_NUMBER))
            },
            style = ParagraphStyle(
                direction = if (random.nextBoolean()) TextDirection.RTL else TextDirection.LTR,
                tabStopsPt = if (random.nextBoolean()) listOf(120f, 443f) else null,
                listMarker = if (random.nextBoolean()) ListMarker.BULLET else null,
            ),
        )

        fun document() = DocumentModel(
            blocks = (1..random.nextInt(1, 5)).map {
                when (random.nextInt(3)) {
                    0 -> Table(listOf(TableRow(listOf(TableCell(listOf(paragraph())), TableCell(listOf(paragraph()))))))
                    1 -> picture()
                    else -> paragraph()
                }
            },
            header = if (random.nextBoolean()) listOf(paragraph()) else emptyList(),
            footer = if (random.nextBoolean()) listOf(paragraph()) else emptyList(),
        )
    }

    /** How many pictures a document holds, wherever they are kept. */
    private fun picturesIn(document: DocumentModel): Int {
        var count = 0
        fun walk(blocks: List<Block>) {
            for (block in blocks) when (block) {
                is Paragraph -> count += block.runs.count { it.image != null }
                is Table -> for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                is ImageBlock -> count++
            }
        }
        walk(document.header)
        walk(document.blocks)
        walk(document.footer)
        return count
    }

    @Test
    fun `every picture of a document is drawn in the file`() {
        for (seed in 1..200) {
            val document = Pictured(Random(seed)).document()
            val parts = partsOf(DocxWriter.toByteArray(document))
            val drawings = listOf("word/document.xml", "word/header1.xml", "word/footer1.xml")
                .mapNotNull { parts[it] }
                .sumOf { Regex("<w:drawing>").findAll(it).count() }
            assertEquals(picturesIn(document), drawings, "seed $seed")
        }
    }

    @Test
    fun `every picture a file draws is a picture the package holds`() {
        for (seed in 1..200) {
            val document = Pictured(Random(seed)).document()
            val bytes = DocxWriter.toByteArray(document)
            val parts = partsOf(bytes)
            // Every relationship a drawing points at must be declared in
            // the relationships of the part that draws it, and the file it
            // names must be in the package. A picture Word cannot resolve
            // is a red cross where the head of the page should be.
            for (part in listOf("word/document.xml", "word/header1.xml", "word/footer1.xml")) {
                val xml = parts[part] ?: continue
                val rels = parts[part.replaceFirst("word/", "word/_rels/") + ".rels"].orEmpty()
                for (match in Regex("""r:embed="([^"]+)"""").findAll(xml)) {
                    val id = match.groupValues[1]
                    val target = Regex("""Id="$id"[^>]*Target="([^"]+)"""").find(rels)?.groupValues?.get(1)
                    assertNotNull(target, "seed $seed: $part draws $id with no relationship for it")
                    assertTrue(
                        parts.containsKey("word/" + target), 
                        "seed $seed: $part points at word/$target, which the package does not hold",
                    )
                }
            }
        }
    }

    /** A real 1x1 PNG; what matters here is that every one of them arrives. */
    private val PNG: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAC0lEQVR4nGNgQAcAABIAAeRVjecAAAAASUVORK5CYII="
    )

    /** The package as its parts, the XML ones as text. */
    private fun partsOf(docx: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                out[entry.name] = if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) {
                    bytes.toString(Charsets.UTF_8)
                } else {
                    ""
                }
            }
        }
        return out
    }
}
