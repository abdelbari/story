package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxWriterTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private val fixture = DocumentModel(
        blocks = listOf(
            Paragraph(
                runs = listOf(TextRun("Quarterly Report")),
                style = ParagraphStyle(kind = ParagraphKind.HEADING_1),
            ),
            Paragraph(
                runs = listOf(
                    TextRun("Numbers are "),
                    TextRun("up", bold = true),
                    TextRun(" and costs are "),
                    TextRun("down", italic = true),
                    TextRun(". 5 < 6 & \"quotes\" work."),
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
                    TableRow(listOf(cell("الرباط"), cell("580000"))),
                ),
            ),
        ),
    )

    private fun cell(text: String) = TableCell(listOf(Paragraph(listOf(TextRun(text)))))

    private fun numbered(text: String) = Paragraph(
        runs = listOf(TextRun(text)),
        style = ParagraphStyle(listMarker = ListMarker.NUMBERED),
    )

    private fun bullet(text: String) = Paragraph(
        runs = listOf(TextRun(text)),
        style = ParagraphStyle(listMarker = ListMarker.BULLET),
    )

    private fun entries(docx: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    @Test
    fun `package contains every required part and all parts are well-formed xml`() {
        val parts = entries(DocxWriter.toByteArray(fixture))
        val required = listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "word/_rels/document.xml.rels",
            "word/document.xml",
            "word/styles.xml",
            "word/numbering.xml",
            "docProps/core.xml",
            "docProps/app.xml",
        )
        for (name in required) {
            assertTrue(parts.containsKey(name), "missing part: $name")
            parse(parts.getValue(name)) // throws if not well-formed
        }
    }

    @Test
    fun `document text round-trips in order including escaped characters`() {
        val doc = parse(entries(DocxWriter.toByteArray(fixture)).getValue("word/document.xml"))
        val allText = doc.documentElement.textContent
        val expectedInOrder = listOf(
            "Quarterly Report",
            "Numbers are up and costs are down. 5 < 6 & \"quotes\" work.",
            "مرحبا بالعالم",
            "first bullet",
            "first numbered",
            "City", "Population", "الرباط", "580000",
        )
        var cursor = 0
        for (piece in expectedInOrder) {
            val at = allText.indexOf(piece, cursor)
            assertTrue(at >= 0, "not found in order: $piece")
            cursor = at + piece.length
        }
    }

    @Test
    fun `heading paragraph references the Heading1 style`() {
        val doc = parse(entries(DocxWriter.toByteArray(fixture)).getValue("word/document.xml"))
        val heading = findParagraphContaining(doc, "Quarterly Report")
        val pStyle = firstChildNS(firstChildNS(heading, "pPr")!!, "pStyle")
        assertNotNull(pStyle)
        assertEquals("Heading1", pStyle!!.getAttributeNS(wNs, "val"))
    }

    @Test
    fun `arabic paragraph carries bidi and its run carries rtl and lang`() {
        val doc = parse(entries(DocxWriter.toByteArray(fixture)).getValue("word/document.xml"))
        val paragraph = findParagraphContaining(doc, "مرحبا بالعالم")
        val pPr = firstChildNS(paragraph, "pPr")
        assertNotNull(firstChildNS(pPr!!, "bidi"), "w:bidi missing on RTL paragraph")

        val run = firstChildNS(paragraph, "r")!!
        val rPr = firstChildNS(run, "rPr")!!
        assertNotNull(firstChildNS(rPr, "rtl"), "w:rtl missing on RTL run")
        val lang = firstChildNS(rPr, "lang")!!
        assertEquals("ar", lang.getAttributeNS(wNs, "bidi"))
    }

    @Test
    fun `bullets use numId 1 and the first numbered list uses numId 2`() {
        val doc = parse(entries(DocxWriter.toByteArray(fixture)).getValue("word/document.xml"))
        val bullet = findParagraphContaining(doc, "first bullet")
        val numbered = findParagraphContaining(doc, "first numbered")
        assertEquals("1", numIdOf(bullet))
        assertEquals("2", numIdOf(numbered))
    }

    @Test
    fun `separate numbered lists get distinct numIds and numbering declares each exactly once`() {
        val parts = entries(
            DocxWriter.toByteArray(
                DocumentModel(
                    blocks = listOf(
                        numbered("one a"),
                        numbered("one b"),
                        Paragraph(listOf(TextRun("interlude"))),
                        numbered("two a"),
                        numbered("two b"),
                    ),
                )
            )
        )
        val doc = parse(parts.getValue("word/document.xml"))
        val firstListId = numIdOf(findParagraphContaining(doc, "one a"))
        val secondListId = numIdOf(findParagraphContaining(doc, "two a"))
        assertEquals("2", firstListId)
        assertEquals("3", secondListId)

        val numMap = numToAbstractMap(parse(parts.getValue("word/numbering.xml")))
        assertEquals("1", numMap[firstListId])
        assertEquals("1", numMap[secondListId])
        assertEquals(setOf("1", firstListId, secondListId), numMap.keys)
    }

    @Test
    fun `items within one numbered list share the same numId`() {
        val doc = parse(
            entries(
                DocxWriter.toByteArray(
                    DocumentModel(
                        blocks = listOf(numbered("alpha"), numbered("beta"), numbered("gamma")),
                    )
                )
            ).getValue("word/document.xml")
        )
        val ids = listOf("alpha", "beta", "gamma").map { numIdOf(findParagraphContaining(doc, it)) }
        assertEquals(listOf("2", "2", "2"), ids)
    }

    @Test
    fun `bullet lists share numId 1 even when separated by other blocks`() {
        val doc = parse(
            entries(
                DocxWriter.toByteArray(
                    DocumentModel(
                        blocks = listOf(
                            bullet("north"),
                            bullet("south"),
                            Paragraph(listOf(TextRun("interlude"))),
                            bullet("east"),
                        ),
                    )
                )
            ).getValue("word/document.xml")
        )
        val ids = listOf("north", "south", "east").map { numIdOf(findParagraphContaining(doc, it)) }
        assertEquals(listOf("1", "1", "1"), ids)
    }

    @Test
    fun `a numbered list inside a table cell restarts independently of body lists`() {
        val parts = entries(
            DocxWriter.toByteArray(
                DocumentModel(
                    blocks = listOf(
                        numbered("body item"),
                        Table(
                            rows = listOf(
                                TableRow(
                                    listOf(
                                        TableCell(listOf(numbered("cell item 1"), numbered("cell item 2"))),
                                    )
                                ),
                            ),
                        ),
                        numbered("after table"),
                    ),
                )
            )
        )
        val doc = parse(parts.getValue("word/document.xml"))
        val bodyId = numIdOf(findParagraphContaining(doc, "body item"))
        val cellId1 = numIdOf(findParagraphContaining(doc, "cell item 1"))
        val cellId2 = numIdOf(findParagraphContaining(doc, "cell item 2"))
        val afterId = numIdOf(findParagraphContaining(doc, "after table"))
        assertEquals(cellId1, cellId2, "one cell list must share one numId")
        assertEquals(3, setOf(bodyId, cellId1, afterId).size, "each list needs its own numId")

        val numMap = numToAbstractMap(parse(parts.getValue("word/numbering.xml")))
        assertEquals(setOf("1", bodyId, cellId1, afterId), numMap.keys)
    }

    @Test
    fun `arabic numbered lists restart per list and keep their rtl direction`() {
        val doc = parse(
            entries(
                DocxWriter.toByteArray(
                    DocumentModel(
                        blocks = listOf(
                            numbered("البند الأول"),
                            numbered("البند الثاني"),
                            Paragraph(listOf(TextRun("فاصل"))),
                            numbered("العنصر الأول"),
                        ),
                        defaultLanguage = "ar",
                        defaultDirection = TextDirection.RTL,
                    )
                )
            ).getValue("word/document.xml")
        )
        val first = findParagraphContaining(doc, "البند الأول")
        val second = findParagraphContaining(doc, "البند الثاني")
        val restarted = findParagraphContaining(doc, "العنصر الأول")
        assertEquals(numIdOf(first), numIdOf(second))
        assertTrue(numIdOf(first) != numIdOf(restarted), "second list must restart with its own numId")
        for (paragraph in listOf(first, second, restarted)) {
            assertNotNull(
                firstChildNS(firstChildNS(paragraph, "pPr")!!, "bidi"),
                "w:bidi missing on RTL list paragraph"
            )
        }
    }

    @Test
    fun `table has the declared rows and cells and each cell ends with a paragraph`() {
        val doc = parse(entries(DocxWriter.toByteArray(fixture)).getValue("word/document.xml"))
        val tables = doc.getElementsByTagNameNS(wNs, "tbl")
        assertEquals(1, tables.length)
        val table = tables.item(0) as Element
        assertEquals(2, table.getElementsByTagNameNS(wNs, "tr").length)
        val cells = table.getElementsByTagNameNS(wNs, "tc")
        assertEquals(4, cells.length)
        for (i in 0 until cells.length) {
            val cell = cells.item(i) as Element
            assertTrue(
                cell.getElementsByTagNameNS(wNs, "p").length > 0,
                "cell without a paragraph"
            )
        }
    }

    @Test
    fun `styles part defines the styles the document references`() {
        val styles = parse(entries(DocxWriter.toByteArray(fixture)).getValue("word/styles.xml"))
        val ids = mutableSetOf<String>()
        val styleNodes = styles.getElementsByTagNameNS(wNs, "style")
        for (i in 0 until styleNodes.length) {
            ids += (styleNodes.item(i) as Element).getAttributeNS(wNs, "styleId")
        }
        assertTrue(
            ids.containsAll(listOf("Normal", "Title", "Heading1", "Heading2", "Heading3", "ListParagraph")),
            "styles present: $ids"
        )
    }

    @Test
    fun `images are rejected loudly instead of being dropped`() {
        val withImage = DocumentModel(
            blocks = listOf(ImageBlock(byteArrayOf(1, 2, 3), "image/png", 10, 10)),
        )
        assertThrows(UnsupportedOperationException::class.java) {
            DocxWriter.toByteArray(withImage)
        }
    }

    // ------------------------------------------------------------------

    private fun findParagraphContaining(doc: Document, needle: String): Element {
        val paragraphs = doc.getElementsByTagNameNS(wNs, "p")
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as Element
            if (p.textContent.contains(needle)) return p
        }
        throw AssertionError("no paragraph containing: $needle")
    }

    private fun firstChildNS(parent: Element, localName: String): Element? {
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == wNs && node.localName == localName) return node
            node = node.nextSibling
        }
        return null
    }

    private fun numIdOf(paragraph: Element): String {
        val pPr = firstChildNS(paragraph, "pPr")!!
        val numPr = firstChildNS(pPr, "numPr")!!
        return firstChildNS(numPr, "numId")!!.getAttributeNS(wNs, "val")
    }

    /** numId -> abstractNumId from numbering.xml, failing on duplicate w:num ids. */
    private fun numToAbstractMap(numbering: Document): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val nums = numbering.getElementsByTagNameNS(wNs, "num")
        for (i in 0 until nums.length) {
            val num = nums.item(i) as Element
            val numId = num.getAttributeNS(wNs, "numId")
            assertTrue(numId !in result, "duplicate w:num for numId $numId")
            result[numId] = firstChildNS(num, "abstractNumId")!!.getAttributeNS(wNs, "val")
        }
        return result
    }
}
