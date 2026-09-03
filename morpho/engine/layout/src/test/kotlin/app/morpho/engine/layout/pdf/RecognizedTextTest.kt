package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The words recognition found, as the lines a page's reading takes.
 *
 * A scan used to be converted by asking for one string a page and handing
 * it to the plain-text importer, which is built for text files and can
 * only find structure in Markdown conventions recognised text never has.
 * Measured on a real paper, the whole eleven pages came back as a single
 * paragraph. The same words as positioned lines come back as a hundred
 * and eight, because everything that reads an untagged PDF — headings
 * from type size, paragraphs from spacing, columns from gutters, tables
 * from the alignment of words — takes lines and was simply never given
 * any.
 */
class RecognizedTextTest {

    private fun word(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        page: Int = 1,
        startsLine: Boolean = false,
    ) = RecognizedWord(text, page, left, top, right, bottom, startsLine)

    @Test
    fun `words recognition put on one line come back as one line`() {
        val lines = RecognizedText.linesOf(
            listOf(
                word("The", 72f, 100f, 96f, 112f, startsLine = true),
                word("form", 100f, 100f, 130f, 112f),
                word("in", 134f, 100f, 144f, 112f),
            )
        )
        assertEquals(1, lines.size)
        assertEquals("The form in", lines[0].text)
        assertEquals(72f, lines[0].x, "the line starts where its first word does")
        assertEquals(144f, lines[0].xEnd, "and ends where its last one does")
        assertEquals(1, lines[0].page)
    }

    @Test
    fun `a word that opens a line opens a line`() {
        val lines = RecognizedText.linesOf(
            listOf(
                word("first", 72f, 100f, 100f, 112f, startsLine = true),
                word("line", 104f, 100f, 130f, 112f),
                word("second", 72f, 118f, 110f, 130f, startsLine = true),
                word("line", 114f, 118f, 140f, 130f),
            )
        )
        assertEquals(listOf("first line", "second line"), lines.map { it.text })
    }

    @Test
    fun `a new page starts a new line even where nothing said so`() {
        // Recognition is asked a page at a time, so the first word of a
        // page may not be marked: run together, the last line of one page
        // and the first of the next become one line that is on neither.
        val lines = RecognizedText.linesOf(
            listOf(
                word("end", 72f, 700f, 100f, 712f, page = 1, startsLine = true),
                word("beginning", 72f, 100f, 130f, 112f, page = 2),
            )
        )
        assertEquals(listOf("end", "beginning"), lines.map { it.text })
        assertEquals(listOf(1, 2), lines.map { it.page })
    }

    @Test
    fun `every word becomes a segment, which is what a table is found from`() {
        // A table has no lines drawn round it in a scan; it is found from
        // the gaps between words repeating down the page. Without the
        // segments there are no gaps to see.
        val lines = RecognizedText.linesOf(
            listOf(
                word("Name", 72f, 100f, 110f, 112f, startsLine = true),
                word("Rabiha", 200f, 100f, 250f, 112f),
            )
        )
        assertEquals(
            listOf(PdfSegment("Name", 72f, 110f), PdfSegment("Rabiha", 200f, 250f)),
            lines[0].segments,
        )
    }

    @Test
    fun `the size of a line is the tallest word that is more than one letter`() {
        // A word's box is as tall as the tallest thing in it, so a lone
        // "l" or a full stop is all extreme and would decide the answer
        // on a short line. The two body lines here are ten tall and the
        // third is twenty, so the body sets the scale and the third comes
        // back at twice it — a lone tall letter on a body line must not
        // make that line a heading.
        val lines = RecognizedText.linesOf(
            listOf(
                word("l", 72f, 90f, 75f, 112f, startsLine = true),
                word("man", 80f, 102f, 110f, 112f),
                word("body", 72f, 122f, 110f, 132f, startsLine = true),
                word("text", 114f, 122f, 150f, 132f),
                word("Tagged", 72f, 146f, 160f, 166f, startsLine = true),
            )
        )
        assertEquals(12f, lines[0].maxFontSize, "a lone tall letter decided a body line")
        assertEquals(12f, lines[1].maxFontSize)
        assertEquals(24f, lines[2].maxFontSize, "a line of twice the ink is twice the type")
    }

    @Test
    fun `a line of nothing but single letters is still measured`() {
        val lines = RecognizedText.linesOf(
            listOf(
                word("A", 72f, 100f, 84f, 118f, startsLine = true),
                word("ordinary", 72f, 130f, 150f, 139f, startsLine = true),
                word("ordinary", 72f, 150f, 150f, 159f, startsLine = true),
            )
        )
        assertEquals(12f, lines[1].maxFontSize, "an ordinary line reads as ordinary type")
        assertEquals(24f, lines[0].maxFontSize, "a line of one letter is measured, not skipped")
    }

    @Test
    fun `the document's own middle is its body, and the rest follows from it`() {
        // What recognition measures is the ink, and the ink is not the
        // point size: measured on the real thing, Arabic set at twelve
        // points measures nearer fourteen, where a Latin face would
        // measure nearer eleven. No constant serves both, so none is
        // used. The ratio between one line and another is what recognition
        // gets right, and the ratio is all this takes.
        fun page(inks: List<Float>) = RecognizedText.linesOf(
            inks.mapIndexed { at, ink ->
                word("line$at", 72f, at * 40f, 300f, at * 40f + ink, startsLine = true)
            }
        ).map { it.maxFontSize }

        assertEquals(listOf(12f), page(listOf(10.8f)), "one line is the body by itself")
        assertEquals(listOf(9f, 12f, 16f), page(listOf(9f, 12f, 16f)))
        // The same document measured in any other unit gives the same
        // answer, which is the point of taking only the ratios.
        assertEquals(listOf(9f, 12f, 16f), page(listOf(0.9f, 1.2f, 1.6f)))
        assertEquals(listOf(9f, 12f, 16f), page(listOf(90f, 120f, 160f)))
        // And the ratios themselves come through whole.
        val scale = page(listOf(6f, 12f, 24f))
        assertEquals(listOf(6f, 12f, 24f), scale)
        assertEquals(2f, scale[1] / scale[0], 0.001f)
        assertEquals(2f, scale[2] / scale[1], 0.001f)
    }

    @Test
    fun `a paper set in two scripts is measured in both`() {
        // The ratio between a line's ink and its point size is a fact
        // about the typeface, and the two scripts this converter is most
        // often given disagree by a quarter: measured on the real thing,
        // Arabic set at twelve points measures about fourteen and Latin at
        // twelve measures about eleven. An Arabic paper with an English
        // abstract — which is what an Arabic paper is — cannot be put
        // right by one number, and read on its Arabic its English came
        // back a quarter too small.
        val arabic = listOf("البحث", "العلمي", "الاستمارة", "أدوات", "جمع",
            "البيانات", "الميدانية", "أنواع", "مزايا", "الخلاصة", "المراجع", "مقدمة")
        val latin = listOf("research", "method", "abstract", "keywords", "findings",
            "sources", "figures", "tables", "summary", "notes", "index", "appendix")
        var y = 0f
        val words = arabic.map { word(it, 72f, y.also { _ -> y += 40f }, 300f, y - 40f + 14f, startsLine = true) } +
            latin.map { word(it, 72f, y.also { _ -> y += 40f }, 300f, y - 40f + 11f, startsLine = true) }
        val lines = RecognizedText.linesOf(words)
        assertEquals(
            List(arabic.size + latin.size) { 12f },
            lines.map { it.maxFontSize },
            "one script was measured with the other's rule",
        )
    }

    @Test
    fun `a script with only a few lines takes the document's own measure`() {
        // A middle taken from four lines is not a middle. A handful of
        // words in the other script are read with the document's rule
        // rather than being given one of their own.
        val arabic = List(12) { "الاستمارة" }
        var y = 0f
        val words = arabic.map { word(it, 72f, y.also { _ -> y += 40f }, 300f, y - 40f + 14f, startsLine = true) } +
            listOf(
                word("Abstract", 72f, y.also { y += 40f }, 300f, y - 40f + 14f, startsLine = true),
                word("Keywords", 72f, y.also { y += 40f }, 300f, y - 40f + 14f, startsLine = true),
            )
        val lines = RecognizedText.linesOf(words)
        assertEquals(List(14) { 12f }, lines.map { it.maxFontSize })
    }

    @Test
    fun `a line within the spread of the body is the body`() {
        // Of the two hundred and sixty-six lines of the real paper the
        // file itself sets at twelve points, recognition measured them
        // from ten to seventeen — a fifth either side of the middle.
        // Written out as they came, a document set in one size arrives set
        // in nine, and every body line that measured high reads as a
        // heading; four of them did, on a paper with one heading that size
        // can find. What is outside that spread is a real difference and
        // keeps it.
        var y = 0f
        fun line(ink: Float) = word("ordinary words here", 72f, y.also { y += 40f }, 300f, y - 40f + ink, startsLine = true)
        val lines = RecognizedText.linesOf(
            listOf(line(12f), line(12f), line(12f), line(14f), line(10f), line(16f), line(9f))
        )
        assertEquals(
            listOf(12f, 12f, 12f, 12f, 12f, 16f, 9f),
            lines.map { it.maxFontSize },
            "the spread of the body is the body; a third above it is a heading",
        )
    }

    @Test
    fun `a measurement that has gone wrong is not written as a size`() {
        // Nothing a page could be set in is a fortieth of a point or a
        // foot tall, and a run Word is asked to set at either is a
        // document that will not open the same way twice.
        val lines = RecognizedText.linesOf(
            listOf(
                word("tiny", 72f, 100f, 90f, 100.02f, startsLine = true),
                word("ordinary", 72f, 120f, 200f, 130.8f, startsLine = true),
                word("ordinary", 72f, 140f, 200f, 150.8f, startsLine = true),
                word("ordinary", 72f, 160f, 200f, 170.8f, startsLine = true),
                word("vast", 72f, 200f, 300f, 1400f, startsLine = true),
            )
        )
        assertEquals(listOf(4f, 12f, 12f, 12f, 96f), lines.map { it.maxFontSize })
    }

    @Test
    fun `an Arabic paper measured as recognition really measures it`() {
        // The numbers are the real ones: the models this app ships, on the
        // paper this project was built for, measure its twelve-point body
        // at about fourteen points of ink and its fifteen-point title at
        // about eighteen and a half. Read as points those would set the
        // whole document a quarter too large; read as ratios the body
        // lands where the file says it is.
        val lines = RecognizedText.linesOf(
            listOf(
                word("title", 72f, 100f, 300f, 118.4f, startsLine = true),
                word("body", 72f, 140f, 200f, 153.9f, startsLine = true),
                word("body", 72f, 180f, 200f, 193.9f, startsLine = true),
                word("body", 72f, 220f, 200f, 233.9f, startsLine = true),
            )
        )
        assertEquals(12f, lines[1].maxFontSize, "the body of the paper is twelve points")
        assertEquals(16f, lines[0].maxFontSize, "and its title fifteen, near enough")
    }

    @Test
    fun `every word of a scanned line carries the size its line was set in`() {
        // Without this a scanned paper's title and its footnotes both
        // convert at the size of its body, which is what a reader sees
        // first and what "no sizes" meant.
        val lines = RecognizedText.linesOf(
            listOf(
                word("Introduction", 72f, 100f, 300f, 120f, startsLine = true),
                word("The", 72f, 140f, 100f, 150f, startsLine = true),
                word("body.", 104f, 140f, 150f, 150f),
                word("also", 72f, 170f, 110f, 180f, startsLine = true),
                word("body.", 114f, 170f, 160f, 180f),
            )
        )
        assertEquals(listOf(24f, 12f, 12f), lines.map { it.maxFontSize })
        for (line in lines) {
            assertEquals(
                List(line.segments.size) { line.maxFontSize },
                line.runs.map { it.look?.fontSizePt },
                "\"${line.text}\" did not carry its size onto its words",
            )
            assertEquals(
                line.text,
                line.runs.joinToString("") { it.text }.trimEnd(),
                "the runs of a line have to be the line",
            )
        }
        assertTrue(
            lines.all { it.runs.all { run -> run.look?.fontFamily == null } },
            "recognition can name no typeface, so none may be claimed",
        )
    }

    @Test
    fun `the foot of the words is where the line sits`() {
        // Reading order is worked out by comparing lines with each other,
        // and every line of a page hangs below its baseline by about the
        // same amount, so the foot of the ink serves.
        val lines = RecognizedText.linesOf(
            listOf(
                word("above", 72f, 100f, 110f, 112f, startsLine = true),
                word("below", 72f, 120f, 110f, 132f, startsLine = true),
            )
        )
        assertTrue(lines[0].baselineY < lines[1].baselineY, "the page reads downward")
        assertEquals(112f, lines[0].baselineY)
    }

    @Test
    fun `an Arabic line reads in one order and sits in another`() {
        // Recognition hands its words over in the order they are read,
        // which for Arabic is right to left across the page. The line has
        // to say them in that order — and its pieces have to come back
        // left to right, because that is what every other reader gives
        // and what the gaps between them are for: finding the columns of
        // a page and the cells of a table.
        val lines = RecognizedText.linesOf(
            listOf(
                word("الاستمارة", 460f, 100f, 520f, 112f, startsLine = true),
                word("في", 430f, 100f, 456f, 112f),
                word("البحث", 380f, 100f, 426f, 112f),
            )
        )
        val line = lines.single()
        assertEquals("الاستمارة في البحث", line.text, "the line is not read in the order it is read")
        assertEquals(
            listOf("البحث", "في", "الاستمارة"),
            line.segments.map { it.text },
            "the pieces of a line run left to right whatever the words do",
        )
        assertEquals(
            line.segments.map { it.xStart }.sorted(),
            line.segments.map { it.xStart },
        )
        assertEquals(380f, line.x)
        assertEquals(520f, line.xEnd)
        // And the runs still spell the line, in the order it is read.
        assertEquals("الاستمارة في البحث", line.runs.joinToString("") { it.text }.trimEnd())
    }

    @Test
    fun `nothing recognised is no lines at all`() {
        assertEquals(emptyList<PdfLine>(), RecognizedText.linesOf(emptyList()))
        assertEquals(
            emptyList<PdfLine>(),
            RecognizedText.linesOf(listOf(word("   ", 0f, 0f, 10f, 10f, startsLine = true))),
            "a word of nothing but space is not a word",
        )
    }

    @Test
    fun `a blank between words does not break the line`() {
        val lines = RecognizedText.linesOf(
            listOf(
                word("one", 72f, 100f, 100f, 112f, startsLine = true),
                word("  ", 102f, 100f, 106f, 112f),
                word("two", 108f, 100f, 136f, 112f),
            )
        )
        assertEquals(listOf("one two"), lines.map { it.text })
    }
}
