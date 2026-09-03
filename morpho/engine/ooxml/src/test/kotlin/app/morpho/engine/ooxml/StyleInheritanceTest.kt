package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Nearly every real Word document says how it looks in its styles rather
 * than on each run, and a style may be based on another, which is based on
 * the document's own defaults. A reader that only reads what a paragraph
 * writes on itself sees an unstyled document; these hold it to the chain.
 */
class StyleInheritanceTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    @Test
    fun `a run with nothing of its own takes the document defaults`() {
        val doc = readDocx(
            defaults = """<w:rPr><w:rFonts w:ascii="Cambria"/><w:sz w:val="26"/></w:rPr>""",
            body = """<w:p><w:r><w:t>plain words</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertEquals("Cambria", run.fontFamily)
        assertEquals(13f, run.fontSizePt)
    }

    @Test
    fun `a style based on another gathers the whole chain`() {
        val doc = readDocx(
            defaults = """<w:rPr><w:rFonts w:ascii="Cambria"/><w:sz w:val="20"/></w:rPr>""",
            styles = """
                <w:style w:type="paragraph" w:styleId="Body">
                  <w:rPr><w:rFonts w:ascii="Calibri"/><w:sz w:val="22"/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="Quote">
                  <w:basedOn w:val="Body"/>
                  <w:rPr><w:i/><w:color w:val="C00000"/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Quote"/></w:pPr>
                <w:r><w:t>as the poet says</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        // Face and size come from the style it is based on, the slope and
        // the colour from the style itself.
        assertEquals("Calibri", run.fontFamily)
        assertEquals(11f, run.fontSizePt)
        assertTrue(run.italic)
        assertEquals(0xC00000, run.colorRgb)
    }

    @Test
    fun `what a run writes on itself beats what its style says`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="Loud">
                  <w:rPr><w:b/><w:sz w:val="40"/><w:color w:val="0000FF"/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Loud"/></w:pPr>
                <w:r><w:rPr><w:b w:val="0"/><w:sz w:val="24"/></w:rPr>
                <w:t>quietly, though</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertFalse(run.bold)
        assertEquals(12f, run.fontSizePt)
        // What the run leaves alone it still inherits.
        assertEquals(0x0000FF, run.colorRgb)
    }

    @Test
    fun `a run style sits between the paragraph and the run`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="Body">
                  <w:rPr><w:rFonts w:ascii="Calibri"/><w:sz w:val="22"/></w:rPr>
                </w:style>
                <w:style w:type="character" w:styleId="Code">
                  <w:rPr><w:rFonts w:ascii="Consolas"/><w:sz w:val="20"/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Body"/></w:pPr>
                <w:r><w:t>call </w:t></w:r>
                <w:r><w:rPr><w:rStyle w:val="Code"/></w:rPr><w:t>read()</w:t></w:r></w:p>""",
        )
        val runs = doc.blocks.filterIsInstance<Paragraph>().first().runs
        assertEquals("Calibri", runs[0].fontFamily)
        assertEquals("Consolas", runs[1].fontFamily)
        assertEquals(10f, runs[1].fontSizePt)
    }

    @Test
    fun `a heading is known by its outline level when its name is not Heading`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="Titre1">
                  <w:pPr><w:outlineLvl w:val="0"/></w:pPr>
                  <w:rPr><w:b/><w:sz w:val="32"/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="Titre2">
                  <w:basedOn w:val="Titre1"/>
                  <w:pPr><w:outlineLvl w:val="1"/></w:pPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Titre1"/></w:pPr><w:r><w:t>Chapitre</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="Titre2"/></w:pPr><w:r><w:t>Section</w:t></w:r></w:p>""",
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(ParagraphKind.HEADING_1, paragraphs[0].style.kind)
        assertEquals(ParagraphKind.HEADING_2, paragraphs[1].style.kind)
        // The size of the style it is based on reaches the second heading too.
        assertEquals(16f, paragraphs[1].runs.first().fontSizePt)
    }

    @Test
    fun `an Arabic style names the face for its own script`() {
        val doc = readDocx(
            defaults = """<w:rPr><w:rFonts w:ascii="Calibri" w:cs="Traditional Arabic"/>
                <w:sz w:val="22"/><w:szCs w:val="28"/></w:rPr>""",
            styles = """
                <w:style w:type="paragraph" w:styleId="Arabe">
                  <w:pPr><w:bidi/></w:pPr>
                  <w:rPr><w:rtl/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Arabe"/></w:pPr>
                <w:r><w:t>مرحبا</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertEquals("Traditional Arabic", run.fontFamily)
        assertEquals(14f, run.fontSizePt)
    }

    @Test
    fun `styles based on each other in a circle do not hang the reader`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="A">
                  <w:basedOn w:val="B"/><w:rPr><w:b/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="B">
                  <w:basedOn w:val="A"/><w:rPr><w:sz w:val="30"/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="A"/></w:pPr><w:r><w:t>round we go</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertTrue(run.bold)
        assertEquals(15f, run.fontSizePt)
    }

    @Test
    fun `a heading is known by the name Word gives its style in any language`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="Ttulo1">
                  <w:name w:val="heading 1"/>
                  <w:rPr><w:b/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="Ttulo">
                  <w:name w:val="Title"/>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Ttulo"/></w:pPr><w:r><w:t>El libro</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr><w:r><w:t>Primero</w:t></w:r></w:p>""",
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(ParagraphKind.TITLE, paragraphs[0].style.kind)
        assertEquals(ParagraphKind.HEADING_1, paragraphs[1].style.kind)
    }

    @Test
    fun `a table ruled by its style is a ruled table`() {
        // Word's default Table Grid draws a full grid and writes not one
        // border on the table itself; a reader that looks only at the table
        // hands back a table with no lines at all.
        val doc = readDocx(
            styles = """
                <w:style w:type="table" w:styleId="TableGrid">
                  <w:name w:val="Table Grid"/>
                  <w:tblPr>
                    <w:tblBorders>
                      <w:top w:val="single" w:sz="4"/>
                      <w:left w:val="single" w:sz="4"/>
                      <w:bottom w:val="single" w:sz="4"/>
                      <w:right w:val="single" w:sz="4"/>
                      <w:insideH w:val="single" w:sz="4"/>
                      <w:insideV w:val="single" w:sz="4"/>
                    </w:tblBorders>
                  </w:tblPr>
                  <w:rPr><w:rFonts w:ascii="Calibri"/><w:sz w:val="20"/></w:rPr>
                </w:style>
            """,
            body = """<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr>
                <w:tr><w:tc><w:p><w:r><w:t>Year</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>Total</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )
        val table = doc.blocks.filterIsInstance<Table>().single()
        assertTrue(table.ruled, "the grid Word draws was not drawn")
        // The cells are set in the face the table style names, too.
        val cell = table.rows.first().cells.first().blocks.filterIsInstance<Paragraph>().first()
        assertEquals("Calibri", cell.runs.first().fontFamily)
        assertEquals(10f, cell.runs.first().fontSizePt)
    }

    @Test
    fun `a table that turns its style's rules off is not ruled`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="table" w:styleId="TableGrid">
                  <w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4"/></w:tblBorders></w:tblPr>
                </w:style>
            """,
            body = """<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/>
                <w:tblBorders><w:top w:val="none"/><w:bottom w:val="nil"/></w:tblBorders></w:tblPr>
                <w:tr><w:tc><w:p><w:r><w:t>bare</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )
        assertFalse(doc.blocks.filterIsInstance<Table>().single().ruled)
    }

    @Test
    fun `a table ruled a cell at a time is ruled`() {
        val doc = readDocx(
            body = """<w:tbl>
                <w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val="single" w:sz="4"/></w:tcBorders></w:tcPr>
                <w:p><w:r><w:t>drawn by hand</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )
        assertTrue(doc.blocks.filterIsInstance<Table>().single().ruled)
    }

    @Test
    fun `a paragraph style inside a cell beats what the table hands down`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="table" w:styleId="Plain">
                  <w:rPr><w:rFonts w:ascii="Calibri"/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="Figure">
                  <w:rPr><w:rFonts w:ascii="Consolas"/></w:rPr>
                </w:style>
            """,
            body = """<w:tbl><w:tblPr><w:tblStyle w:val="Plain"/></w:tblPr>
                <w:tr><w:tc><w:p><w:r><w:t>prose</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:pPr><w:pStyle w:val="Figure"/></w:pPr>
                <w:r><w:t>42</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )
        val row = doc.blocks.filterIsInstance<Table>().single().rows.first()
        val faces = row.cells.map {
            it.blocks.filterIsInstance<Paragraph>().first().runs.first().fontFamily
        }
        assertEquals(listOf("Calibri", "Consolas"), faces)
    }

    // ------------------------------------------------------------------

    private fun readDocx(body: String, defaults: String = "", styles: String = ""): DocumentModel {
        val stylesXml = DECLARATION +
            """<w:styles xmlns:w="$wNs">""" +
            (if (defaults.isBlank()) "" else "<w:docDefaults><w:rPrDefault>" + defaults + "</w:rPrDefault></w:docDefaults>") +
            styles +
            "</w:styles>"
        val documentXml = DECLARATION +
            """<w:document xmlns:w="$wNs"><w:body>""" + body + "</w:body></w:document>"
        return DocxReader.read(zipOf("word/styles.xml" to stylesXml, "word/document.xml" to documentXml))
    }

    private fun zipOf(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val DECLARATION = """<?xml version="1.0" encoding="UTF-8"?>"""
    }

    @Test
    fun `a cell keeps the colour it is filled with`() {
        val doc = readDocx(
            body = """<w:tbl>
                <w:tr><w:tc><w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="4472C4"/></w:tcPr>
                <w:p><w:r><w:t>Year</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>2019</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )
        val row = doc.blocks.filterIsInstance<Table>().single().rows.first()
        assertEquals(0x4472C4, row.cells[0].shadingRgb)
        assertEquals(null, row.cells[1].shadingRgb)
    }

    @Test
    fun `the head of a table takes the colour its style gives it`() {
        // Word writes the look of a table's head in the style and nothing
        // at all on the cells: a report's coloured header row is invisible
        // to a reader that looks only at the cells.
        val doc = readDocx(
            styles = """
                <w:style w:type="table" w:styleId="GridTable4">
                  <w:tblStylePr w:type="firstRow">
                    <w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="4472C4"/></w:tcPr>
                  </w:tblStylePr>
                </w:style>
            """,
            body = """<w:tbl><w:tblPr><w:tblStyle w:val="GridTable4"/>
                <w:tblLook w:firstRow="1"/></w:tblPr>
                <w:tr><w:tc><w:p><w:r><w:t>Year</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:p><w:r><w:t>2019</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )
        val rows = doc.blocks.filterIsInstance<Table>().single().rows
        assertEquals(0x4472C4, rows[0].cells[0].shadingRgb)
        assertEquals(null, rows[1].cells[0].shadingRgb, "the colour of the head ran down the table")
    }

    @Test
    fun `a table that says it has no head is given none`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="table" w:styleId="GridTable4">
                  <w:tblStylePr w:type="firstRow">
                    <w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="4472C4"/></w:tcPr>
                  </w:tblStylePr>
                </w:style>
            """,
            body = """<w:tbl><w:tblPr><w:tblStyle w:val="GridTable4"/>
                <w:tblLook w:firstRow="0"/></w:tblPr>
                <w:tr><w:tc><w:p><w:r><w:t>Year</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )
        assertEquals(null, doc.blocks.filterIsInstance<Table>().single().rows[0].cells[0].shadingRgb)
    }
}
