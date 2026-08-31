package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime

/** Regression tests for the adversarial-review fixes in :layout. */
class HardeningTest {

    private fun paragraphs(model: DocumentModel) = model.blocks.filterIsInstance<Paragraph>()

    @Test
    fun `arabic-indic digits start numbered list items`() {
        val paras = paragraphs(PlainTextImporter.import("١. بند أول\n٢. بند ثانٍ\n"))
        assertEquals(2, paras.size)
        assertTrue(paras.all { it.style.listMarker == ListMarker.NUMBERED })
        assertEquals("بند أول", paras[0].text)
        assertEquals(TextDirection.RTL, paras[0].style.direction)
    }

    @Test
    fun `eastern arabic-indic digits start numbered list items`() {
        val paras = paragraphs(PlainTextImporter.import("۱. یک\n۲. دو\n"))
        assertTrue(paras.all { it.style.listMarker == ListMarker.NUMBERED })
    }

    @Test
    fun `a year in arabic-indic digits is still not a list item`() {
        val paras = paragraphs(PlainTextImporter.import("٢٠٢٤. تلك كانت السنة."))
        assertEquals(null, paras[0].style.listMarker)
    }

    @Test
    fun `escaped backslash and pipe are literal like escaped asterisk`() {
        val para = paragraphs(PlainTextImporter.import("""a \\ b \| c \* d"""))[0]
        assertEquals("""a \ b | c * d""", para.text)
    }

    @Test
    fun `special characters survive a markdown write and re-import round trip`() {
        val raw = """C:\dir | pipe * star"""
        val model = DocumentModel(listOf(Paragraph(listOf(TextRun(raw)))))
        val markdown = MarkdownWriter.write(model)
        assertEquals(raw, paragraphs(PlainTextImporter.import(markdown))[0].text)
    }

    @Test
    fun `nbsp and narrow spaces do not count as text differences`() {
        assertEquals(1.0, FidelityScorer.textSimilarity("Bonjour\u00A0: monde", "Bonjour : monde"))
        assertEquals(1.0, FidelityScorer.textSimilarity("12\u202F345", "12 345"))
    }

    @Test
    fun `emphasis markers hug the text when styled runs carry boundary whitespace`() {
        val model = DocumentModel(
            listOf(
                Paragraph(
                    listOf(
                        TextRun("Numbers are"),
                        TextRun(" up ", bold = true),
                        TextRun("today"),
                    )
                )
            )
        )
        val markdown = MarkdownWriter.write(model)
        assertEquals("Numbers are **up** today\n", markdown)
        val back = paragraphs(PlainTextImporter.import(markdown))[0]
        assertEquals("Numbers are up today", back.text)
        assertTrue(back.runs.any { it.bold && it.text == "up" })
    }

    @Test
    fun `a whitespace-only styled run is written plain, not as empty emphasis`() {
        val model = DocumentModel(
            listOf(Paragraph(listOf(TextRun("x"), TextRun(" ", bold = true), TextRun("y"))))
        )
        val markdown = MarkdownWriter.write(model)
        assertEquals("x y\n", markdown)
        assertTrue(paragraphs(PlainTextImporter.import(markdown))[0].runs.none { it.bold })
    }

    @Test
    fun `a flood of unmatched asterisks parses linearly and stays literal`() {
        val text = "a" + " *b".repeat(30_000)
        var model: DocumentModel
        val nanos = measureNanoTime { model = PlainTextImporter.import(text) }
        val para = paragraphs(model)[0]
        assertEquals(text, para.text)
        assertEquals(1, para.runs.size)
        assertTrue(nanos < 2_000_000_000L, "emphasis parsing took ${nanos / 1_000_000} ms")
    }
}
