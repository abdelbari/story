package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.TextDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the untagged heuristics make of a page besides its words: the
 * furniture they drop, the runs they keep, and where they judge a paragraph
 * to sit. A PDF from a scanner or an older tool has no tags to read, and
 * before this it came out as unstyled text in one long block.
 */
class UntaggedLookTest {

    private val a4 = (1..3).map { PdfPageSheet(it, 595f, 842f) }
    private val margin = 56f
    private val right = 539f

    /** A justified line: full width, both edges on the margins. */
    private fun full(y: Float, text: String, page: Int = 1, size: Float = 12f, bold: Boolean = false) =
        line(text, margin, right, y, page, size, bold)

    /** A paragraph's last line: it stops well short of the end margin. */
    private fun short(y: Float, text: String, page: Int = 1, size: Float = 12f, bold: Boolean = false) =
        line(text, margin, margin + 120f, y, page, size, bold)

    private fun line(
        text: String,
        x: Float,
        xEnd: Float,
        y: Float,
        page: Int = 1,
        size: Float = 12f,
        bold: Boolean = false,
        family: String = "Times New Roman",
    ) = PdfLine(
        text = text,
        x = x,
        xEnd = xEnd,
        baselineY = y,
        maxFontSize = size,
        page = page,
        runs = text.map { PdfRun(it.toString(), PdfLook(family, size, bold)) },
    )

    /** Twenty justified lines: enough for the heuristics to see a justified document. */
    private fun body(from: Float, count: Int, page: Int = 1): List<PdfLine> =
        (0 until count).map { full(from + it * 15f, "line ${it + 1} of the body text", page) }

    private fun paragraphs(lines: List<PdfLine>) =
        PdfLayout.reconstruct(lines, confidence = 0.6f, sheets = a4).blocks.filterIsInstance<Paragraph>()

    @Test
    fun `a running header and footer are not part of the text`() {
        val lines = mutableListOf<PdfLine>()
        for (page in 1..3) {
            lines += line("A Paper About Forms", margin, right, 30f, page)
            lines += body(140f, 8, page)
            lines += line("page ${page + 1}", margin, margin + 40f, 820f, page)
        }
        val text = paragraphs(lines).joinToString(" ") { it.text }
        assertTrue(!text.contains("A Paper About Forms"), "running header kept: $text")
        assertTrue(!text.contains("page 2"), "running footer kept: $text")
        assertTrue(text.contains("line 1 of the body text"), "body lost: $text")
    }

    @Test
    fun `a header that appears on one page only is text`() {
        val lines = mutableListOf<PdfLine>()
        lines += line("A Paper About Forms", margin, right, 30f, 1)
        for (page in 1..3) lines += body(140f, 8, page)
        val text = paragraphs(lines).joinToString(" ") { it.text }
        assertTrue(text.contains("A Paper About Forms"), "a one-off line is not furniture: $text")
    }

    @Test
    fun `in a justified document a line that stops short ends its paragraph`() {
        val lines = body(100f, 10) + short(250f, "the last line") + body(265f, 10)
        val texts = paragraphs(lines).map { it.text }
        assertEquals(2, texts.size, "$texts")
        assertTrue(texts[0].endsWith("the last line"), texts[0])
        assertTrue(texts[1].startsWith("line 1 of the body text"), texts[1])
    }

    @Test
    fun `runs keep the face, size and weight each line was drawn in`() {
        // One line, two looks: the bold label at the head of an abstract
        // and the sentence that follows it.
        val mixed = line("Abstract: the study", margin, right, 100f).copy(
            runs = "Abstract:".map { PdfRun(it.toString(), PdfLook("Times New Roman", 12f, bold = true)) } +
                " the study".map { PdfRun(it.toString(), PdfLook("Times New Roman", 12f)) },
        )
        val paragraph = paragraphs(listOf(mixed) + body(200f, 10)).first()
        // The space between them belongs to the label's run: a space shows
        // nothing of the weight it was set in, and a page whose spaces are
        // set apart from its words breaks into a run for every word.
        assertEquals(listOf("Abstract: ", "the study"), paragraph.runs.map { it.text })
        assertEquals(listOf(true, false), paragraph.runs.map { it.bold })
        assertEquals("Times New Roman", paragraph.runs[0].fontFamily)
        assertEquals(12f, paragraph.runs[0].fontSizePt)
    }

    @Test
    fun `a short bold line is a heading even at the body's size`() {
        val lines = body(100f, 10) + short(250f, "1-Introduction", bold = true) + body(265f, 10)
        val heading = paragraphs(lines).first { it.text == "1-Introduction" }
        assertTrue(heading.style.kind != ParagraphKind.BODY, "bold heading not promoted")
    }

    @Test
    fun `a centred line is centred and a justified paragraph is justified`() {
        val title = line("The Title", 250f, 345f, 40f)
        val lines = listOf(title) + body(100f, 10)
        val (first, rest) = paragraphs(lines)
        assertEquals(Alignment.CENTER, first.style.alignment)
        assertEquals(Alignment.JUSTIFY, rest.style.alignment)
    }

    @Test
    fun `a first line indent and a hanging indent are told apart`() {
        val indented = listOf(full(400f, "first line").copy(x = margin + 36f)) +
            (1..3).map { full(400f + it * 15f, "line $it") }
        val hanging = listOf(full(500f, "entry")) +
            (1..3).map { full(500f + it * 15f, "line $it").copy(x = margin + 24f) }
        val paragraphs = paragraphs(body(100f, 10) + indented + hanging)
        val withIndent = paragraphs.first { it.text.startsWith("first line") }
        val withHang = paragraphs.first { it.text.startsWith("entry") }
        assertEquals(36f, withIndent.style.firstLineIndentPt)
        assertNull(withIndent.style.hangingIndentPt)
        assertEquals(24f, withHang.style.hangingIndentPt)
        assertEquals(24f, withHang.style.startIndentPt)
    }

    @Test
    fun `the page and its margins are measured from the lines that were kept`() {
        val lines = mutableListOf<PdfLine>()
        for (page in 1..3) {
            lines += line("running header", 20f, 575f, 20f, page)
            lines += body(140f, 8, page)
        }
        val page = PdfLayout.reconstruct(lines, confidence = 0.6f, sheets = a4).pageSetup
        assertNotNull(page)
        assertEquals(595f, page!!.widthPt)
        assertEquals(842f, page.heightPt)
        assertEquals(margin, page.marginLeftPt, "the header's own margin is not the document's")
        assertEquals(595f - right, page.marginRightPt)
    }

    @Test
    fun `spacing between paragraphs and the pitch of their lines are measured`() {
        val first = body(100f, 4) + short(160f, "the last line")
        val second = body(200f, 4)
        val (one, two) = paragraphs(first + second)
        assertEquals(15f, one.style.linePitchPt)
        assertEquals(25f, one.style.spaceAfterPt, "200 - 160 less the next paragraph's own pitch")
        assertEquals(0f, two.style.spaceAfterPt)
    }

    @Test
    fun `a document with no looks still reads as plain text`() {
        val plain = (0 until 10).map {
            PdfLine(
                text = "line $it",
                x = margin,
                xEnd = right,
                baselineY = 100f + it * 15f,
                maxFontSize = 12f,
                page = 1,
            )
        }
        val paragraph = paragraphs(plain).first()
        assertEquals(1, paragraph.runs.size)
        assertNull(paragraph.runs.single().fontFamily)
        assertEquals(TextDirection.LTR, paragraph.style.direction ?: TextDirection.LTR)
    }
}
