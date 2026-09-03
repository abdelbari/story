package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The preview is where somebody checks a conversion before trusting it.
 * A document that came in with a supervisor's remarks on it and previews
 * without them looks like a clean document, and whoever is looking at it
 * has no way to tell that anything was lost.
 */
class CommentedPreviewTest {

    private fun html(document: DocumentModel) = HtmlWriter.write(document)

    private val note = Comment(id = 1, text = "Say which year.", author = "Amina Barry")

    @Test
    fun `the words a note is about are marked, and the note is at the end`() {
        val page = html(
            DocumentModel(
                blocks = listOf(
                    Paragraph(
                        listOf(
                            TextRun("Written in "),
                            TextRun("the spring", commentIds = listOf(1)),
                            TextRun(" of that year."),
                        )
                    )
                ),
                comments = listOf(note),
            )
        )
        assertTrue(page.contains("""<span class="commented""""), page)
        // What was said, without leaving the words to hunt for it.
        assertTrue(page.contains("""title="Amina Barry: Say which year.""""), page)
        assertTrue(page.contains("""<section class="comments">"""), page)
        assertTrue(page.contains("Say which year."), page)
        assertTrue(page.contains("Amina Barry"), page)
        // The mark leads to the note and the note leads back.
        assertTrue(page.contains("""href="#comment-1""""), page)
        assertTrue(page.contains("""id="comment-1""""), page)
        assertTrue(page.contains("""href="#comment-mark-1""""), page)
    }

    @Test
    fun `a note about a passage is marked once, at the end of it`() {
        val page = html(
            DocumentModel(
                blocks = listOf(
                    Paragraph(listOf(TextRun("The first claim.", commentIds = listOf(1)))),
                    Paragraph(listOf(TextRun("The second.", commentIds = listOf(1)))),
                ),
                comments = listOf(note),
            )
        )
        assertEquals(2, Regex("""<span class="commented"""").findAll(page).count(), page)
        // One mark, after the last of the words it is about — not one per
        // paragraph, which would read as two notes.
        assertEquals(1, Regex("""<sup class="comment-mark"""").findAll(page).count(), page)
        assertTrue(page.indexOf("The second.") < page.indexOf("""<sup class="comment-mark""""), page)
    }

    @Test
    fun `a document nobody commented on previews as it always did`() {
        // The style sheet always names them, as it always names the
        // footnotes; what must not be there is anything in the document.
        val body = html(DocumentModel(listOf(Paragraph(listOf(TextRun("Plain."))))))
            .substringAfter("</style>")
        assertFalse(body.contains("comment"), body)
    }

    @Test
    fun `a note nothing is about is not marked in the text and is still read`() {
        // A note that lost its anchor is still what somebody said. It is
        // shown at the end rather than dropped without a word.
        val page = html(
            DocumentModel(
                blocks = listOf(Paragraph(listOf(TextRun("Plain.")))),
                comments = listOf(note),
            )
        )
        assertFalse(page.contains("""<span class="commented""""), page)
        assertFalse(page.contains("""<section class="comments">"""), page)
    }

    @Test
    fun `a note in Arabic is set in the page's own direction`() {
        val page = html(
            DocumentModel(
                blocks = listOf(Paragraph(listOf(TextRun("المنهج الوصفي", commentIds = listOf(2))))),
                defaultDirection = TextDirection.RTL,
                comments = listOf(Comment(id = 2, text = "وضّح المصدر.", author = "المشرف")),
            )
        )
        assertTrue(page.contains("وضّح المصدر."), page)
        assertTrue(page.contains("""<html dir="rtl""""), page)
    }

    @Test
    fun `a note whose words are a link keeps both`() {
        val page = html(
            DocumentModel(
                blocks = listOf(
                    Paragraph(
                        listOf(TextRun("the register", link = "https://example.org/", commentIds = listOf(1)))
                    )
                ),
                comments = listOf(note),
            )
        )
        // The link is inside the mark: a link inside a link is not HTML,
        // and a browser given one drops the outer.
        assertTrue(page.contains("""<span class="commented"""), page)
        assertTrue(page.contains("""<a href="https://example.org/">the register</a>"""), page)
        assertFalse(Regex("""<a [^>]*><a """).containsMatchIn(page), page)
    }
}
