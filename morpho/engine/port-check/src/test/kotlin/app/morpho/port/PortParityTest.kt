package app.morpho.port

import app.morpho.engine.layout.Paragraph
import app.morpho.pdf.AndroidStructureTreeReader
import com.tom_roush.pdfbox.pdmodel.PDDocument as PortDocument
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * The app's own Android reader, run against the real PDFBox-Android port.
 *
 * The engine's tests prove what the desktop library reads. They cannot
 * prove what the phone reads, and the two have already differed where it
 * mattered: a repair of a corrupt font map counted the evidence one way on
 * the desktop and another on the port, so a paper's bold words came out
 * right on a laptop and wrong in the reader's hand. These tests read a
 * fixture with the port itself.
 */
class PortParityTest {

    private val title = "الاستمارة"
    private val inWord = "في"
    private val research = "البحث"

    @Test
    fun `the port reads a tagged arabic page in the order it was written`() {
        val model = readWithPort(taggedArabicPdf(listOf(title, inWord, research)))
        val paragraph = model.blocks.filterIsInstance<Paragraph>().single()
        assertEquals("$title $inWord $research", paragraph.text)
    }

    @Test
    fun `the port overrules a broken font map the same way the desktop does`() {
        // Word 2010 writes a corrupt ToUnicode over a sound font: the
        // medial lam labelled meem, the digit 0 labelled 5. Whether the
        // repair engages depends on how a library answers for a code the
        // map never had, and the two libraries answer differently — which
        // is the whole reason this test exists.
        val broken = corruptToUnicode(taggedArabicPdf(listOf(title, inWord, research), subset = false))
        val paragraph = readWithPort(broken).blocks.filterIsInstance<Paragraph>().single()
        assertEquals("$title $inWord $research", paragraph.text)
    }

    @Test
    fun `the port resolves a symbol font's own codes to the characters they stand for`() {
        assertEquals("•", app.morpho.pdf.SymbolFonts.character("ABCDEE+Symbol", ""))
        assertEquals("−", app.morpho.pdf.SymbolFonts.character("ABCDEE+Symbol", ""))
    }

    /** Reads [pdf] with the port's own document and the app's Android reader. */
    private fun readWithPort(pdf: ByteArray) =
        PortDocument.load(pdf.inputStream()).use { document ->
            AndroidStructureTreeReader.read(document, emptyList())
                ?: error("the port's tagged reader read nothing")
        }

    /** Shifts every Arabic code point in the ToUnicode maps one along, as Word 2010 does. */
    private fun corruptToUnicode(pdf: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument.load(pdf).use { document ->
            var rewritten = 0
            val page = document.getPage(0)
            for (name in page.resources.fontNames) {
                val font = page.resources.getFont(name)
                val stream = font.cosObject.getDictionaryObject(COSName.TO_UNICODE) as? COSStream ?: continue
                val text = stream.createInputStream().use { it.readBytes().toString(Charsets.ISO_8859_1) }
                val corrupted = Regex("<06([0-9A-Fa-f]{2})>").replace(text) { match ->
                    rewritten++
                    "<%04X>".format(0x0600 + match.groupValues[1].toInt(16) + 1)
                }
                stream.createOutputStream().use { it.write(corrupted.toByteArray(Charsets.ISO_8859_1)) }
            }
            assertTrue(rewritten >= 5, "fixture corrupted only $rewritten mappings")
            document.save(out)
        }
        return out.toByteArray()
    }

    /**
     * A tagged Arabic page, written with desktop PDFBox: one paragraph
     * whose words are painted right to left, each under a marked-content
     * id of its own, the way a producer paints one.
     */
    private fun taggedArabicPdf(words: List<String>, subset: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val font = PDType0Font.load(
                document,
                javaClass.getResourceAsStream("/fonts/NotoNaskhArabic-Regular.ttf") ?: error("test font missing"),
                subset,
            )
            val root = PDStructureTreeRoot()
            document.documentCatalog.structureTreeRoot = root
            document.documentCatalog.language = "ar"
            val docElement = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
            docElement.page = page
            root.appendKid(docElement)
            val paragraph = PDStructureElement(StandardStructureTypes.P, docElement)
            paragraph.page = page
            docElement.appendKid(paragraph)
            PDPageContentStream(document, page).use { content ->
                var x = 400f
                for ((mcid, word) in words.withIndex()) {
                    val width = font.getStringWidth(word) / 1000f * 14f
                    x -= width
                    val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                    content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                    content.beginText()
                    content.setFont(font, 14f)
                    content.newLineAtOffset(x, 700f)
                    content.showText(word.reversed())
                    content.endText()
                    content.endMarkedContent()
                    paragraph.appendKid(PDMarkedContent(COSName.P, properties))
                    x -= font.getStringWidth(" ") / 1000f * 14f
                }
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }
}
