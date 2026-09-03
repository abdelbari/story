package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TextDirection
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A table of Arabic is read from the right: the rightmost column is the
 * first. The untagged reader gathers cells across the page from the left,
 * so it has to turn them round, and the columns it measured with them —
 * otherwise the years come back under the names.
 */
class ArabicTableTest {

    @Test
    fun `an Arabic table is read from its right`() {
        val table = PdfReader().extract(arabicTablePdf()).blocks
            .filterIsInstance<Table>().singleOrNull()
        assertTrue(table != null, "no table was found")
        assertEquals(TextDirection.RTL, table!!.direction)
        val head = table.rows.first().cells.map {
            it.blocks.filterIsInstance<Paragraph>().first().text
        }
        // On the page, "السنة" is drawn at the right and "العدد" to its
        // left; the first cell of the table is the one on the right.
        assertEquals(listOf("السنة", "العدد"), head)
    }

    @Test
    fun `its columns are measured the same way round`() {
        val table = PdfReader().extract(arabicTablePdf()).blocks
            .filterIsInstance<Table>().single()
        val widths = table.columnWidthsPt
        assertTrue(widths != null && widths.size == 2, "the columns were not measured: " + widths)
        // The right-hand column is the wider of the two on the page, and it
        // is the first of the table.
        assertTrue(widths!![0] > widths[1], "the widths came back the wrong way round: " + widths)
    }

    /**
     * Two columns of Arabic, the wider one on the right, drawn as a PDF
     * draws them: each word painted where it stands, nothing tagged.
     */
    private fun arabicTablePdf(): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val font = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")
                    ?: error("test font missing"),
            )
            PDPageContentStream(document, page).use { content ->
                val rows = listOf(
                    listOf("السنة", "العدد"),
                    listOf("ألفان وتسعة عشر", "مئة"),
                    listOf("ألفان وعشرون", "مئتان"),
                )
                var y = 700f
                for (row in rows) {
                    // The right column starts at 400 and the left at 200:
                    // two columns with a clear gutter between them.
                    for ((index, word) in row.withIndex()) {
                        content.beginText()
                        content.setFont(font, 12f)
                        content.newLineAtOffset(if (index == 0) 400f else 200f, y)
                        // A producer paints Arabic in the order the glyphs
                        // stand on the paper, which is the reverse of the
                        // order they are typed in.
                        content.showText(word.reversed())
                        content.endText()
                    }
                    y -= 24f
                }
            }
            document.save(out)
        }
        return out.toByteArray()
    }
}
