package app.morpho.engine.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * What the file says it is. A PDF keeps its title, its author, what it is
 * about and its keywords in an information dictionary; every reader shows
 * them and every search across a folder reads them. Thrown away, a
 * converted paper arrives called nothing at all.
 */
class NamedDocumentTest {

    private fun pdf(name: (org.apache.pdfbox.pdmodel.PDDocumentInformation) -> Unit): ByteArray =
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("The body of it.")
                content.endText()
            }
            name(doc.documentInformation)
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }

    @Test
    fun `a paper that names itself comes back named`() {
        val said = PdfReader().extract(
            pdf {
                it.title = "الاستمارة في البحث العلمي"
                it.author = "ربيحة نبار"
                it.subject = "أدوات البحث"
                it.keywords = "استمارة; بحث علمي"
            }
        ).properties
        assertEquals("الاستمارة في البحث العلمي", said.title)
        assertEquals("ربيحة نبار", said.author)
        assertEquals("أدوات البحث", said.subject)
        assertEquals("استمارة; بحث علمي", said.keywords)
    }

    @Test
    fun `a file that says nothing about itself says nothing`() {
        val said = PdfReader().extract(pdf { }).properties
        assertTrue(said.isEmpty, "a file with an empty information dictionary named itself something")
    }

    @Test
    fun `a producer's empty title is not a title`() {
        // Word writes the fields whether or not anybody filled them in.
        val said = PdfReader().extract(pdf { it.title = "   "; it.author = "" }).properties
        assertTrue(said.isEmpty)
        assertNull(said.title)
    }

    @Test
    fun `naming the file does not disturb what it says`() {
        val model = PdfReader().extract(pdf { it.title = "A Study of Forms" })
        assertEquals(
            "The body of it.",
            model.blocks.filterIsInstance<app.morpho.engine.layout.Paragraph>().single().text,
        )
    }
}
