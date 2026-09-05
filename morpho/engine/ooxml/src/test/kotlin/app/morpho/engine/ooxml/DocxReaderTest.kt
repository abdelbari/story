package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.PlainTextImporter
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
    fun `uax9-refined mixed-direction runs survive the round trip`() {
        // The importer's UAX #9 pass splits this RTL paragraph at every
        // direction boundary; the writer marks each piece (w:rtl or its
        // absence) and the reader must hand back the same effective runs.
        val imported = PlainTextImporter.import("النص **bold** مع English مدمج")
        val para = DocxReader.read(DocxWriter.toByteArray(imported)).blocks[0] as Paragraph
        assertEquals(TextDirection.RTL, para.style.direction)
        assertEquals(
            listOf(
                "النص " to TextDirection.RTL,
                "bold" to TextDirection.LTR,
                " مع " to TextDirection.RTL,
                "English" to TextDirection.LTR,
                " مدمج" to TextDirection.RTL,
            ),
            para.runs.map { it.text to (it.direction ?: TextDirection.RTL) },
        )
        assertTrue(para.runs[1].bold)
        assertFalse(para.runs[3].bold)
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
    fun `a document that runs right to left says so, and comes back saying it`() {
        // Word keeps a document's direction once, in its section, and
        // every paragraph runs that way unless it says otherwise. Written
        // without it, an Arabic document comes back from Word laid out
        // from the left: its tables read backwards and its running head
        // sits at the wrong margin.
        val model = DocumentModel(
            listOf(Paragraph(listOf(TextRun("الاستمارة في البحث العلمي")))),
            defaultDirection = TextDirection.RTL,
        )
        val docx = DocxWriter.toByteArray(model)
        val xml = String(
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(docx)).use { zip ->
                var found = ByteArray(0)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") found = zip.readBytes()
                }
                found
            },
            Charsets.UTF_8,
        )
        assertTrue(xml.contains("<w:bidi/></w:sectPr>"), "the section does not say which way it runs")
        assertEquals(TextDirection.RTL, DocxReader.read(docx).defaultDirection)
    }

    @Test
    fun `a paragraph running the other way from its document says so outright`() {
        // The one English paragraph of an Arabic document — an address, an
        // abstract, a line of code. Left to the section it comes back
        // turned round, and a paragraph with nothing at all to say about
        // direction is exactly what it looked like.
        val model = PlainTextImporter.import(
            "الاستمارة في البحث العلمي\n\nUne ligne en français ici.\n\nسطر عربي آخر بعده."
        )
        assertEquals(TextDirection.RTL, model.defaultDirection)
        val back = DocxReader.read(DocxWriter.toByteArray(model))
        assertEquals(TextDirection.RTL, back.defaultDirection)
        assertEquals(
            model.blocks.filterIsInstance<Paragraph>().map { it.style.direction },
            back.blocks.filterIsInstance<Paragraph>().map { it.style.direction },
            "the directions the document gave its paragraphs did not come back",
        )
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

    @Test
    fun `a document whose section says nothing about direction takes it from its own words`() {
        // Word only writes the section mark when somebody set the
        // document right-to-left outright; typed in an Arabic Word the
        // marks land on the paragraphs and the section stays bare — which
        // is what a real Arabic paper looks like. Read from the section
        // alone such a file is left-to-right, and everything downstream
        // lays it out from the wrong margin.
        val arabic = DocxReader.read(
            docxOf(
                "word/document.xml" to
                    """<w:document xmlns:w="$wNs"><w:body>""" +
                    """<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:t>الاستمارة في البحث العلمي</w:t></w:r></w:p>""" +
                    """<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:t>ربيحة نبار، جامعة الجزائر</w:t></w:r></w:p>""" +
                    """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr>""" +
                    "</w:body></w:document>"
            )
        )
        assertEquals(TextDirection.RTL, arabic.defaultDirection)
        // And the same silence over English words still means what it
        // always meant: a bare section is not evidence of anything.
        val english = DocxReader.read(
            docxOf(
                "word/document.xml" to
                    """<w:document xmlns:w="$wNs"><w:body>""" +
                    """<w:p><w:r><w:t>The form in scientific research</w:t></w:r></w:p>""" +
                    """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr>""" +
                    "</w:body></w:document>"
            )
        )
        assertEquals(TextDirection.LTR, english.defaultDirection)
    }

    @Test
    fun `a document whose section says it runs left to right is believed over its words`() {
        // An Arabic document somebody deliberately set left-to-right —
        // a glossary, a language primer. The section is what the author
        // said; the words are only what the reader falls back on.
        val model = DocxReader.read(
            docxOf(
                "word/document.xml" to
                    """<w:document xmlns:w="$wNs"><w:body>""" +
                    """<w:p><w:r><w:t>الاستمارة في البحث العلمي</w:t></w:r></w:p>""" +
                    """<w:sectPr><w:bidi w:val="0"/><w:pgSz w:w="11906" w:h="16838"/></w:sectPr>""" +
                    "</w:body></w:document>"
            )
        )
        assertEquals(TextDirection.LTR, model.defaultDirection)
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
