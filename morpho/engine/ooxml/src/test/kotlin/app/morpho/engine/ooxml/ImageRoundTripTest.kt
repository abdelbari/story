package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ImageRoundTripTest {

    // A real 1x1 PNG; content validity matters to Word, not to these tests.
    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    private fun body(text: String) = Paragraph(listOf(TextRun(text)))

    private fun entries(docx: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    @Test
    fun `an image writes a media part, a relationship, and inline drawing markup`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(listOf(body("before"), ImageBlock(png, "image/png", 120, 80), body("after")))
        )
        val parts = entries(docx)

        assertArrayEquals(png, parts["word/media/image1.png"], "media part carries the bytes verbatim")

        val rels = String(parts.getValue("word/_rels/document.xml.rels"), Charsets.UTF_8)
        assertTrue(rels.contains("rIdImg1") && rels.contains("media/image1.png"), rels)

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue("word/document.xml")))
        val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val wpNs = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        assertEquals(1, doc.getElementsByTagNameNS(wNs, "drawing").length)
        val extent = doc.getElementsByTagNameNS(wpNs, "extent").item(0) as Element
        assertEquals((120 * 9525L).toString(), extent.getAttribute("cx"))
        assertEquals((80 * 9525L).toString(), extent.getAttribute("cy"))
    }

    @Test
    fun `an oversized image is scaled into the content area preserving aspect`() {
        val docx = DocxWriter.toByteArray(
            DocumentModel(listOf(ImageBlock(png, "image/png", 2000, 1000)))
        )
        val parts = entries(docx)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue("word/document.xml")))
        val wpNs = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
        val extent = doc.getElementsByTagNameNS(wpNs, "extent").item(0) as Element
        val cx = extent.getAttribute("cx").toLong()
        val cy = extent.getAttribute("cy").toLong()
        assertEquals(5_731_933L, cx)
        assertTrue(cy in (cx / 2 - 10)..(cx / 2 + 10), "aspect preserved: cx=$cx cy=$cy")
    }

    @Test
    fun `writer to reader round-trip preserves image bytes, type, order and size`() {
        val model = DocumentModel(
            listOf(body("intro"), ImageBlock(png, "image/png", 32, 16), body("outro"))
        )
        val back = DocxReader.read(DocxWriter.toByteArray(model))

        assertEquals(3, back.blocks.size, "blocks: ${back.blocks.map { it.javaClass.simpleName }}")
        assertEquals("intro", (back.blocks[0] as Paragraph).text)
        val image = back.blocks[1] as ImageBlock
        assertArrayEquals(png, image.bytes)
        assertEquals("image/png", image.mimeType)
        assertEquals(32, image.widthPx)
        assertEquals(16, image.heightPx)
        assertEquals("outro", (back.blocks[2] as Paragraph).text)
    }

    @Test
    fun `an image inside a table cell round-trips too`() {
        val model = DocumentModel(
            listOf(
                app.morpho.engine.layout.Table(
                    rows = listOf(
                        app.morpho.engine.layout.TableRow(
                            listOf(
                                app.morpho.engine.layout.TableCell(
                                    listOf(body("caption"), ImageBlock(png, "image/png", 8, 8))
                                )
                            )
                        )
                    )
                )
            )
        )
        val back = DocxReader.read(DocxWriter.toByteArray(model))
        val cell = (back.blocks[0] as app.morpho.engine.layout.Table).rows[0].cells[0]
        assertTrue(cell.blocks.any { it is ImageBlock }, "cell blocks: ${cell.blocks.map { it.javaClass.simpleName }}")
    }
}
