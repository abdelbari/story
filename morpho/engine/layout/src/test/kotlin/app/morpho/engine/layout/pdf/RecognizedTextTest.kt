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
        assertEquals(11f, lines[0].maxFontSize, "a lone tall letter decided a body line")
        assertEquals(11f, lines[1].maxFontSize)
        assertEquals(22f, lines[2].maxFontSize, "a line of twice the ink is twice the type")
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
        assertEquals(10f, lines[1].maxFontSize, "an ordinary line reads as ordinary type")
        assertEquals(20f, lines[0].maxFontSize, "a line of one letter is measured, not skipped")
    }

    @Test
    fun `the ink a line covers is read as the point size it was set in`() {
        // Recognition measures the ink — the top of the ascenders to the
        // foot of the descenders — and that is not a point size. A point
        // size is the body the type is cast on, and a typeface fills about
        // nine tenths of it, so nine tenths is what turns one into the
        // other.
        fun page(inks: List<Float>) = RecognizedText.linesOf(
            inks.mapIndexed { at, ink ->
                word("line$at", 72f, at * 40f, 300f, at * 40f + ink, startsLine = true)
            }
        ).map { it.maxFontSize }

        // 10.8 points of ink is a line set in 12, which is what a 12-point
        // line of an ordinary text face really measures.
        assertEquals(listOf(12f), page(listOf(10.8f)))
        assertEquals(listOf(9f, 12f, 16f), page(listOf(8.1f, 10.8f, 14.4f)))
        // The ratios recognition measured survive the conversion, because
        // it is one multiplication and not a fit to anything.
        val scale = page(listOf(5.4f, 10.8f, 21.6f))
        assertEquals(listOf(6f, 12f, 24f), scale)
        assertEquals(2f, scale[1] / scale[0], 0.001f)
        assertEquals(2f, scale[2] / scale[1], 0.001f)
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
    fun `a document measured in something other than ink is put back on its feet`() {
        // The point size comes from a fact about how type is drawn, so it
        // needs no document to calibrate it — but a recogniser that
        // measured something else would put every size of a document out
        // together, and there is no real scan here to prove one against.
        // A body of two points is the sign of it. The document's middle
        // then goes to the size a body is set at, and the scale it was
        // written in survives whole.
        val lines = RecognizedText.linesOf(
            listOf(
                word("title", 72f, 100f, 300f, 112f, startsLine = true),
                word("body", 72f, 120f, 200f, 126f, startsLine = true),
                word("body", 72f, 140f, 200f, 146f, startsLine = true),
                word("small", 72f, 160f, 200f, 163f, startsLine = true),
            )
        )
        assertEquals(listOf(24f, 12f, 12f, 6f), lines.map { it.maxFontSize })
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
        assertEquals(listOf(22f, 11f, 11f), lines.map { it.maxFontSize })
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
