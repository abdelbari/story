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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The break a paragraph holds inside itself — Word's shift+Enter.
 *
 * It was read only far enough to establish that it was not a page break,
 * and then dropped. An address block, a signature, a stanza of verse and
 * the two-line title an Arabic paper puts on its first page all came out
 * with their lines run together, and nothing in the file said a break had
 * ever been there. Nor could anything put one back: no writer wrote one,
 * so a document could not even be given a break by hand.
 */
class LineBreakRoundTripTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    @Test
    fun `an address keeps the lines it was set on`() {
        val document = read(
            body = """<w:p>
                <w:r><w:t>Faculty of Letters</w:t><w:br/><w:t>University of Algiers</w:t></w:r>
            </w:p>"""
        )
        assertEquals(
            "Faculty of Letters\nUniversity of Algiers",
            document.blocks.filterIsInstance<Paragraph>().single().text,
        )
    }

    @Test
    fun `a break Word wrote as a run of its own is a break`() {
        // This is how Word actually writes one: the break does not sit
        // inside the run whose words it follows, it is a run holding
        // nothing else. Read for its text elements alone, such a run has
        // none, and was thrown away before it could be looked at.
        val document = read(
            body = """<w:p>
                <w:r><w:t>First line</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>second line</w:t></w:r>
            </w:p>"""
        )
        assertEquals("First line\nsecond line", document.blocks.filterIsInstance<Paragraph>().single().text)
    }

    @Test
    fun `a page break is still not a line break`() {
        // The break that carries a type breaks the page or the column, and
        // is read as the paragraph property it amounts to. Read here as
        // well it would put a stray empty line into the first paragraph of
        // every page.
        val document = read(
            body = """<w:p><w:r><w:t>Last words.</w:t></w:r></w:p>
                <w:p><w:r><w:br w:type="page"/></w:r><w:r><w:t>A new page.</w:t></w:r></w:p>
                <w:p><w:r><w:br w:type="column"/></w:r><w:r><w:t>A new column.</w:t></w:r></w:p>"""
        )
        val paragraphs = document.blocks.filterIsInstance<Paragraph>()
        assertTrue(paragraphs.none { it.text.contains('\n') }, paragraphs.map { it.text }.toString())
        assertEquals("A new page.", paragraphs[1].text)
    }

    @Test
    fun `a break is written as a break and not as whitespace`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(listOf(Paragraph(listOf(TextRun("Faculty of Letters\nUniversity of Algiers")))))
        )
        val body = partOf(docx, "word/document.xml")
        assertTrue(body.contains("<w:br/>"), body)
        // The trap this replaces: a newline left inside w:t is whitespace
        // to Word, and the two lines are set as one.
        assertFalse(body.contains("Letters\n"), "the newline was left in the text")
    }

    @Test
    fun `a document's breaks survive being read and written and read again`() {
        val was = DocumentModel(
            listOf(
                Paragraph(listOf(TextRun("الاستمارة\nفي البحث العلمي"))),
                Paragraph(
                    listOf(TextRun("Two lines, ", bold = true), TextRun("both of them\nbold", bold = true)),
                ),
                Paragraph(
                    listOf(TextRun("A title\nof two lines")),
                    style = ParagraphStyle(kind = ParagraphKind.HEADING_1),
                ),
            )
        )
        val now = DocxReader.read(DocxWriter.toByteArray(was))
        assertEquals(
            was.blocks.filterIsInstance<Paragraph>().map { it.text },
            now.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a break keeps the look of the words it breaks`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(listOf(Paragraph(listOf(TextRun("one\ntwo", bold = true, italic = true)))))
        )
        val run = DocxReader.read(docx).blocks.filterIsInstance<Paragraph>().single().runs.single()
        assertEquals("one\ntwo", run.text)
        assertTrue(run.bold && run.italic, "the break split the run's look off its words")
    }

    @Test
    fun `a tab and a break in the same run are both themselves`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(listOf(Paragraph(listOf(TextRun("Name:\tRebih\nDate:\t1377")))))
        )
        val body = partOf(docx, "word/document.xml")
        assertTrue(body.contains("<w:tab/>"), body)
        assertTrue(body.contains("<w:br/>"), body)
        assertEquals(
            "Name:\tRebih\nDate:\t1377",
            DocxReader.read(docx).blocks.filterIsInstance<Paragraph>().single().text,
        )
    }

    private fun read(body: String): DocumentModel {
        val declaration = """<?xml version="1.0" encoding="UTF-8"?>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in listOf(
                "[Content_Types].xml" to declaration +
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="xml" ContentType="application/xml"/></Types>""",
                "_rels/.rels" to declaration +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Target="word/document.xml" """ +
                    """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"/>""" +
                    """</Relationships>""",
                "word/document.xml" to declaration +
                    """<w:document xmlns:w="$wNs"><w:body>""" + body + "</w:body></w:document>",
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return DocxReader.read(out.toByteArray())
    }

    private fun partOf(docx: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw AssertionError("$name is not in the file")
    }
}
