package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The paragraph WordprocessingML wants after a table, and only where it
 * wants one.
 *
 * Written after every table, it puts a blank line under every table of a
 * converted document — a line the original does not have, which pushes
 * everything after it down the page. Our own reader drops an empty
 * paragraph and so could never see it; an independent reading of the
 * files we write is what found it.
 *
 * Two places genuinely need one: between two tables, which Word would
 * otherwise read as a single table, and at the end of a body, a cell, a
 * note or a running head, each of which must end with a paragraph.
 */
class TableSpacerTest {

    private fun line(text: String) = Paragraph(listOf(TextRun(text)))

    private fun table(text: String) =
        Table(listOf(TableRow(listOf(TableCell(listOf(line(text)))))))

    private fun documentXml(docx: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no document part")
    }

    private fun xmlOf(vararg blocks: app.morpho.engine.layout.Block) =
        documentXml(DocxWriter.toByteArray(DocumentModel(blocks.toList())))

    @Test
    fun `a table with words after it gets no blank line under it`() {
        val xml = xmlOf(line("Before."), table("a cell"), line("After."))
        assertFalse(xml.contains("</w:tbl><w:p/>"), "a blank line was written under the table: $xml")
        assertTrue(xml.contains("</w:tbl><w:p>"), xml)
    }

    @Test
    fun `two tables in a row are kept apart, or Word reads them as one`() {
        val xml = xmlOf(table("first"), table("second"))
        assertTrue(xml.contains("</w:tbl><w:p/><w:tbl>"), "the two tables would be read as one: $xml")
        // And the last of them still ends the body with a paragraph.
        assertTrue(xml.contains("</w:tbl><w:p/><w:sectPr>"), xml)
    }

    @Test
    fun `a document ending on a table still ends with a paragraph`() {
        val xml = xmlOf(line("Before."), table("a cell"))
        assertTrue(xml.contains("</w:tbl><w:p/><w:sectPr>"), xml)
        assertEquals(1, Regex(Regex.escape("</w:tbl><w:p/>")).findAll(xml).count(), xml)
    }

    @Test
    fun `a cell ending on a table of its own still ends with a paragraph`() {
        val inner = table("inner")
        val outer = Table(listOf(TableRow(listOf(TableCell(listOf(line("above"), inner))))))
        val xml = xmlOf(outer)
        // The inner table's own spacer is the paragraph the cell must end
        // with; the outer table's is the one the body must end with.
        assertEquals(2, Regex(Regex.escape("</w:tbl><w:p/>")).findAll(xml).count(), xml)
        assertTrue(xml.contains("</w:tbl><w:p/></w:tc>"), xml)
    }

    @Test
    fun `a cell whose table is followed by words gets no blank line either`() {
        val inner = table("inner")
        val outer = Table(listOf(TableRow(listOf(TableCell(listOf(inner, line("below")))))))
        val xml = xmlOf(outer)
        assertTrue(xml.contains("</w:tbl><w:p>"), xml)
        // Only the outer table's own, at the end of the body.
        assertEquals(1, Regex(Regex.escape("</w:tbl><w:p/>")).findAll(xml).count(), xml)
    }

    @Test
    fun `a running head that ends on a table ends with a paragraph`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(listOf(line("Body.")), header = listOf(table("head cell")))
        )
        val header = ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            var found = ""
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/header1.xml") found = zip.readBytes().toString(Charsets.UTF_8)
            }
            found
        }
        assertTrue(header.contains("</w:tbl><w:p/>"), header)
    }
}
