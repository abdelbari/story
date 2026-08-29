package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxReaderTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private val fixture = DocumentModel(
        blocks = listOf(
            Paragraph(
                runs = listOf(TextRun("Annual Summary")),
                style = ParagraphStyle(kind = ParagraphKind.HEADING_1),
            ),
            Paragraph(
                runs = listOf(
                    TextRun("Revenue is "),
                    TextRun("strong", bold = true),
                    TextRun(" but margins are "),
                    TextRun("thin", italic = true),
                    TextRun(" as noted", underline = true),
                    TextRun("."),
                ),
            ),
            Paragraph(
                runs = listOf(TextRun("مرحبا بالعالم", language = "ar", direction = TextDirection.RTL)),
                style = ParagraphStyle(direction = TextDirection.RTL),
            ),
            Paragraph(
                runs = listOf(TextRun("first bullet")),
                style = ParagraphStyle(listMarker = ListMarker.BULLET),
            ),
            Paragraph(
                runs = listOf(TextRun("first numbered")),
                style = ParagraphStyle(listMarker = ListMarker.NUMBERED),
            ),
            Table(
                rows = listOf(
                    TableRow(listOf(cell("City"), cell("Population"))),
                    TableRow(listOf(arabicCell("الرباط"), cell("580000"))),
                ),
            ),
        ),
    )

    private fun cell(text: String) = TableCell(listOf(Paragraph(listOf(TextRun(text)))))

    private fun arabicCell(text: String) = TableCell(
        listOf(
            Paragraph(
                runs = listOf(TextRun(text, language = "ar", direction = TextDirection.RTL)),
                style = ParagraphStyle(direction = TextDirection.RTL),
            )
        )
    )

    private fun roundTrip(): DocumentModel = DocxReader.read(DocxWriter.toByteArray(fixture))

    @Test
    fun `paragraph texts kinds and list markers round-trip in order`() {
        val doc = roundTrip()
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(
            listOf(
                "Annual Summary",
                "Revenue is strong but margins are thin as noted.",
                "مرحبا بالعالم",
                "first bullet",
                "first numbered",
            ),
            paragraphs.map { it.text },
        )
        assertEquals(ParagraphKind.HEADING_1, paragraphs[0].style.kind)
        assertEquals(ParagraphKind.BODY, paragraphs[1].style.kind)
        assertEquals(ParagraphKind.BODY, paragraphs[3].style.kind)
        assertNull(paragraphs[0].style.listMarker)
        assertNull(paragraphs[1].style.listMarker)
        assertEquals(ListMarker.BULLET, paragraphs[3].style.listMarker)
        assertEquals(ListMarker.NUMBERED, paragraphs[4].style.listMarker)
    }

    @Test
    fun `run flags round-trip on body runs`() {
        val body = (roundTrip().blocks[1] as Paragraph).runs
        assertEquals(6, body.size)
        assertFalse(body[0].bold)
        assertFalse(body[0].italic)
        assertFalse(body[0].underline)
        assertTrue(body[1].bold)
        assertFalse(body[1].italic)
        assertTrue(body[3].italic)
        assertFalse(body[3].bold)
        assertTrue(body[4].underline)
        assertNull(body[0].language)
        assertNull(body[0].direction)
    }

    @Test
    fun `arabic paragraph round-trips rtl direction and language`() {
        val doc = roundTrip()
        val arabic = doc.blocks[2] as Paragraph
        assertEquals(TextDirection.RTL, arabic.style.direction)
        assertEquals(TextDirection.RTL, arabic.runs.single().direction)
        assertEquals("ar", arabic.runs.single().language)

        val heading = doc.blocks[0] as Paragraph
        assertNull(heading.style.direction)
        assertNull(heading.runs.single().direction)
    }

    @Test
    fun `table round-trips shape and the arabic cell keeps rtl and language`() {
        val table = roundTrip().blocks.last() as Table
        assertEquals(2, table.rows.size)
        assertEquals(listOf(2, 2), table.rows.map { it.cells.size })
        assertEquals(
            listOf("City", "Population", "الرباط", "580000"),
            table.rows.flatMap { row -> row.cells.map { (it.blocks.single() as Paragraph).text } },
        )
        val arabicCell = table.rows[1].cells[0].blocks.single() as Paragraph
        assertEquals(TextDirection.RTL, arabicCell.style.direction)
        assertEquals(TextDirection.RTL, arabicCell.runs.single().direction)
        assertEquals("ar", arabicCell.runs.single().language)
    }

    @Test
    fun `spacer paragraph after the table is skipped and blocks read with full confidence`() {
        val doc = roundTrip()
        assertEquals(fixture.blocks.size, doc.blocks.size)
        assertTrue(doc.blocks.last() is Table)
        assertTrue(doc.blocks.all { it.confidence == 1f })
    }

    @Test
    fun `minimal package without numbering xml still parses`() {
        val documentXml = XML_DECL +
            """<w:document xmlns:w="$wNs"><w:body>""" +
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="9"/></w:numPr></w:pPr>""" +
            """<w:r><w:t>orphan list item</w:t></w:r></w:p>""" +
            """<w:bookmarkStart w:id="0" w:name="extra"/>""" +
            """<w:p><w:r><w:t>plain</w:t></w:r></w:p>""" +
            "<w:p/>" +
            "<w:sectPr/>" +
            "</w:body></w:document>"
        val doc = DocxReader.read(
            ByteArrayInputStream(docxOf("word/document.xml" to documentXml))
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf("orphan list item", "plain"), paragraphs.map { it.text })
        assertNull(paragraphs[0].style.listMarker, "unresolvable numId must not invent a marker")
    }

    @Test
    fun `list markers resolve through numbering xml not through hardcoded numIds`() {
        val numberingXml = XML_DECL +
            """<w:numbering xmlns:w="$wNs">""" +
            """<w:abstractNum w:abstractNumId="3"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum>""" +
            """<w:abstractNum w:abstractNumId="4"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum>""" +
            """<w:num w:numId="7"><w:abstractNumId w:val="3"/></w:num>""" +
            """<w:num w:numId="12"><w:abstractNumId w:val="4"/></w:num>""" +
            "</w:numbering>"
        val documentXml = XML_DECL +
            """<w:document xmlns:w="$wNs"><w:body>""" +
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="7"/></w:numPr></w:pPr>""" +
            """<w:r><w:t>a bullet</w:t></w:r></w:p>""" +
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="12"/></w:numPr></w:pPr>""" +
            """<w:r><w:t>a number</w:t></w:r></w:p>""" +
            "</w:body></w:document>"
        val doc = DocxReader.read(
            docxOf("word/document.xml" to documentXml, "word/numbering.xml" to numberingXml)
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(ListMarker.BULLET, paragraphs[0].style.listMarker)
        assertEquals(ListMarker.NUMBERED, paragraphs[1].style.listMarker)
    }

    // ------------------------------------------------------------------

    private fun docxOf(vararg parts: Pair<String, String>): ByteArray {
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
}
