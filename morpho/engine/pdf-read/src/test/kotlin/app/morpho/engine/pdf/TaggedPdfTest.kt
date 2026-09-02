package app.morpho.engine.pdf

import app.morpho.engine.layout.ImageBlock
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
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

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
                TaggedBuilder(doc, page, content, document).populate()
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private class TaggedBuilder(
        private val doc: PDDocument,
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
            font: PDType1Font = PDType1Font.HELVETICA,
            size: Float = 12f,
        ): PDStructureElement {
            val element = group(structType, parent)
            val mcid = nextMcid++
            val properties = COSDictionary()
            properties.setInt(COSName.MCID, mcid)
            content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
            content.beginText()
            content.setFont(font, size)
            content.newLineAtOffset(72f, if (at >= 0f) at else y.also { y -= 18f })
            content.showText(text)
            content.endText()
            content.endMarkedContent()
            element.appendKid(PDMarkedContent(COSName.P, properties))
            return element
        }

        /**
         * A Figure structure element whose marked-content block wraps an
         * actual image draw — the shape Word/LibreOffice exporters produce.
         * beginMarkedContent(tag, propertyList) writes the named-resource
         * BDC form, so the MCID resolution through page resources is what
         * gets exercised, same as in real exports.
         */
        fun figure(parent: PDStructureElement, widthPx: Int, heightPx: Int): PDStructureElement {
            val element = group("Figure", parent)
            val mcid = nextMcid++
            val properties = COSDictionary()
            properties.setInt(COSName.MCID, mcid)
            val tag = COSName.getPDFName("Figure")
            content.beginMarkedContent(tag, PDPropertyList.create(properties))
            drawImage(widthPx, heightPx)
            content.endMarkedContent()
            element.appendKid(PDMarkedContent(tag, properties))
            return element
        }

        /** An image drawn outside any marked content — untracked by the tree. */
        fun rawImage(widthPx: Int, heightPx: Int) = drawImage(widthPx, heightPx)

        private fun drawImage(widthPx: Int, heightPx: Int) {
            val awt = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB)
            awt.createGraphics().apply {
                color = Color(30, 90, 200)
                fillRect(0, 0, widthPx, heightPx)
                dispose()
            }
            val xobject = LosslessFactory.createFromImage(doc, awt)
            y -= heightPx + 8f
            content.drawImage(xobject, 72f, y, widthPx.toFloat(), heightPx.toFloat())
        }
    }

    @Test
    fun `a list inside a list item is a list of its own`() {
        // A report's clauses with sub-clauses under them: the inner list is
        // tagged inside the item it belongs to, and its words are not more
        // words of that item.
        val pdf = taggedPdf {
            val list = group(StandardStructureTypes.L, document)
            val first = group(StandardStructureTypes.LI, list)
            leaf(StandardStructureTypes.LBL, first, "1.")
            val firstBody = group(StandardStructureTypes.L_BODY, first)
            leaf(StandardStructureTypes.P, firstBody, "Aims of the study")
            val inner = group(StandardStructureTypes.L, firstBody)
            for (label in listOf("a.", "b.")) {
                val item = group(StandardStructureTypes.LI, inner)
                leaf(StandardStructureTypes.LBL, item, label)
                leaf(StandardStructureTypes.L_BODY, item, "sub-aim " + label.first())
            }
            val second = group(StandardStructureTypes.LI, list)
            leaf(StandardStructureTypes.LBL, second, "2.")
            leaf(StandardStructureTypes.L_BODY, second, "Method of the study")
        }
        val items = paragraphs(pdf).filter { it.style.listMarker != null }
        val shape = items.map { it.text to it.style.listLevel }
        assertEquals(
            listOf(
                "Aims of the study" to 0,
                "sub-aim a" to 1,
                "sub-aim b" to 1,
                "Method of the study" to 0,
            ),
            shape,
            "the list came back as: " + shape,
        )
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
    fun `a figure inside a paragraph is kept, not dropped`() {
        // A picture tagged inside the paragraph it illustrates — a logo
        // in a heading, a formula in a line. The paragraph is read from
        // its glyphs, and a figure has none, so it used to vanish.
        val pdf = taggedPdf {
            val paragraph = group(StandardStructureTypes.P, document)
            leaf(StandardStructureTypes.SPAN, paragraph, "Before the mark")
            figure(paragraph, widthPx = 12, heightPx = 8)
            leaf(StandardStructureTypes.P, document, "The paragraph after it.")
        }
        val model = PdfReader().extract(pdf)
        val text = model.blocks.filterIsInstance<Paragraph>().map { it.text }
        assertTrue(text.any { it.contains("Before the mark") }, text.toString())
        val image = model.blocks.filterIsInstance<ImageBlock>().singleOrNull()
        assertTrue(image != null, "the figure inside the paragraph was lost")
        assertEquals(12, image!!.widthPx)
        // It follows the words it was tagged among, and the next
        // paragraph follows it.
        assertEquals(1, model.blocks.indexOf(image))
        assertTrue((model.blocks[2] as Paragraph).text.contains("after it"))
    }

    @Test
    fun `a Figure element resolves to its image, in tag order`() {
        val pdf = taggedPdf {
            leaf(StandardStructureTypes.P, document, "Text before the figure.")
            figure(document, widthPx = 20, heightPx = 10)
            leaf(StandardStructureTypes.P, document, "Text after the figure.")
        }

        val model = PdfReader().extract(pdf)
        assertEquals(3, model.blocks.size, "paragraph, image, paragraph")
        assertEquals("Text before the figure.", (model.blocks[0] as Paragraph).text)
        assertEquals("Text after the figure.", (model.blocks[2] as Paragraph).text)

        val image = model.blocks[1] as ImageBlock
        assertEquals(20, image.widthPx)
        assertEquals(10, image.heightPx)
        assertEquals("image/png", image.mimeType)
        val decoded = ImageIO.read(ByteArrayInputStream(image.bytes))
        assertEquals(20, decoded.width, "captured bytes decode to the drawn image")
        assertTrue(model.blocks.all { it.confidence == 0.9f }, "figures ride the tagged path")
    }

    @Test
    fun `an image the structure tree never references is appended at the end`() {
        val pdf = taggedPdf {
            rawImage(widthPx = 24, heightPx = 12)
            leaf(StandardStructureTypes.P, document, "Only tagged text.")
        }

        val model = PdfReader().extract(pdf)
        assertEquals(2, model.blocks.size)
        assertEquals("Only tagged text.", (model.blocks[0] as Paragraph).text)
        assertEquals(24, (model.blocks[1] as ImageBlock).widthPx)
    }

    @Test
    fun `a figure-only tagged PDF still takes the tagged path`() {
        val pdf = taggedPdf { figure(document, widthPx = 16, heightPx = 16) }
        val model = PdfReader().extract(pdf)
        val image = model.blocks.single() as ImageBlock
        assertEquals(0.9f, image.confidence, "0.9 proves the tags were read, not the fallback")
    }

    @Test
    fun `an empty structure shell falls back to the position heuristics`() {
        // The image is the trap: an empty tree plus a captured image must not
        // become an images-only "tagged" model that silently drops the text.
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
                val awt = BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB)
                content.drawImage(LosslessFactory.createFromImage(doc, awt), 72f, 500f, 20f, 12f)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }
        val model = PdfReader().extract(pdf)
        val para = model.blocks.filterIsInstance<Paragraph>().single()
        assertEquals("untagged body text", para.text)
        assertEquals(1, model.blocks.filterIsInstance<ImageBlock>().size)
        // The heuristics did the work, so the score must say so.
        assertTrue(
            model.blocks.all { it.confidence == 0.6f },
            "fallback of a tagged PDF scores as untagged extraction",
        )
    }

    @Test
    fun `a tree that tags no headings falls back to type size`() {
        // Word only tags H1 when the author used a heading style. A paper
        // whose headings were made by hand arrives as a flat run of P.
        val pdf = taggedPdf {
            leaf(StandardStructureTypes.P, document, "The Title", size = 18f)
            leaf(StandardStructureTypes.P, document, "Body text of the paper follows here.")
            leaf(StandardStructureTypes.P, document, "More body text, same size as the rest.")
        }
        val paragraphs = paragraphs(pdf)
        assertEquals(ParagraphKind.HEADING_1, paragraphs[0].style.kind)
        assertEquals(ParagraphKind.BODY, paragraphs[1].style.kind)
        assertEquals(ParagraphKind.BODY, paragraphs[2].style.kind)
    }

    @Test
    fun `a bold line at body size becomes a heading below the larger title`() {
        val pdf = taggedPdf {
            leaf(StandardStructureTypes.P, document, "The Title", size = 18f)
            leaf(StandardStructureTypes.P, document, "1. Introduction", font = PDType1Font.HELVETICA_BOLD)
            leaf(StandardStructureTypes.P, document, "Body text of the paper follows here.")
            leaf(StandardStructureTypes.P, document, "More body text, same size as the rest.")
        }
        val paragraphs = paragraphs(pdf)
        assertEquals(ParagraphKind.HEADING_1, paragraphs[0].style.kind)
        assertEquals(ParagraphKind.HEADING_2, paragraphs[1].style.kind)
        assertEquals(ParagraphKind.BODY, paragraphs[2].style.kind)
    }

    @Test
    fun `a tree that does tag a heading is trusted as it stands`() {
        // The tags have spoken, so a large paragraph is a large paragraph.
        val pdf = taggedPdf {
            leaf(StandardStructureTypes.H1, document, "Tagged Title")
            leaf(StandardStructureTypes.P, document, "Pull quote", size = 20f)
            leaf(StandardStructureTypes.P, document, "Body text of the paper follows here.")
        }
        val paragraphs = paragraphs(pdf)
        assertEquals(ParagraphKind.HEADING_1, paragraphs[0].style.kind)
        assertEquals(ParagraphKind.BODY, paragraphs[1].style.kind)
    }

    @Test
    fun `bold says nothing when most of the document is bold`() {
        val pdf = taggedPdf {
            leaf(StandardStructureTypes.P, document, "One bold line", font = PDType1Font.HELVETICA_BOLD)
            leaf(StandardStructureTypes.P, document, "Two bold line", font = PDType1Font.HELVETICA_BOLD)
            leaf(StandardStructureTypes.P, document, "Three bold line", font = PDType1Font.HELVETICA_BOLD)
            leaf(StandardStructureTypes.P, document, "Plain line here")
        }
        assertTrue(paragraphs(pdf).all { it.style.kind == ParagraphKind.BODY })
    }

    @Test
    fun `a long bold paragraph is not a heading`() {
        val pdf = taggedPdf {
            leaf(
                StandardStructureTypes.P, document,
                "A bold paragraph long enough to be prose rather than a heading, " +
                    "which is the whole point of the length test applied here.",
                font = PDType1Font.HELVETICA_BOLD,
            )
            leaf(StandardStructureTypes.P, document, "Body text of the paper follows here.")
            leaf(StandardStructureTypes.P, document, "More body text, same size as the rest.")
        }
        assertTrue(paragraphs(pdf).all { it.style.kind == ParagraphKind.BODY })
    }

}
