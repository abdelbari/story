package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where one paragraph of an untagged page ends and the next begins.
 *
 * A PDF holds lines, not paragraphs, so the break has to be read off the
 * page: a line set in from the edge its block starts at, or a gap wider
 * than the one between the lines of a paragraph. Both readings were
 * written for a left-to-right page and quietly did nothing on an Arabic
 * one — the first because the edge an Arabic line starts at is its right,
 * the second because Arabic is set with more leading than English and a
 * fixed multiple of the pitch turns on how open the setting is.
 */
class ParagraphBreaksTest {

    private val size = 10f

    private fun line(text: String, x: Float, xEnd: Float, y: Float, page: Int = 1) =
        PdfLine(text = text, x = x, baselineY = y, maxFontSize = size, page = page, xEnd = xEnd)

    private fun paragraphs(lines: List<PdfLine>) =
        PdfLayout.reconstruct(lines, confidence = 0.6f)
            .blocks.filterIsInstance<Paragraph>()

    /**
     * Three Arabic paragraphs of three lines, each opening with a line set
     * in from the right edge and closing with one that stops short of the
     * left, with nothing but the page's own line pitch between them.
     */
    private fun indentedArabic(): List<PdfLine> {
        val out = mutableListOf<PdfLine>()
        var y = 100f
        // Ragged, as a page with no justification is: the lines start
        // flush at the right and stop where their words stop. Only the
        // first line of each paragraph starts anywhere else.
        val ends = listOf(148f, 100f, 300f, 172f, 118f, 300f, 160f, 104f, 300f)
        for (paragraph in 0 until 3) {
            out += line("الاستمارة في البحث العلمي هي الأداة التي", ends[paragraph * 3], 470f, y)
            out += line("يجمع بها الباحث ما لا يمنحه الميدان من", ends[paragraph * 3 + 1], 500f, y + 21f)
            out += line("تلقاء نفسه وتصميمها", ends[paragraph * 3 + 2], 500f, y + 42f)
            y += 63f
        }
        return out
    }

    @Test
    fun `an arabic first line set in from the right edge opens a paragraph`() {
        val paragraphs = paragraphs(indentedArabic())
        assertEquals(3, paragraphs.size, "read as ${paragraphs.size}: ${paragraphs.map { it.text }}")
        assertTrue(
            paragraphs.all { it.text.startsWith("الاستمارة") },
            "a paragraph began somewhere other than at an indent: ${paragraphs.map { it.text.take(20) }}",
        )
    }

    @Test
    fun `coming back out of the indent does not open another`() {
        // Measured against the line above rather than against the block,
        // the indent fires twice for every paragraph — once going in and
        // once coming back out — and cuts each one after its first line.
        val paragraphs = paragraphs(indentedArabic())
        assertTrue(
            paragraphs.all { it.text.contains("يجمع بها") },
            "a paragraph was cut after its first line: ${paragraphs.map { it.text.take(24) }}",
        )
    }

    @Test
    fun `an english first line set in from the left edge opens a paragraph`() {
        val out = mutableListOf<PdfLine>()
        var y = 100f
        val ends = listOf(452f, 500f, 300f, 476f, 482f, 300f, 440f, 496f, 300f)
        for (paragraph in 0 until 3) {
            out += line("The form in scientific research is the tool", 130f, ends[paragraph * 3], y)
            out += line("by which a researcher gathers what the", 100f, ends[paragraph * 3 + 1], y + 21f)
            out += line("field will not hand over what it holds", 100f, ends[paragraph * 3 + 2], y + 42f)
            y += 63f
        }
        val paragraphs = paragraphs(out)
        assertEquals(3, paragraphs.size, "read as ${paragraphs.size}: ${paragraphs.map { it.text }}")
    }

    @Test
    fun `paragraphs set apart by less than half a line again are still apart`() {
        // Openly set: a pitch of twenty-one points at ten-point type, with
        // a paragraph space of a further nine. Half the pitch again is
        // thirty-three and a half, so a fixed multiple of the pitch reads
        // the whole page as one paragraph — which is what happened to an
        // Arabic page whose English twin, set tighter, came apart
        // correctly, and missed by a seventh of a point.
        val out = mutableListOf<PdfLine>()
        var y = 100f
        val ends = listOf(148f, 100f, 300f, 172f, 118f, 300f, 160f, 104f, 300f)
        for (paragraph in 0 until 3) {
            out += line("الاستمارة في البحث العلمي هي الأداة التي", ends[paragraph * 3], 500f, y)
            out += line("يجمع بها الباحث ما لا يمنحه الميدان من", ends[paragraph * 3 + 1], 500f, y + 21f)
            out += line("تلقاء نفسه وتصميمها", ends[paragraph * 3 + 2], 500f, y + 42f)
            y += 72f
        }
        val paragraphs = paragraphs(out)
        assertEquals(3, paragraphs.size, "read as ${paragraphs.size}: ${paragraphs.map { it.text }}")
    }

    @Test
    fun `lines evenly spaced and evenly set are one paragraph, not nine`() {
        // The guard on both readings above: nothing here says a paragraph
        // ended, and a converter that splits anyway hands back a document
        // broken at every line — the worse mistake of the two, since a
        // reader can join two paragraphs and cannot easily unbreak fifty.
        val out = mutableListOf<PdfLine>()
        for (row in 0 until 9) {
            out += line("يجمع بها الباحث ما لا يمنحه الميدان من", 100f, 500f, 100f + row * 21f)
        }
        assertEquals(1, paragraphs(out).size, "the page came apart at its line breaks")
    }
}
