package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextRun
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.color.PDColor
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A PDF that has been through review carries the reviewer's own words:
 * a remark typed against a highlight, or a note dropped in the margin
 * beside a line. This is the file a supervisor sends a thesis back as,
 * and the whole reason it was sent back — and every converter hands over
 * the document without a word of it.
 *
 * Nothing in the file joins a note to the words it is about, so the two
 * are joined by where they sit, the way a highlight's words are.
 */
class ReviewedPdfTest {

    private fun runsOf(pdf: ByteArray): List<TextRun> =
        PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>().flatMap { it.runs }

    @Test
    fun `a remark typed against a highlight comes back as a note on those words`() {
        val model = PdfReader().extract(reviewedPdf())
        assertEquals(1, model.comments.size, model.comments.toString())
        val note = model.comments.single()
        assertEquals("Say which year.", note.text)
        assertEquals("Amina Barry", note.author)
        assertEquals("2026-09-03T09:15:00Z", note.dateIso)

        val runs = model.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        val about = runs.filter { it.commentIds.isNotEmpty() }
        assertTrue(about.isNotEmpty(), "nothing came back with a note on it")
        assertTrue(
            about.any { it.text.contains("important") },
            "the noted words were: " + about.map { it.text },
        )
        assertEquals(listOf(note.id), about.first().commentIds)
        assertTrue(
            runs.filter { it.text.contains("ordinary") }.none { it.commentIds.isNotEmpty() },
            "the words beside the marking got the note too",
        )
    }

    @Test
    fun `a highlight nobody remarked on is a marking and nothing more`() {
        // Most highlighting says nothing: somebody paints a passage
        // yellow to find it again. A note where there is none would put
        // an empty comment into the converted file.
        val model = PdfReader().extract(reviewedPdf(remark = null))
        assertTrue(model.comments.isEmpty(), model.comments.toString())
        val runs = model.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        assertTrue(runs.none { it.commentIds.isNotEmpty() })
        // And the marking itself is still there.
        assertTrue(runs.any { it.highlightRgb != null }, "the highlight was lost")
    }

    @Test
    fun `a note left in the margin is about the line it sits beside`() {
        val model = PdfReader().extract(reviewedPdf(remark = null, sticky = true))
        assertEquals(1, model.comments.size, model.comments.toString())
        assertEquals("Where is this from?", model.comments.single().text)
        val runs = model.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        val about = runs.filter { it.commentIds.isNotEmpty() }
        assertTrue(
            about.any { it.text.contains("important") },
            "the noted words were: " + about.map { it.text },
        )
        // The line above it is a different line and is not the subject.
        assertTrue(
            runs.filter { it.text.contains("ordinary") }.none { it.commentIds.isNotEmpty() },
            "a note beside one line reached the line above it",
        )
    }

    @Test
    fun `a document nobody reviewed comes back with nothing said about it`() {
        val model = PdfReader().extract(reviewedPdf(highlighted = false, remark = null))
        assertTrue(model.comments.isEmpty())
        assertTrue(runsOf(reviewedPdf(highlighted = false, remark = null)).none { it.commentIds.isNotEmpty() })
    }

    @Test
    fun `an unsigned note is kept as what it says`() {
        val model = PdfReader().extract(reviewedPdf(author = null, modified = null))
        val note = model.comments.single()
        assertEquals("Say which year.", note.text)
        assertEquals(null, note.author)
        assertEquals(null, note.dateIso)
    }

    @Test
    fun `a note in Arabic is kept as it was written`() {
        val model = PdfReader().extract(reviewedPdf(remark = "وضّح المصدر.", author = "المشرف"))
        assertEquals("وضّح المصدر.", model.comments.single().text)
        assertEquals("المشرف", model.comments.single().author)
    }

    @Test
    fun `converting part of a document keeps the notes on those pages`() {
        val reviewed = reviewedPdf(pages = 2)
        val first = PdfReader().extract(reviewed, pages = 1..1)
        assertEquals(1, first.comments.size, first.comments.toString())
        val runs = first.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        assertTrue(
            runs.filter { it.commentIds.isNotEmpty() }.any { it.text.contains("important") },
            "the note did not come with the page it was on",
        )
        // Only the first page was reviewed, so the second on its own has
        // nothing said about it: a note is carried with its page, not
        // stamped on whatever was asked for.
        val second = PdfReader().extract(reviewed, pages = 2..2)
        assertTrue(second.comments.isEmpty(), second.comments.toString())
        assertTrue(
            second.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
                .none { it.commentIds.isNotEmpty() },
        )
    }

    private fun reviewedPdf(
        highlighted: Boolean = true,
        remark: String? = "Say which year.",
        author: String? = "Amina Barry",
        modified: String? = "D:20260903091500Z",
        sticky: Boolean = false,
        pages: Int = 1,
    ): ByteArray {
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    for ((offset, words) in listOf(720f to "ordinary words", 700f to "important words")) {
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(72f, offset)
                        content.showText(if (index == 0) words else "$words on page two")
                        content.endText()
                    }
                }
                // Only the first page is reviewed, so a conversion of it
                // alone can be told from a conversion of the other.
                if (index > 0) return@repeat
                if (highlighted) {
                    val highlight = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
                    highlight.color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                    highlight.rectangle = PDRectangle(70f, 696f, 200f, 16f)
                    highlight.quadPoints = floatArrayOf(70f, 712f, 270f, 712f, 70f, 696f, 270f, 696f)
                    if (remark != null) {
                        highlight.contents = remark
                        highlight.titlePopup = author
                        modified?.let { highlight.modifiedDate = it }
                    }
                    page.annotations.add(highlight)
                }
                if (sticky) {
                    val note = PDAnnotationText()
                    // An icon in the margin, level with the second line.
                    note.rectangle = PDRectangle(20f, 698f, 16f, 16f)
                    note.contents = "Where is this from?"
                    note.titlePopup = author
                    page.annotations.add(note)
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }
}
