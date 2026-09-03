package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
            val misnested = misnested(page.getElementsByTagName("body").item(0))
            assertTrue(misnested.isEmpty(), "seed $seed: " + misnested.joinToString(", "))
        }
    }

    /** Blocks HTML does not allow where they stand, which a browser moves. */
    private val blockTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "table", "thead", "tbody",
        "tr", "td", "th", "ul", "ol", "li", "section", "header", "footer", "hr",
        "blockquote", "pre", "figure",
    )

    /** Tags that hold text and nothing block-shaped. */
    private val inlineTags = setOf("span", "a", "em", "strong", "u", "s", "sub", "sup", "b", "i")

    /**
     * Every block written somewhere HTML does not allow one.
     *
     * A paragraph closes at the first block inside it and an inline
     * element cannot hold one at all, so a browser does not read what
     * such a page says — it moves the block out and leaves the rest
     * behind it, which on a phone is a preview whose halves have swapped
     * places. Parsing as XML proves the tags are balanced and says
     * nothing about this: a div inside a p is perfectly well-formed XML.
     */
    private fun misnested(root: org.w3c.dom.Node): List<String> {
        val faults = mutableListOf<String>()
        fun walk(node: org.w3c.dom.Node, inside: List<String>) {
            val name = (node as? org.w3c.dom.Element)?.tagName?.lowercase()
            if (name != null && name in blockTags) {
                if (name != "p" && "p" in inside) faults += "<$name> inside <p>"
                if (name == "p" && "p" in inside) faults += "<p> inside <p>"
                inside.lastOrNull()?.takeIf { it in inlineTags }?.let { faults += "<$name> inside <$it>" }
            }
            val deeper = if (name == null) inside else inside + name
            val children = node.childNodes
            for (index in 0 until children.length) walk(children.item(index), deeper)
        }
        walk(root, emptyList())
        return faults
    }

    /**
     * A document made of pictures as much as words: one in a line of text,
     * one beside a tab stop the way a page's foot sets its number, one in
     * a cell, one standing alone, and the page's own head and foot.
     *
     * Every one of those places has lost a picture. A picture is a run
     * with no text of its own, and a writer that walks a paragraph asking
     * it for its words passes over the picture without noticing — which is
     * how a foot set as the page set it came out as its page number and
     * nothing else. Counting them is the check that catches it: the words
     * are all still there when the picture is gone.
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

        private fun runs(): List<TextRun> = buildList {
            if (random.nextBoolean()) add(TextRun("before "))
            add(TextRun("", image = picture()))
            if (random.nextBoolean()) add(TextRun("\t"))
            if (random.nextBoolean()) add(TextRun("after"))
        }

        private fun paragraph() = Paragraph(
            runs = runs(),
            style = ParagraphStyle(
                direction = if (random.nextBoolean()) TextDirection.RTL else TextDirection.LTR,
                // The stops that broke it: a picture, a tab, a number.
                tabStopsPt = if (random.nextBoolean()) listOf(120f, 443f) else null,
                alignment = if (random.nextBoolean()) Alignment.CENTER else null,
            ),
        )

        fun document() = DocumentModel(
            blocks = (1..random.nextInt(1, 5)).map {
                when (random.nextInt(3)) {
                    0 -> Table(
                        rows = listOf(TableRow(listOf(TableCell(listOf(paragraph())), TableCell(listOf(paragraph()))))),
                    )
                    1 -> picture()
                    else -> paragraph()
                }
            },
            header = if (random.nextBoolean()) listOf(paragraph()) else emptyList(),
            footer = if (random.nextBoolean()) listOf(paragraph()) else emptyList(),
            defaultDirection = if (random.nextBoolean()) TextDirection.RTL else TextDirection.LTR,
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
    fun `every picture of a document reaches the preview`() {
        for (seed in 1..200) {
            val document = Pictured(Random(seed)).document()
            val html = HtmlWriter.write(document, "doc")
            assertEquals(
                picturesIn(document),
                Regex("<img\\b").findAll(html).count(),
                "seed $seed",
            )
        }
    }
}
