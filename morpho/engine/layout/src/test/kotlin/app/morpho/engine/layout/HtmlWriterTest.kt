package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `a contents line leads to the heading it names`() {
        // The preview is the app's own reading of a document: a thesis's
        // contents page has to work there too, not only in Word.
        val model = DocumentModel(
            listOf(
                Paragraph(listOf(TextRun("1. Introduction", link = "#_Toc 1"))),
                Paragraph(
                    listOf(TextRun("Introduction")),
                    ParagraphStyle(kind = ParagraphKind.HEADING_1),
                    bookmarks = listOf("_Toc 1"),
                ),
            )
        )
        val doc = parse(HtmlWriter.write(model))
        val href = elements(doc, "a").single().getAttribute("href")
        val id = elements(doc, "h1").single().getAttribute("id")
        assertTrue(id.isNotEmpty(), "the heading must be somewhere to arrive at")
        assertEquals("#" + id, href, "the line and the heading must meet")
    }

    @Test
    fun `the page a report turns sideways is a sheet of its own`() {
        // A browser printing this must lay each part of the document on
        // the sheet that part was set on, which is what a named page is
        // for; on screen there are no sheets and nothing changes.
        val landscape = PageSetup(
            widthPt = 842f, heightPt = 595f,
            marginTopPt = 36f, marginBottomPt = 36f, marginLeftPt = 36f, marginRightPt = 36f,
        )
        val model = DocumentModel(
            blocks = listOf(
                body("Before the wide table."),
                Paragraph(listOf(TextRun("The page of the wide table.")), ParagraphStyle(sectionSetup = landscape)),
            ),
            pageSetup = PageSetup(
                widthPt = 595f, heightPt = 842f,
                marginTopPt = 72f, marginBottomPt = 72f, marginLeftPt = 72f, marginRightPt = 72f,
            ),
        )
        val html = HtmlWriter.write(model)
        assertTrue(html.contains("@page sheet1{size:842.0pt 595.0pt;"), html)
        assertTrue(html.contains(".sheet1{page:sheet1;break-before:page;}"), html)
        val doc = parse(html)
        val turned = elements(doc, "div").single { it.getAttribute("class") == "sheet1" }
        assertEquals("The page of the wide table.", turned.textContent.trim())
    }

    @Test
    fun `a document of one shape names no sheets`() {
        val html = HtmlWriter.write(DocumentModel(listOf(body("One."), body("Two."))))
        assertTrue("@page sheet" !in html, html)
        assertTrue("<div" !in html, html)
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

    @Test
    fun `the preview is headed with what the document calls itself`() {
        // A tab full of pages all called "Document" tells its reader
        // nothing about which is which.
        val model = DocumentModel(
            listOf(Paragraph(listOf(TextRun("The body of it.")))),
            properties = DocumentProperties(
                title = "A Study of Forms",
                author = "R. Nebbar",
                subject = "The tools of research",
            ),
        )
        val html = HtmlWriter.write(model)
        assertTrue(html.contains("<title>A Study of Forms</title>"), "the preview kept the name Document")
        assertTrue(html.contains("""<meta name="author" content="R. Nebbar"/>"""))
        assertTrue(html.contains("""<meta name="description" content="The tools of research"/>"""))
        // What the caller asks for still wins: the app names the preview
        // after the file the reader chose.
        assertTrue(HtmlWriter.write(model, title = "chosen.pdf").contains("<title>chosen.pdf</title>"))
    }

    @Test
    fun `a document that names itself nothing is still headed something`() {
        val html = HtmlWriter.write(DocumentModel(listOf(Paragraph(listOf(TextRun("Body."))))))
        assertTrue(html.contains("<title>Document</title>"))
        assertFalse(html.contains("""<meta name="author"""))
    }

    /** Every element of [doc] that says which block it is, in the order the page has them. */
    private fun marked(doc: Document): List<Element> {
        val all = doc.getElementsByTagName("*")
        return (0 until all.length).map { all.item(it) as Element }.filter { it.hasAttribute("data-block") }
    }

    private fun editable(): DocumentModel {
        val item = ParagraphStyle(listMarker = ListMarker.BULLET)
        return DocumentModel(
            blocks = listOf(
                body("A paragraph"),
                Paragraph(listOf(TextRun("first point")), item),
                Paragraph(listOf(TextRun("second point")), item),
                Table(listOf(TableRow(listOf(TableCell(listOf(body("in a cell"))))))),
                ImageBlock(ByteArray(4), "image/png", 1, 1),
                Paragraph(listOf(TextRun("with a note"), TextRun("1", superscript = true, note = listOf(body("the note"))))),
            ),
            header = listOf(body("running head")),
            footer = listOf(body("running foot")),
        )
    }

    @Test
    fun `every block of the body says which block it is, and nothing else does`() {
        // What an edit names a place by, and what the screen finds the
        // element to repaint by. On the outermost element — the item, for
        // an item of a list — and on the body's blocks only: a cell's
        // paragraph, a note, a running head are not blocks an edit can
        // name yet, and an element that claimed to be one would be
        // repainted with the wrong block.
        val doc = parse(HtmlWriter.write(editable()))
        val marks = marked(doc)
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), marks.map { it.getAttribute("data-block") })
        assertEquals(listOf("p", "li", "li", "table", "p", "p"), marks.map { it.tagName })
        assertEquals("ul", marks[1].parentNode.nodeName, "the item is marked, not the list round it")
    }

    @Test
    fun `a block written on its own is the element the page wrote for it`() {
        val document = editable()
        val page = parse(HtmlWriter.write(document))
        for (index in document.blocks.indices) {
            val alone = parse(HtmlWriter.writeBlock(document, index)).documentElement
            val onPage = marked(page)[index]
            assertEquals(onPage.tagName, alone.tagName, "block $index")
            assertEquals(index.toString(), alone.getAttribute("data-block"), "block $index")
            assertEquals(onPage.textContent, alone.textContent, "block $index")
        }
        // The item comes without its list: the list belongs to its
        // neighbours as much as to it.
        assertEquals("li", parse(HtmlWriter.writeBlock(document, 1)).documentElement.tagName)
    }
}
