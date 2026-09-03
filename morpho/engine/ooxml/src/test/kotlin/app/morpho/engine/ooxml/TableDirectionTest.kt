package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An Arabic table is laid out from the right: its first column is the
 * rightmost one. A converter that lays the same cells out from the left
 * hands back a table read backwards — the years under the names and the
 * names under the years — which is a different table, not a table that
 * merely looks different.
 */
class TableDirectionTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun cell(text: String) =
        TableCell(listOf(Paragraph(runs = listOf(TextRun(text)))))

    private val arabicTable = DocumentModel(
        blocks = listOf(
            Table(
                rows = listOf(TableRow(listOf(cell("السنة"), cell("العدد")))),
                direction = TextDirection.RTL,
            )
        ),
        defaultDirection = TextDirection.RTL,
    )

    @Test
    fun `a table laid out from the right says so in Word`() {
        val body = partOf(DocxWriter.toByteArray(arabicTable), "word/document.xml")
        assertTrue(body.contains("<w:bidiVisual/>"), body.take(400))
    }

    @Test
    fun `and comes back laid out from the right`() {
        val back = DocxReader.read(DocxWriter.toByteArray(arabicTable))
        val table = back.blocks.filterIsInstance<Table>().single()
        assertEquals(TextDirection.RTL, table.direction)
        assertEquals(
            listOf("السنة", "العدد"),
            table.rows.first().cells.map { it.blocks.filterIsInstance<Paragraph>().first().text },
        )
    }

    @Test
    fun `a table of a left-to-right document is laid out from the left`() {
        val plain = DocumentModel(
            blocks = listOf(Table(rows = listOf(TableRow(listOf(cell("Year"), cell("Count"))))))
        )
        val body = partOf(DocxWriter.toByteArray(plain), "word/document.xml")
        assertTrue(!body.contains("bidiVisual"), body.take(400))
        // Word lays a table out from the left unless the table says
        // otherwise, so that is what comes back.
        assertEquals(
            TextDirection.LTR,
            DocxReader.read(DocxWriter.toByteArray(plain)).blocks
                .filterIsInstance<Table>().single().direction,
        )
    }

    @Test
    fun `the preview lays an Arabic table out from the right`() {
        val html = HtmlWriter.write(arabicTable, "table")
        assertTrue(html.contains("""<table dir="rtl""""), html.take(600))
    }

    @Test
    fun `a table Word marked as laid out from the right is read that way`() {
        val docx = docxOf(
            """<w:tbl><w:tblPr><w:bidiVisual/></w:tblPr>
                <w:tr><w:tc><w:p><w:r><w:t>الاسم</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>الرقم</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""
        )
        val table = DocxReader.read(docx).blocks.filterIsInstance<Table>().single()
        assertEquals(TextDirection.RTL, table.direction)
    }

    private fun docxOf(body: String): ByteArray {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<w:document xmlns:w="$wNs"><w:body>""" + body + "</w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun partOf(docx: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw AssertionError(name + " is not in the file")
    }
}
