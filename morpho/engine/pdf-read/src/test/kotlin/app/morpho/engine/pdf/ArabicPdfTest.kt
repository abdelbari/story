package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextDirection
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Arabic through the untagged reader, end to end.
 *
 * Every other test in this module is Latin, and that is exactly how a
 * reversal bug reached a user: the logic had unit tests, but nothing built
 * an Arabic PDF and read it back, so nobody noticed the words were being
 * assembled left to right. These build one the way a real right-to-left
 * producer does — words placed right to left across the page, glyphs inside
 * each word painted left to right — and assert the logical text comes back.
 */
class ArabicPdfTest {

    private val title = "الاستمارة"
    private val inWord = "في"
    private val research = "البحث"

    @Test
    fun `a right-to-left line comes back in the order it was written`() {
        val pdf = rtlPdf(listOf(listOf(title, inWord, research)))
        val text = paragraphText(pdf)
        assertEquals("$title $inWord $research", text)
    }

    @Test
    fun `the paragraph is marked right-to-left`() {
        val pdf = rtlPdf(listOf(listOf(title, inWord, research)))
        val paragraph = paragraphs(pdf).first()
        assertEquals(TextDirection.RTL, paragraph.style.direction)
    }

    @Test
    fun `a latin word inside an arabic line keeps its own direction`() {
        // Painted between two Arabic words but left to right itself, the way
        // a citation or an address sits inside an Arabic sentence.
        val pdf = rtlPdf(listOf(listOf(title, "Morpho", research)))
        val text = paragraphText(pdf)
        assertTrue(text.contains("Morpho"), "latin run lost: $text")
        assertEquals("$title Morpho $research", text)
    }

    @Test
    fun `arabic survives with no reversed word left in the output`() {
        val pdf = rtlPdf(listOf(listOf(title, inWord, research)))
        val text = paragraphText(pdf)
        assertTrue(
            !text.contains(title.reversed()) && !text.contains(research.reversed()),
            "text still holds a reversed word: $text",
        )
    }

    private fun paragraphs(pdf: ByteArray) =
        PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>()

    private fun paragraphText(pdf: ByteArray) =
        paragraphs(pdf).joinToString(separator = " ") { it.text }.trim()

    /**
     * An untagged PDF whose content stream is in painting order: each line's
     * words run right to left, and each word's glyphs are painted left to
     * right, which for right-to-left script means reversed.
     */
    private fun rtlPdf(lines: List<List<String>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val font = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")
                    ?: error("test font missing"),
            )
            PDPageContentStream(document, page).use { content ->
                var y = 700f
                for (words in lines) {
                    var x = RIGHT_EDGE
                    for (word in words) {
                        // Latin runs are painted in their own order; Arabic
                        // is painted in the order the glyphs appear on paper.
                        val painted = if (isRtl(word)) word.reversed() else word
                        val width = font.getStringWidth(painted) / 1000f * SIZE
                        x -= width
                        content.beginText()
                        content.setFont(font, SIZE)
                        content.newLineAtOffset(x, y)
                        content.showText(painted)
                        content.endText()
                        x -= WORD_GAP
                    }
                    y -= LINE_GAP
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }

    /** Hebrew through Arabic Extended-A, enough for what these tests draw. */
    private fun isRtl(word: String) = word.any { it in '\u0590'..'\u08FF' }

    private companion object {
        const val RIGHT_EDGE = 500f
        const val SIZE = 14f
        const val WORD_GAP = 6f
        const val LINE_GAP = 30f
    }

    @Test
    fun `a tagged tree keeps its own word order and only rebuilds the words`() {
        // The structure tree lists content in reading order, so the words are
        // already right and only their letters are painted backwards.
        // Reconstructing the whole line here would reverse the tree's order:
        // a real bibliography entry came back publisher-first, author-last.
        val pdf = taggedArabicPdf(listOf(title, inWord, research))
        val text = paragraphs(pdf).first().text
        assertEquals("$title $inWord $research", text)
    }

    @Test
    fun `a number inside tagged arabic keeps its digits in order`() {
        // Reversing "2005" is wrong whichever way the line runs.
        val pdf = taggedArabicPdf(listOf(title, "2005", research))
        val text = paragraphs(pdf).first().text
        assertTrue(text.contains("2005"), "digits were reordered: $text")
    }

    /**
     * A tagged PDF shaped like the ones Word produces for Arabic: the
     * structure tree lists the words in reading order, and each word's
     * glyphs are painted left to right, so its letters arrive reversed.
     */
    private fun taggedArabicPdf(logicalWords: List<String>, asOneBlock: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val font = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")
                    ?: error("test font missing"),
            )
            val root = PDStructureTreeRoot()
            document.documentCatalog.structureTreeRoot = root
            val docElement = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
            // Without a page the marked-content ids cannot be resolved and the
            // reader falls back to the untagged heuristics, silently testing
            // the wrong path.
            docElement.page = page
            root.appendKid(docElement)
            val paragraph = PDStructureElement(StandardStructureTypes.P, docElement)
            paragraph.page = page
            docElement.appendKid(paragraph)

            PDPageContentStream(document, page).use { content ->
                if (asOneBlock) {
                    // Visual order of the whole line, painted once, left to
                    // right: the logically last word comes first.
                    val visual = logicalWords.reversed()
                        .joinToString(" ") { if (isRtl(it)) it.reversed() else it }
                    val width = font.getStringWidth(visual) / 1000f * SIZE
                    val properties = COSDictionary().apply { setInt(COSName.MCID, 0) }
                    content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                    content.beginText()
                    content.setFont(font, SIZE)
                    content.newLineAtOffset(RIGHT_EDGE - width, 700f)
                    content.showText(visual)
                    content.endText()
                    content.endMarkedContent()
                    paragraph.appendKid(PDMarkedContent(COSName.P, properties))
                    return@use
                }
                var x = RIGHT_EDGE
                for ((mcid, word) in logicalWords.withIndex()) {
                    // Real exporters emit the space between words as a glyph
                    // of its own. On a right-to-left line the next word sits
                    // to the LEFT, so the separator is painted first — glyphs
                    // advance rightward, which puts it at the word's left edge.
                    val separator = if (mcid < logicalWords.size - 1) " " else ""
                    val painted = separator + (if (isRtl(word)) word.reversed() else word)
                    val width = font.getStringWidth(painted) / 1000f * SIZE
                    x -= width
                    val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                    content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                    content.beginText()
                    content.setFont(font, SIZE)
                    content.newLineAtOffset(x, 700f)
                    content.showText(painted)
                    content.endText()
                    content.endMarkedContent()
                    // Appended in reading order, which is what a tagged tree does.
                    paragraph.appendKid(PDMarkedContent(COSName.P, properties))
                    x -= WORD_GAP
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }


    @Test
    fun `the tagged fixture really takes the tagged path`() {
        val pdf = taggedArabicPdf(listOf(title, inWord, research))
        val inspection = PdfReader().inspect(pdf)
        val confidence = paragraphs(pdf).first().confidence
        assertTrue(inspection.isTagged, "fixture is not tagged at all")
        assertEquals(0.9f, confidence, "fell back to the untagged heuristics")
    }


    @Test
    fun `a run painted as one left-to-right block is read off the page, not the stream`() {
        // The other way a producer paints an Arabic paragraph: one text
        // operation, glyphs advancing left to right across the whole line,
        // so content order is visual — the first word in the stream is the
        // last one read. The same document that positions its short runs
        // word by word does this for its long ones, and a rule about content
        // order that suits one is backwards for the other. Position is not.
        val pdf = taggedArabicPdf(listOf(title, inWord, research), asOneBlock = true)
        assertEquals("$title $inWord $research", paragraphs(pdf).first().text)
    }

}
