package app.morpho.engine.ooxml

import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * The pictures Word drew before DrawingML, and still draws for some of
 * them: anything pasted in compatibility mode, an equation saved as a
 * picture, the output of a good many converters. Looked for only under
 * `w:drawing`, every one of them was dropped — a converted document
 * simply had no picture where the original plainly has one, and nothing
 * said so.
 */
class LegacyPictureTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val v = "urn:schemas-microsoft-com:vml"

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    private fun docx(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                (
                    """<w:document xmlns:w="$w" xmlns:r="$r" xmlns:v="$v"><w:body>""" +
                        body + "</w:body></w:document>"
                    ).toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(
                (
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                        """<Relationship Id="rId5" """ +
                        """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" """ +
                        """Target="media/one.png"/></Relationships>"""
                    ).toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/media/one.png"))
            zip.write(png)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun shape(style: String) =
        """<w:p><w:r><w:pict><v:shape style="$style"><v:imagedata r:id="rId5"/></v:shape></w:pict></w:r></w:p>"""

    @Test
    fun `a picture written the old way is a picture still`() {
        val blocks = DocxReader.read(docx(shape("width:120pt;height:60pt"))).blocks
        val picture = blocks.filterIsInstance<ImageBlock>().singleOrNull()
            ?: error("the legacy picture was dropped: $blocks")
        assertEquals("image/png", picture.mimeType)
        assertTrue(picture.bytes.contentEquals(png))
        assertEquals(120f, picture.widthPt)
        assertEquals(60f, picture.heightPt)
    }

    @Test
    fun `the size is read in whatever unit the shape gave it`() {
        val inches = DocxReader.read(docx(shape("width:1in;height:0.5in")))
            .blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(72f, inches.widthPt)
        assertEquals(36f, inches.heightPt)
        val pixels = DocxReader.read(docx(shape("width:96px;height:48px")))
            .blocks.filterIsInstance<ImageBlock>().single()
        assertTrue(abs(pixels.widthPt!! - 72f) < 0.01f, "px: ${pixels.widthPt}")
        val centimetres = DocxReader.read(docx(shape("width:2.54cm;height:1.27cm")))
            .blocks.filterIsInstance<ImageBlock>().single()
        assertTrue(abs(centimetres.widthPt!! - 72f) < 0.01f, "cm: ${centimetres.widthPt}")
    }

    @Test
    fun `a shape that says nothing about its size leaves it to the writer`() {
        val picture = DocxReader.read(docx(shape("mso-position-horizontal:center")))
            .blocks.filterIsInstance<ImageBlock>().single()
        assertNull(picture.widthPt)
        assertNull(picture.heightPt)
    }

    @Test
    fun `a picture in a line of words stays in the line`() {
        val body = """<w:p><w:r><w:t xml:space="preserve">before </w:t></w:r>""" +
            """<w:r><w:pict><v:shape style="width:12pt;height:12pt">""" +
            """<v:imagedata r:id="rId5"/></v:shape></w:pict></w:r>""" +
            """<w:r><w:t xml:space="preserve"> after</w:t></w:r></w:p>"""
        val model = DocxReader.read(docx(body))
        val paragraph = model.blocks.filterIsInstance<Paragraph>().single()
        assertEquals("before  after", paragraph.text)
        assertEquals(1, paragraph.runs.count { it.image != null } + model.blocks.count { it is ImageBlock })
    }

    @Test
    fun `a picture written both ways at once is one picture`() {
        // Word writes a shape twice: the way it prefers, and a fallback
        // for readers that do not know it. Both hold the same picture,
        // and reading both puts it into the document twice.
        val body = """<w:p><w:r><mc:AlternateContent """ +
            """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">""" +
            """<mc:Choice Requires="wps"><w:drawing><wp:inline """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">""" +
            """<wp:extent cx="1524000" cy="762000"/><a:graphic """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData>""" +
            """<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            """<pic:blipFill><a:blip r:embed="rId5"/></pic:blipFill></pic:pic>""" +
            """</a:graphicData></a:graphic></wp:inline></w:drawing></mc:Choice>""" +
            """<mc:Fallback><w:pict><v:shape style="width:120pt;height:60pt">""" +
            """<v:imagedata r:id="rId5"/></v:shape></w:pict></mc:Fallback>""" +
            """</mc:AlternateContent></w:r></w:p>"""
        val model = DocxReader.read(docx(body))
        val pictures = model.blocks.filterIsInstance<ImageBlock>() +
            model.blocks.filterIsInstance<Paragraph>().flatMap { p -> p.runs.mapNotNull { it.image } }
        assertEquals(1, pictures.size, "the picture was read once for each way it was written")
    }

    @Test
    fun `the preview an embedded object shows for itself is kept`() {
        // An equation from the old editor, a chart pasted from a
        // spreadsheet: the thing itself cannot be carried across, and the
        // picture it shows for itself is what a reader of the document
        // sees. Dropped, the page simply has a hole in it.
        val body = """<w:p><w:r><w:object w:dxaOrig="2400" w:dyaOrig="1200">""" +
            """<v:shape style="width:120pt;height:60pt"><v:imagedata r:id="rId5"/></v:shape>""" +
            """</w:object></w:r></w:p>"""
        val model = DocxReader.read(docx(body))
        val pictures = model.blocks.filterIsInstance<ImageBlock>() +
            model.blocks.filterIsInstance<Paragraph>().flatMap { p -> p.runs.mapNotNull { it.image } }
        assertEquals(1, pictures.size, "the object showed nothing at all: ${model.blocks}")
        assertEquals(120f, pictures.single().widthPt)
    }

    @Test
    fun `a text box is text, not a picture of one`() {
        val body = """<w:p><w:r><w:pict><v:shape style="width:120pt;height:60pt">""" +
            """<v:textbox><w:txbxContent><w:p><w:r><w:t>inside the box</w:t></w:r></w:p>""" +
            """</w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"""
        val model = DocxReader.read(docx(body))
        assertTrue(model.blocks.none { it is ImageBlock }, "the text box became a picture: ${model.blocks}")
        assertTrue(
            model.blocks.filterIsInstance<Paragraph>().any { it.text == "inside the box" },
            "the words in the box were lost: ${model.blocks}",
        )
    }

    @Test
    fun `a shape holding no picture at all is not one`() {
        val body = """<w:p><w:r><w:pict><v:rect style="width:12pt;height:12pt"/></w:pict></w:r></w:p>"""
        assertTrue(DocxReader.read(docx(body)).blocks.none { it is ImageBlock })
    }
}
