package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The look a reader measured — faces, sizes, raised marks, indents,
 * spacing, tab stops, the page — written into a .docx and read back.
 */
class LookRoundTripTest {

    private val document = DocumentModel(
        blocks = listOf(
            Paragraph(
                runs = listOf(
                    TextRun("ربيحة نبار ", bold = true, fontFamily = "Simplified Arabic", fontSizePt = 12f),
                    TextRun("1", bold = true, fontFamily = "Simplified Arabic", fontSizePt = 8f, superscript = true),
                    TextRun("2", fontFamily = "Simplified Arabic", fontSizePt = 8f, subscript = true),
                ),
                style = ParagraphStyle(
                    direction = TextDirection.RTL,
                    firstLineIndentPt = 36f,
                    spaceBeforePt = 0f,
                    spaceAfterPt = 6f,
                    linePitchPt = 21.5f,
                ),
            ),
            Paragraph(
                runs = listOf(TextRun("تاريخ:2022-04-21\tتاريخ:2022-05-19")),
                style = ParagraphStyle(
                    direction = TextDirection.RTL,
                    startIndentPt = 60f,
                    hangingIndentPt = 30f,
                    tabStopsPt = listOf(182.5f),
                    ruleBelow = true,
                ),
            ),
        ),
        defaultDirection = TextDirection.RTL,
        pageSetup = PageSetup(595.3f, 841.9f, 61.1f, 91.7f, 56.6f, 84.8f),
    )

    @Test
    fun `the writer puts each property where the schema wants it`() {
        val xml = documentXml(DocxWriter.toByteArray(document))
        assertTrue(xml.contains("""<w:rFonts w:ascii="Simplified Arabic" w:hAnsi="Simplified Arabic" w:cs="Simplified Arabic"/><w:b/><w:bCs/><w:sz w:val="24"/><w:szCs w:val="24"/><w:rtl/>"""), xml)
        assertTrue(xml.contains("""<w:sz w:val="16"/><w:szCs w:val="16"/><w:vertAlign w:val="superscript"/><w:rtl/>"""), xml)
        assertTrue(xml.contains("""<w:vertAlign w:val="subscript"/>"""), xml)
        assertTrue(xml.contains("""<w:bidi/><w:spacing w:before="0" w:after="120" w:line="430" w:lineRule="exact"/><w:ind w:firstLine="720"/>"""), xml)
        assertTrue(xml.contains("""<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="auto"/></w:pBdr><w:tabs><w:tab w:val="left" w:pos="3650"/></w:tabs><w:bidi/><w:ind w:left="1200" w:hanging="600"/>"""), xml)
        assertTrue(xml.contains("""</w:t><w:tab/><w:t xml:space="preserve">"""), xml)
        assertTrue(xml.contains("""<w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1222" w:right="1696" w:bottom="1834" w:left="1132" """), xml)
    }

    @Test
    fun `the reader gets it all back`() {
        val back = DocxReader.read(DocxWriter.toByteArray(document))
        val (first, second) = back.blocks.filterIsInstance<Paragraph>()

        val (name, mark, lowered) = first.runs
        assertEquals("Simplified Arabic", name.fontFamily)
        assertEquals(12f, name.fontSizePt)
        assertTrue(name.bold)
        assertTrue(mark.superscript && !mark.subscript)
        assertEquals(8f, mark.fontSizePt)
        assertTrue(lowered.subscript && !lowered.superscript)
        assertEquals(36f, first.style.firstLineIndentPt)
        assertEquals(0f, first.style.spaceBeforePt)
        assertEquals(6f, first.style.spaceAfterPt)
        assertEquals(21.5f, first.style.linePitchPt)

        assertEquals("تاريخ:2022-04-21\tتاريخ:2022-05-19", second.text)
        assertEquals(60f, second.style.startIndentPt)
        assertEquals(30f, second.style.hangingIndentPt)
        assertEquals(listOf(182.5f), second.style.tabStopsPt)
        assertTrue(second.style.ruleBelow && !second.style.ruleAbove)

        val page = back.pageSetup
        assertNotNull(page)
        assertEquals(595.3f, page!!.widthPt)
        assertEquals(841.9f, page.heightPt)
        assertEquals(61.1f, page.marginTopPt)
        assertEquals(91.7f, page.marginBottomPt)
        assertEquals(56.6f, page.marginLeftPt)
        assertEquals(84.8f, page.marginRightPt)
    }

    @Test
    fun `every property is emitted in the order the schema fixes`() {
        // WordprocessingML declares w:pPr and w:rPr as sequences, not
        // choices: a file whose children are out of order is rejected by
        // Word with nothing more helpful than "unreadable content". This
        // pins the order of everything the writer emits, so an added
        // property cannot quietly break every file the app writes.
        val everything = DocumentModel(
            blocks = listOf(
                Paragraph(
                    runs = listOf(
                        TextRun(
                            "text",
                            bold = true,
                            italic = true,
                            underline = true,
                            language = "ar",
                            direction = TextDirection.RTL,
                            fontFamily = "Arial",
                            fontSizePt = 11f,
                            superscript = true,
                        )
                    ),
                    style = ParagraphStyle(
                        kind = ParagraphKind.HEADING_2,
                        direction = TextDirection.RTL,
                        alignment = Alignment.JUSTIFY,
                        firstLineIndentPt = 12f,
                        startIndentPt = 24f,
                        spaceBeforePt = 6f,
                        spaceAfterPt = 6f,
                        linePitchPt = 18f,
                        tabStopsPt = listOf(100f),
                        ruleAbove = true,
                        ruleBelow = true,
                    ),
                )
            ),
            defaultDirection = TextDirection.RTL,
        )
        val xml = documentXml(DocxWriter.toByteArray(everything))
        assertInOrder(xml, listOf("<w:pPr>", "<w:pStyle", "<w:pBdr>", "<w:tabs>", "<w:bidi/>", "<w:spacing", "<w:ind", "<w:jc", "</w:pPr>"))
        assertInOrder(xml, listOf("<w:rPr>", "<w:rFonts", "<w:b/>", "<w:i/>", "<w:sz ", "<w:u ", "<w:vertAlign", "<w:rtl/>", "<w:lang", "</w:rPr>"))
    }

    private fun assertInOrder(xml: String, parts: List<String>) {
        var cursor = 0
        for (part in parts) {
            val at = xml.indexOf(part, cursor)
            assertTrue(at >= 0, "$part missing after index $cursor in $xml")
            cursor = at + part.length
        }
    }

    @Test
    fun `an exact line is never shorter than its largest type needs`() {
        // A measured pitch of nine points under twelve-point type would
        // clip every ascender; Word is asked for 1.15 times the type instead.
        val xml = documentXml(
            DocxWriter.toByteArray(
                DocumentModel(
                    listOf(
                        Paragraph(
                            listOf(TextRun("tall", fontSizePt = 12f)),
                            ParagraphStyle(linePitchPt = 9f),
                        )
                    )
                )
            )
        )
        assertTrue(xml.contains("""<w:spacing w:line="276" w:lineRule="exact"/>"""), xml)
    }

    @Test
    fun `a run's colour is written where the schema puts it, and read back`() {
        val document = DocumentModel(
            listOf(
                Paragraph(
                    listOf(
                        TextRun("Heading", bold = true, fontSizePt = 16f, colorRgb = 0xC00000),
                        TextRun(" and plain"),
                    )
                )
            )
        )
        val docx = DocxWriter.toByteArray(document)
        val xml = documentXml(docx)
        // Colour sits after the weight and before the size: Word rejects a
        // file whose run properties come in any other order.
        assertTrue(xml.contains("""<w:b/><w:bCs/><w:color w:val="C00000"/><w:sz w:val="32"/>"""), xml)
        val read = DocxReader.read(docx).blocks.filterIsInstance<Paragraph>().single()
        assertEquals(0xC00000, read.runs[0].colorRgb)
        assertNull(read.runs[1].colorRgb, "a run with no colour of its own keeps none")
    }

    @Test
    fun `a document that names no colour writes none`() {
        val docx = DocxWriter.toByteArray(DocumentModel(listOf(Paragraph(listOf(TextRun("plain"))))))
        assertTrue(!documentXml(docx).contains("w:color"), "no colour is written for a plain run")
    }

    @Test
    fun `a link is a relationship outside the package, and comes back the same`() {
        val document = DocumentModel(
            listOf(
                Paragraph(
                    listOf(
                        TextRun("write to "),
                        TextRun("nebbarrebih@gmail.com", link = "mailto:nebbarrebih@gmail.com"),
                        TextRun(" today"),
                    )
                )
            )
        )
        val docx = DocxWriter.toByteArray(document)
        val parts = entries(docx)
        val body = parts.getValue("word/document.xml")
        assertTrue(body.contains("""<w:hyperlink r:id="rIdLnk1">"""), body)
        val rels = parts.getValue("word/_rels/document.xml.rels")
        assertTrue(
            rels.contains("""Id="rIdLnk1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="mailto:nebbarrebih@gmail.com" TargetMode="External""""),
            rels,
        )
        val read = DocxReader.read(docx).blocks.filterIsInstance<Paragraph>().single()
        val linked = read.runs.single { it.link != null }
        assertEquals("nebbarrebih@gmail.com", linked.text)
        assertEquals("mailto:nebbarrebih@gmail.com", linked.link)
        assertNull(read.runs.first().link)
    }

    @Test
    fun `runs that point at one place are one link`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(
                            TextRun("neb", bold = true, link = "mailto:neb@x.dz"),
                            TextRun("@x.dz", link = "mailto:neb@x.dz"),
                        )
                    )
                )
            )
        )
        val body = entries(docx).getValue("word/document.xml")
        assertEquals(1, Regex("<w:hyperlink ").findAll(body).count(), body)
        assertEquals(1, Regex("rIdLnk").findAll(entries(docx).getValue("word/_rels/document.xml.rels")).count())
    }

    @Test
    fun `a table keeps the columns a reader measured, and the rules the page drew`() {
        val document = DocumentModel(
            listOf(
                Table(
                    rows = listOf(
                        TableRow(
                            listOf(
                                TableCell(listOf(Paragraph(listOf(TextRun("2022-04-21"))))),
                                TableCell(listOf(Paragraph(listOf(TextRun("a long column of prose"))))),
                            )
                        )
                    ),
                    columnWidthsPt = listOf(72f, 288f),
                )
            )
        )
        val xml = documentXml(DocxWriter.toByteArray(document))
        assertTrue(xml.contains("""<w:tblW w:w="7200" w:type="dxa"/>"""), xml)
        assertTrue(xml.contains("""<w:gridCol w:w="1440"/><w:gridCol w:w="5760"/>"""), xml)
        assertTrue(xml.contains("""<w:tcW w:w="1440" w:type="dxa"/>"""), xml)
        assertTrue(xml.contains("<w:tblBorders>"), "a ruled table keeps its rules")
    }

    @Test
    fun `a table the page never ruled is written without rules`() {
        val xml = documentXml(
            DocxWriter.toByteArray(
                DocumentModel(
                    listOf(
                        Table(
                            rows = listOf(TableRow(listOf(TableCell(listOf(Paragraph(listOf(TextRun("x")))))))),
                            ruled = false,
                        )
                    )
                )
            )
        )
        assertTrue(!xml.contains("<w:tblBorders>"), xml)
    }

    @Test
    fun `a table read back keeps its grid and whether it was ruled`() {
        val document = DocumentModel(
            listOf(
                Table(
                    rows = listOf(
                        TableRow(
                            listOf(
                                TableCell(listOf(Paragraph(listOf(TextRun("a"))))),
                                TableCell(listOf(Paragraph(listOf(TextRun("b"))))),
                            )
                        )
                    ),
                    columnWidthsPt = listOf(60f, 300f),
                    ruled = false,
                )
            )
        )
        val read = DocxReader.read(DocxWriter.toByteArray(document)).blocks.filterIsInstance<Table>().single()
        assertEquals(listOf(60f, 300f), read.columnWidthsPt)
        assertTrue(!read.ruled)
    }

    @Test
    fun `a running header and footer become parts of their own`() {
        val picture = ImageBlock(PNG, "image/png", 2, 2, widthPt = 100f, heightPt = 20f)
        val document = DocumentModel(
            blocks = listOf(Paragraph(listOf(TextRun("body")))),
            pageSetup = PageSetup(595f, 842f, 56f, 72f, 56f, 84f, headerDistancePt = 30f, footerDistancePt = 40f, firstPageNumber = 48),
            header = listOf(picture),
            footer = listOf(
                Paragraph(
                    listOf(
                        TextRun("", image = ImageBlock(PNG, "image/png", 2, 2, widthPt = 50f, heightPt = 10f)),
                        TextRun("\t"),
                        TextRun("48", field = RunField.PAGE_NUMBER),
                    ),
                    ParagraphStyle(direction = TextDirection.RTL, tabStopsPt = listOf(400f)),
                )
            ),
        )
        val docx = DocxWriter.toByteArray(document)
        val parts = entries(docx)
        val body = parts.getValue("word/document.xml")
        assertTrue(body.contains("""<w:sectPr><w:headerReference w:type="default" r:id="rIdHdr1"/><w:footerReference w:type="default" r:id="rIdFtr1"/><w:pgSz"""), body)
        assertTrue(body.contains("""w:header="600" w:footer="800" w:gutter="0"/><w:pgNumType w:start="48"/></w:sectPr>"""), body)
        assertTrue(parts.getValue("[Content_Types].xml").contains("/word/header1.xml"), "header content type")
        assertTrue(parts.getValue("[Content_Types].xml").contains("/word/footer1.xml"), "footer content type")
        val rels = parts.getValue("word/_rels/document.xml.rels")
        assertTrue(rels.contains("""Id="rIdHdr1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml""""), rels)
        assertTrue(rels.contains("""Target="footer1.xml""""), rels)
        val header = parts.getValue("word/header1.xml")
        assertTrue(header.startsWith("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:hdr """) || header.contains("<w:hdr "), header.take(120))
        // The picture at its measured size: 100pt by 20pt in EMUs.
        assertTrue(header.contains("""<wp:extent cx="1270000" cy="254000"/>"""), header)
        assertTrue(parts.getValue("word/_rels/header1.xml.rels").contains("media/image1.png"), "header picture relationship")
        val footer = parts.getValue("word/footer1.xml")
        assertTrue(footer.contains("<w:ftr "), footer.take(120))
        // An inline picture in a run, then a tab, then the page field with the first page's number as its cached text.
        assertTrue(footer.contains("""<w:r><w:drawing>"""), footer)
        assertTrue(footer.contains("""<w:tab/>"""), footer)
        assertTrue(footer.contains("""<w:fldSimple w:instr=" PAGE "><w:r><w:rPr><w:rtl/></w:rPr><w:t xml:space="preserve">48</w:t></w:r></w:fldSimple>"""), footer)
        assertTrue(parts.getValue("word/_rels/footer1.xml.rels").contains("media/image2.png"), "footer picture relationship")
        assertTrue(parts.containsKey("word/media/image1.png") && parts.containsKey("word/media/image2.png"), "media parts")
    }

    @Test
    fun `a running header and footer read back as they were written`() {
        val document = DocumentModel(
            blocks = listOf(Paragraph(listOf(TextRun("body")))),
            pageSetup = PageSetup(595f, 842f, 56f, 72f, 56f, 84f, headerDistancePt = 30f, footerDistancePt = 40f, firstPageNumber = 48),
            header = listOf(ImageBlock(PNG, "image/png", 2, 2, widthPt = 100f, heightPt = 20f)),
            footer = listOf(
                Paragraph(
                    listOf(
                        TextRun("", image = ImageBlock(PNG, "image/png", 2, 2, widthPt = 50f, heightPt = 10f)),
                        TextRun("\t"),
                        TextRun("48", field = RunField.PAGE_NUMBER),
                    ),
                    ParagraphStyle(direction = TextDirection.RTL, tabStopsPt = listOf(400f)),
                )
            ),
        )
        val read = DocxReader.read(DocxWriter.toByteArray(document))
        val header = read.header.single() as ImageBlock
        assertEquals(100f, header.widthPt)
        assertEquals(20f, header.heightPt)
        assertTrue(header.bytes.contentEquals(PNG), "the header picture's bytes")
        val footer = read.footer.single() as Paragraph
        assertEquals(3, footer.runs.size, footer.runs.toString())
        // The picture stays in the line, the tab is a run of its own, and the field is a field.
        assertEquals(50f, footer.runs[0].image?.widthPt)
        assertEquals("\t", footer.runs[1].text)
        assertEquals(RunField.PAGE_NUMBER, footer.runs[2].field)
        assertEquals("48", footer.runs[2].text)
        assertEquals(listOf(400f), footer.style.tabStopsPt)
        assertEquals(TextDirection.RTL, footer.style.direction)
        val page = read.pageSetup!!
        assertEquals(30f, page.headerDistancePt)
        assertEquals(40f, page.footerDistancePt)
        assertEquals(48, page.firstPageNumber)
        // The body is its own: one paragraph, and no furniture picture leaked into it.
        assertEquals(listOf("body"), read.blocks.map { (it as Paragraph).text })
    }

    @Test
    fun `a document without furniture writes no header or footer part`() {
        val parts = entries(DocxWriter.toByteArray(DocumentModel(listOf(Paragraph(listOf(TextRun("plain")))))))
        assertTrue(!parts.containsKey("word/header1.xml") && !parts.containsKey("word/footer1.xml"))
        assertTrue(!parts.getValue("word/document.xml").contains("headerReference"))
    }

    @Test
    fun `a document that measured nothing keeps the defaults`() {
        val xml = documentXml(DocxWriter.toByteArray(DocumentModel(listOf(Paragraph(listOf(TextRun("plain")))))))
        assertTrue(xml.contains("""<w:p><w:r><w:t xml:space="preserve">plain</w:t></w:r></w:p>"""), xml)
        assertTrue(xml.contains("""<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" """), xml)
    }

    private fun documentXml(docx: ByteArray): String = entries(docx).getValue("word/document.xml")

    private fun entries(docx: ByteArray): Map<String, String> {
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.ISO_8859_1)
            }
        }
        return parts
    }

    /** A 2x2 PNG. */
    private val PNG: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAC0lEQVR4nGNgQAcAABIAAeRVjecAAAAASUVORK5CYII="
    )
}
