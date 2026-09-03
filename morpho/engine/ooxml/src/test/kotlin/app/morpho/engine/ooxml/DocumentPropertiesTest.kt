package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.DocumentProperties
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What a document says about itself, apart from what it says.
 *
 * Word shows these in its Properties pane, a reader puts the title in the
 * window, and a search across a folder reads them before a word of the
 * text. The converter signed every file it wrote as its own work with no
 * title at all, so a paper converted from a PDF arrived called nothing,
 * by nobody.
 */
class DocumentPropertiesTest {

    private fun model(properties: DocumentProperties) = DocumentModel(
        listOf(Paragraph(listOf(TextRun("The body of it.")))),
        properties = properties,
    )

    private fun corePropsOf(docx: ByteArray): String =
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            var found = ByteArray(0)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "docProps/core.xml") found = zip.readBytes()
            }
            String(found, Charsets.UTF_8)
        }

    private fun packageOf(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `what a document says about itself survives the round trip`() {
        val said = DocumentProperties(
            title = "الاستمارة في البحث العلمي",
            author = "ربيحة نبار",
            subject = "أدوات البحث",
            keywords = "استمارة; بحث علمي",
        )
        val back = DocxReader.read(DocxWriter.toByteArray(model(said))).properties
        assertEquals(said, back)
    }

    @Test
    fun `the converter signs the file it wrote without taking the credit`() {
        val docx = DocxWriter.toByteArray(model(DocumentProperties(author = "ربيحة نبار")))
        val xml = corePropsOf(docx)
        assertTrue(xml.contains("<dc:creator>ربيحة نبار</dc:creator>"), "the author was not kept")
        assertTrue(xml.contains("<cp:lastModifiedBy>Morpho</cp:lastModifiedBy>"))
    }

    @Test
    fun `a document that says nothing about itself still says nothing`() {
        val docx = DocxWriter.toByteArray(model(DocumentProperties()))
        val xml = corePropsOf(docx)
        assertFalse(xml.contains("<dc:title>"), "an empty title was written where the source had none")
        assertFalse(xml.contains("<dc:subject>"))
        assertFalse(xml.contains("<cp:keywords>"))
        // A file has to say who made it, and this is the truth about that one.
        assertTrue(xml.contains("<dc:creator>Morpho</dc:creator>"))
        assertTrue(DocxReader.read(docx).properties.isEmpty, "the converter's own name came back as an author")
    }

    @Test
    fun `a package with no properties at all is read, not refused`() {
        val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val bare = packageOf(
            "word/document.xml" to
                """<w:document xmlns:w="$wNs"><w:body>""" +
                """<w:p><w:r><w:t>Nothing about itself.</w:t></w:r></w:p>""" +
                "</w:body></w:document>"
        )
        val model = DocxReader.read(bare)
        assertTrue(model.properties.isEmpty)
        assertEquals("Nothing about itself.", model.blocks.filterIsInstance<Paragraph>().single().text)
    }

    @Test
    fun `properties written in their own namespaces are still found`() {
        val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val docx = packageOf(
            "word/document.xml" to
                """<w:document xmlns:w="$wNs"><w:body>""" +
                """<w:p><w:r><w:t>Body.</w:t></w:r></w:p>""" +
                "</w:body></w:document>",
            "docProps/core.xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<cp:coreProperties """ +
                """xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
                """<dc:title>A Study of Forms</dc:title>""" +
                """<dc:creator>R. Nebbar</dc:creator>""" +
                """<cp:keywords>forms; research</cp:keywords>""" +
                """</cp:coreProperties>""",
        )
        val said = DocxReader.read(docx).properties
        assertEquals("A Study of Forms", said.title)
        assertEquals("R. Nebbar", said.author)
        assertEquals("forms; research", said.keywords)
        assertNull(said.subject)
    }

    @Test
    fun `blank fields are silence, not an empty title`() {
        val said = DocumentProperties.of("  ", "", null, "\t")
        assertTrue(said.isEmpty)
        assertNull(said.title)
    }
}
