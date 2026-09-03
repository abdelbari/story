package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableGrid
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
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
            return TextRun(
                text = if (note != null) (++marks).toString() else words(),
                bold = random.nextBoolean(),
                italic = random.nextBoolean(),
                underline = random.nextBoolean(),
                strikethrough = random.nextBoolean(),
                superscript = note != null || random.nextInt(6) == 0,
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

        fun document() = DocumentModel(
            blocks = (1..random.nextInt(1, 8)).map {
                if (random.nextInt(5) == 0) table() else paragraph()
            },
            defaultDirection = if (random.nextBoolean()) TextDirection.RTL else TextDirection.LTR,
        )
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
    private fun look(blocks: List<Block>, said: List<TextRun>): List<String> {
        val named = said.flatMap { run -> run.text.map { run } }
        return runsOf(blocks)
            .flatMap { run -> run.text.map { it to run } }
            .mapIndexed { index, (character, run) ->
                val asWritten = named.getOrNull(index)
                "$character bold=${run.bold} italic=${run.italic} under=${run.underline}" +
                    " struck=${run.strikethrough} raised=${run.superscript}" +
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
            assertEquals(look(document.blocks, said), look(back.blocks, said), "seed $seed: look")
        }
    }
}
