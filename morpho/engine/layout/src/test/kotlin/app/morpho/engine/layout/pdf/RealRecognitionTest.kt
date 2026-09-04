package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two pages as recognition really wrote them.
 *
 * Every other test of this reader is written against markup shaped like
 * what Tesseract writes. These two are what it wrote: the app's own
 * language packs, over pages rendered at the resolution the app renders
 * at, with the page segmentation it asks for. They were captured because
 * recognition could be run beside the engine for an afternoon and cannot
 * be run in the build, and because a reader held only to markup somebody
 * wrote for it is held to their idea of the thing rather than the thing.
 *
 * What they are worth is what they catch. Recognition's own default is to
 * read a page as one block of text and work nothing out, which is quiet
 * and ruinous: a page in two columns then reads straight across the
 * gutter. These pages were read with that turned off, so the two-column
 * one carries the proof in its coordinates — a reading that took them in
 * the order they were painted would interleave the columns, and the words
 * would come back as nonsense.
 */
class RealRecognitionTest {

    /** What the app renders a page at before recognition reads it. */
    private val dpi = 200f

    private fun page(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name.hocr")) { "no $name.hocr" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun readingOf(name: String) = PdfLayout.reconstruct(
        lines = RecognizedText.linesOf(Hocr.wordsOf(page(name), page = 1, dpi = dpi)),
        confidence = 0.5f,
        sheets = listOf(PdfPageSheet(1, 595.3f, 841.9f)),
    )

    @Test
    fun `a page set in two columns is read down one column and then the other`() {
        val model = readingOf("two-columns")
        val paragraphs = model.blocks.filterIsInstance<Paragraph>()
        assertEquals(5, model.blocks.size, "blocks: " + paragraphs.map { it.text.take(20) })
        assertEquals(
            ParagraphKind.HEADING_1,
            paragraphs.first().style.kind,
            "the one line set larger than the rest is the page's heading",
        )
        assertEquals("Findings across both columns", paragraphs.first().text)
        // The order is the whole point. A page read as one block takes the
        // two columns a line at a time, alternating, and the sentences
        // come back interleaved.
        assertTrue(
            paragraphs[1].text.startsWith("The first column opens the argument"),
            "the first column did not come first: \"${paragraphs[1].text.take(60)}\"",
        )
        assertTrue(
            paragraphs[3].text.startsWith("The second column takes up where the first"),
            "the second column did not come second: \"${paragraphs[3].text.take(60)}\"",
        )
        for (paragraph in paragraphs.drop(1)) {
            assertEquals(
                ParagraphKind.BODY,
                paragraph.style.kind,
                "\"${paragraph.text.take(40)}\" is body text and was read as a heading",
            )
        }
    }

    @Test
    fun `a document set in one size comes back set in one size`() {
        // Recognition measures a line's ink, which is noisy: on the paper
        // this project was built for, lines the file sets at twelve points
        // measured from ten to seventeen. Written out as measured, a
        // document set in one size arrives set in nine of them.
        val sizes = readingOf("two-columns").blocks.filterIsInstance<Paragraph>()
            .flatMap { it.runs }.mapNotNull { it.fontSizePt }.distinct().sorted()
        assertEquals(2, sizes.size, "the page is set in a heading and a body, and got $sizes")
        assertTrue(sizes.last() / sizes.first() > 1.2f, "the heading has to measure larger: $sizes")
    }

    @Test
    fun `a table a page ruled still comes back as its words, in the order they are read`() {
        // Not as a table: recognition cuts a table into text columns, so
        // its rows never form, and the reasons are written out in the
        // README. What must not happen is losing the words or scrambling
        // them, which is what this holds.
        val model = readingOf("a-ruled-table")
        val text = model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        for (word in listOf("Findings", "Section", "Item", "Share", "Design", "Clear", "80%", "Late", "36%")) {
            assertTrue(word in text, "\"$word\" was lost: $text")
        }
        assertTrue(
            text.startsWith("Findings by section."),
            "the line above the table is still the line above it: ${text.take(40)}",
        )
        assertEquals(emptyList<Table>(), model.blocks.filterIsInstance<Table>())
    }

    @Test
    fun `what recognition measured is on the page it says it is`() {
        val words = Hocr.wordsOf(page("two-columns"), page = 1, dpi = dpi)
        assertTrue(words.size > 100, "only ${words.size} words came out of a full page")
        assertTrue(words.all { it.page == 1 })
        assertTrue(
            words.all { it.left >= 0f && it.right <= 596f && it.top >= 0f && it.bottom <= 842f },
            "a word was placed off the sheet it was recognised on",
        )
        assertTrue(words.count { it.startsLine } in 10..40, "lines: ${words.count { it.startsLine }}")
        assertTrue(words.all { it.sizePt != null }, "recognition measured every line, and one was dropped")
    }

    /** The reading with recognition's own rules handed to it. */
    private fun ruledReadingOf(name: String) = PdfLayout.reconstruct(
        lines = RecognizedText.linesOf(Hocr.wordsOf(page(name), page = 1, dpi = dpi)),
        confidence = 0.5f,
        sheets = listOf(PdfPageSheet(1, 595.3f, 841.9f)),
        drawings = Hocr.rulesOf(page(name), page = 1, dpi = dpi),
    )

    @Test
    fun `a ruled table on a page of prose comes back a table`() {
        // The page an institution actually sends: a heading, some prose, a
        // ruled table, more prose. Recognition finds the rules and reports
        // them, and nothing read them, so the table arrived at the ruled
        // reader with no rules at all and came back as loose paragraphs.
        val model = ruledReadingOf("prose-and-a-table")
        val table = model.blocks.filterIsInstance<Table>().singleOrNull()
        assertTrue(table != null, "no table: " + model.blocks.map { it::class.simpleName })
        assertEquals(4, table!!.rows.size, "the table did not come back with its four rows")
        val said = table.rows.map { row ->
            row.cells.joinToString(" ") { cell ->
                cell.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
            }.trim()
        }
        assertEquals("Section Applications Outstanding", said[0])
        assertTrue(said[1].startsWith("Design"), "second row: \"${said[1]}\"")
        assertTrue(said.any { it.startsWith("Records") }, "rows: $said")
        // And the prose either side is still prose, not swallowed by it.
        val paragraphs = model.blocks.filterIsInstance<Paragraph>()
        assertTrue(
            paragraphs.any { it.text.startsWith("Report of the Standing Committee") },
            "the heading was lost: " + paragraphs.map { it.text.take(24) },
        )
        assertTrue(
            paragraphs.any { it.text.contains("agreed that the figures") },
            "the prose after the table was lost",
        )
    }

    @Test
    fun `a page with no rules on it gains no table from this`() {
        // The cost side of the trade, and the reason it is safe: on a page
        // recognition found no rules on, it reports no separators, so a
        // reading handed them is the reading it always was. Held over both
        // column pages, since a false table on a page of prose would be
        // far worse than a missed one.
        for (name in listOf("two-columns", "three-columns")) {
            assertEquals(
                emptyList<PdfDrawing>(), Hocr.rulesOf(page(name), page = 1, dpi = dpi),
                "$name: recognition reported rules on a page that has none",
            )
            assertEquals(
                readingOf(name).blocks.filterIsInstance<Table>().size,
                ruledReadingOf(name).blocks.filterIsInstance<Table>().size,
                "$name gained or lost a table",
            )
        }
    }

    @Test
    fun `a page set in three columns is read one column at a time`() {
        // The second column page, and the one that says the flow ordering
        // still does its job: a change made for tables must not cost this.
        val paragraphs = readingOf("three-columns").blocks.filterIsInstance<Paragraph>()
        val whole = paragraphs.joinToString(" ") { it.text }
        val first = whole.indexOf("The first column opens")
        val second = whole.indexOf("The second column")
        val third = whole.indexOf("The third column ends")
        assertTrue(first >= 0 && second >= 0 && third >= 0, "a column went missing: $whole")
        assertTrue(first < second && second < third, "the columns came back interleaved: $whole")
        assertTrue(
            whole.contains("down the left of the page"),
            "the first column's own sentence was broken across the others: $whole",
        )
    }

    @Test
    fun `the rules recognition reports across a page are given the only reach they could have`() {
        // Recognition reports the ones down the page whole and the ones
        // across it as a position and nothing else — bbox 0 y 0 y'. That
        // is the signature they are read by, so a rule that arrives with a
        // width of its own is left exactly as it is.
        val rules = Hocr.rulesOf(page("prose-and-a-table"), page = 1, dpi = dpi)
        assertEquals(9, rules.size, "recognition reported nine separators on this page")
        val down = rules.filter { it.heightPt > it.widthPt * 2 }
        val across = rules - down.toSet()
        assertEquals(4, down.size, "the four rules down the page")
        assertEquals(5, across.size, "the five across it")
        val from = down.minOf { it.left }
        val to = down.maxOf { it.right }
        for (rule in across) {
            assertEquals(from, rule.left, "a rule across the page did not start where the table does")
            assertEquals(to, rule.right, "a rule across the page did not end where the table does")
        }
        assertTrue(to - from > 400f, "the table is most of the measure: ${to - from}pt")
    }
}
