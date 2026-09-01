package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class HtmlWriterTest {

    private fun parse(html: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))

    private fun body(text: String) = Paragraph(listOf(TextRun(text)))

    private fun elements(doc: Document, tag: String): List<Element> {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `an arabic-majority document gets an rtl root and ltr paragraphs carry their own dir`() {
        val model = DocumentModel(
            blocks = listOf(
                Paragraph(listOf(TextRun("مرحبا")), ParagraphStyle(direction = TextDirection.RTL)),
                Paragraph(listOf(TextRun("عالم")), ParagraphStyle(direction = TextDirection.RTL)),
                Paragraph(listOf(TextRun("English aside")), ParagraphStyle(direction = TextDirection.LTR)),
            ),
            defaultDirection = TextDirection.RTL,
            defaultLanguage = "ar",
        )
        val doc = parse(HtmlWriter.write(model, "تقرير"))
        assertEquals("rtl", doc.documentElement.getAttribute("dir"))
        assertEquals("ar", doc.documentElement.getAttribute("lang"))
        val paragraphs = elements(doc, "p")
        assertEquals("", paragraphs[0].getAttribute("dir"), "default-direction paragraph needs no dir")
        assertEquals("ltr", paragraphs[2].getAttribute("dir"))
    }

    @Test
    fun `headings map to h tags and the title gets its class`() {
        val model = DocumentModel(
            listOf(
                Paragraph(listOf(TextRun("The Title")), ParagraphStyle(kind = ParagraphKind.TITLE)),
                Paragraph(listOf(TextRun("One")), ParagraphStyle(kind = ParagraphKind.HEADING_1)),
                Paragraph(listOf(TextRun("Two")), ParagraphStyle(kind = ParagraphKind.HEADING_2)),
                body("text"),
            )
        )
        val doc = parse(HtmlWriter.write(model))
        val h1 = elements(doc, "h1")
        assertEquals(2, h1.size)
        assertEquals("doc-title", h1[0].getAttribute("class"))
        assertEquals(1, elements(doc, "h2").size)
    }

    @Test
    fun `contiguous list items group and numbering restarts per list`() {
        fun item(text: String, marker: ListMarker) =
            Paragraph(listOf(TextRun(text)), ParagraphStyle(listMarker = marker))
        val model = DocumentModel(
            listOf(
                item("a", ListMarker.NUMBERED), item("b", ListMarker.NUMBERED),
                body("interlude"),
                item("c", ListMarker.NUMBERED),
                item("d", ListMarker.BULLET),
            )
        )
        val doc = parse(HtmlWriter.write(model))
        assertEquals(2, elements(doc, "ol").size, "two separate ordered lists")
        assertEquals(1, elements(doc, "ul").size)
        assertEquals(4, elements(doc, "li").size)
    }

    @Test
    fun `styled runs nest strong em u and spans carry dir and lang`() {
        val model = DocumentModel(
            blocks = listOf(
                Paragraph(
                    runs = listOf(
                        TextRun("عادي "),
                        TextRun("مهم", bold = true, direction = TextDirection.RTL),
                        TextRun("Latin", direction = TextDirection.LTR, language = "en"),
                    ),
                    style = ParagraphStyle(direction = TextDirection.RTL),
                )
            ),
            defaultDirection = TextDirection.RTL,
        )
        val html = HtmlWriter.write(model)
        assertTrue(html.contains("<strong>مهم</strong>"), html)
        assertTrue(html.contains("""<span dir="ltr" lang="en">Latin</span>"""), html)
        parse(html)
    }

    @Test
    fun `tables render rows and cells with nested content`() {
        val model = DocumentModel(
            listOf(
                Table(
                    rows = listOf(
                        TableRow(listOf(TableCell(listOf(body("a"))), TableCell(listOf(body("b"))))),
                        TableRow(listOf(TableCell(listOf(body("c"))), TableCell(listOf(body("d"))))),
                    )
                )
            )
        )
        val doc = parse(HtmlWriter.write(model))
        assertEquals(2, elements(doc, "tr").size)
        assertEquals(4, elements(doc, "td").size)
    }

    @Test
    fun `images embed as data uris with their dimensions`() {
        val model = DocumentModel(listOf(ImageBlock(byteArrayOf(1, 2, 3), "image/png", 40, 20)))
        val doc = parse(HtmlWriter.write(model))
        val img = elements(doc, "img").single()
        assertTrue(img.getAttribute("src").startsWith("data:image/png;base64,AQID"))
        assertEquals("40", img.getAttribute("width"))
        assertEquals("20", img.getAttribute("height"))
    }

    @Test
    fun `markup in text is neutralized`() {
        val model = DocumentModel(listOf(body("<script>alert('x')</script> & done")))
        val html = HtmlWriter.write(model)
        assertTrue("<script>" !in html)
        val doc = parse(html)
        assertTrue(doc.getElementsByTagName("p").item(0).textContent.contains("<script>"))
        assertTrue(doc.getElementsByTagName("script").length == 0)
    }
}
