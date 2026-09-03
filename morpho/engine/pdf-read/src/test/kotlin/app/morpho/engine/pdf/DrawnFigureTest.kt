package app.morpho.engine.pdf

import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
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
        val withChart = PdfReader().extract(report(withChart = true))
        val without = PdfReader().extract(report(withChart = false))
        assertEquals(
            without.blocks.filterIsInstance<Paragraph>().map { it.text },
            withChart.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }
}
