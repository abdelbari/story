package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A thesis, a manual, a report: the first thing in it is a contents page,
 * and every line of that page is a link into the document itself. Word
 * writes such a link as a name, not as an address, and the place it leads
 * to carries the same name as a bookmark.
 *
 * Written as an address instead — which is what a converter that knows
 * only web links does — every line of the contents page becomes a broken
 * link to a website called "#_Toc1", and the contents page of a converted
 * thesis stops working.
 */
class BookmarkTest {

    @Test
    fun `a contents line links to the heading it names, and nowhere on the web`() {
        val doc = readDocx(
            """<w:p><w:hyperlink w:anchor="_Toc1"><w:r><w:t>1. Introduction</w:t></w:r></w:hyperlink></w:p>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
              <w:bookmarkStart w:id="1" w:name="_Toc1"/>
              <w:r><w:t>Introduction</w:t></w:r>
              <w:bookmarkEnd w:id="1"/>
            </w:p>"""
        )
        assertEquals("#_Toc1", doc.blocks.filterIsInstance<Paragraph>().first().runs.first().link)
        assertEquals(listOf("_Toc1"), doc.blocks.filterIsInstance<Paragraph>()[1].bookmarks)

        val parts = entries(DocxWriter.toByteArray(doc))
        val xml = parts.getValue("word/document.xml")
        assertTrue(xml.contains("""<w:hyperlink w:anchor="_Toc1">"""), xml)
        assertTrue(xml.contains("""w:name="_Toc1""""), xml)
        // The link leads inside the file, so nothing about it belongs in
        // the list of places outside it.
        assertFalse(parts.getValue("word/_rels/document.xml.rels").contains("_Toc1"))
    }

    @Test
    fun `a heading a converted document names can still be found by name`() {
        // Straight from the model, as a PDF-to-Word conversion would build
        // it: the contents line and the heading meet on the name alone.
        val model = DocumentModel(
            listOf(
                Paragraph(listOf(TextRun("Chapter one", link = "#chapter one"))),
                Paragraph(
                    listOf(TextRun("Chapter one")),
                    style = ParagraphStyle(kind = ParagraphKind.HEADING_1),
                    bookmarks = listOf("chapter one"),
                ),
            )
        )
        val xml = entries(DocxWriter.toByteArray(model)).getValue("word/document.xml")
        // A space is not a character Word allows in a bookmark name; both
        // ends of the link go through the same repair, so they still meet.
        assertTrue(xml.contains("""<w:hyperlink w:anchor="chapter_one">"""), xml)
        assertTrue(xml.contains("""<w:bookmarkStart w:id="0" w:name="chapter_one"/>"""), xml)
        assertTrue(xml.contains("""<w:bookmarkEnd w:id="0"/>"""), xml)
        // And it reads back as the link it was.
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        val paragraphs = read.blocks.filterIsInstance<Paragraph>()
        assertEquals("#chapter_one", paragraphs.first().runs.first().link)
        assertEquals(listOf("chapter_one"), paragraphs[1].bookmarks)
    }

    @Test
    fun `a bookmark put around several paragraphs names the first of them`() {
        // Word writes a bookmark over a selection of paragraphs outside
        // them all, which is a place in the body rather than in any one
        // paragraph: it names where the selection starts.
        val doc = readDocx(
            """<w:bookmarkStart w:id="2" w:name="theRules"/>
            <w:p><w:r><w:t>First rule.</w:t></w:r></w:p>
            <w:p><w:r><w:t>Second rule.</w:t></w:r></w:p>
            <w:bookmarkEnd w:id="2"/>"""
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf("theRules"), paragraphs[0].bookmarks)
        assertEquals(emptyList<String>(), paragraphs[1].bookmarks)
    }

    @Test
    fun `Word's own note of where the typist was is not a bookmark of the document`() {
        val doc = readDocx(
            """<w:p><w:bookmarkStart w:id="0" w:name="_GoBack"/><w:r><w:t>Text.</w:t></w:r></w:p>"""
        )
        assertEquals(emptyList<String>(), doc.blocks.filterIsInstance<Paragraph>().first().bookmarks)
    }

    @Test
    fun `two places cannot answer to the same number`() {
        val model = DocumentModel(
            (1..3).map {
                Paragraph(listOf(TextRun("Heading $it")), bookmarks = listOf("mark$it"))
            }
        )
        val xml = entries(DocxWriter.toByteArray(model)).getValue("word/document.xml")
        val ids = Regex("""<w:bookmarkStart w:id="(\d+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(listOf("0", "1", "2"), ids)
    }

    @Test
    fun `an address is still an address`() {
        // The new path must not swallow the ordinary case.
        val model = DocumentModel(
            listOf(Paragraph(listOf(TextRun("the site", link = "https://example.org"))))
        )
        val parts = entries(DocxWriter.toByteArray(model))
        assertTrue(parts.getValue("word/document.xml").contains("""<w:hyperlink r:id="rIdLnk1">"""))
        assertTrue(parts.getValue("word/_rels/document.xml.rels").contains("https://example.org"))
    }

    private fun readDocx(body: String): DocumentModel {
        val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val documentXml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<w:document xmlns:w="$wNs"><w:body>""" + body + "</w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return DocxReader.read(out.toByteArray())
    }

    private fun entries(docx: ByteArray): Map<String, String> {
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return parts
    }
}
