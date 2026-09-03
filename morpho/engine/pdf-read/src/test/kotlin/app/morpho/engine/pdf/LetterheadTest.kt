package app.morpho.engine.pdf

import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A letterhead: the same picture at the top of every page.
 *
 * It is the page's furniture as surely as a running head of words is, and
 * a converter that takes every picture it finds and drops it into the
 * text puts the logo of a fifty-page report into the reading fifty times
 * — between paragraphs, in the middle of sentences, wherever on the page
 * it happened to be drawn.
 */
class LetterheadTest {

    /**
     * A 32x32 PNG. Big enough that the reader keeps it: anything with a
     * side under eight pixels is a rule or a bullet, not a picture.
     */
    private val logo: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAB00lEQVR4nGNgYOcTlVHWMrSwd/MNiU7KLCiva+2ZPGvhivXb9h45fenmg+fvvv5h5hKUkFfTNbF28gwIj0vNKa5q7OifNnfJ6k07Dxw/d/XO41cff/xn4xWRVtI0MLdz9QmOSszIL6tt6Z40k4ECvQuWr9u65/CpizfuP3v75TcTp4C4nKqOsZWjh39YbEp2UWVDe9/UOQwU6F28auOO/cfOXrn96OWH7/9YeYSlFDX0zWxdvIMiE9LzSmuauybOmM9Agd5la7fsPnTywvV7T998/sXIwS8mq6JtZOng7hcak5xVWFHf1jtl9iIGCvSu3LB939Ezl289fPH+218WbiFJBXU9Uxtnr8CI+LTckuqmzgnT5y1loEDvms27Dp44f+3uk9effuJMKwwU6CUqnTHQNI0C0woDTdMoMK0w0DSNAtMKA03TKDCtMNA0jQLTCgNN0ygwrTDQNI0C0woDTdMoMK0w0DSNAtMKA03TKDCtMNA0jQLTCgNN0ygwrTDQNI0C0woDTdMoMK0w0DSNAtMKA03TKDCtMNA0jQLTCgNN0ygwrTDQNI0C0woDTdMoMK0w0DSNAtMKA03TKDCtMNA0jQLTCgNN0ygwrTDQNI0C0woAGebuTOYjL2YAAAAASUVORK5CYII="
    )

    /** Four pages of prose, each under the same picture. */
    private fun report(logoOnEveryPage: Boolean): ByteArray {
        PDDocument().use { doc ->
            val picture = PDImageXObject.createFromByteArray(doc, logo, "logo")
            for (page in 0 until 4) {
                val sheet = PDPage(PDRectangle.A4)
                doc.addPage(sheet)
                PDPageContentStream(doc, sheet).use { content ->
                    if (logoOnEveryPage || page == 1) {
                        content.drawImage(picture, 72f, PDRectangle.A4.height - 60f, 180f, 30f)
                    }
                    var y = 700f
                    for (piece in 1..6) {
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 11f)
                        content.newLineAtOffset(72f, y)
                        content.showText("Paragraph $piece of page ${page + 1}, set in the measure of the page.")
                        content.endText()
                        y -= 30f
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `a picture at the head of every page is the page's, not the text's`() {
        val model = PdfReader().extract(report(logoOnEveryPage = true))
        assertTrue(
            model.blocks.none { it is ImageBlock },
            "the letterhead was dropped into the reading " +
                "${model.blocks.count { it is ImageBlock }} times",
        )
        assertTrue(model.header.isNotEmpty(), "and it is the head of the page, where it was")
        assertEquals(
            PdfReader().extract(report(logoOnEveryPage = false)).blocks.count { it is Paragraph },
            model.blocks.count { it is Paragraph },
            "and the text reads exactly as it does without a letterhead over it",
        )
    }

    @Test
    fun `a picture drawn on one page only is part of the document`() {
        val model = PdfReader().extract(report(logoOnEveryPage = false))
        assertEquals(
            1,
            model.blocks.count { it is ImageBlock },
            "a figure that appears once is a figure, and belongs in the reading",
        )
    }
}
