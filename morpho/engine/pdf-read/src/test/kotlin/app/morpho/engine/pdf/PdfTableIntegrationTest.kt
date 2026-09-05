package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/** End-to-end: an untagged PDF drawn as a grid comes back as a Table. */
class PdfTableIntegrationTest {

    private fun cellAt(content: PDPageContentStream, text: String, x: Float, y: Float) {
        content.beginText()
        content.setFont(PDType1Font.HELVETICA, 12f)
        content.newLineAtOffset(x, y)
        content.showText(text)
        content.endText()
    }

    @Test
    fun `an untagged grid becomes a table between body paragraphs`() {
        val pdf = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { c ->
                cellAt(c, "An introductory body paragraph above the grid.", 72f, 740f)

                var y = 700f
                for (row in listOf(
                    listOf("City", "Population", "Country"),
                    listOf("Rabat", "580000", "Morocco"),
                    listOf("Paris", "2100000", "France"),
                )) {
                    for ((column, text) in row.withIndex()) {
                        cellAt(c, text, 72f + column * 150f, y)
                    }
                    y -= 18f
                }

                cellAt(c, "A closing body paragraph below the grid.", 72f, 600f)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }

        val model = PdfReader().extract(pdf)
        val table = model.blocks.filterIsInstance<Table>().single()
        assertEquals(3, table.rows.size)
        assertEquals(
            listOf("City", "Population", "Country"),
            table.rows[0].cells.map { it.blocks.filterIsInstance<Paragraph>().single().text },
        )
        assertEquals(
            listOf("Rabat", "580000", "Morocco"),
            table.rows[1].cells.map { it.blocks.filterIsInstance<Paragraph>().single().text },
        )

        val paragraphs = model.blocks.filterIsInstance<Paragraph>()
        assertTrue(paragraphs.any { it.text.startsWith("An introductory") })
        assertTrue(paragraphs.any { it.text.startsWith("A closing") })
        assertTrue(model.blocks.indexOf(table) in 1 until model.blocks.size - 1, "table sits between the paragraphs")
    }
}
