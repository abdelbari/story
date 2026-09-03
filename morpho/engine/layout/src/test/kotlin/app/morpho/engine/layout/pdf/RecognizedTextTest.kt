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
        // on a short line.
        val lines = RecognizedText.linesOf(
            listOf(
                word("l", 72f, 90f, 75f, 112f, startsLine = true),
                word("man", 80f, 100f, 110f, 112f),
                word("Tagged", 114f, 96f, 160f, 116f),
            )
        )
        assertEquals(20f, lines[0].maxFontSize, "the tallest real word is 20 tall")
    }

    @Test
    fun `a line of nothing but single letters is still measured`() {
        val lines = RecognizedText.linesOf(
            listOf(word("A", 72f, 100f, 84f, 118f, startsLine = true))
        )
        assertEquals(18f, lines[0].maxFontSize)
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
