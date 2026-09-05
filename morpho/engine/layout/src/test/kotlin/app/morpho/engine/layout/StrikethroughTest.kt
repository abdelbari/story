package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Text struck through says what a document once said and no longer does —
 * a price that changed, a clause that was dropped, a name that was wrong.
 * Bold and italic have always survived a conversion; this did not.
 */
class StrikethroughTest {

    private val document = DocumentModel(
        blocks = listOf(
            Paragraph(
                runs = listOf(
                    TextRun("The fee is "),
                    TextRun("40 dinars", strikethrough = true),
                    TextRun(" 30 dinars."),
                ),
            )
        )
    )

    @Test
    fun `the preview strikes the words the document struck`() {
        val html = HtmlWriter.write(document, "fees")
        assertTrue(html.contains("<s>40 dinars</s>"), html)
    }

    @Test
    fun `Markdown writes it the way Markdown writes it`() {
        assertTrue(MarkdownWriter.write(document).contains("~~40 dinars~~"))
    }

    @Test
    fun `Markdown read back in is struck through again`() {
        val back = PlainTextImporter.import(MarkdownWriter.write(document))
        val runs = back.blocks.filterIsInstance<Paragraph>().first().runs
        val struck = runs.filter { it.strikethrough }
        assertEquals(listOf("40 dinars"), struck.map { it.text.trim() })
    }

    @Test
    fun `a struck word that is also bold keeps both`() {
        val both = DocumentModel(
            blocks = listOf(
                Paragraph(runs = listOf(TextRun("gone", bold = true, strikethrough = true)))
            )
        )
        val markdown = MarkdownWriter.write(both)
        assertTrue(markdown.contains("~~**gone**~~"), markdown)
        val run = PlainTextImporter.import(markdown).blocks
            .filterIsInstance<Paragraph>().first().runs.first()
        assertTrue(run.bold)
        assertTrue(run.strikethrough)
    }

    @Test
    fun `a tilde that means nothing stays a tilde`() {
        val plain = PlainTextImporter.import("about ~5 dinars, or ~~ maybe")
        val text = plain.blocks.filterIsInstance<Paragraph>().first().text
        assertEquals("about ~5 dinars, or ~~ maybe", text)
        assertFalse(plain.blocks.filterIsInstance<Paragraph>().first().runs.any { it.strikethrough })
    }

    @Test
    fun `a tilde in the text survives being written and read`() {
        val tilde = DocumentModel(blocks = listOf(Paragraph(runs = listOf(TextRun("about ~5 each")))))
        val back = PlainTextImporter.import(MarkdownWriter.write(tilde))
        assertEquals("about ~5 each", back.blocks.filterIsInstance<Paragraph>().first().text)
    }
}
