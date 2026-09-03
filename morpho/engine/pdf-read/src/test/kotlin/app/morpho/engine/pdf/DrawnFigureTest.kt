package app.morpho.engine.pdf

import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A figure the page draws rather than places.
 *
 * A spreadsheet, a word processor and every drawing tool export a chart
 * as paths — not as a picture the file holds — so a reader that gathers
 * only pictures converts the text of a report and loses every figure in
 * it. That is the loss a reader cannot see: what is missing leaves no
 * gap in the words.
 */
class DrawnFigureTest {

    private val height = PDRectangle.A4.height

    private fun show(content: PDPageContentStream, x: Float, topY: Float, text: String) {
        content.beginText()
        content.setFont(PDType1Font.HELVETICA, 11f)
        content.newLineAtOffset(x, height - topY)
        content.showText(text)
        content.endText()
    }

    /** Three pages of prose; the middle one carries a bar chart. */
    private fun report(withChart: Boolean): ByteArray {
        PDDocument().use { doc ->
            for (page in 0 until 3) {
                val sheet = PDPage(PDRectangle.A4)
                doc.addPage(sheet)
                PDPageContentStream(doc, sheet).use { content ->
                    var y = 100f
                    for (piece in 1..4) {
                        show(content, 72f, y, "Paragraph $piece of page ${page + 1}, above the figure.")
                        y += 26f
                    }
                    if (withChart && page == 1) {
                        content.setNonStrokingColor(Color(40, 80, 160))
                        for (bar in 0 until 5) {
                            content.addRect(120f + bar * 60f, height - 480f, 40f, 30f + bar * 40f)
                            content.fill()
                        }
                        content.setStrokingColor(Color.BLACK)
                        content.moveTo(110f, height - 480f)
                        content.lineTo(440f, height - 480f)
                        content.stroke()
                        content.setNonStrokingColor(Color.BLACK)
                        // A count over each bar, standing inside the figure
                        // as a chart's own labels do. A chart that loses
                        // them for standing in it is a chart lost.
                        for (bar in 0 until 5) {
                            show(content, 128f + bar * 60f, 476f - (30f + bar * 40f), "${bar * 4 + 3}")
                        }
                    }
                    y = 560f
                    for (piece in 1..4) {
                        show(content, 72f, y, "Paragraph $piece of page ${page + 1}, below the figure.")
                        y += 26f
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `a chart drawn as paths is kept as the picture the page draws`() {
        val model = PdfReader().extract(report(withChart = true))
        val pictures = model.blocks.filterIsInstance<ImageBlock>()
        assertEquals(1, pictures.size, "the report's one figure")
        val drawn = ImageIO.read(ByteArrayInputStream(pictures.single().bytes))
        assertTrue(drawn.width > 100 && drawn.height > 100, "${drawn.width}x${drawn.height}")
        // The bars are painted in a blue nothing else on the page is.
        var blue = 0
        for (y in 0 until drawn.height) for (x in 0 until drawn.width) {
            val rgb = drawn.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF
            val b = rgb and 0xFF
            if (b > r + 60) blue++
        }
        assertTrue(blue > 500, "the bars are in the picture: $blue blue pixels")
    }

    @Test
    fun `the figure stands where the page put it`() {
        val model = PdfReader().extract(report(withChart = true))
        val at = model.blocks.indexOfFirst { it is ImageBlock }
        assertTrue(at > 0, "the words above it come first")
        assertTrue(at < model.blocks.size - 1, "and the words below it come after")
    }

    @Test
    fun `a report of words alone gains no picture`() {
        val model = PdfReader().extract(report(withChart = false))
        assertTrue(model.blocks.none { it is ImageBlock }, "a page that drew nothing has no figure")
        assertEquals(
            model.blocks.filterIsInstance<Paragraph>().size,
            model.blocks.size,
        )
    }

    @Test
    fun `finding a figure takes nothing away from the text`() {
        // The chart's own labels are text of the page too, and adding
        // them changes where the reader draws paragraph boundaries — a
        // page with more lines on it clusters differently. What must not
        // change is a sentence of the report: every one that was there
        // without the chart is still there, word for word, with it.
        val withChart = PdfReader().extract(report(withChart = true))
            .blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        val sentences = PdfReader().extract(report(withChart = false))
            .blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
            .split(". ")
            .map { it.trim() }
            .filter { it.contains("the figure") }
        assertTrue(sentences.size >= 20, "the report has its paragraphs: ${sentences.size}")
        for (sentence in sentences) {
            assertTrue(sentence in withChart, "the chart swallowed \"$sentence\"")
        }
    }

    @Test
    fun `a chart keeps its own labels and is still a figure`() {
        val model = PdfReader().extract(report(withChart = true))
        assertEquals(1, model.blocks.filterIsInstance<ImageBlock>().size, "the chart is a figure")
        val text = model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        assertTrue(text.contains("3"), "and its labels are still text of the page: $text")
    }

    /**
     * A tagged report whose Figure is drawn rather than placed — which is
     * what a word processor exports when the figure is a chart it made
     * itself. The tags say plainly that it is a figure; there is simply no
     * picture in the file to be had.
     */
    private fun taggedReport(): ByteArray {
        PDDocument().use { doc ->
            val root = PDStructureTreeRoot()
            doc.documentCatalog.structureTreeRoot = root
            val document = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
            root.appendKid(document)
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            document.page = page
            var mcid = 0
            PDPageContentStream(doc, page).use { content ->
                fun paragraph(text: String, topY: Float) {
                    val element = PDStructureElement(StandardStructureTypes.P, document)
                    element.page = page
                    document.appendKid(element)
                    val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                    content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 11f)
                    content.newLineAtOffset(72f, height - topY)
                    content.showText(text)
                    content.endText()
                    content.endMarkedContent()
                    element.appendKid(PDMarkedContent(COSName.P, properties))
                    mcid++
                }
                paragraph("The paragraph above the figure.", 100f)

                val figure = PDStructureElement(StandardStructureTypes.Figure, document)
                figure.page = page
                document.appendKid(figure)
                val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                content.beginMarkedContent(COSName.getPDFName("Figure"), PDPropertyList.create(properties))
                content.setNonStrokingColor(Color(40, 80, 160))
                for (bar in 0 until 4) {
                    content.addRect(120f + bar * 60f, height - 400f, 40f, 40f + bar * 30f)
                    content.fill()
                }
                content.endMarkedContent()
                figure.appendKid(PDMarkedContent(COSName.getPDFName("Figure"), properties))
                mcid++

                // A rule, tagged as a Figure — which the paper this was
                // measured on does with the rule under its dates.
                val ruled = PDStructureElement(StandardStructureTypes.Figure, document)
                ruled.page = page
                document.appendKid(ruled)
                val ruleProperties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                content.beginMarkedContent(COSName.getPDFName("Figure"), PDPropertyList.create(ruleProperties))
                content.setNonStrokingColor(Color.BLACK)
                content.addRect(72f, height - 460f, 400f, 0.7f)
                content.fill()
                content.endMarkedContent()
                ruled.appendKid(PDMarkedContent(COSName.getPDFName("Figure"), ruleProperties))
                mcid++

                paragraph("The paragraph below the figure.", 500f)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `a tagged Figure with no picture in the file is photographed`() {
        val model = PdfReader().extract(taggedReport())
        val pictures = model.blocks.filterIsInstance<ImageBlock>()
        assertEquals(1, pictures.size, "the Figure the tags name: ${model.blocks.map { it::class.simpleName }}")
        val drawn = ImageIO.read(ByteArrayInputStream(pictures.single().bytes))
        var blue = 0
        for (y in 0 until drawn.height) for (x in 0 until drawn.width) {
            val rgb = drawn.getRGB(x, y)
            if ((rgb and 0xFF) > ((rgb shr 16) and 0xFF) + 60) blue++
        }
        assertTrue(blue > 500, "the bars are in it: $blue blue pixels")
    }

    @Test
    fun `a tagged Figure stands between the paragraphs it stood between`() {
        val model = PdfReader().extract(taggedReport())
        val texts = model.blocks.map { (it as? Paragraph)?.text }
        assertEquals(
            listOf("The paragraph above the figure.", null, "The paragraph below the figure."),
            texts,
        )
    }

    @Test
    fun `a rule the tree calls a Figure is still a rule`() {
        // Photographed, it comes out as a strip of ink one point tall,
        // which is a picture of nothing anybody wanted.
        val model = PdfReader().extract(taggedReport())
        assertEquals(
            1,
            model.blocks.filterIsInstance<ImageBlock>().size,
            "the chart is a figure and the rule is not",
        )
    }
}
