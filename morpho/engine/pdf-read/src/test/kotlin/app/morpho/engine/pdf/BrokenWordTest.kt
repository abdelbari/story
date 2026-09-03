package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A justified page breaks a word to fill its line, and a reading that
 * keeps the hyphen hands back a document littered with "admin-istrative".
 * A reading that drops every hyphen instead corrupts "well-known" into
 * "wellknown", which is worse: the first is untidy and the second is
 * wrong.
 *
 * There is no dictionary here to tell them apart — this app carries no
 * word lists and never reaches the network — so the document is the
 * dictionary. A paper that breaks "administrative" at one line writes it
 * whole at another, and one that writes "well-known" writes it with its
 * hyphen wherever it falls.
 */
class BrokenWordTest {

    private fun textOf(pdf: ByteArray): String =
        PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }

    @Test
    fun `a word the page broke is whole again where the page writes it whole`() {
        val text = textOf(
            pageOf(
                "The committee deferred the admin-",
                "istrative matters until the spring.",
                "Administrative work of every kind was",
                "deferred with them.",
            )
        )
        assertTrue(text.contains("administrative matters"), text)
        assertFalse(text.contains("admin-istrative"), text)
    }

    @Test
    fun `a word that carries its own hyphen keeps it`() {
        val text = textOf(
            pageOf(
                "The critics were unmoved, and the well-",
                "known ones said so at length.",
                "Every well-known critic said the same",
                "thing twice over.",
            )
        )
        assertTrue(text.contains("well-known ones"), text)
        assertFalse(text.contains("wellknown"), "a real hyphen was thrown away: $text")
    }

    @Test
    fun `a word the page never writes whole keeps its hyphen`() {
        // Nothing in the document settles it, so it is left as it stands:
        // wrong at worst in the way that destroys nothing.
        val text = textOf(
            pageOf(
                "The committee deferred the admin-",
                "istrative matters until the spring.",
                "Nothing else here bears on the question",
                "at all.",
            )
        )
        assertTrue(text.contains("admin-istrative") || text.contains("admin‐istrative"), text)
    }

    /**
     * A page of lines set one under another in a single column, the way a
     * justified page sets them. The lines are close enough together to be
     * read as one paragraph, which is what makes them a paragraph whose
     * words have to be rejoined.
     */
    private fun pageOf(vararg lines: String): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A5)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                var y = 500f
                for (line in lines) {
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 11f)
                    content.newLineAtOffset(60f, y)
                    content.showText(line)
                    content.endText()
                    y -= 15f
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }
}
