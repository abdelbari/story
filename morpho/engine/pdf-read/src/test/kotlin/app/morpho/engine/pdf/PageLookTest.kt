package app.morpho.engine.pdf

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Paragraph
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
import kotlin.math.abs

/**
 * What the page shows besides its words, read back through the tagged
 * reader: the weight and size of each run, raised footnote marks, where a
 * paragraph sits between the margins, the spacing between paragraphs, the
 * tab stops a line was set to, and the page itself. A conversion that gets
 * every word right and loses all of this still does not look like the
 * document it came from.
 */
class PageLookTest {

    /** One text operation: [text] in logical order, painted at ([x], [y]) in [font] at [size]. */
    private class Piece(val text: String, val x: Float, val y: Float, val font: (PDDocument) -> PDFont, val size: Float = 12f)

    private val arabic: (PDDocument) -> PDFont = { document ->
        PDType0Font.load(
            document,
            javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf") ?: error("test font missing"),
            true,
        )
    }
    private val regular: (PDDocument) -> PDFont = { PDType1Font.HELVETICA }
    private val bold: (PDDocument) -> PDFont = { PDType1Font.HELVETICA_BOLD }

    @Test
    fun `a painted space with no room between its neighbours is not a word break`() {
        // Word's Arabic justification leaves a space glyph inside a word:
        // خطوات painted as خط, a space, and وات, with the و and the ط
        // touching. The page shows one word.
        val pdf = tagged { document ->
            val font = arabic(document)
            val tail = width(font, "تاو")
            listOf(
                listOf(
                    Piece("وات", 300f, 700f, arabic),
                    Piece(" ", 300f + tail - 2f, 700f, arabic),
                    Piece("خط", 300f + tail + 0.2f, 700f, arabic),
                )
            )
        }
        assertEquals("خطوات", paragraphs(pdf).single().text)
    }

    @Test
    fun `a painted space with room between its neighbours is still a word break`() {
        val pdf = tagged { document ->
            val font = arabic(document)
            val tail = width(font, "تاو")
            val space = width(font, " ")
            listOf(
                listOf(
                    Piece("وات", 300f, 700f, arabic),
                    Piece(" ", 300f + tail, 700f, arabic),
                    Piece("خط", 300f + tail + space, 700f, arabic),
                )
            )
        }
        assertEquals("خط وات", paragraphs(pdf).single().text)
    }

    @Test
    fun `a smaller glyph raised off the baseline becomes a superscript run`() {
        // A footnote mark after an author's name: set smaller, five points
        // up, to the left of the name on a right-to-left line.
        val pdf = tagged { document ->
            val font = arabic(document)
            val name = width(font, "ربيحة")
            listOf(
                listOf(
                    Piece("ربيحة", 300f, 700f, arabic),
                    Piece("1", 300f - 8f, 695f, arabic, size = 8f),
                )
            )
        }
        val runs = paragraphs(pdf).single().runs
        assertTrue(runs.size >= 2, "expected a run of its own for the mark: $runs")
        assertEquals("1", runs.last().text.trim())
        assertTrue(runs.last().superscript, "mark not raised: $runs")
        assertTrue(runs.first().text.startsWith("ربيحة"), "name lost: $runs")
        assertTrue(runs.none { it.text.contains("ربيحة") && it.superscript }, "name raised too: $runs")
        assertEquals(8f, runs.last().fontSizePt)
        assertEquals(12f, runs.first().fontSizePt)
    }

    @Test
    fun `bold and regular glyphs on one line become separate runs`() {
        val pdf = tagged(language = "en") { document ->
            val label = width(PDType1Font.HELVETICA_BOLD, "Abstract:")
            listOf(
                listOf(
                    Piece("Abstract:", 60f, 700f, bold),
                    Piece(" One condition", 60f + label, 700f, regular),
                )
            )
        }
        val runs = paragraphs(pdf).single().runs
        assertEquals(listOf("Abstract:", " One condition"), runs.map { it.text })
        assertEquals(listOf(true, false), runs.map { it.bold })
        assertEquals("Helvetica-Bold", runs[0].fontFamily)
        assertEquals("Helvetica", runs[1].fontFamily)
    }

    @Test
    fun `a centred line is centred and a flush line with an indent is not`() {
        // Against the text block, not the sheet: the first line reaches
        // from 60 to 500, so the block's middle is 280 while the page's is
        // 297.
        val pdf = tagged { document ->
            val font = arabic(document)
            val title = width(font, "العنوان")
            val word = width(font, "كلمة")
            val short = width(font, "كلمة كلمة كلمة")
            listOf(
                listOf(Piece("كلمة", 500f - word, 700f, arabic), Piece("كلمة", 60f, 700f, arabic)),
                listOf(Piece("كلمة كلمة كلمة", 500f - 36f - short, 680f, arabic)),
                listOf(Piece("العنوان", 280f - title / 2, 660f, arabic)),
            )
        }
        val (flush, indented, centred) = paragraphs(pdf)
        assertNull(flush.style.alignment)
        assertNull(flush.style.firstLineIndentPt)
        assertNull(indented.style.alignment, "an indented line is not centred")
        assertNear(36f, indented.style.firstLineIndentPt, "first-line indent")
        assertEquals(Alignment.CENTER, centred.style.alignment)
    }

    @Test
    fun `the space between paragraphs and the pitch of their lines are measured`() {
        val pdf = tagged { document ->
            val font = arabic(document)
            val line = "كلمة ".repeat(10).trim()
            val full = width(font, line)
            fun paragraph(top: Float) = List(3) { Piece(line, 500f - full, top + it * 14f, arabic) }
            // Three lines fourteen points apart, then twenty points of air
            // before the next paragraph's first line.
            listOf(paragraph(700f), paragraph(700f + 2 * 14f + 14f + 20f))
        }
        val (first, second) = paragraphs(pdf)
        assertNear(14f, first.style.linePitchPt, "pitch")
        assertNear(20f, first.style.spaceAfterPt, "space after")
        assertEquals(0f, second.style.spaceAfterPt, "nothing follows the last paragraph")
    }

    @Test
    fun `a stretch of spaces is a tab with a stop where the text after it starts`() {
        val pdf = tagged { document ->
            val font = arabic(document)
            // Painted as one operation, left to right: the logically later
            // word, five spaces, the logically earlier one.
            listOf(listOf(Piece("أ     ب", 300f, 700f, arabic)))
        }
        val paragraph = paragraphs(pdf).single()
        assertEquals("أ\tب", paragraph.text)
        val stops = paragraph.style.tabStopsPt
        assertNotNull(stops, "no tab stops")
        // From the block's right edge to the right edge of ب: the five
        // spaces and the أ.
        PDDocument.load(pdf).use { document ->
            val font = arabic(document)
            assertNear(width(font, "     أ"), stops!!.single(), "tab stop")
        }
    }

    @Test
    fun `a rule drawn across the page goes to the paragraph it belongs to`() {
        // A line under a paper's dates and the separator above its
        // footnote: each belongs to the paragraph it sits nearer.
        val pdf = tagged(
            rules = { page ->
                listOf(
                    Rule(page.mediaBox.height - 690f, 60f, 500f),
                    Rule(page.mediaBox.height - 754f, 60f, 260f),
                )
            }
        ) { document ->
            val font = arabic(document)
            fun right(text: String, y: Float, size: Float = 12f) =
                listOf(Piece(text, 500f - width(font, text, size), y, arabic, size))
            listOf(right("تاريخ الاستلام", 680f), right("ملخص: من شروط البحث", 720f), right("المؤلف المرسل", 760f))
        }
        val (dates, abstract, footnote) = paragraphs(pdf)
        assertTrue(dates.style.ruleBelow, "no rule under the dates")
        assertTrue(!dates.style.ruleAbove)
        assertTrue(!abstract.style.ruleAbove && !abstract.style.ruleBelow, "the abstract has no rule of its own")
        assertTrue(footnote.style.ruleAbove, "no separator above the footnote")
    }

    @Test
    fun `a rule in a running header is not a paragraph's`() {
        val pdf = tagged(
            artifactRules = { page -> listOf(Rule(page.mediaBox.height - 706f, 60f, 500f)) }
        ) { document ->
            val font = arabic(document)
            val text = "ملخص: من شروط البحث"
            listOf(listOf(Piece(text, 500f - width(font, text), 700f, arabic)))
        }
        val paragraph = paragraphs(pdf).single()
        assertTrue(!paragraph.style.ruleAbove && !paragraph.style.ruleBelow, "an artifact's rule was taken")
    }

    @Test
    fun `the page the source was set on comes across with its margins`() {
        val pdf = tagged { document ->
            val font = arabic(document)
            val line = "كلمة ".repeat(12).trim()
            listOf(listOf(Piece(line, 500f - width(font, line), 700f, arabic)))
        }
        val page = PdfReader().extract(pdf).pageSetup
        assertNotNull(page, "no page setup")
        assertNear(PDRectangle.A4.width, page!!.widthPt, "width")
        assertNear(PDRectangle.A4.height, page.heightPt, "height")
        assertNear(PDRectangle.A4.width - 500f, page.marginRightPt, "right margin", tolerance = 1.5f)
        assertTrue(page.marginLeftPt > 50f, "left margin ${page.marginLeftPt}")
    }

    private fun assertNear(expected: Float, actual: Float?, what: String, tolerance: Float = 1f) {
        assertNotNull(actual, "$what missing")
        assertTrue(abs(expected - actual!!) <= tolerance, "$what: expected $expected, was $actual")
    }

    private fun width(font: PDFont, text: String, size: Float = 12f): Float =
        font.getStringWidth(text) / 1000f * size

    private fun paragraphs(pdf: ByteArray): List<Paragraph> =
        PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>()

    private fun isRtl(text: String): Boolean = text.any {
        Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    /**
     * One A4 page whose structure tree holds one P per inner list, each
     * piece painted under a marked-content id of its own. Right-to-left
     * text is painted reversed, glyphs left to right, as a real producer
     * paints it.
     */
    /** A horizontal line at [y] in user space (from the bottom of the page). */
    private class Rule(val y: Float, val from: Float, val to: Float)

    private fun tagged(
        language: String? = "ar",
        rules: (PDPage) -> List<Rule> = { emptyList() },
        artifactRules: (PDPage) -> List<Rule> = { emptyList() },
        pieces: (PDDocument) -> List<List<Piece>>,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val root = PDStructureTreeRoot()
            document.documentCatalog.structureTreeRoot = root
            if (language != null) document.documentCatalog.language = language
            val docElement = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
            docElement.page = page
            root.appendKid(docElement)
            val fonts = HashMap<(PDDocument) -> PDFont, PDFont>()
            var mcid = 0
            PDPageContentStream(document, page).use { content ->
                for (rule in rules(page)) {
                    content.moveTo(rule.from, rule.y)
                    content.lineTo(rule.to, rule.y)
                    content.stroke()
                }
                for (rule in artifactRules(page)) {
                    // Inside a pagination artifact, the way a running
                    // header's rule is drawn.
                    content.beginMarkedContent(COSName.getPDFName("Artifact"))
                    content.moveTo(rule.from, rule.y)
                    content.lineTo(rule.to, rule.y)
                    content.stroke()
                    content.endMarkedContent()
                }
                for (paragraphPieces in pieces(document)) {
                    val paragraph = PDStructureElement(StandardStructureTypes.P, docElement)
                    paragraph.page = page
                    docElement.appendKid(paragraph)
                    for (piece in paragraphPieces) {
                        val font = fonts.getOrPut(piece.font) { piece.font(document) }
                        val painted = if (isRtl(piece.text)) piece.text.reversed() else piece.text
                        val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                        content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                        content.beginText()
                        content.setFont(font, piece.size)
                        content.newLineAtOffset(piece.x, PDRectangle.A4.height - piece.y)
                        content.showText(painted)
                        content.endText()
                        content.endMarkedContent()
                        paragraph.appendKid(PDMarkedContent(COSName.P, properties))
                        mcid++
                    }
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }
}
