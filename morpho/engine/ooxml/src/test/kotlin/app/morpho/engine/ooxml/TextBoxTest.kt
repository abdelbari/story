package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A poster, a CV, a form, a certificate: Word lays them out in text boxes,
 * and a text box is written inside the run it is anchored to rather than
 * in the body of the document. A reader that walks only the body reads
 * none of it, and a document made of boxes converts to a blank page.
 */
class TextBoxTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val mcNs = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private val wpsNs = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    private val vNs = "urn:schemas-microsoft-com:vml"

    @Test
    fun `the text a box holds is text of the document`() {
        val doc = readDocx(
            """<w:p><w:r><w:t>Before the box.</w:t></w:r></w:p>
            <w:p><w:r><mc:AlternateContent>
              <mc:Choice Requires="wps"><w:drawing><wps:txbx><w:txbxContent>
                <w:p><w:r><w:t>Inside the box.</w:t></w:r></w:p>
              </w:txbxContent></wps:txbx></w:drawing></mc:Choice>
              <mc:Fallback><w:pict><v:shape><v:textbox><w:txbxContent>
                <w:p><w:r><w:t>Inside the box.</w:t></w:r></w:p>
              </w:txbxContent></v:textbox></v:shape></w:pict></mc:Fallback>
            </mc:AlternateContent></w:r></w:p>
            <w:p><w:r><w:t>After the box.</w:t></w:r></w:p>"""
        )
        // Said once, not twice: the box is written out both ways, and only
        // the one Word itself would draw is read.
        assertEquals(
            listOf("Before the box.", "Inside the box.", "After the box."),
            doc.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `an older Word's text box is read too`() {
        val doc = readDocx(
            """<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent>
                <w:p><w:r><w:t>Drawn the old way.</w:t></w:r></w:p>
              </w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"""
        )
        assertEquals(
            listOf("Drawn the old way."),
            doc.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a box of several paragraphs keeps them all, in order`() {
        val doc = readDocx(
            """<w:p><w:r><w:drawing><wps:txbx><w:txbxContent>
                <w:p><w:r><w:t>First line.</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second line.</w:t></w:r></w:p>
              </w:txbxContent></wps:txbx></w:drawing></w:r></w:p>"""
        )
        assertEquals(
            listOf("First line.", "Second line."),
            doc.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a box inside a box is read as deep as it goes`() {
        val doc = readDocx(
            """<w:p><w:r><w:drawing><wps:txbx><w:txbxContent>
                <w:p><w:r><w:t>Outer.</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><wps:txbx><w:txbxContent>
                  <w:p><w:r><w:t>Inner.</w:t></w:r></w:p>
                </w:txbxContent></wps:txbx></w:drawing></w:r></w:p>
              </w:txbxContent></wps:txbx></w:drawing></w:r></w:p>"""
        )
        assertEquals(
            listOf("Outer.", "Inner."),
            doc.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    private fun readDocx(body: String): DocumentModel {
        val documentXml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<w:document xmlns:w="$wNs" xmlns:mc="$mcNs" xmlns:wps="$wpsNs" xmlns:v="$vNs"><w:body>""" +
            body + "</w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return DocxReader.read(out.toByteArray())
    }

    @Test
    fun `a paragraph inside a content control is a paragraph of the document`() {
        // A template's cover page, a table of contents, a citation field:
        // what a control holds is wrapped, not replaced.
        val doc = readDocx(
            """<w:sdt><w:sdtPr><w:alias w:val="Title"/></w:sdtPr><w:sdtContent>
                <w:p><w:r><w:t>The title of the work</w:t></w:r></w:p>
              </w:sdtContent></w:sdt>
            <w:p><w:r><w:t>and its first line.</w:t></w:r></w:p>"""
        )
        assertEquals(
            listOf("The title of the work", "and its first line."),
            doc.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a table inside a content control is read as a table`() {
        val doc = readDocx(
            """<w:sdt><w:sdtContent><w:tbl>
                <w:tr><w:tc><w:p><w:r><w:t>held</w:t></w:r></w:p></w:tc></w:tr>
              </w:tbl></w:sdtContent></w:sdt>"""
        )
        assertEquals(1, doc.blocks.filterIsInstance<Table>().size)
    }
}
