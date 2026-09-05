package app.morpho.engine.ooxml

import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A converter is handed whatever a phone's file picker can reach: files
 * half-written by another program, files a script assembled wrongly, files
 * meant for a different reader entirely. None of it may hang the app or
 * take it down, and a document that is wrong in one place should still
 * come back with everything that was right about it.
 *
 * These cover the parts read since the reader learned styles, boxes,
 * controls, notes and equations.
 */
class MalformedDocumentTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val mcNs = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private val mNs = "http://schemas.openxmlformats.org/officeDocument/2006/math"

    @Test
    fun `a style based on itself does not hang the reader`() {
        val doc = assertDoesNotThrow {
            read(
                body = """<w:p><w:pPr><w:pStyle w:val="Loop"/></w:pPr><w:r><w:t>round</w:t></w:r></w:p>""",
                styles = """<w:style w:type="paragraph" w:styleId="Loop">
                    <w:basedOn w:val="Loop"/><w:rPr><w:b/></w:rPr></w:style>""",
            )
        }
        assertTrue(doc.blocks.filterIsInstance<Paragraph>().first().runs.first().bold)
    }

    @Test
    fun `a table whose style does not exist is still a table`() {
        val doc = read(
            body = """<w:tbl><w:tblPr><w:tblStyle w:val="NoSuchStyle"/></w:tblPr>
                <w:tr><w:tc><w:p><w:r><w:t>held</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""
        )
        assertEquals("held", doc.blocks.filterIsInstance<app.morpho.engine.layout.Table>()
            .single().rows.first().cells.first().blocks
            .filterIsInstance<Paragraph>().first().text)
    }

    @Test
    fun `a choice between drawings with neither drawing in it keeps the paragraph`() {
        val doc = read(
            body = """<w:p><w:r><w:t>before</w:t></w:r>
                <w:r><mc:AlternateContent></mc:AlternateContent></w:r>
                <w:r><w:t> after</w:t></w:r></w:p>"""
        )
        assertEquals("before after", doc.blocks.filterIsInstance<Paragraph>().first().text)
    }

    @Test
    fun `a content control holding nothing holds nothing`() {
        val doc = read(
            body = """<w:sdt><w:sdtPr/></w:sdt>
                <w:p><w:r><w:t>the document goes on</w:t></w:r></w:p>"""
        )
        assertEquals(
            listOf("the document goes on"),
            doc.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a mark pointing at a note that is not there is left as it is`() {
        val doc = read(
            body = """<w:p><w:r><w:t>said</w:t></w:r>
                <w:r><w:footnoteReference w:id="99"/></w:r></w:p>""",
            footnotes = """<w:footnote w:id="2"><w:p><w:r><w:t>a note nobody calls</w:t></w:r></w:p></w:footnote>""",
        )
        val runs = doc.blocks.filterIsInstance<Paragraph>().first().runs
        assertEquals("said", runs.joinToString("") { it.text })
        assertNull(runs.firstOrNull { it.note != null })
    }

    @Test
    fun `a broken notes part costs the notes and not the document`() {
        val doc = assertDoesNotThrow {
            read(
                body = """<w:p><w:r><w:t>the text survives</w:t></w:r>
                    <w:r><w:footnoteReference w:id="2"/></w:r></w:p>""",
                footnotes = "<w:footnote w:id=",
            )
        }
        assertEquals("the text survives", doc.blocks.filterIsInstance<Paragraph>().first().text)
    }

    @Test
    fun `numbering that points nowhere leaves a paragraph unmarked`() {
        val doc = read(
            body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="7"/></w:numPr></w:pPr>
                <w:r><w:t>not a list after all</w:t></w:r></w:p>""",
            numbering = """<w:num w:numId="7"><w:abstractNumId w:val="42"/></w:num>""",
        )
        val style = doc.blocks.filterIsInstance<Paragraph>().first().style
        assertNull(style.listMarker)
        assertEquals(0, style.listLevel)
    }

    @Test
    fun `a list level beyond counting is brought back within it`() {
        val doc = read(
            body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="9999"/><w:numId w:val="7"/></w:numPr></w:pPr>
                <w:r><w:t>deep</w:t></w:r></w:p>""",
            numbering = """<w:abstractNum w:abstractNumId="3"><w:lvl w:ilvl="0">
                    <w:numFmt w:val="decimal"/></w:lvl></w:abstractNum>
                <w:num w:numId="7"><w:abstractNumId w:val="3"/></w:num>""",
        )
        assertTrue(doc.blocks.filterIsInstance<Paragraph>().first().style.listLevel <= 8)
    }

    @Test
    fun `a colour that is not a colour is no colour`() {
        val doc = read(
            body = """<w:p><w:r><w:rPr><w:color w:val="zzzzzz"/>
                <w:shd w:val="clear" w:fill="nonsense"/></w:rPr><w:t>plain</w:t></w:r></w:p>"""
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertNull(run.colorRgb)
        assertNull(run.highlightRgb)
    }

    @Test
    fun `an equation of nothing at all is not a paragraph of nothing`() {
        val doc = read(
            body = """<w:p><m:oMath><m:f><m:num/><m:den/></m:f></m:oMath></w:p>
                <w:p><w:r><w:t>after the equation</w:t></w:r></w:p>"""
        )
        assertEquals(
            listOf("after the equation"),
            doc.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a page break in a paragraph of nothing else breaks nothing that follows nothing`() {
        val doc = read(body = """<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
        assertTrue(doc.blocks.isEmpty(), "a break alone made a document out of nothing")
    }

    private fun read(
        body: String,
        styles: String = "",
        numbering: String = "",
        footnotes: String = "",
    ): app.morpho.engine.layout.DocumentModel {
        val declaration = """<?xml version="1.0" encoding="UTF-8"?>"""
        val parts = mutableListOf(
            "word/document.xml" to declaration +
                """<w:document xmlns:w="$wNs" xmlns:mc="$mcNs" xmlns:m="$mNs"><w:body>""" +
                body + "</w:body></w:document>"
        )
        if (styles.isNotEmpty()) {
            parts += "word/styles.xml" to declaration +
                """<w:styles xmlns:w="$wNs">""" + styles + "</w:styles>"
        }
        if (numbering.isNotEmpty()) {
            parts += "word/numbering.xml" to declaration +
                """<w:numbering xmlns:w="$wNs">""" + numbering + "</w:numbering>"
        }
        if (footnotes.isNotEmpty()) {
            parts += "word/footnotes.xml" to declaration +
                """<w:footnotes xmlns:w="$wNs">""" + footnotes + "</w:footnotes>"
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return DocxReader.read(out.toByteArray())
    }
}
