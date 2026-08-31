package app.morpho.engine.pdf

import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.Table
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
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Authors real tagged PDFs with PDFBox and checks the structure-tree fast
 * path. Text is deliberately Latin-only: rendering Arabic in a test fixture
 * would depend on the host's fonts, and direction handling is covered by the
 * BiDi suites — what is under test here is structure and logical order.
 */
class TaggedPdfTest {

    /** Builds a one-page tagged PDF; [populate] adds tagged leaves. */
    private fun taggedPdf(populate: TaggedBuilder.() -> Unit): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val root = PDStructureTreeRoot()
            doc.documentCatalog.structureTreeRoot = root
            val document = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
            document.page = page
            root.appendKid(document)
            PDPageContentStream(doc, page).use { content ->
                TaggedBuilder(page, content, document).populate()
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private class TaggedBuilder(
        private val page: PDPage,
        private val content: PDPageContentStream,
        val document: PDStructureElement,
    ) {
        private var nextMcid = 0
        private var y = 760f

        fun group(structType: String, parent: PDStructureElement): PDStructureElement {
            val element = PDStructureElement(structType, parent)
            element.page = page
            parent.appendKid(element)
            return element
        }

        /** A structure leaf whose marked content is drawn at the given [at] y. */
        fun leaf(
            structType: String,
            parent: PDStructureElement,
            text: String,
            at: Float = -1f,
        ): PDStructureElement {
            val element = group(structType, parent)
            val mcid = nextMcid++
            val properties = COSDictionary()
            properties.setInt(COSName.MCID, mcid)
            content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
            content.beginText()
            content.setFont(PDType1Font.HELVETICA, 12f)
            content.newLineAtOffset(72f, if (at >= 0f) at else y.also { y -= 18f })
            content.showText(text)
            content.endText()
            content.endMarkedContent()
            element.appendKid(PDMarkedContent(COSName.P, properties))
            return element
        }
    }

    private fun paragraphs(bytes: ByteArray) =
        PdfReader().extract(bytes).blocks.filterIsInstance<Paragraph>()

    @Test
    fun `headings paragraphs lists and tables come straight from the tags`() {
        val pdf = taggedPdf {
            leaf(StandardStructureTypes.H1, document, "Quarterly Report")
            leaf(StandardStructureTypes.P, document, "Opening paragraph of the report.")

            val list = group(StandardStructureTypes.L, document)
            for ((label, body) in listOf("1." to "first item", "2." to "second item")) {
                val item = group(StandardStructureTypes.LI, list)
                leaf(StandardStructureTypes.LBL, item, label)
                leaf(StandardStructureTypes.L_BODY, item, body)
            }

            val bullets = group(StandardStructureTypes.L, document)
            val bulletItem = group(StandardStructureTypes.LI, bullets)
            leaf(StandardStructureTypes.LBL, bulletItem, "-")
            leaf(StandardStructureTypes.L_BODY, bulletItem, "a bullet point")

            val table = group(StandardStructureTypes.TABLE, document)
            for (row in listOf(listOf("City", "Population"), listOf("Rabat", "580000"))) {
                val tr = group(StandardStructureTypes.TR, table)
                for (cell in row) leaf(StandardStructureTypes.TD, tr, cell)
            }
        }

        val model = PdfReader().extract(pdf)
        val paras = model.blocks.filterIsInstance<Paragraph>()

        assertEquals(ParagraphKind.HEADING_1, paras[0].style.kind)
        assertEquals("Quarterly Report", paras[0].text)
        assertEquals("Opening paragraph of the report.", paras[1].text)

        assertEquals(ListMarker.NUMBERED, paras[2].style.listMarker)
        assertEquals("first item", paras[2].text)
        assertEquals(ListMarker.NUMBERED, paras[3].style.listMarker)
        assertEquals(ListMarker.BULLET, paras[4].style.listMarker)
        assertEquals("a bullet point", paras[4].text)

        val table = model.blocks.filterIsInstance<Table>().single()
        assertEquals(2, table.rows.size)
        assertEquals(listOf("City", "Population"), table.rows[0].cells.map { cell ->
            cell.blocks.filterIsInstance<Paragraph>().single().text
        })

        assertTrue(model.blocks.all { it.confidence == 0.9f }, "tagged reads score 0.9")
    }

    @Test
    fun `tag order beats drawing order — the logical reading order wins`() {
        val pdf = taggedPdf {
            // Drawn bottom-first in the content stream, but tagged first.
            leaf(StandardStructureTypes.P, document, "logically first", at = 200f)
            leaf(StandardStructureTypes.P, document, "logically second", at = 700f)
        }
        assertEquals(
            listOf("logically first", "logically second"),
            paragraphs(pdf).map { it.text },
        )
    }

    @Test
    fun `an empty structure shell falls back to the position heuristics`() {
        val pdf = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            // A tree with no content — some producers write exactly this.
            doc.documentCatalog.structureTreeRoot = PDStructureTreeRoot()
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("untagged body text")
                content.endText()
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }
        val paras = paragraphs(pdf)
        assertEquals("untagged body text", paras.single().text)
    }
}
