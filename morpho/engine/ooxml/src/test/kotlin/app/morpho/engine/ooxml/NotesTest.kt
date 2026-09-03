package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Word draws a note's number itself, so the run that refers to a note
 * writes nothing at all: a reader that keeps only what is written loses
 * the mark, and the note it called with it. A thesis keeps its notes at
 * the end rather than the foot, and those were lost outright — the
 * endnotes part was never even opened.
 */
class NotesTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    @Test
    fun `a footnote Word numbered itself keeps its note and its number`() {
        val doc = read(
            body = """<w:p><w:r><w:t>As has been shown</w:t></w:r>
                <w:r><w:footnoteReference w:id="2"/></w:r>
                <w:r><w:t> in the field.</w:t></w:r></w:p>""",
            footnotes = """<w:footnote w:id="2"><w:p><w:r><w:t>Haddad, 2019, p. 4.</w:t></w:r></w:p></w:footnote>""",
        )
        val runs = doc.blocks.filterIsInstance<Paragraph>().first().runs
        val mark = runs.firstOrNull { it.note != null }
        assertNotNull(mark, "the mark and its note were dropped")
        assertEquals("1", mark!!.text, "the number Word would have drawn was not drawn")
        assertTrue(mark.superscript, "the mark was not raised")
        assertEquals(
            "Haddad, 2019, p. 4.",
            mark.note!!.filterIsInstance<Paragraph>().first().text,
        )
    }

    @Test
    fun `notes are numbered in the order they are called`() {
        val doc = read(
            body = """<w:p><w:r><w:t>First</w:t></w:r><w:r><w:footnoteReference w:id="2"/></w:r>
                <w:r><w:t> and second</w:t></w:r><w:r><w:footnoteReference w:id="3"/></w:r></w:p>""",
            footnotes = """<w:footnote w:id="2"><w:p><w:r><w:t>One.</w:t></w:r></w:p></w:footnote>
                <w:footnote w:id="3"><w:p><w:r><w:t>Two.</w:t></w:r></w:p></w:footnote>""",
        )
        val marks = doc.blocks.filterIsInstance<Paragraph>().first().runs.filter { it.note != null }
        assertEquals(listOf("1", "2"), marks.map { it.text })
    }

    @Test
    fun `the separator above the notes is not a note`() {
        val doc = read(
            body = """<w:p><w:r><w:t>Text</w:t></w:r><w:r><w:footnoteReference w:id="1"/></w:r></w:p>""",
            footnotes = """<w:footnote w:id="-1" w:type="separator"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>
                <w:footnote w:id="1"><w:p><w:r><w:t>A real note.</w:t></w:r></w:p></w:footnote>""",
        )
        val mark = doc.blocks.filterIsInstance<Paragraph>().first().runs.first { it.note != null }
        assertEquals("A real note.", mark.note!!.filterIsInstance<Paragraph>().first().text)
    }

    @Test
    fun `a thesis's endnotes are read as the notes they are`() {
        val doc = read(
            body = """<w:p><w:r><w:t>As set out above</w:t></w:r>
                <w:r><w:endnoteReference w:id="2"/></w:r></w:p>""",
            endnotes = """<w:endnote w:id="2"><w:p><w:r><w:t>See chapter three.</w:t></w:r></w:p></w:endnote>""",
        )
        val mark = doc.blocks.filterIsInstance<Paragraph>().first().runs.firstOrNull { it.note != null }
        assertNotNull(mark, "the endnote was lost")
        assertEquals("i", mark!!.text, "an endnote is numbered the way Word numbers one")
        assertEquals(
            "See chapter three.",
            mark.note!!.filterIsInstance<Paragraph>().first().text,
        )
    }

    @Test
    fun `a footnote and an endnote of the same number are different notes`() {
        // Word counts the two kinds apart, so note 2 is two notes.
        val doc = read(
            body = """<w:p><w:r><w:t>Both</w:t></w:r>
                <w:r><w:footnoteReference w:id="2"/></w:r>
                <w:r><w:endnoteReference w:id="2"/></w:r></w:p>""",
            footnotes = """<w:footnote w:id="2"><w:p><w:r><w:t>At the foot.</w:t></w:r></w:p></w:footnote>""",
            endnotes = """<w:endnote w:id="2"><w:p><w:r><w:t>At the end.</w:t></w:r></w:p></w:endnote>""",
        )
        val marks = doc.blocks.filterIsInstance<Paragraph>().first().runs.filter { it.note != null }
        assertEquals(
            listOf("At the foot.", "At the end."),
            marks.map { it.note!!.filterIsInstance<Paragraph>().first().text },
        )
        assertEquals(listOf("1", "i"), marks.map { it.text })
    }

    private fun read(body: String, footnotes: String = "", endnotes: String = ""): DocumentModel {
        val declaration = """<?xml version="1.0" encoding="UTF-8"?>"""
        val parts = mutableListOf(
            "word/document.xml" to declaration +
                """<w:document xmlns:w="$wNs"><w:body>""" + body + "</w:body></w:document>"
        )
        if (footnotes.isNotEmpty()) {
            parts += "word/footnotes.xml" to declaration +
                """<w:footnotes xmlns:w="$wNs">""" + footnotes + "</w:footnotes>"
        }
        if (endnotes.isNotEmpty()) {
            parts += "word/endnotes.xml" to declaration +
                """<w:endnotes xmlns:w="$wNs">""" + endnotes + "</w:endnotes>"
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

    @Test
    fun `a page break somebody typed breaks the page`() {
        // Ctrl+Enter, which is how most page breaks in most documents are
        // made: a paragraph of nothing but the break.
        val doc = read(
            body = """<w:p><w:r><w:t>End of the first page.</w:t></w:r></w:p>
                <w:p><w:r><w:br w:type="page"/></w:r></w:p>
                <w:p><w:r><w:t>Start of the second.</w:t></w:r></w:p>"""
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf("End of the first page.", "Start of the second."), paragraphs.map { it.text })
        assertTrue(!paragraphs[0].style.pageBreakBefore)
        assertTrue(paragraphs[1].style.pageBreakBefore, "the break somebody typed was lost")
    }

    @Test
    fun `a break after a paragraph's words leaves it where it was`() {
        val doc = read(
            body = """<w:p><w:r><w:t>Last words.</w:t></w:r><w:r><w:br w:type="page"/></w:r></w:p>
                <w:p><w:r><w:t>Next page.</w:t></w:r></w:p>"""
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertTrue(!paragraphs[0].style.pageBreakBefore)
        assertTrue(paragraphs[1].style.pageBreakBefore)
    }

    @Test
    fun `a line break is not a page break`() {
        val doc = read(
            body = """<w:p><w:r><w:t>One line</w:t></w:r><w:r><w:br/></w:r></w:p>
                <w:p><w:r><w:t>and the next.</w:t></w:r></w:p>"""
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertTrue(paragraphs.none { it.style.pageBreakBefore }, "a line break broke the page")
    }

    @Test
    fun `a break written inside a tracked insertion still breaks the page`() {
        val doc = read(
            body = """<w:p><w:r><w:t>Before.</w:t></w:r></w:p>
                <w:p><w:ins w:id="1"><w:r><w:br w:type="page"/></w:r></w:ins></w:p>
                <w:p><w:r><w:t>After.</w:t></w:r></w:p>"""
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf("Before.", "After."), paragraphs.map { it.text })
        assertTrue(paragraphs[1].style.pageBreakBefore, "a break inside an insertion was missed")
    }

    @Test
    fun `a break before a content control breaks before what it holds`() {
        val doc = read(
            body = """<w:p><w:r><w:br w:type="page"/></w:r></w:p>
                <w:sdt><w:sdtContent>
                  <w:p><w:r><w:t>The held paragraph.</w:t></w:r></w:p>
                </w:sdtContent></w:sdt>"""
        )
        val paragraph = doc.blocks.filterIsInstance<Paragraph>().single()
        assertEquals("The held paragraph.", paragraph.text)
        assertTrue(paragraph.style.pageBreakBefore, "the break stopped at the control")
    }
}
