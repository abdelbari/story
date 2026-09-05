package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Reading
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A long reading, watched and stopped.
 *
 * A book of two hundred pages is seconds of work on a desktop and the
 * better part of a minute on a phone. Until now the reader who asked for
 * one saw a spinner that said nothing and offered nothing — no page
 * count, and no way to change their mind about a file picked by mistake.
 * Recognition already had both; this is the same two for the reading
 * every conversion does.
 *
 * What the channel must never do is change the reading. Every existing
 * caller passes nothing, and a reading nobody watches has to be the
 * reading it always was.
 */
class WatchedReadingTest {

    private val pages = 12

    @Test
    fun `a reading says which page it has reached, and of how many`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        PdfReader().extract(book(), reading = Reading(onPage = { page, count -> seen += page to count }))
        assertEquals(
            (1..pages).map { it to pages },
            seen,
            "the pages were not reported one by one, in order, out of the right total",
        )
    }

    @Test
    fun `a reading of part of a document counts the part`() {
        // Asking for five pages reads a document of five pages, and a
        // count out of the whole book would leave the bar stuck at a
        // fortieth of the way across for the whole read.
        val seen = mutableListOf<Pair<Int, Int>>()
        PdfReader().extract(
            book(), pages = 3..7,
            reading = Reading(onPage = { page, count -> seen += page to count }),
        )
        assertEquals((1..5).map { it to 5 }, seen)
    }

    @Test
    fun `a reading stops where it is told to`() {
        var reached = 0
        val stopped = assertThrows(Reading.Cancelled::class.java) {
            PdfReader().extract(
                book(),
                reading = Reading(
                    onPage = { page, _ -> reached = page },
                    shouldContinue = { reached < 4 },
                ),
            )
        }
        assertTrue(reached in 1..5, "it read $reached pages before stopping")
        assertTrue(stopped.message.orEmpty().isNotBlank())
    }

    @Test
    fun `a stopped reading is not a failed one`() {
        // The passes are wrapped so that one of them failing costs a
        // document its pictures rather than the reader the document.
        // Swallowed there, a stopped reading would come back as a whole
        // document with everything that pass would have found missing
        // from it, which is not what stopping means.
        assertThrows(Reading.Cancelled::class.java) {
            PdfReader().extract(book(), reading = Reading(shouldContinue = { false }))
        }
    }

    @Test
    fun `a reading nobody watches is the reading it always was`() {
        val watched = PdfReader().extract(book(), reading = Reading(onPage = { _, _ -> }))
        val unwatched = PdfReader().extract(book())
        assertEquals(text(unwatched), text(watched))
        assertEquals(unwatched.blocks.size, watched.blocks.size)
    }

    private fun text(model: app.morpho.engine.layout.DocumentModel) =
        model.blocks.filterIsInstance<Paragraph>().joinToString("\n") { it.text }

    /** A book of [pages] pages, each with a line or two on it. */
    private fun book(): ByteArray {
        PDDocument().use { doc ->
            for (number in 1..pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    var y = 760f
                    for (line in 1..3) {
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(72f, y)
                        content.showText("Page $number, line $line, of a book long enough to watch.")
                        content.endText()
                        y -= 18f
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }
}
