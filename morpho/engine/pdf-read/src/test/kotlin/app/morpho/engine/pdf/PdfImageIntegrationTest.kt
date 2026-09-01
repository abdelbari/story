package app.morpho.engine.pdf

import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
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

class PdfImageIntegrationTest {

    private fun bufferedImage(width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            graphics.color = Color.RED
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()
        }

    private fun text(content: PDPageContentStream, text: String, y: Float) {
        content.beginText()
        content.setFont(PDType1Font.HELVETICA, 12f)
        content.newLineAtOffset(72f, y)
        content.showText(text)
        content.endText()
    }

    @Test
    fun `a drawn image comes back between its surrounding paragraphs`() {
        val pdf = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val image = LosslessFactory.createFromImage(doc, bufferedImage(20, 10))
            PDPageContentStream(doc, page).use { c ->
                text(c, "Paragraph above the figure, first line.", 740f)
                text(c, "Paragraph above the figure, second line.", 725f)
                c.drawImage(image, 72f, 600f, 80f, 40f)
                text(c, "Paragraph below the figure, first line.", 500f)
                text(c, "Paragraph below the figure, second line.", 485f)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }

        val model = PdfReader().extract(pdf)
        assertEquals(
            listOf("Paragraph", "ImageBlock", "Paragraph"),
            model.blocks.map { it.javaClass.simpleName },
            "blocks: " + model.blocks.map { it.javaClass.simpleName },
        )
        val image = model.blocks[1] as ImageBlock
        assertEquals("image/png", image.mimeType)
        assertEquals(20, image.widthPx)
        assertEquals(10, image.heightPx)
        val decoded = ImageIO.read(ByteArrayInputStream(image.bytes))
        assertEquals(20, decoded.width)
        assertEquals(0.6f, image.confidence)
        assertTrue((model.blocks[0] as Paragraph).text.startsWith("Paragraph above"))
    }

    @Test
    fun `tiny decoration images are skipped`() {
        val pdf = PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val dot = LosslessFactory.createFromImage(doc, bufferedImage(4, 4))
            PDPageContentStream(doc, page).use { c ->
                text(c, "Text with a decorative dot only.", 700f)
                c.drawImage(dot, 72f, 650f, 4f, 4f)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }
        val model = PdfReader().extract(pdf)
        assertTrue(model.blocks.none { it is ImageBlock })
    }
}
