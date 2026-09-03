package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The same sentence in the same document was clickable when it arrived as
 * a PDF and plain when it arrived as a .docx: the readings of a PDF and of
 * a plain text file both look for an address a document merely writes out,
 * and the reading of a Word file did not. An author who typed an address
 * without making a link of it did not mean it to be uncopyable, which is
 * the whole argument for looking in the first place.
 */
class TypedAddressTest {

    private fun linksIn(docx: ByteArray): List<String> =
        DocxReader.read(docx).blocks.filterIsInstance<Paragraph>()
            .flatMap { it.runs }.mapNotNull { it.link }.distinct()

    @Test
    fun `an address a Word file writes out is a link when it is read back`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(
                listOf(
                    Paragraph(listOf(TextRun("Write to a.b@example.org or see www.example.org for it.")))
                )
            )
        )
        assertEquals(
            listOf("mailto:a.b@example.org", "https://www.example.org"),
            linksIn(docx),
        )
    }

    @Test
    fun `a link the Word file carried is the one that is kept`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(TextRun("a.b@example.org", link = "https://example.org/elsewhere"))
                    )
                )
            )
        )
        assertEquals(listOf("https://example.org/elsewhere"), linksIn(docx))
    }
}
