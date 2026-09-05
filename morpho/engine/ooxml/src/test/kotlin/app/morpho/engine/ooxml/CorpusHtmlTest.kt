package app.morpho.engine.ooxml

import app.morpho.engine.layout.FidelityScorer
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The corpus gate extended to the HTML half of the Word→PDF pipeline. */
class CorpusHtmlTest {

    companion object {
        @JvmStatic
        fun corpusFiles(): List<String> {
            val url = CorpusHtmlTest::class.java.getResource("/corpus")
                ?: error("corpus resource directory missing")
            return File(url.toURI()).list()!!.sorted()
        }
    }

    @ParameterizedTest(name = "{0}: html preserves the model text")
    @MethodSource("corpusFiles")
    fun `html output parses and preserves the imported text`(name: String) {
        val text = CorpusHtmlTest::class.java.getResourceAsStream("/corpus/$name")!!
            .readBytes().toString(Charsets.UTF_8)
        val model = PlainTextImporter.import(text)
        val html = HtmlWriter.write(model, name)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        val rendered = doc.getElementsByTagName("body").item(0).textContent
        val expected = textOf(model.blocks)
        val similarity = FidelityScorer.textSimilarity(expected, rendered)
        assertTrue(similarity >= 0.999, "$name html text similarity: $similarity")
    }

    /**
     * Every word of a document, cells included: a walk of the top-level
     * paragraphs alone would let a table go missing unnoticed.
     */
    private fun textOf(blocks: List<app.morpho.engine.layout.Block>): String {
        val out = mutableListOf<String>()
        fun walk(list: List<app.morpho.engine.layout.Block>) {
            for (block in list) when (block) {
                is Paragraph -> out += block.text
                is app.morpho.engine.layout.Table ->
                    for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                else -> {}
            }
        }
        walk(blocks)
        // A note's words are the document's words. The preview gathers
        // them under a rule at the end, which is where a page puts them,
        // so they are counted last here too — and a walk that skipped
        // them would let every note go missing unnoticed.
        out += notesOf(blocks)
        return out.joinToString(" ")
    }

    /**
     * Each note as a page sets it at its foot: the mark, then the words.
     * A document with notes says each mark twice — once where it stands
     * in the sentence and once at the head of the note it calls — and the
     * preview says it twice for the same reason.
     */
    private fun notesOf(blocks: List<app.morpho.engine.layout.Block>): List<String> {
        val out = mutableListOf<String>()
        fun collect(list: List<app.morpho.engine.layout.Block>) {
            for (block in list) when (block) {
                is Paragraph -> for (run in block.runs) run.note?.let { note ->
                    out += run.text
                    for (held in note) if (held is Paragraph) out += held.text
                }
                is app.morpho.engine.layout.Table ->
                    for (row in block.rows) for (cell in row.cells) collect(cell.blocks)
                else -> {}
            }
        }
        collect(blocks)
        return out
    }
}
