package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextDirection
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
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
    /**
     * [subset] false embeds the whole font. PDFBox's subsetter drops the
     * cmap table, and a font with no cmap cannot overrule a broken
     * ToUnicode — so the corruption test needs the font as a real
     * exporter embeds it, cmap and all.
     */
    /**
     * One untagged page painting الجزائر as two operations whose glyphs
     * overlap by a fraction of a point, the way the source document does.
     */
    /**
     * One untagged page painting a right-to-left line as two words: the
     * first at the right, the second a word's width to its left, each
     * painted left to right as a producer paints them.
     */
    private fun twoWordPdf(): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            document.documentCatalog.language = "ar"
            val font = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")
                    ?: error("test font missing"),
                false,
            )
            val second = "في".reversed()
            val secondWidth = font.getStringWidth(second) / 1000f * SIZE
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(font, SIZE)
                content.newLineAtOffset(300f, 700f)
                content.showText("الجزائر".reversed())
                content.endText()
                content.beginText()
                content.setFont(font, SIZE)
                content.newLineAtOffset(300f - secondWidth - WORD_GAP, 700f)
                content.showText(second)
                content.endText()
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }

    private fun kernedPdf(gap: Float): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            document.documentCatalog.language = "ar"
            val font = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")
                    ?: error("test font missing"),
                false,
            )
            // Visual order, left to right: the word reversed, split where
            // the two halves overlap.
            val visual = "الجزائر".reversed()
            val head = visual.substring(0, 3)
            val tail = visual.substring(3)
            val headWidth = font.getStringWidth(head) / 1000f * SIZE
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(font, SIZE)
                content.newLineAtOffset(200f, 700f)
                content.showText(head)
                content.endText()
                content.beginText()
                content.setFont(font, SIZE)
                content.newLineAtOffset(200f + headWidth + gap, 700f)
                content.showText(tail)
                content.endText()
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }

    private fun taggedArabicPdf(
        logicalWords: List<String>,
        asOneBlock: Boolean = false,
        subset: Boolean = true,
        language: String? = "ar",
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val font = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf")
                    ?: error("test font missing"),
                subset,
            )
            val root = PDStructureTreeRoot()
            document.documentCatalog.structureTreeRoot = root
            if (language != null) document.documentCatalog.language = language
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
    fun `a kerned glyph keeps its place in the word on the untagged path`() {
        // Painted in content order — ز then ا, the ا a fraction of a point
        // to the left — and sorted strictly by x the two come back swapped,
        // which is how الجزائر became الجازئر.
        assertEquals("الجزائر", paragraphText(kernedPdf(gap = -0.4f)))
    }

    @Test
    fun `a real step backwards is still the next word`() {
        // A right-to-left line places its next word to the left: a step
        // backwards of a whole word, which the kerning rule must not treat
        // as a hair and glue on.
        assertEquals("الجزائر في", paragraphText(twoWordPdf()))
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


    @Test
    fun `a broken ToUnicode map is overruled by the embedded font`() {
        // Word 2010 writes a corrupt ToUnicode over a sound font: on a real
        // paper the medial lam was labelled meem and the digit 0 labelled 5.
        // The font's own cmap is the authority when the two disagree.
        val broken = corruptToUnicode(taggedArabicPdf(listOf(title, inWord, research), subset = false))
        assertEquals("$title $inWord $research", paragraphs(broken).first().text)
    }

    @Test
    fun `two wrong entries are enough for the font to be believed`() {
        // The bold face of a real paper had three: the digits 1 and 0
        // swapped and the medial lam called a meem — and that lam is in
        // every other word. A rule that waited for a share of the map to
        // be wrong repaired the paper on one PDF library and not another,
        // because the two fill the map's gaps differently.
        val broken = corruptToUnicode(taggedArabicPdf(listOf(title, inWord, research), subset = false), limit = 2)
        assertEquals("$title $inWord $research", paragraphs(broken).first().text)
    }

    @Test
    fun `once a font is believed, a colon its map calls a digit is a colon`() {
        // The regular face of the same paper named its colon glyph "4", so
        // every "ملخص:" read "ملخص4". A digit for a colon is no evidence on
        // its own — but a font already known to be broken is wrong about
        // that glyph too.
        val pdf = taggedArabicPdf(listOf("$title:", inWord, research), subset = false)
        var shifted = 0
        val broken = rewriteToUnicode(pdf) { text ->
            when {
                text == ":" -> "4"
                text.length == 1 && text[0] in '\u0600'..'\u06FF' && shifted < 3 -> { shifted++; (text[0] + 1).toString() }
                else -> text
            }
        }
        assertEquals("$title: $inWord $research", paragraphs(broken).first().text)
    }

    /**
     * The PDF with every font's ToUnicode map written afresh, entry by
     * entry, as [mutate] rewrites it — so a single glyph can be mislabelled
     * whatever ranges the original map grouped it into.
     */
    private fun rewriteToUnicode(pdf: ByteArray, mutate: (String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument.load(pdf).use { document ->
            val page = document.getPage(0)
            for (name in page.resources.fontNames) {
                val font = page.resources.getFont(name)
                val entries = (0 until 0x10000).mapNotNull { code ->
                    val text = runCatching { font.toUnicode(code) }.getOrNull() ?: return@mapNotNull null
                    code to mutate(text)
                }
                assertTrue(entries.size > 50, "fixture font maps only ${'$'}{entries.size} codes")
                val cmap = buildString {
                    append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                    append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
                    append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
                    for (chunk in entries.chunked(100)) {
                        append(chunk.size).append(" beginbfchar\n")
                        for ((code, text) in chunk) {
                            append("<%04X> <".format(code))
                            for (unit in text) append("%04X".format(unit.code))
                            append(">\n")
                        }
                        append("endbfchar\n")
                    }
                    append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
                }
                val stream = document.document.createCOSStream()
                stream.createOutputStream().use { it.write(cmap.toByteArray(Charsets.ISO_8859_1)) }
                font.cosObject.setItem(COSName.TO_UNICODE, stream)
            }
            document.save(out)
        }
        return out.toByteArray()
    }

    @Test
    fun `a healthy ToUnicode map is left alone`() {
        // The corrector must not engage on a font whose maps agree.
        val healthy = taggedArabicPdf(listOf(title, "Morpho", research))
        assertEquals("$title Morpho $research", paragraphs(healthy).first().text)
    }

    /**
     * Shifts every Arabic code point in the fonts' ToUnicode maps one along,
     * the way Word 2010 mislabels its subsets. The glyphs and the embedded
     * font's own cmap are untouched, so the page still renders correctly —
     * only the text a reader is told about is wrong.
     */
    /**
     * The PDF with its ToUnicode map broken the way Word 2010 breaks one:
     * each Arabic letter labelled as the next, for the first [limit]
     * entries — all of them by default.
     */
    private fun corruptToUnicode(pdf: ByteArray, limit: Int = Int.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument.load(pdf).use { document ->
            var rewritten = 0
            val page = document.getPage(0)
            for (name in page.resources.fontNames) {
                val font = page.resources.getFont(name)
                val stream = font.cosObject.getDictionaryObject(COSName.TO_UNICODE) as? COSStream ?: continue
                val text = stream.createInputStream().use { it.readBytes().toString(Charsets.ISO_8859_1) }
                val corrupted = Regex("<06([0-9A-Fa-f]{2})>").replace(text) { match ->
                    if (rewritten >= limit) return@replace match.value
                    rewritten++
                    "<%04X>".format(0x0600 + match.groupValues[1].toInt(16) + 1)
                }
                stream.createOutputStream().use { it.write(corrupted.toByteArray(Charsets.ISO_8859_1)) }
            }
            assertTrue(rewritten >= minOf(limit, 5), "fixture corrupted only $rewritten mappings")
            document.save(out)
        }
        return out.toByteArray()
    }


    @Test
    fun `an arabic line whose leftmost word is latin still reads arabic-first`() {
        // The affiliation line of a real paper: "جامعة الوادي، nebbar@…" —
        // painted, its leftmost glyph is the "n" of the address. Read on its
        // own the line looks left-to-right; the document says otherwise.
        val pdf = taggedArabicPdf(listOf(title, "nebbar@example.com"), language = "ar-DZ")
        assertEquals("$title nebbar@example.com", paragraphs(pdf).first().text)
    }

    @Test
    fun `without a language tag the document's own text decides its direction`() {
        // Sixteen Arabic letters to six Latin ones: the document is Arabic.
        // (A longer address ties the count, and a tie proves nothing.)
        val pdf = taggedArabicPdf(listOf(title, inWord, research, "nb@ex.com"), language = null)
        assertEquals("$title $inWord $research nb@ex.com", paragraphs(pdf).first().text)
    }

}
