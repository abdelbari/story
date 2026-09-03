package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

/**
 * Documents nobody wrote, shown in the preview.
 *
 * The preview is a page the app builds out of the document and hands to a
 * WebView, so a document's own words end up inside markup. Two things
 * must hold however hostile those words are, and a document's words are
 * not chosen by us: the page must stay well formed, and it must show the
 * document's words — all of them, and nothing that was markup.
 *
 * The words here are chosen to break it: `</p>`, `<script>`, `]]>`, an
 * ampersand on its own, a quotation mark in the middle of a word; and the
 * things that end up inside attributes rather than between tags — a link
 * whose target holds `">`, a typeface named with a quotation mark, a
 * bookmark named with both. The WebView the preview is shown in runs no
 * JavaScript and may reach no file, so a page that escaped nothing would
 * still not be dangerous; it would be wrong, which is enough.
 */
class HtmlFuzzTest {

    private val words = listOf(
        "report", "الجزائر", "données", "2019",
        "a<b&c", "\"quoted\"", "x'y", "</p>", "<script>", "&amp;", "a&b", "-->", "]]>",
    )

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
                superscript = note != null,
                fontFamily = sometimes(1, 4) { "Georgia\"onload=\"alert(1)" },
                colorRgb = sometimes(1, 4) { 0xC00000 },
                link = if (note == null) {
                    sometimes(1, 5) { "https://example.org/\"><b>" + random.nextInt(9) }
                } else {
                    null
                },
                note = note,
            )
        }

        private fun paragraph() = Paragraph(
            runs = (1..random.nextInt(1, 4)).map { run() },
            style = ParagraphStyle(
                kind = ParagraphKind.entries[random.nextInt(ParagraphKind.entries.size)],
                listMarker = sometimes(1, 3) { ListMarker.entries[random.nextInt(2)] },
                listLevel = random.nextInt(3),
                alignment = sometimes(1, 3) { Alignment.entries[random.nextInt(4)] },
                direction = sometimes(1, 4) { TextDirection.entries[random.nextInt(2)] },
            ),
            bookmarks = sometimes(1, 5) { listOf("bm \"x\" <y>") }.orEmpty(),
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

    /** Every word of a document, its cells and the words of its notes included. */
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
        // The preview gathers the notes under a rule at the end, where a
        // page puts them, each under the mark that calls it.
        fun notes(list: List<Block>) {
            for (block in list) when (block) {
                is Paragraph -> for (run in block.runs) run.note?.let { note ->
                    out += run.text
                    for (held in note) if (held is Paragraph) out += held.text
                }
                is Table -> for (row in block.rows) for (cell in row.cells) notes(cell.blocks)
                else -> {}
            }
        }
        notes(blocks)
        return out.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    @Test
    fun `a document nobody wrote is shown whole and leaves the page well formed`() {
        for (seed in 1..300) {
            val document = Documents(Random(seed)).document()
            val html = HtmlWriter.write(document, "doc \"x\" <y>")
            val page = DocumentBuilderFactory.newInstance()
                .also { it.isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(InputSource(ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))))
            val shown = page.getElementsByTagName("body").item(0)
                .textContent.replace(Regex("\\s+"), " ").trim()
            assertEquals(textOf(document.blocks), shown, "seed $seed")
        }
    }
}
