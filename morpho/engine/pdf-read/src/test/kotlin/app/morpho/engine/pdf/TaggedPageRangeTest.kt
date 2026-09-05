package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Asking for a few pages of a document reads them as a document of their
 * own, which is what lets everything else — the pictures, the outline,
 * the page setup — see the part as the whole it now is. A document of its
 * own made that way has no tags, though, so a tagged file converted a
 * chapter at a time was read the way a scan is read: its headings guessed
 * from the type rather than taken from the tree that names them.
 *
 * The tree points at pages, and the part holds those same pages, so it
 * travels with them.
 */
class TaggedPageRangeTest {

    /**
     * Two pages, tagged. Every line is set in the same plain 12pt face,
     * so nothing but the tags can say which of them is a heading.
     */
    private fun twoTaggedPages(): ByteArray {
        PDDocument().use { doc ->
            val root = PDStructureTreeRoot()
            doc.documentCatalog.structureTreeRoot = root
            val document = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
            root.appendKid(document)
            for ((index, lines) in listOf(
                listOf("Heading of the first page" to StandardStructureTypes.H1, "Words of the first page." to StandardStructureTypes.P),
                listOf("Heading of the second page" to StandardStructureTypes.H1, "Words of the second page." to StandardStructureTypes.P),
            ).withIndex()) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                if (index == 0) document.page = page
                var mcid = 0
                var y = 740f
                PDPageContentStream(doc, page).use { content ->
                    for ((text, type) in lines) {
                        val element = PDStructureElement(type, document)
                        element.page = page
                        document.appendKid(element)
                        val properties = COSDictionary()
                        properties.setInt(COSName.MCID, mcid++)
                        content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(72f, y)
                        content.showText(text)
                        content.endText()
                        content.endMarkedContent()
                        element.appendKid(PDMarkedContent(COSName.P, properties))
                        y -= 24f
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun kinds(bytes: ByteArray, pages: IntRange?): List<Pair<ParagraphKind, String>> =
        PdfReader().extract(bytes, "", pages)
            .blocks.filterIsInstance<Paragraph>()
            .map { it.style.kind to it.text.trim() }

    @Test
    fun `a part of a tagged document is still read from its tags`() {
        val bytes = twoTaggedPages()
        // Nothing about the type says which line is a heading; only the
        // tree does, so a heading here is proof the tree was read.
        assertEquals(
            listOf(
                ParagraphKind.HEADING_1 to "Heading of the second page",
                ParagraphKind.BODY to "Words of the second page.",
            ),
            kinds(bytes, 2..2),
        )
    }

    @Test
    fun `the part says what the same pages of the whole say`() {
        val bytes = twoTaggedPages()
        assertEquals(kinds(bytes, null).drop(2), kinds(bytes, 2..2))
        assertEquals(kinds(bytes, null).take(2), kinds(bytes, 1..1))
    }
}
