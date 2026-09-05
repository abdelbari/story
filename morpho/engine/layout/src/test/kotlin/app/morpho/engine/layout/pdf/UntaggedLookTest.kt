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

    /** A line of two looks: [head] set bold, then [rest] in the body's weight. */
    private fun runIn(y: Float, head: String, rest: String, page: Int = 1) =
        line(head + rest, margin, right, y, page).copy(
            runs = head.map { PdfRun(it.toString(), PdfLook("Times New Roman", 12f, bold = true)) } +
                rest.map { PdfRun(it.toString(), PdfLook("Times New Roman", 12f)) },
        )

    /** A document that marks its sections by weight, as the paper this was built for does. */
    private fun marked(vararg own: PdfLine): List<PdfLine> =
        body(100f, 6) + short(200f, "1-The first section", bold = true) + body(215f, 6) + own.toList()

    @Test
    fun `a head the page set at the head of its own paragraph is still a head`() {
        // The shape that loses a section: the page sets the head and the
        // sentence after it on one line, the head in bold and the sentence
        // in the body's weight. Every other head in the document stands on
        // a line of its own, so the outline comes back with a number
        // missing out of the middle of it.
        val blocks = paragraphs(marked(runIn(400f, "2-The second section: ", "which opens here and runs on")))
        val at = blocks.indexOfFirst { it.text.startsWith("2-The second section") }
        assertTrue(at >= 0, "the head was lost: " + blocks.map { it.text.take(24) })
        // The level a head standing on its own line gets in this
        // document, because a head is a head wherever the page put it.
        val onItsOwn = blocks.first { it.text == "1-The first section" }.style.kind
        assertEquals(onItsOwn, blocks[at].style.kind, "not read as a head")
        assertEquals("2-The second section:", blocks[at].text, "the head kept the sentence after it")
        assertEquals(
            "which opens here and runs on",
            blocks[at + 1].text,
            "the paragraph did not keep what the head left",
        )
        assertEquals(ParagraphKind.BODY, blocks[at + 1].style.kind)
    }

    @Test
    fun `a close typed outside the weight closes the head just the same`() {
        // The same document does both, one section to the next: `**Step
        // one**: text` and `**Step two:** text`. Held to the weight alone,
        // a reader finds every other one of them.
        val blocks = paragraphs(marked(runIn(400f, "Step one", ": the planning of it")))
        val at = blocks.indexOfFirst { it.text.startsWith("Step one") }
        assertTrue(at >= 0, "the head was lost: " + blocks.map { it.text.take(24) })
        assertEquals("Step one:", blocks[at].text, "the close did not come with the head")
        assertEquals(blocks.first { it.text == "1-The first section" }.style.kind, blocks[at].style.kind)
        assertEquals("the planning of it", blocks[at + 1].text)
    }

    @Test
    fun `a bold opening with nothing closing it is emphasis`() {
        val blocks = paragraphs(marked(runIn(400f, "Nevertheless ", "the committee went on to say that")))
        assertEquals(
            listOf("Nevertheless the committee went on to say that"),
            blocks.filter { it.text.startsWith("Nevertheless") }.map { it.text },
            "an emphasised opening was made a head",
        )
    }

    @Test
    fun `a list item with a bold label is still a list item`() {
        // A list whose items open with a bold label and a colon is
        // ordinary — six of them on the paper this was measured on — and
        // read as heads it loses the list and gains six sections that are
        // not there.
        val blocks = paragraphs(marked(runIn(400f, "\u2022By post: ", "the form is sent out and returned")))
        assertEquals(
            listOf("\u2022By post: the form is sent out and returned"),
            blocks.filter { it.text.contains("By post") }.map { it.text },
            "a list item was made a head",
        )
    }

    @Test
    fun `a document that marks no head by weight keeps its bold openings`() {
        // The guard the whole reading rests on. Bold means a head only in
        // a document that sets a head in bold somewhere on its own line;
        // in one that never does, a bold opening is a label, a defined
        // term, the lead-in to a note.
        val blocks = paragraphs(
            body(100f, 6) + runIn(300f, "Abstract: ", "the study set out to establish") + body(400f, 6),
        )
        assertEquals(
            listOf("Abstract: the study set out to establish"),
            blocks.filter { it.text.startsWith("Abstract") }.map { it.text },
        )
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
