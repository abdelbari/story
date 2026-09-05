package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Documents nobody wrote, written as Markdown and read back.
 *
 * The app writes Markdown and reads Markdown, so its own output is one of
 * its inputs. The cases that break that round trip are the ones nobody
 * thinks to write a test for: a paragraph whose first word is `1.`, a
 * note whose words hold a bracket, two struck-through runs Word happened
 * to split in the middle of a phrase, a mark that cannot be its own label
 * landing on a number another note already answers to. Each of those was
 * found here, by making documents out of the characters the writers must
 * escape and the words they must not read as syntax.
 *
 * The generator separates a change of emphasis with a space, which is
 * what a document does: `**bold** *italic*`. Written with nothing between
 * them the two markers meet as one run of four asterisks, which neither
 * this reader nor CommonMark's own rules read as emphasis — the words all
 * survive and the markers show as characters. That is the one thing this
 * round trip is known not to keep, and [`markers that meet are the one
 * thing this cannot keep`] pins it.
 */
class MarkdownFuzzTest {

    /**
     * Words a document holds, and every character the writers escape or
     * read as syntax. A round trip that survives prose but not a bracket
     * is one that fails on the first bibliography it meets.
     */
    private val words = listOf(
        "report", "annual", "الجزائر", "التقرير", "données", "Bericht", "2019", "12,400",
        "a*b", "x~y", "p|q", "see [note 3]", "[Ibn Khaldun 1377]", "back\\slash",
        "# not a heading", "- not a list", "1. not numbered", "> not a quote",
        "![image](x)", "[](empty)", "a[b](c)d", "[^7]", "[^7]: not a note",
        "ends with *", "**", "~~", "|||", "]", "[",
    )

    /**
     * One document's worth of nonsense. [marks] counts the notes so that
     * each has a mark of its own, as a document's notes do: two notes that
     * print the same mark cannot both keep it in Markdown, where a label
     * names one note, and `a note that shares a mark is numbered instead`
     * pins what happens then.
     */
    private inner class Documents(private val random: Random) {
        private var marks = 0

        private fun words(): String =
            (1..random.nextInt(1, 4)).joinToString(" ") { words[random.nextInt(words.size)] }

        private fun run(first: Boolean): TextRun {
            val note = if (random.nextInt(6) == 0) {
                listOf<Block>(Paragraph(listOf(TextRun(words()))))
            } else {
                null
            }
            // A change of emphasis carries a space before it, as a
            // document's own words do; see the class comment for what
            // happens when it does not.
            val text = if (note != null) (++marks).toString() else words()
            return TextRun(
                text = if (first || note != null) text else " $text",
                bold = random.nextBoolean(),
                italic = random.nextBoolean(),
                strikethrough = random.nextBoolean(),
                underline = random.nextBoolean(),
                superscript = note != null,
                link = if (note == null && random.nextInt(5) == 0) {
                    "https://example.org/" + random.nextInt(99)
                } else {
                    null
                },
                note = note,
            )
        }

        private fun paragraph() = Paragraph(
            runs = (1..random.nextInt(1, 5)).map { run(first = it == 1) },
            style = ParagraphStyle(
                kind = ParagraphKind.entries[random.nextInt(ParagraphKind.entries.size)],
                listMarker = if (random.nextInt(3) == 0) ListMarker.entries[random.nextInt(2)] else null,
                listLevel = random.nextInt(3),
            ),
        )

        private fun table(): Table {
            val columns = random.nextInt(1, 4)
            return Table(
                rows = (1..random.nextInt(1, 4)).map { row ->
                    TableRow(
                        cells = (1..columns).map { TableCell(listOf(paragraph())) },
                        repeatsAsHeader = row == 1 && random.nextBoolean(),
                    )
                },
            )
        }

        fun document() = DocumentModel(
            blocks = (1..random.nextInt(1, 8)).map {
                if (random.nextInt(5) == 0) table() else paragraph()
            },
        )
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

    /** Every word of a document, its tables and the words of its notes included. */
    private fun textOf(blocks: List<Block>): String {
        val out = mutableListOf<String>()
        fun walk(list: List<Block>) {
            for (block in list) when (block) {
                is Paragraph -> out += block.text
                is Table -> for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                else -> {}
            }
        }
        walk(blocks)
        for (run in runsOf(blocks)) run.note?.let { note ->
            out += run.text
            for (held in note) if (held is Paragraph) out += held.text
        }
        return out.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Where the document points. A link on two runs is one link: the
     * direction pass splits a run wherever the script changes, and
     * emphasis inside a link is a run of its own.
     */
    private fun linksOf(blocks: List<Block>): List<String> =
        runsOf(blocks).mapNotNull { it.link }.fold(mutableListOf()) { out, link ->
            if (out.lastOrNull() != link) out += link
            out
        }

    /** The words of each note, in the order their marks appear. */
    private fun notesOf(blocks: List<Block>): List<String> =
        runsOf(blocks).filter { it.note != null }.map { run ->
            run.note!!.filterIsInstance<Paragraph>().joinToString(" ") { it.text }.trim()
        }

    @Test
    fun `a document nobody wrote survives being written as markdown and read back`() {
        for (seed in 1..300) {
            val document = Documents(Random(seed)).document()
            val back = PlainTextImporter.import(MarkdownWriter.write(document))
            assertEquals(textOf(document.blocks), textOf(back.blocks), "seed $seed: words")
            assertEquals(linksOf(document.blocks), linksOf(back.blocks), "seed $seed: links")
            assertEquals(notesOf(document.blocks), notesOf(back.blocks), "seed $seed: notes")
        }
    }

    @Test
    fun `markers that meet are the one thing this cannot keep`() {
        // Bold against italic with nothing between writes `**aa***bb*`,
        // and a run of more than two asterisks opens nothing — here or by
        // CommonMark's own rules. Every word still comes back; the markers
        // come back with them, as the characters they are. Stated so that
        // the day it is fixed, this test is what says so.
        val document = DocumentModel(
            listOf(Paragraph(listOf(TextRun("aa", bold = true), TextRun("bb", italic = true))))
        )
        val back = PlainTextImporter.import(MarkdownWriter.write(document))
        val text = (back.blocks.single() as Paragraph).text
        assertTrue(text.contains("aa") && text.contains("bb"), text)
        assertEquals("**aa***bb*", text, "the markers show as characters")
    }

    @Test
    fun `a note that shares a mark is numbered instead`() {
        // A Markdown label names one note, so two notes a page marked the
        // same way — two asterisks, on two different pages — cannot both
        // keep their mark. They are numbered rather than merged: a mark
        // that changes is a smaller loss than a note that is lost.
        val first = listOf<Block>(Paragraph(listOf(TextRun("The first note."))))
        val second = listOf<Block>(Paragraph(listOf(TextRun("The second note."))))
        val document = DocumentModel(
            listOf(
                Paragraph(listOf(
                    TextRun("a"), TextRun("*", note = first),
                    TextRun("b"), TextRun("*", note = second),
                ))
            )
        )
        val runs = (PlainTextImporter.import(MarkdownWriter.write(document))
            .blocks.single() as Paragraph).runs
        val marked = runs.filter { it.note != null }
        assertEquals(listOf("1", "2"), marked.map { it.text })
        assertEquals(
            listOf("The first note.", "The second note."),
            marked.map { (it.note!!.single() as Paragraph).text },
        )
    }

    /**
     * A document made of pictures as much as words: one in a line of text,
     * one beside a tab, one in a cell, one standing alone, and the page's
     * own head and foot.
     *
     * A picture is a run with no text of its own, and a writer that walks
     * a paragraph asking it for its words passes over the picture without
     * noticing. That is how a foot set as the page set it — the running
     * head, a tab, the page number — came out of this writer as the
     * number alone, and out of the preview the same way. Counting them is
     * what catches it: every word is still there when the picture is gone.
     */
    private inner class Pictured(private val random: Random) {

        private fun picture() = ImageBlock(
            bytes = byteArrayOf(1, 2, 3, random.nextInt(256).toByte()),
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
                if (random.nextBoolean()) add(TextRun("after", link = "https://example.org"))
            },
            style = ParagraphStyle(
                listMarker = if (random.nextBoolean()) ListMarker.BULLET else null,
                tabStopsPt = if (random.nextBoolean()) listOf(120f, 443f) else null,
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
    fun `every picture of a document is written out`() {
        for (seed in 1..200) {
            val document = Pictured(Random(seed)).document()
            val md = MarkdownWriter.write(document)
            assertEquals(
                picturesIn(document),
                Regex("!\\[image]\\(data:").findAll(md).count(),
                "seed $seed",
            )
        }
    }

    @Test
    fun `nothing written as Markdown begins a line with a tab`() {
        for (seed in 1..200) {
            val md = MarkdownWriter.write(Pictured(Random(seed)).document())
            // A line that begins with a tab is a block of code, which is
            // not what a foot set to a tab stop meant to say.
            assertTrue(md.lineSequence().none { it.startsWith("\t") }, "seed $seed: $md")
        }
    }
}
