package app.morpho.engine.pdf

import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextDirection
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Who supplies a list item's label. A producer that writes the label into
 * a label element of its own leaves the item's text without one, and a
 * writer must draw it. Word, tagging an Arabic list, does the opposite:
 * the bullet is painted at the head of the item's own text. Adding a
 * marker to that item would show two, and would lose what the page shows —
 * the dash of a second level, the "أ-" the author typed.
 */
class ListLabelTest {

    private val bullet = "•"
    private val minus = "−"

    @Test
    fun `a bullet painted into the item is its label, and the item keeps its own side`() {
        val model = PdfReader().extract(
            list(items = listOf(null to "$bullet الاستمارة البريدية: إذا لم يكن مجتمع الدراسة محصورا"))
        )
        val item = model.blocks.filterIsInstance<Paragraph>().single()
        assertNull(item.style.listMarker, "a second marker would double the one the page drew")
        assertTrue(item.text.startsWith("$bullet "), item.text)
        // The bullet says nothing about direction, so the Arabic decides:
        // right-to-left, which is the side the marker belongs on.
        assertEquals(TextDirection.RTL, item.style.direction)
    }

    @Test
    fun `a dash at a second level is kept as the dash it is`() {
        val model = PdfReader().extract(
            list(items = listOf(null to "$minus أن يرسل مع الاستمارة ظرف عليه الطابع والعنوان"))
        )
        val item = model.blocks.filterIsInstance<Paragraph>().single()
        assertNull(item.style.listMarker)
        assertTrue(item.text.startsWith("$minus "), item.text)
        assertEquals(TextDirection.RTL, item.style.direction)
    }

    @Test
    fun `the author's own enumerator is a label too`() {
        val model = PdfReader().extract(
            list(items = listOf(null to "أ- إذا كانت صياغة السؤال غير جيدة فان المبحوث قد يجيب"))
        )
        val item = model.blocks.filterIsInstance<Paragraph>().single()
        assertNull(item.style.listMarker, "the page's own \"أ-\" says more than a bullet")
        assertTrue(item.text.startsWith("أ- "), item.text)
    }

    @Test
    fun `an item whose label is an element of its own still gets one from the writer`() {
        val model = PdfReader().extract(list(items = listOf("1." to "the first thing to do")))
        val item = model.blocks.filterIsInstance<Paragraph>().single()
        assertEquals(ListMarker.NUMBERED, item.style.listMarker)
        assertEquals("the first thing to do", item.text)
    }

    @Test
    fun `a sentence that merely opens with a dash is not a label`() {
        // No space after it: this is the author writing, not a marker.
        val model = PdfReader().extract(list(items = listOf(null to "-tightly written, so a marker is due")))
        val item = model.blocks.filterIsInstance<Paragraph>().single()
        assertEquals(ListMarker.BULLET, item.style.listMarker)
    }

    /** A tagged list of [items], each an optional label element and a body. */
    private fun list(items: List<Pair<String?, String>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val root = PDStructureTreeRoot()
            document.documentCatalog.structureTreeRoot = root
            val docElement = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
            docElement.page = page
            root.appendKid(docElement)
            val list = PDStructureElement(StandardStructureTypes.L, docElement)
            list.page = page
            docElement.appendKid(list)
            val arabic: PDFont = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf") ?: error("test font missing"),
                false,
            )
            var mcid = 0
            var y = 760f
            PDPageContentStream(document, page).use { content ->
                fun leaf(type: String, parent: PDStructureElement, text: String) {
                    val element = PDStructureElement(type, parent)
                    element.page = page
                    parent.appendKid(element)
                    val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                    val rtl = text.any { it in '؀'..'ۿ' }
                    val font = if (rtl || text.any { it.code > 0x2000 }) arabic else PDType1Font.HELVETICA
                    content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                    content.beginText()
                    content.setFont(font, 12f)
                    content.newLineAtOffset(if (rtl) 300f else 72f, y)
                    // A right-to-left line is painted the way a producer
                    // paints one: its glyphs left to right, so the reader
                    // has to put the line back together.
                    content.showText(if (rtl) text.reversed() else text)
                    content.endText()
                    content.endMarkedContent()
                    element.appendKid(PDMarkedContent(COSName.P, properties))
                    mcid++
                    y -= 18f
                }
                for ((label, body) in items) {
                    val item = PDStructureElement(StandardStructureTypes.LI, list)
                    item.page = page
                    list.appendKid(item)
                    if (label != null) leaf(StandardStructureTypes.LBL, item, label)
                    leaf(StandardStructureTypes.L_BODY, item, body)
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }
}
