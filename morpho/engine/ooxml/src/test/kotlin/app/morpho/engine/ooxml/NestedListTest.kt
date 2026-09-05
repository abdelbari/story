package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ListLabels
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Word writes a list's depth as the level of its numbering, and what a
 * level counts with is the numbering's business, not the paragraph's. A
 * reader that takes only the outermost level hands back a report's
 * lettered sub-clauses as prose, and a writer that writes only level zero
 * flattens an outline into one list.
 */
class NestedListTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun item(text: String, marker: ListMarker, level: Int) = Paragraph(
        runs = listOf(TextRun(text)),
        style = ParagraphStyle(listMarker = marker, listLevel = level),
    )

    @Test
    fun `an outline goes to Word and comes back the same shape`() {
        val document = DocumentModel(
            blocks = listOf(
                item("Aims", ListMarker.NUMBERED, 0),
                item("To read a page", ListMarker.NUMBERED, 1),
                item("in Arabic", ListMarker.NUMBERED, 2),
                item("Method", ListMarker.NUMBERED, 0),
                item("by hand", ListMarker.BULLET, 1),
            )
        )
        val read = DocxReader.read(DocxWriter.toByteArray(document))
        val shape = read.blocks.filterIsInstance<Paragraph>().map {
            it.style.listMarker to it.style.listLevel
        }
        assertEquals(
            listOf(
                ListMarker.NUMBERED to 0,
                ListMarker.NUMBERED to 1,
                ListMarker.NUMBERED to 2,
                ListMarker.NUMBERED to 0,
                ListMarker.BULLET to 1,
            ),
            shape,
        )
    }

    @Test
    fun `the numbering a document writes counts each level its own way`() {
        val document = DocumentModel(blocks = listOf(item("deep", ListMarker.NUMBERED, 1)))
        val parts = entries(DocxWriter.toByteArray(document))
        val body = parts.getValue("word/document.xml")
        assertTrue(body.contains("""<w:ilvl w:val="1"/>"""), "the level was not written: " + body)
        val numbering = parts.getValue("word/numbering.xml")
        // Word's own ladder: 1. then a) then i.
        assertTrue(numbering.contains("""<w:numFmt w:val="lowerLetter"/>"""), numbering)
        assertTrue(numbering.contains("""<w:numFmt w:val="lowerRoman"/>"""), numbering)
    }

    @Test
    fun `a lettered clause is a numbered list, not prose`() {
        val doc = readDocx(
            numbering = """
                <w:abstractNum w:abstractNumId="3">
                  <w:lvl w:ilvl="0"><w:numFmt w:val="lowerLetter"/></w:lvl>
                </w:abstractNum>
                <w:num w:numId="5"><w:abstractNumId w:val="3"/></w:num>
            """,
            body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="5"/></w:numPr></w:pPr>
                <w:r><w:t>the party of the first part</w:t></w:r></w:p>""",
        )
        val style = doc.blocks.filterIsInstance<Paragraph>().first().style
        assertEquals(ListMarker.NUMBERED, style.listMarker)
    }

    @Test
    fun `each level is marked the way its own level says`() {
        val doc = readDocx(
            numbering = """
                <w:abstractNum w:abstractNumId="3">
                  <w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl>
                  <w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/></w:lvl>
                </w:abstractNum>
                <w:num w:numId="5"><w:abstractNumId w:val="3"/></w:num>
            """,
            body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="5"/></w:numPr></w:pPr>
                <w:r><w:t>a clause</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="5"/></w:numPr></w:pPr>
                <w:r><w:t>a note under it</w:t></w:r></w:p>""",
        )
        val styles = doc.blocks.filterIsInstance<Paragraph>().map { it.style }
        assertEquals(ListMarker.NUMBERED, styles[0].listMarker)
        assertEquals(0, styles[0].listLevel)
        assertEquals(ListMarker.BULLET, styles[1].listMarker)
        assertEquals(1, styles[1].listLevel)
    }

    @Test
    fun `a level the numbering never defined keeps the list it belongs to`() {
        val doc = readDocx(
            numbering = """
                <w:abstractNum w:abstractNumId="3">
                  <w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl>
                </w:abstractNum>
                <w:num w:numId="5"><w:abstractNumId w:val="3"/></w:num>
            """,
            body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="2"/><w:numId w:val="5"/></w:numPr></w:pPr>
                <w:r><w:t>three deep</w:t></w:r></w:p>""",
        )
        val style = doc.blocks.filterIsInstance<Paragraph>().first().style
        assertEquals(ListMarker.NUMBERED, style.listMarker)
        assertEquals(2, style.listLevel)
    }

    @Test
    fun `a paragraph Word took out of its list is no list item`() {
        // numId 0 is how Word says "not numbered", against a style that is.
        val doc = readDocx(
            numbering = """
                <w:abstractNum w:abstractNumId="3">
                  <w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl>
                </w:abstractNum>
                <w:num w:numId="5"><w:abstractNumId w:val="3"/></w:num>
            """,
            body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="0"/></w:numPr></w:pPr>
                <w:r><w:t>prose again</w:t></w:r></w:p>""",
        )
        val style = doc.blocks.filterIsInstance<Paragraph>().first().style
        assertNull(style.listMarker)
        assertEquals(0, style.listLevel)
    }

    @Test
    fun `a list that prints no marker at all is not a list`() {
        val doc = readDocx(
            numbering = """
                <w:abstractNum w:abstractNumId="3">
                  <w:lvl w:ilvl="0"><w:numFmt w:val="none"/></w:lvl>
                </w:abstractNum>
                <w:num w:numId="5"><w:abstractNumId w:val="3"/></w:num>
            """,
            body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="5"/></w:numPr></w:pPr>
                <w:r><w:t>indented, but nothing is drawn before it</w:t></w:r></w:p>""",
        )
        assertNull(doc.blocks.filterIsInstance<Paragraph>().first().style.listMarker)
    }

    // ------------------------------------------------------------------

    private fun readDocx(numbering: String, body: String): DocumentModel {
        val declaration = """<?xml version="1.0" encoding="UTF-8"?>"""
        return DocxReader.read(
            zipOf(
                "word/numbering.xml" to declaration +
                    """<w:numbering xmlns:w="$wNs">""" + numbering + "</w:numbering>",
                "word/document.xml" to declaration +
                    """<w:document xmlns:w="$wNs"><w:body>""" + body + "</w:body></w:document>",
            )
        )
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

    @Test
    fun `a list lettered in Arabic is lettered in Arabic`() {
        val doc = readDocx(
            numbering = """
                <w:abstractNum w:abstractNumId="3">
                  <w:lvl w:ilvl="0"><w:numFmt w:val="arabicAlpha"/></w:lvl>
                </w:abstractNum>
                <w:num w:numId="5"><w:abstractNumId w:val="3"/></w:num>
            """,
            body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="5"/></w:numPr></w:pPr>
                <w:r><w:t>البند الأول</w:t></w:r></w:p>""",
        )
        val style = doc.blocks.filterIsInstance<Paragraph>().first().style
        assertEquals(ListMarker.NUMBERED, style.listMarker)
        assertEquals("arabicAlpha", style.listFormat)
    }

    @Test
    fun `and is written back lettered rather than numbered`() {
        val document = DocumentModel(
            blocks = listOf(
                Paragraph(
                    runs = listOf(TextRun("البند الأول")),
                    style = ParagraphStyle(listMarker = ListMarker.NUMBERED, listFormat = "arabicAlpha"),
                ),
                Paragraph(
                    runs = listOf(TextRun("البند الثاني")),
                    style = ParagraphStyle(listMarker = ListMarker.NUMBERED, listFormat = "arabicAlpha"),
                ),
            )
        )
        val docx = DocxWriter.toByteArray(document)
        val numbering = entries(docx).getValue("word/numbering.xml")
        assertTrue(numbering.contains("""<w:numFmt w:val="arabicAlpha"/>"""), numbering)
        val back = DocxReader.read(docx).blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf("arabicAlpha", "arabicAlpha"), back.map { it.style.listFormat })
    }

    @Test
    fun `the page draws the letters a list counts in`() {
        val arabic = ParagraphStyle(listMarker = ListMarker.NUMBERED, listFormat = "arabicAlpha")
        assertEquals("\u0623- ", ListLabels.markerFor(arabic, 1))
        assertEquals("\u0628- ", ListLabels.markerFor(arabic, 2))
        assertEquals("\u062a- ", ListLabels.markerFor(arabic, 3))
        // The older abjad order counts differently at the third.
        val abjad = ParagraphStyle(listMarker = ListMarker.NUMBERED, listFormat = "arabicAbjad")
        assertEquals("\u062c- ", ListLabels.markerFor(abjad, 3))
    }

    @Test
    fun `a list that says nothing about counting counts as an outline does`() {
        val plain = ParagraphStyle(listMarker = ListMarker.NUMBERED)
        assertEquals("1. ", ListLabels.markerFor(plain, 1))
        assertEquals("b) ", ListLabels.markerFor(plain.copy(listLevel = 1), 2))
    }

    @Test
    fun `every way of counting a Word file names is drawn its own way`() {
        fun marker(format: String, count: Int) = ListLabels.number(0, count, format)
        assertEquals("3.", marker("decimal", 3))
        assertEquals("03.", marker("decimalZero", 3))
        assertEquals("c)", marker("lowerLetter", 3))
        assertEquals("C)", marker("upperLetter", 3))
        assertEquals("iii.", marker("lowerRoman", 3))
        assertEquals("III.", marker("upperRoman", 3))
        // A way of counting nobody here draws falls back to the level's own.
        assertEquals("3.", marker("cardinalText", 3))
    }
}
