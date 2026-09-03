package app.morpho.engine.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * A piece of a page, drawn.
 *
 * Only the band asked for is rendered, never the whole sheet: an A4 page
 * at this resolution is eighteen megabytes and a document wants one for
 * its head and another for its foot, which is more than a phone always
 * has. Drawing a part of a page rather than all of it is easy to get
 * subtly wrong — the renderer clears what it is about to draw on, with a
 * background that is transparent black unless it is told otherwise, and a
 * header came back as a black strip with its rules still visible on it.
 */
class PageImagesTest {

    /** One page: a line across the top, a line of prose down the middle. */
    private fun page(): PDDocument {
        val doc = PDDocument()
        val sheet = PDPage(PDRectangle.A4)
        doc.addPage(sheet)
        PDPageContentStream(doc, sheet).use { content ->
            content.beginText()
            content.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            content.newLineAtOffset(72f, PDRectangle.A4.height - 40f)
            content.showText("The Journal of Something")
            content.endText()
            content.beginText()
            content.setFont(PDType1Font.HELVETICA, 11f)
            content.newLineAtOffset(72f, 400f)
            content.showText("The words of the page itself.")
            content.endText()
        }
        return doc
    }

    private fun pixels(bytes: ByteArray): List<Int> {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        return (0 until image.height).flatMap { y ->
            (0 until image.width).map { x -> image.getRGB(x, y) and 0xFFFFFF }
        }
    }

    @Test
    fun `a band of a page is drawn on the white the paper is`() {
        page().use { doc ->
            val head = PageImages.crop(doc, 0, 60f, 20f, 520f, 50f)
            assertNotNull(head)
            val shades = pixels(head!!.image.bytes)
            assertTrue(shades.count { it == 0xFFFFFF } > shades.size / 2, "most of a head is paper")
            assertTrue(shades.any { it != 0xFFFFFF }, "and some of it is the words")
        }
    }

    @Test
    fun `a band with nothing drawn in it is refused`() {
        page().use { doc ->
            // The middle of the page, well clear of both lines of text. A
            // blank answer written into a document is an empty white strip
            // where the header should be, which reads as the header having
            // been lost; refusing it lets the caller say something else.
            assertNull(PageImages.crop(doc, 0, 60f, 500f, 520f, 560f))
        }
    }

    @Test
    fun `a band trimmed comes back as the ink and says where it sits`() {
        page().use { doc ->
            // Asked for generously — everything above the words of the page
            // — and trimmed back to the head, which is the only thing that
            // says where an unreadable head begins and ends.
            val whole = PageImages.crop(doc, 0, 0f, 0f, 595f, 60f, trim = false)
            val trimmed = PageImages.crop(doc, 0, 0f, 0f, 595f, 60f, trim = true)
            assertNotNull(whole)
            assertNotNull(trimmed)
            assertTrue(trimmed!!.top > 20f, "the paper above the head is not part of it: ${trimmed.top}")
            assertTrue(trimmed.bottom <= 60f)
            assertTrue(trimmed.left > 60f, "nor the margin beside it: ${trimmed.left}")
            val trimmedHeight = trimmed.image.heightPt!!
            val wholeHeight = whole!!.image.heightPt!!
            assertTrue(
                trimmedHeight < wholeHeight,
                "trimmed $trimmedHeight is not less than whole $wholeHeight",
            )
            // Untrimmed, the picture is placed at exactly the size asked
            // for rather than rounded through the pixel grid.
            assertEquals(60f, wholeHeight)
            assertEquals(595f, whole.image.widthPt!!)
        }
    }

    @Test
    fun `a page that is not there is refused rather than guessed at`() {
        page().use { doc ->
            assertNull(PageImages.crop(doc, 7, 60f, 20f, 520f, 50f))
            assertNull(PageImages.crop(doc, -1, 60f, 20f, 520f, 50f))
        }
    }
}
