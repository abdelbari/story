package app.morpho.engine.pdf

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.RunField
import org.apache.pdfbox.cos.COSArray
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * The running head and the foot of the page, read from the pagination
 * artifacts a producer marks them with: the head comes across as a
 * picture at the size it had, the foot with its page number as a field
 * so every page numbers itself, and the document starts counting where
 * the source did. A journal paper's first page says 48 at the foot; a
 * conversion that drops the head and prints "1" is not the same paper.
 */
class FurnitureTest {

    /** One text operation at top-down ([x], [y]) on an A4 page. */
    private class Piece(val text: String, val x: Float, val y: Float, val size: Float = 12f, val arabic: Boolean = false)

    /** A filled rectangle, top-down: [left], [top], [right], [bottom]. */
    private class Bar(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** A pagination artifact: what one running head or foot paints. */
    private class Furniture(val atTop: Boolean, val pieces: List<Piece>, val bars: List<Bar> = emptyList())

    /** One page: its body paragraphs and its furniture. */
    private class Sheet(val body: List<List<Piece>>, val furniture: List<Furniture>)

    private val headline = "Journal of Careful Conversion"
    private val headBar = Bar(60f, 50f, 500f, 50.72f)

    @Test
    fun `the running head becomes a picture the size it had, at its distance from the edge`() {
        val pdf = tagged("en") { pages(numbers = listOf(48, 49, 50), numberX = 60f) }
        val model = PdfReader().extract(pdf)
        val head = model.header.single() as? ImageBlock
        assertNotNull(head, "the head is not a picture: ${model.header}")
        // The picture spans the head's own reach — the bar, padded — not
        // just the text; the bar under the headline is the tallest thing.
        assertNear(443f, head!!.widthPt, "head width", tolerance = 4f)
        assertTrue(head.heightPt!! in 15f..30f, "head height ${head.heightPt}")
        assertTrue(inkPixels(head) > 100, "the head picture is blank")
        val page = model.pageSetup!!
        assertTrue(page.headerDistancePt!! in 30f..40f, "head distance ${page.headerDistancePt}")
        assertTrue(page.footerDistancePt!! in 28f..45f, "foot distance ${page.footerDistancePt}")
        // The head does not pull the body's top margin up to the edge.
        assertTrue(page.marginTopPt > 80f, "top margin ${page.marginTopPt}")
    }

    @Test
    fun `a number that advances by one each page is the page number, written as a field`() {
        val pdf = tagged("en") { pages(numbers = listOf(48, 49, 50), numberX = 60f) }
        val model = PdfReader().extract(pdf)
        val foot = model.footer.single() as? Paragraph
        assertNotNull(foot, "the foot is not a paragraph: ${model.footer}")
        val field = foot!!.runs.single { it.field == RunField.PAGE_NUMBER }
        assertEquals("48", field.text)
        assertEquals(48, model.pageSetup!!.firstPageNumber)
        // Left to right with the number at the left: the field first, a
        // tab to where the rest of the foot began, and the rest as a picture.
        assertEquals(RunField.PAGE_NUMBER, foot.runs.first().field)
        assertTrue(foot.runs.any { it.text == "\t" }, "no tab between the number and the rest")
        val picture = foot.runs.single { it.image != null }
        assertTrue(inkPixels(picture.image!!) > 50, "the rest of the foot is blank")
        val stop = foot.style.tabStopsPt?.singleOrNull()
        assertNotNull(stop, "no tab stop")
        assertTrue(stop!! in 10f..40f, "tab stop $stop")
    }

    @Test
    fun `right to left, the number at the outer edge still leads the line`() {
        val pdf = tagged("ar") { pages(numbers = listOf(48, 49, 50), numberX = 480f, arabic = true) }
        val model = PdfReader().extract(pdf)
        val foot = model.footer.single() as Paragraph
        assertEquals(RunField.PAGE_NUMBER, foot.runs.first().field)
        assertEquals("48", foot.runs.first().text)
        assertEquals(48, model.pageSetup!!.firstPageNumber)
        assertTrue(foot.runs.any { it.image != null }, "the words of the foot were lost")
    }

    @Test
    fun `a number that stays put is not a page number`() {
        val pdf = tagged("en") { pages(numbers = listOf(2024, 2024, 2024), numberX = 60f) }
        val model = PdfReader().extract(pdf)
        assertTrue(model.footer.single() is ImageBlock, "a year became a page number: ${model.footer}")
        assertEquals(1, model.pageSetup!!.firstPageNumber)
    }

    @Test
    fun `a number in the middle is masked out of the picture and set beneath it, centred`() {
        val pdf = tagged("en") { pages(numbers = listOf(7, 8, 9), numberX = 295f) }
        val model = PdfReader().extract(pdf)
        assertEquals(2, model.footer.size, "foot: ${model.footer}")
        assertTrue(model.footer[0] is ImageBlock)
        val centred = model.footer[1] as Paragraph
        assertEquals(Alignment.CENTER, centred.style.alignment)
        assertEquals("7", centred.runs.single().text)
        assertEquals(RunField.PAGE_NUMBER, centred.runs.single().field)
        assertEquals(7, model.pageSetup!!.firstPageNumber)
    }

    @Test
    fun `a page with no furniture has none`() {
        val pdf = tagged("en") { List(2) { Sheet(body(it), emptyList()) } }
        val model = PdfReader().extract(pdf)
        assertTrue(model.header.isEmpty() && model.footer.isEmpty())
        assertNull(model.pageSetup!!.headerDistancePt)
        assertNull(model.pageSetup!!.footerDistancePt)
        assertEquals(1, model.pageSetup!!.firstPageNumber)
    }

    /** Three pages with the same head, a foot with a volume line, and the given page numbers at [numberX]. */
    private fun pages(numbers: List<Int>, numberX: Float, arabic: Boolean = false): List<Sheet> =
        numbers.mapIndexed { index, number ->
            val volume = if (arabic) Piece("المجلد ١٢", 60f, 802f, 10f, arabic = true) else Piece("Volume 12", 400f, 802f, 10f)
            Sheet(
                body = body(index, arabic),
                furniture = listOf(
                    Furniture(atTop = true, pieces = listOf(Piece(headline, 60f, 42f, 9f)), bars = listOf(headBar)),
                    Furniture(atTop = false, pieces = listOf(Piece(number.toString(), numberX, 802f, 10f), volume)),
                ),
            )
        }

    private fun body(pageIndex: Int, arabic: Boolean = false): List<List<Piece>> =
        List(3) { paragraph ->
            List(3) { line ->
                val y = 120f + (paragraph * 4 + line) * 14f
                if (arabic) Piece("كلمة أخرى في صفحة ${pageIndex + 1}", 300f, y, arabic = true)
                else Piece("Words of page ${pageIndex + 1}, paragraph $paragraph, line $line", 60f, y)
            }
        }

    private fun inkPixels(image: ImageBlock): Int {
        val pixels = ImageIO.read(image.bytes.inputStream()) ?: return 0
        var ink = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels.getRGB(x, y) and 0xFFFFFF != 0xFFFFFF) ink++
        }
        return ink
    }

    private fun assertNear(expected: Float, actual: Float?, what: String, tolerance: Float = 1f) {
        assertNotNull(actual, "$what missing")
        assertTrue(abs(expected - actual!!) <= tolerance, "$what: expected $expected, was $actual")
    }

    private fun isRtl(text: String): Boolean = text.any {
        Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    /**
     * A tagged document of A4 pages: each body paragraph is a P in the
     * structure tree with one marked-content id per piece; each furniture
     * item is a top-level Artifact marked /Type /Pagination /Attached
     * [/Top] or [/Bottom], holding its text and its bars, outside the tree
     * — as Word marks a header and a footer.
     */
    private fun tagged(language: String, sheets: (PDDocument) -> List<Sheet>): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val root = PDStructureTreeRoot()
            document.documentCatalog.structureTreeRoot = root
            document.documentCatalog.language = language
            val docElement = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
            root.appendKid(docElement)
            val latin: PDFont = PDType1Font.HELVETICA
            val arabicFont: PDFont by lazy {
                PDType0Font.load(
                    document,
                    javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf") ?: error("test font missing"),
                    true,
                )
            }
            fun PDPageContentStream.paint(piece: Piece) {
                beginText()
                setFont(if (piece.arabic) arabicFont else latin, piece.size)
                newLineAtOffset(piece.x, PDRectangle.A4.height - piece.y)
                showText(if (isRtl(piece.text)) piece.text.reversed() else piece.text)
                endText()
            }
            var mcid = 0
            for (sheet in sheets(document)) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    for (furniture in sheet.furniture) {
                        val properties = COSDictionary().apply {
                            setName(COSName.TYPE, "Pagination")
                            setItem(
                                COSName.getPDFName("Attached"),
                                COSArray().apply { add(COSName.getPDFName(if (furniture.atTop) "Top" else "Bottom")) },
                            )
                        }
                        content.beginMarkedContent(COSName.getPDFName("Artifact"), PDPropertyList.create(properties))
                        for (bar in furniture.bars) {
                            content.addRect(bar.left, PDRectangle.A4.height - bar.bottom, bar.right - bar.left, bar.bottom - bar.top)
                            content.fill()
                        }
                        for (piece in furniture.pieces) content.paint(piece)
                        content.endMarkedContent()
                    }
                    for (paragraphPieces in sheet.body) {
                        val paragraph = PDStructureElement(StandardStructureTypes.P, docElement)
                        paragraph.page = page
                        docElement.appendKid(paragraph)
                        for (piece in paragraphPieces) {
                            val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                            content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                            content.paint(piece)
                            content.endMarkedContent()
                            paragraph.appendKid(PDMarkedContent(COSName.P, properties))
                            mcid++
                        }
                    }
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }
}
