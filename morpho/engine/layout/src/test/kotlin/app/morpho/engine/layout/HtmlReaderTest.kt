package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.random.Random

/** Rich text from another app's clipboard, read as blocks. */
class HtmlReaderTest {

    private fun texts(blocks: List<Block>): List<Any?> = blocks.map { block ->
        when (block) {
            is Paragraph -> block.text
            is Table -> block.rows.map { r -> r.cells.map { c -> texts(c.blocks) } }
            is ImageBlock -> "<image>"
        }
    }

    private fun runs(block: Block) = (block as Paragraph).runs.map { listOf(it.text, it.bold, it.italic, it.underline, it.strikethrough, it.link) }

    @Test
    fun `paragraphs, headings, and the words set inside them`() {
        val blocks = HtmlReader.read("<h1>Title</h1><p>Hello <b>bold</b> and <i>it</i>, <a href=\"https://x\">a link</a>.</p><h2>Head</h2><h5>deep</h5><p>plain")
        assertEquals(listOf("Title", "Hello bold and it, a link.", "Head", "deep", "plain"), texts(blocks))
        assertEquals(listOf(ParagraphKind.HEADING_1, ParagraphKind.BODY, ParagraphKind.HEADING_2, ParagraphKind.HEADING_3, ParagraphKind.BODY), blocks.map { (it as Paragraph).style.kind })
        assertEquals(
            listOf(
                listOf("Hello ", false, false, false, false, null), listOf("bold", true, false, false, false, null), listOf(" and ", false, false, false, false, null),
                listOf("it", false, true, false, false, null), listOf(", ", false, false, false, false, null), listOf("a link", false, false, false, false, "https://x"), listOf(".", false, false, false, false, null),
            ),
            runs(blocks[1]),
        )
        assertEquals(ParagraphKind.TITLE, (HtmlReader.read("""<h1 class="doc-title">The paper</h1>""")[0] as Paragraph).style.kind)
    }

    @Test
    fun `lists nest, and the level of an item is how many lists are open round it`() {
        val blocks = HtmlReader.read("<ul><li>one</li><li>two<ul><li>deep</li></ul></li></ul><ol><li><p>numbered</p></li></ol><p>after</p>")
        assertEquals(listOf("one", "two", "deep", "numbered", "after"), texts(blocks))
        val styles = blocks.map { (it as Paragraph).style }
        assertEquals(listOf(ListMarker.BULLET, ListMarker.BULLET, ListMarker.BULLET, ListMarker.NUMBERED, null), styles.map { it.listMarker })
        assertEquals(listOf(0, 0, 1, 0, 0), styles.map { it.listLevel })
    }

    @Test
    fun `what Docs writes as spans with styles is read as what it means`() {
        val html = """<p dir="rtl" style="text-align:center"><span style="font-weight:700;font-style:italic;text-decoration:underline;color:#ff0000;background-color:#ffff00;font-size:14pt;font-family:'Amiri',serif">x</span><b><span style="font-weight:400">not bold</span></b><span style="vertical-align:super">2</span></p>"""
        val paragraph = HtmlReader.read(html).single() as Paragraph
        assertEquals(TextDirection.RTL, paragraph.style.direction)
        assertEquals(Alignment.CENTER, paragraph.style.alignment)
        val x = paragraph.runs[0]
        assertEquals(listOf(true, true, true, 0xFF0000, 0xFFFF00, 14f, "Amiri"), listOf(x.bold, x.italic, x.underline, x.colorRgb, x.highlightRgb, x.fontSizePt, x.fontFamily))
        assertEquals(false, paragraph.runs[1].bold, "a weight of 400 inside a b is not bold")
        assertTrue(paragraph.runs[2].superscript)
        val word = HtmlReader.read("""<p class=MsoNormal><b><span style='font-size:12.0pt;mso-bidi-language:AR-DZ'>Word</span></b> text&nbsp;&amp;&#8217;&hellip;<br>next</p>""").single() as Paragraph
        assertEquals("Word text &’…\nnext", word.text)
        assertTrue(word.runs[0].bold)
        assertEquals(12f, word.runs[0].fontSizePt)
    }

    @Test
    fun `a table is read with its spans, its head, its fills and its widths, and a table inside a cell`() {
        val html = """<table dir="rtl"><col style="width:100pt"><col style="width:2in"><thead><tr><th colspan="2">Head</th></tr></thead><tbody>
            <tr><td rowspan="2" style="background-color:#eeeeee">a</td><td>b<table><tr><td>in</td></tr></table></td></tr><tr><td style="border:0">c</td></tr></tbody></table><p>after</p>"""
        val blocks = HtmlReader.read(html)
        val table = blocks[0] as Table
        assertEquals(listOf("after"), texts(blocks).drop(1))
        assertEquals(listOf(listOf(listOf("Head")), listOf(listOf("a"), listOf("b", listOf(listOf(listOf("in"))))), listOf(listOf("c"))), texts(listOf(table))[0])
        assertEquals(listOf(true, false, false), table.rows.map { it.repeatsAsHeader })
        assertEquals(2, table.rows[0].cells[0].columnSpan)
        assertEquals(2, table.rows[1].cells[0].rowSpan)
        assertEquals(0xEEEEEE, table.rows[1].cells[0].shadingRgb)
        assertTrue(table.rows[0].cells[0].blocks.all { it is Paragraph && it.runs.all { r -> r.bold } }, "a th is bold")
        assertEquals(listOf(100f, 144f), table.columnWidthsPt)
        assertEquals(TextDirection.RTL, table.direction)
        assertTrue(table.ruled, "one cell without a border does not unrule the table")
        val bare = HtmlReader.read("""<table><tr><td style="border:0">a</td><td style="border: 0">b</td></tr></table>""")[0] as Table
        assertEquals(false, bare.ruled)
        val empty = HtmlReader.read("<table><tr><td></td><td> </td></tr></table>")[0] as Table
        assertTrue(empty.rows[0].cells.all { it.blocks.size == 1 && (it.blocks[0] as Paragraph).text.isEmpty() }, "an empty cell holds a paragraph to stand in")
    }

    @Test
    fun `a picture comes in as the bytes the markup carries, and never from anywhere else`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val data = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        val blocks = HtmlReader.read("""<p><img src="$data" alt="a seal" width="10" height="20" style="width:36pt;height:72pt"></p><p>text <img src="$data"> inline</p><p><img src="https://x/y.png"></p><p><img src="file:///etc/passwd"></p>""")
        assertEquals(listOf("<image>", "text  inline"), texts(blocks), "a picture alone is a block, one among words is inline, and one that would have to be fetched is nothing")
        val image = blocks[0] as ImageBlock
        assertTrue(image.bytes.contentEquals(bytes))
        assertEquals(listOf("image/png", 10, 20, 36f, 72f, "a seal"), listOf(image.mimeType, image.widthPx, image.heightPx, image.widthPt, image.heightPt, image.description))
        assertTrue((blocks[1] as Paragraph).runs.any { it.image != null })
        assertTrue(HtmlReader.read("""<img src="data:image/png;base64,!!!not base64!!!">""").isEmpty(), "bytes that are not bytes are nothing")
    }

    @Test
    fun `white space is the markup's except where it is kept`() {
        assertEquals(listOf("a b c"), texts(HtmlReader.read("<p>\n   a \n\t b   <b> c </b>\n</p>")))
        assertEquals(listOf("one", "two"), texts(HtmlReader.read("<p>one</p>\n\n  \n<p>two</p>")))
        assertEquals(listOf("a\n b\tc"), texts(HtmlReader.read("<pre>a\n b\tc</pre>")))
        assertEquals(listOf("line\nbreak"), texts(HtmlReader.read("<p>line<br>\nbreak</p>")))
        assertEquals(listOf("two  spaces"), texts(HtmlReader.read("<p>two&nbsp;&nbsp;spaces</p>")), "spaces that do not break are meant")
        assertEquals(listOf("loose text", "then a paragraph"), texts(HtmlReader.read("loose <i>text</i><p>then a paragraph")), "text outside any block is a paragraph too")
    }

    @Test
    fun `what is not markup, or not for a reader, is text or nothing, and nothing throws`() {
        assertEquals(listOf("a < b and 3 > 2"), texts(HtmlReader.read("<p>a < b and 3 &gt; 2</p>")))
        assertEquals(listOf("shown"), texts(HtmlReader.read("<script>alert(1)</script><style>p{}</style><p>shown</p><!-- not --><?xml no?><![CDATA[x]]>")))
        assertEquals(listOf("open"), texts(HtmlReader.read("<p><b><i>open")))
        assertEquals(listOf("mis nested"), texts(HtmlReader.read("<p><b>mis <i>nested</b></i></p>")))
        assertEquals(emptyList<Any?>(), texts(HtmlReader.read("")))
        assertEquals(listOf("<>"), texts(HtmlReader.read("<><//><p></p><table></table>")), "a bracket that opens nothing is text, as a browser shows it")
        val deep = "<div>".repeat(5_000) + "bottom" + "</div>".repeat(5_000)
        assertEquals(listOf("bottom"), texts(HtmlReader.read(deep)), "nested past the bound, the rest is contents")
        val many = "<p>x</p>".repeat(HtmlReader.MOST_BLOCKS + 100)
        assertEquals(HtmlReader.MOST_BLOCKS, HtmlReader.read(many).size)
        assertTrue(HtmlReader.read("<p>" + "y".repeat(HtmlReader.MOST_LENGTH + 10) + "</p>").isNotEmpty())
        assertNull((HtmlReader.read("""<p><a href="#note-1">1</a></p>""")[0] as Paragraph).runs[0].link, "a link to a place in the page is not a link")
    }

    @Test
    fun `whatever the clipboard holds, the reading ends and every block stands`() {
        val pieces = listOf(
            "<p>", "</p>", "<div>", "</div>", "<b>", "</b>", "<i>", "<table>", "<tr>", "<td>", "</td>", "</tr>", "</table>", "<ul>", "<li>", "</li>", "</ul>", "<br>",
            "<img src=\"data:image/png;base64,AQID\">", "<span style=\"font-weight:700;color:#123456\">", "</span>", "text", "&amp;", "&#x41;", "<!--", "-->", "<", ">", "\"", "'", " ", "\n", "<h2>", "</h2>", "<a href='x'>", "</a>", "<pre>", "</pre>",
            "<td colspan=\"3\" rowspan=\"2\">", "<thead>", "</thead>", "<col style=\"width:1in\">", "عربي", "<p dir=rtl>", "<script>", "</script>",
        )
        for (seed in 1..500) {
            val random = Random(seed)
            val html = (1..random.nextInt(1, 60)).joinToString("") { pieces[random.nextInt(pieces.size)] }
            val blocks = HtmlReader.read(html)
            for (block in blocks) check(block, "seed $seed: $html")
        }
    }

    private fun check(block: Block, where: String) {
        when (block) {
            is Paragraph -> {
                assertTrue(block.runs.isNotEmpty(), "$where: a paragraph with no runs")
                for (run in block.runs) assertTrue(run.text.isNotEmpty() || run.image != null || block.runs.size == 1, "$where: an empty run among others")
            }
            is Table -> {
                assertTrue(block.rows.isNotEmpty() && block.rows.all { it.cells.isNotEmpty() }, "$where: a table with an empty row")
                for (row in block.rows) for (cell in row.cells) {
                    assertTrue(cell.blocks.isNotEmpty(), "$where: a cell with nothing to stand in")
                    for (inner in cell.blocks) check(inner, where)
                }
            }
            is ImageBlock -> assertTrue(block.bytes.isNotEmpty(), where)
        }
    }

    @Test
    fun `what the writer writes, the reader reads back`() {
        for (seed in 1..300) {
            val random = Random(seed)
            val document = documentOf(random)
            val html = HtmlWriter.write(document)
            val back = HtmlReader.read(html)
            assertEquals(texts(document.blocks), texts(back), "seed $seed: the words\n$html")
            for ((expected, actual) in document.blocks.zip(back)) compare(expected, actual, "seed $seed\n$html")
        }
    }

    private fun compare(expected: Block, actual: Block, where: String) {
        when (expected) {
            is Paragraph -> {
                actual as Paragraph
                assertEquals(expected.style.kind, actual.style.kind, where)
                assertEquals(expected.style.listMarker, actual.style.listMarker, where)
                assertEquals(expected.style.listLevel, actual.style.listLevel, where)
                assertEquals(runs(expected), runs(actual), where)
                assertEquals(expected.runs.map { it.colorRgb to it.superscript }, actual.runs.map { it.colorRgb to it.superscript }, where)
            }
            is Table -> {
                actual as Table
                assertEquals(expected.rows.map { r -> r.cells.map { listOf(it.columnSpan, it.rowSpan, it.shadingRgb) } }, actual.rows.map { r -> r.cells.map { listOf(it.columnSpan, it.rowSpan, it.shadingRgb) } }, where)
                assertEquals(expected.rows.map { it.repeatsAsHeader }, actual.rows.map { it.repeatsAsHeader }, where)
                assertEquals(expected.ruled, actual.ruled, where)
                for ((row, back) in expected.rows.zip(actual.rows)) for ((cell, cellBack) in row.cells.zip(back.cells)) {
                    for ((inner, innerBack) in cell.blocks.zip(cellBack.blocks)) compare(inner, innerBack, where)
                }
            }
            is ImageBlock -> {
                actual as ImageBlock
                assertTrue(expected.bytes.contentEquals(actual.bytes), where)
                assertEquals(expected.description, actual.description, where)
            }
        }
    }

    private val words = listOf("form", "بحث", "the", "استمارة", "2022", "x&y", "<b>", "a\"q\"")

    /** A document of what a paste can carry: paragraphs of set words, headings, lists, tables, pictures. */
    private fun documentOf(random: Random): DocumentModel {
        fun run() = TextRun(
            text = (1..random.nextInt(1, 3)).joinToString(" ") { words[random.nextInt(words.size)] },
            bold = random.nextBoolean(),
            italic = random.nextInt(3) == 0,
            underline = random.nextInt(4) == 0,
            strikethrough = random.nextInt(5) == 0,
            link = if (random.nextInt(4) == 0) "https://x/" + random.nextInt(3) else null,
            colorRgb = if (random.nextInt(4) == 0) 0xC00000 else null,
            superscript = random.nextInt(6) == 0,
        )
        fun paragraph(kind: ParagraphKind = ParagraphKind.BODY, marker: ListMarker? = null, level: Int = 0) = Paragraph(
            // Neighbours set alike are one run to the reader, so they are made unalike here.
            runs = ParagraphEdit.merged((1..random.nextInt(1, 4)).map { run() }),
            style = ParagraphStyle(kind = kind, listMarker = marker, listLevel = level),
        )
        fun image() = ImageBlock(ByteArray(random.nextInt(1, 6)) { it.toByte() }, "image/png", 2, 1, description = if (random.nextBoolean()) "a seal" else null)
        val blocks = mutableListOf<Block>()
        repeat(random.nextInt(1, 6)) {
            when (random.nextInt(7)) {
                0 -> blocks += paragraph(kind = ParagraphKind.entries[random.nextInt(ParagraphKind.entries.size)])
                1 -> {
                    val marker = if (random.nextBoolean()) ListMarker.BULLET else ListMarker.NUMBERED
                    blocks += paragraph(marker = marker)
                    blocks += paragraph(marker = marker, level = 1)
                    blocks += paragraph(marker = marker)
                }
                2 -> blocks += Table(
                    rows = (1..random.nextInt(1, 3)).map { r ->
                        TableRow(
                            (1..random.nextInt(1, 3)).map {
                                TableCell(listOf(paragraph()), columnSpan = if (random.nextInt(4) == 0) 2 else 1, shadingRgb = if (random.nextInt(3) == 0) 0xEEEEEE else null)
                            },
                            repeatsAsHeader = r == 1 && random.nextBoolean(),
                        )
                    },
                    ruled = random.nextInt(4) != 0,
                )
                3 -> blocks += image()
                else -> blocks += paragraph()
            }
        }
        // A head is a run of rows from the top, and a table that is all head has none.
        val fixed = blocks.map { block ->
            if (block is Table && block.rows.all { it.repeatsAsHeader }) block.copy(rows = block.rows.map { it.copy(repeatsAsHeader = false) }) else block
        }
        return DocumentModel(fixed)
    }
}
