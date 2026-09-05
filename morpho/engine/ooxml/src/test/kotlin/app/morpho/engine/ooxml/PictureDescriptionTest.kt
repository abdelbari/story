package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.MarkdownWriter
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * What a picture shows, in words.
 *
 * A tagged PDF's figure carries the description its author wrote, and a
 * running head photographed because its words are drawn as outlines has
 * words that are nowhere else in the document. Both were thrown away, so
 * every converted document handed its pictures on unlabelled: a screen
 * reader says "image" and stops, and Word's own accessibility check calls
 * the document out.
 */
class PictureDescriptionTest {

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    private val said = "Journal of Careful Conversion, volume 12"

    private fun picture(description: String? = said) =
        ImageBlock(png, "image/png", 1, 1, description = description)

    private fun model(description: String? = said) = DocumentModel(
        listOf(Paragraph(listOf(TextRun("Above."))), picture(description), Paragraph(listOf(TextRun("Below."))))
    )

    private fun partOf(docx: ByteArray, name: String): String =
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            var found = ""
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) found = zip.readBytes().toString(Charsets.UTF_8)
            }
            found
        }

    private fun describedIn(document: DocumentModel): List<String?> =
        document.blocks.filterIsInstance<ImageBlock>().map { it.description }

    @Test
    fun `word keeps what a picture shows, and gives it back`() {
        val docx = DocxWriter.toByteArray(model())
        val xml = partOf(docx, "word/document.xml")
        assertTrue(xml.contains("""descr="$said""""), "Word was not told what the picture shows: $xml")
        assertEquals(listOf(said), describedIn(DocxReader.read(docx)))
    }

    @Test
    fun `a picture nobody described stays undescribed`() {
        val docx = DocxWriter.toByteArray(model(description = null))
        assertTrue(!partOf(docx, "word/document.xml").contains("descr="), "an empty description was invented")
        assertNull(describedIn(DocxReader.read(docx)).single())
    }

    @Test
    fun `the preview and the markdown say it too`() {
        val html = HtmlWriter.write(model())
        assertTrue(html.contains("""alt="$said""""), html.substringAfter("<img").take(200))
        val markdown = MarkdownWriter.write(model())
        assertTrue(markdown.contains("![$said](data:image/png;base64,"), markdown.take(200))
        // And a picture nobody described keeps the word Markdown has
        // always used, rather than an empty pair of brackets.
        assertTrue(MarkdownWriter.write(model(description = null)).contains("![image](data:"))
    }

    @Test
    fun `a description with brackets in it does not break the markdown around it`() {
        val awkward = "Figure [3]: results"
        val markdown = MarkdownWriter.write(model(description = awkward))
        assertTrue(markdown.contains("""![Figure \[3\]: results](data:"""), markdown.take(200))
    }

    @Test
    fun `what a picture shows survives being converted twice`() {
        val once = DocxReader.read(DocxWriter.toByteArray(model()))
        val twice = DocxReader.read(DocxWriter.toByteArray(once))
        assertEquals(listOf(said), describedIn(twice))
    }
}
