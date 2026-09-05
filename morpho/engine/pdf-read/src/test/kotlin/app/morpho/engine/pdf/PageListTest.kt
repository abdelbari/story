package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A page with no tags says nothing about its lists, and the items of a
 * list are set closer together than the lines of a paragraph are — so a
 * reader that breaks paragraphs on the gaps between lines reads a whole
 * checklist as one block of prose, every item run into the next.
 *
 * The label is what says otherwise: a bullet, a dash, a "3." at the head
 * of a line is where one item ends and the next begins.
 */
class PageListTest {

    /** Lines at the given left edge, set as tight as a list is set. */
    private fun page(lines: List<Pair<String, Float>>): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                var y = 740f
                for ((text, x) in lines) {
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 11f)
                    content.newLineAtOffset(x, y)
                    content.showText(text)
                    content.endText()
                    y -= 15f
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun paragraphs(bytes: ByteArray): List<String> =
        PdfReader().extract(bytes).blocks.filterIsInstance<Paragraph>().map { it.text.trim() }

    @Test
    fun `each item of a numbered list is an item of its own`() {
        val read = paragraphs(
            page(
                listOf(
                    "Before the tag, work through the following." to 72f,
                    "1. All the checks pass on the release branch." to 90f,
                    "2. The changelog is reviewed and dated." to 90f,
                    "3. The version is bumped in every manifest." to 90f,
                )
            )
        )
        assertEquals(
            listOf(
                "Before the tag, work through the following.",
                "1. All the checks pass on the release branch.",
                "2. The changelog is reviewed and dated.",
                "3. The version is bumped in every manifest.",
            ),
            read,
        )
    }

    @Test
    fun `each item of a bulleted list is an item of its own`() {
        val read = paragraphs(
            page(
                listOf(
                    "• The rollback script lives beside the deploy script." to 90f,
                    "• Release notes are written for the people who read them." to 90f,
                    "• A red dashboard beats a silent regression." to 90f,
                )
            )
        )
        assertEquals(3, read.size, read.toString())
        assertTrue(read.all { it.startsWith("•") }, read.toString())
    }

    @Test
    fun `a sentence that merely opens with a dash is not an item`() {
        // The label has to be a label: one character and a space, or a
        // short enumerator. A dash joined to the word after it is prose.
        val read = paragraphs(
            page(
                listOf(
                    "The first line of an ordinary paragraph that runs on" to 72f,
                    "-and on across a break with no space after the dash." to 72f,
                )
            )
        )
        assertEquals(1, read.size, read.toString())
    }

    @Test
    fun `an item that runs to a second line keeps that line`() {
        val read = paragraphs(
            page(
                listOf(
                    "1. An item long enough that its words run past the end of" to 90f,
                    "the line it started on and onto the next one below it." to 102f,
                    "2. A second item, shorter than the first." to 90f,
                )
            )
        )
        assertEquals(2, read.size, read.toString())
        assertTrue(read[0].contains("onto the next one"), read[0])
    }
}
