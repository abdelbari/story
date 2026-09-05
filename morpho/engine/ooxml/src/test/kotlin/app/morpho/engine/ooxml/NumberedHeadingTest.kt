package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A numbered heading is a heading.
 *
 * A report, a thesis and a standard number their chapters by a list their
 * heading styles belong to — "2. Method", "2.1 Sampling" — and the
 * paragraph is a heading and an item of a list at the same time. Told
 * only that it was a list item, the Word writer named it List Paragraph
 * and the preview made it a plain `<li>`, so every numbered chapter of
 * every such document came back as body text, its outline gone with it.
 */
class NumberedHeadingTest {

    private val document = DocumentModel(
        listOf(
            Paragraph(
                listOf(TextRun("Method")),
                ParagraphStyle(kind = ParagraphKind.HEADING_1, listMarker = ListMarker.NUMBERED),
            ),
            Paragraph(
                listOf(TextRun("An ordinary item")),
                ParagraphStyle(listMarker = ListMarker.NUMBERED),
            ),
        )
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

    @Test
    fun `Word is told both the heading style and the numbering`() {
        val written = partOf(DocxWriter.toByteArray(document), "word/document.xml")
        assertTrue(written.contains("""<w:pStyle w:val="Heading1"/>"""), written)
        assertTrue(written.contains("<w:numPr>"), written)
        assertTrue(
            written.contains("""<w:pStyle w:val="ListParagraph"/>"""),
            "an item that is not a heading is still Word's List Paragraph",
        )
    }

    @Test
    fun `a numbered heading reads back as a heading that is numbered`() {
        val back = DocxReader.read(DocxWriter.toByteArray(document))
            .blocks.filterIsInstance<Paragraph>()
        assertEquals(ParagraphKind.HEADING_1, back[0].style.kind, "the chapter lost its rank")
        assertEquals(ListMarker.NUMBERED, back[0].style.listMarker, "and its number")
        assertEquals(ParagraphKind.BODY, back[1].style.kind)
        assertEquals(ListMarker.NUMBERED, back[1].style.listMarker)
    }

    @Test
    fun `the preview keeps the heading inside the item`() {
        // A browser is happy to hold a heading in a list item, and a
        // chapter's title should not be set in the body's face for having
        // a number in front of it.
        val html = HtmlWriter.write(document, "t")
        // The item, whatever else its element says about itself — which block it is, for one.
        assertTrue(Regex("<li[^>]*><h1>Method</h1></li>").containsMatchIn(html), html)
        assertTrue(Regex("<li[^>]*>An ordinary item</li>").containsMatchIn(html), html)
    }
}
