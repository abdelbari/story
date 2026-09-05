package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Word marks text two ways: with the highlighter, which knows sixteen
 * colours by name, and with shading, which takes any colour and draws the
 * same. Both have to survive, and a marking read out of a PDF is usually
 * neither Word's yellow nor a colour Word can name.
 */
class HighlightRoundTripTest {

    private fun marked(text: String, rgb: Int?) =
        DocumentModel(blocks = listOf(Paragraph(runs = listOf(TextRun(text, highlightRgb = rgb)))))

    @Test
    fun `a yellow marking is written with Word's own highlighter`() {
        val docx = DocxWriter.toByteArray(marked("read this", 0xFFFF00))
        val body = partOf(docx, "word/document.xml")
        assertTrue(body.contains("""<w:highlight w:val="yellow"/>"""), body)
        assertEquals(0xFFFF00, firstRun(DocxReader.read(docx)).highlightRgb)
    }

    @Test
    fun `a marking Word cannot name is written as shading`() {
        val docx = DocxWriter.toByteArray(marked("read this too", 0xA0B0C0))
        val body = partOf(docx, "word/document.xml")
        assertTrue(body.contains("""w:fill="A0B0C0""""), body)
        assertTrue(!body.contains("w:highlight"), "an unnameable colour was given a name: " + body)
        assertEquals(0xA0B0C0, firstRun(DocxReader.read(docx)).highlightRgb)
    }

    @Test
    fun `text nobody marked carries no marking`() {
        val docx = DocxWriter.toByteArray(marked("plain", null))
        assertNull(firstRun(DocxReader.read(docx)).highlightRgb)
        assertTrue(!partOf(docx, "word/document.xml").contains("w:shd"))
    }

    @Test
    fun `every colour Word can name comes back as itself`() {
        val colors = listOf(0x000000, 0x0000FF, 0x00FFFF, 0x000080, 0x008080, 0x808080,
            0x008000, 0x800080, 0x800000, 0x808000, 0x00FF00, 0xC0C0C0, 0xFF00FF,
            0xFF0000, 0xFFFFFF, 0xFFFF00)
        for (rgb in colors) {
            val back = firstRun(DocxReader.read(DocxWriter.toByteArray(marked("x", rgb))))
            assertEquals(rgb, back.highlightRgb, "the colour came back wrong")
        }
    }

    @Test
    fun `the preview draws the marking behind the words`() {
        val html = HtmlWriter.write(marked("read this", 0xFFFF00), "marked")
        assertTrue(html.contains("background-color:#ffff00"), html)
    }

    private fun firstRun(document: DocumentModel) =
        document.blocks.filterIsInstance<Paragraph>().first().runs.first()

    private fun partOf(docx: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw AssertionError(name + " is not in the file")
    }

    @Test
    fun `text a document struck through comes back struck through`() {
        val struck = DocumentModel(
            blocks = listOf(
                Paragraph(runs = listOf(TextRun("40 dinars", strikethrough = true)))
            )
        )
        val docx = DocxWriter.toByteArray(struck)
        assertTrue(partOf(docx, "word/document.xml").contains("<w:strike/>"))
        assertTrue(firstRun(DocxReader.read(docx)).strikethrough)
    }

    @Test
    fun `a coloured cell is written coloured and read back the same`() {
        val table = DocumentModel(
            blocks = listOf(
                Table(
                    rows = listOf(
                        TableRow(listOf(TableCell(
                            blocks = listOf(Paragraph(runs = listOf(TextRun("Year")))),
                            shadingRgb = 0x4472C4,
                        ))),
                    ),
                    ruled = true,
                )
            )
        )
        val docx = DocxWriter.toByteArray(table)
        assertTrue(partOf(docx, "word/document.xml").contains("""w:fill="4472C4""""))
        val back = DocxReader.read(docx).blocks.filterIsInstance<Table>().single()
        assertEquals(0x4472C4, back.rows[0].cells[0].shadingRgb)
    }

    @Test
    fun `the preview draws a coloured cell coloured`() {
        val table = DocumentModel(
            blocks = listOf(
                Table(
                    rows = listOf(
                        TableRow(listOf(TableCell(
                            blocks = listOf(Paragraph(runs = listOf(TextRun("Year")))),
                            shadingRgb = 0x4472C4,
                        ))),
                    ),
                )
            )
        )
        assertTrue(HtmlWriter.write(table, "t").contains("background-color:#4472c4"))
    }
}
