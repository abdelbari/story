package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * A running head is set against the page, not against the column of text.
 *
 * A journal's head is artwork — its title and its rules drawn rather than
 * typed — so it is kept as the picture it is, and it reaches into the
 * margins as often as not. Held to the text column, as a picture in the
 * body rightly is, it came back narrower than the page it heads: a head
 * that no longer lines up with anything, on every page of the document.
 *
 * And a picture standing alone in a head wants no space around it and no
 * line spacing of Word's own, or the head is taller than the one it was
 * cropped from and the text under it starts lower down the page.
 */
class RunningHeadTest {

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    /** A page whose text column is 400pt wide, with a head 460pt wide across it. */
    private val page = PageSetup(
        widthPt = 595f,
        heightPt = 842f,
        marginTopPt = 60f,
        marginBottomPt = 90f,
        marginLeftPt = 90f,
        marginRightPt = 105f,
        headerDistancePt = 27f,
    )

    private fun head(widthPt: Float) = ImageBlock(png, "image/png", 1379, 84, widthPt = widthPt, heightPt = 28f)

    private fun document(widthPt: Float, direction: TextDirection = TextDirection.RTL) = DocumentModel(
        blocks = listOf(Paragraph(listOf(TextRun("The body of it.")))),
        defaultDirection = direction,
        pageSetup = page,
        header = listOf(head(widthPt)),
    )

    private fun partOf(docx: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw AssertionError("$name is not in the file")
    }

    private fun extentOf(xml: String): Long =
        Regex("""<wp:extent cx="(\d+)"""").find(xml)!!.groupValues[1].toLong()

    @Test
    fun `a head wider than the text keeps the width it had`() {
        // 460pt across a 400pt column: held to the column it would come
        // back at 400, and every page would show a head 13 per cent
        // narrower than the one the paper printed.
        val xml = partOf(DocxWriter.toByteArray(document(widthPt = 460f)), "word/header1.xml")
        assertEquals((460f * 12700).toLong(), extentOf(xml), "the head was shrunk to the text column")
    }

    @Test
    fun `a head wider than the page is held to the page`() {
        val xml = partOf(DocxWriter.toByteArray(document(widthPt = 700f)), "word/header1.xml")
        assertEquals((595f * 12700).toLong(), extentOf(xml), "a head cannot be wider than its sheet")
    }

    @Test
    fun `a picture in the text is still held to the text`() {
        val body = DocumentModel(
            blocks = listOf(head(460f)),
            pageSetup = page,
        )
        val xml = partOf(DocxWriter.toByteArray(body), "word/document.xml")
        assertEquals((400f * 12700).toLong(), extentOf(xml), "a picture in the body belongs to the column")
    }

    @Test
    fun `a head takes no space or line spacing of Word's own`() {
        val xml = partOf(DocxWriter.toByteArray(document(widthPt = 460f)), "word/header1.xml")
        assertTrue(
            xml.contains("""<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>"""),
            xml.take(400),
        )
        assertTrue(xml.contains("<w:bidi/>"), "a head of a right-to-left document runs the same way")
    }

    @Test
    fun `a head of a left-to-right document is not marked right-to-left`() {
        val xml = partOf(
            DocxWriter.toByteArray(document(widthPt = 460f, direction = TextDirection.LTR)),
            "word/header1.xml",
        )
        assertTrue(!xml.contains("<w:bidi/>"), xml.take(400))
    }

    @Test
    fun `the preview shows a head at its own size, against the page`() {
        val html = HtmlWriter.write(document(widthPt = 460f), "paper")
        assertTrue(
            html.contains("header.page-header img,footer.page-footer img{max-width:none;}"),
            "the preview shrinks the head to the column",
        )
        assertTrue(
            html.contains("header.page-header p.image,footer.page-footer p.image{text-align:start;margin:0;}"),
            "the preview centres the head inside the column",
        )
    }
}
