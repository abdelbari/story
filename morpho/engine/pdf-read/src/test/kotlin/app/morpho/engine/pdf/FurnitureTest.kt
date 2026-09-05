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

    /**
     * A mark drawn in the band that no reading of its words accounts for:
     * a logo beside the running head, or — as in the paper this was
     * written for — the letters of the head drawn as outlines rather than
     * set in type. It is what makes a picture of the band the only honest
     * answer, and a band without one is a band the words are all of.
     */
    private val headBlot = Bar(400f, 34f, 460f, 44f)
    private val footBlot = Bar(200f, 794f, 260f, 804f)

    @Test
    fun `a head the page's own words account for comes back as words`() {
        val pdf = tagged("en") { pages(numbers = listOf(48, 49, 50), numberX = 60f) }
        val model = PdfReader().extract(pdf)
        val head = model.header.single() as? Paragraph
        assertNotNull(head, "the head is not words: ${model.header}")
        assertEquals(headline, head!!.text.trim())
        assertTrue(head.runs.none { it.image != null }, "a picture of words that could be read")
        // The line the page ruled under the head is ruled under it again,
        // as a border of the paragraph rather than printed into a picture.
        assertTrue(head.style.ruleBelow, "the rule under the head was lost")
        val page = model.pageSetup!!
        assertTrue(page.headerDistancePt!! in 30f..40f, "head distance ${page.headerDistancePt}")
        assertTrue(page.marginTopPt > 80f, "top margin ${page.marginTopPt}")
    }

    @Test
    fun `a foot the page's own words account for comes back as words`() {
        val pdf = tagged("en") { pages(numbers = listOf(48, 49, 50), numberX = 60f) }
        val model = PdfReader().extract(pdf)
        val foot = model.footer.single() as? Paragraph
        assertNotNull(foot, "the foot is not words: ${model.footer}")
        assertTrue(foot!!.runs.none { it.image != null }, "a picture of words that could be read")
        assertTrue(foot.text.contains("Volume 12"), "the foot's own line: ${foot.text}")
        val field = foot.runs.single { it.field == RunField.PAGE_NUMBER }
        assertEquals("48", field.text)
        assertEquals(48, model.pageSetup!!.firstPageNumber)
    }

    @Test
    fun `the running head becomes a picture the size it had, at its distance from the edge`() {
        val pdf = tagged("en") { pages(numbers = listOf(48, 49, 50), numberX = 60f, drawn = true) }
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
    fun `a head that had to be photographed still says what it said`() {
        // The band holds a mark no reading of its words accounts for, so
        // it comes back as a picture. The words it did hold are then
        // nowhere in the document: not searchable, not read aloud, gone.
        // They are kept as what the picture shows, which is where a reader
        // and a screen reader both look.
        val pdf = tagged("en") { pages(numbers = listOf(48, 49, 50), numberX = 60f, drawn = true) }
        val head = PdfReader().extract(pdf).header.single() as ImageBlock
        assertEquals(headline, head.description)
    }

    @Test
    fun `a foot that gave up only its digits is not described by them`() {
        // A band whose words are drawn as outlines yields its digits and
        // nothing else. Read aloud that is noise, and noise offered as a
        // description says the picture has been accounted for when it has
        // not.
        val pdf = tagged("en") { pages(numbers = listOf(2024, 2024, 2024), numberX = 60f, drawn = true) }
        val foot = PdfReader().extract(pdf).footer.single() as ImageBlock
        assertTrue(
            foot.description == null || foot.description!!.any { it.isLetter() },
            "the foot was described as \"${foot.description}\"",
        )
    }

    @Test
    fun `a number that advances by one each page is the page number, written as a field`() {
        val pdf = tagged("en") { pages(numbers = listOf(48, 49, 50), numberX = 60f, drawn = true) }
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
        val pdf = tagged("ar") { pages(numbers = listOf(48, 49, 50), numberX = 480f, arabic = true, drawn = true) }
        val model = PdfReader().extract(pdf)
        val foot = model.footer.single() as Paragraph
        assertEquals(RunField.PAGE_NUMBER, foot.runs.first().field)
        assertEquals("48", foot.runs.first().text)
        assertEquals(48, model.pageSetup!!.firstPageNumber)
        assertTrue(foot.runs.any { it.image != null }, "the words of the foot were lost")
    }

    @Test
    fun `a number that stays put is not a page number`() {
        val pdf = tagged("en") { pages(numbers = listOf(2024, 2024, 2024), numberX = 60f, drawn = true) }
        val model = PdfReader().extract(pdf)
        assertTrue(model.footer.single() is ImageBlock, "a year became a page number: ${model.footer}")
        assertEquals(1, model.pageSetup!!.firstPageNumber)
    }

    @Test
    fun `a number in the middle is masked out of the picture and set beneath it, centred`() {
        val pdf = tagged("en") { pages(numbers = listOf(7, 8, 9), numberX = 295f, drawn = true) }
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

    /**
     * Three pages with the same head, a foot with a volume line, and the
     * given page numbers at [numberX]. With [drawn], each band also holds
     * a mark the words do not account for, so the band comes back as the
     * picture of itself that such a band needs.
     */
    private fun pages(
        numbers: List<Int>,
        numberX: Float,
        arabic: Boolean = false,
        drawn: Boolean = false,
    ): List<Sheet> =
        numbers.mapIndexed { index, number ->
            val volume = if (arabic) Piece("المجلد ١٢", 60f, 802f, 10f, arabic = true) else Piece("Volume 12", 400f, 802f, 10f)
            Sheet(
                body = body(index, arabic),
                furniture = listOf(
                    Furniture(
                        atTop = true,
                        pieces = listOf(Piece(headline, 60f, 42f, 9f)),
                        bars = listOf(headBar) + if (drawn) listOf(headBlot) else emptyList(),
                    ),
                    Furniture(
                        atTop = false,
                        pieces = listOf(Piece(number.toString(), numberX, 802f, 10f), volume),
                        bars = if (drawn) listOf(footBlot) else emptyList(),
                    ),
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
    /**
     * A tagged book: the right-hand pages headed by the chapter and the
     * left by the book, and each numbered at its own outer edge. Both
     * repeat, so both are marked as pagination artifacts, and a reader
     * that keeps one page's worth of them keeps one and loses the other.
     */
    private fun opening(): List<Sheet> = List(6) { index ->
        val onTheRight = index % 2 == 0
        val head = if (onTheRight) "Chapter Three: Instruments" else "A History of the Sciences"
        Sheet(
            body = body(index),
            furniture = listOf(
                Furniture(atTop = true, pieces = listOf(Piece(head, 60f, 42f, 9f)), bars = listOf(headBar)),
                Furniture(
                    atTop = false,
                    pieces = listOf(Piece((index + 1).toString(), if (onTheRight) 500f else 60f, 802f, 10f)),
                ),
            ),
        )
    }

    @Test
    fun `both sides of a tagged opening keep their own head`() {
        val model = PdfReader().extract(tagged("en") { opening() })
        assertTrue(model.header.isNotEmpty(), "the right-hand pages'")
        assertTrue(model.evenHeader.isNotEmpty(), "and the left-hand pages'")
        // Each side keeps its own, which is to say the two do not say the
        // same thing.
        val right = (model.header.single() as Paragraph).text.trim()
        val left = (model.evenHeader.single() as Paragraph).text.trim()
        assertEquals("Chapter Three: Instruments", right)
        assertEquals("A History of the Sciences", left)
    }

    @Test
    fun `a paper that heads every page alike keeps one head, not two`() {
        val model = PdfReader().extract(tagged("en") { pages(numbers = listOf(48, 49, 50), numberX = 60f) })
        assertTrue(model.header.isNotEmpty())
        assertTrue(model.evenHeader.isEmpty(), "there is nothing different about the left-hand pages")
        assertTrue(model.evenFooter.isEmpty())
    }

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
