package app.morpho.engine.ooxml

import app.morpho.engine.layout.FidelityScorer
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.TextDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The multilingual corpus gate — the seed of the plan's per-language CI
 * quality bar. Every document under `src/test/resources/corpus/` is imported,
 * written as .docx, and checked two ways: the text in the package must match
 * the imported model almost exactly, and reading the package back with
 * [DocxReader] must reproduce the model's structure. The corpus files are the
 * gate: adding a document to the directory automatically extends the gate.
 */
class CorpusRoundTripTest {

    companion object {
        private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        @JvmStatic
        fun corpusFiles(): List<String> {
            val url = CorpusRoundTripTest::class.java.getResource("/corpus")
                ?: error("corpus resource directory missing")
            return File(url.toURI()).list()!!.sorted()
        }
    }

    private fun corpusText(name: String): String =
        CorpusRoundTripTest::class.java.getResourceAsStream("/corpus/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun documentXml(docx: ByteArray): Document {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return@use
                if (entry.name == "word/document.xml") {
                    val factory = DocumentBuilderFactory.newInstance()
                    factory.isNamespaceAware = true
                    return factory.newDocumentBuilder().parse(ByteArrayInputStream(zip.readBytes()))
                }
            }
        }
        error("word/document.xml missing")
    }

    /** Text of each non-empty w:p, in document order. */
    private fun paragraphTexts(doc: Document): List<String> {
        val paragraphs = doc.getElementsByTagNameNS(W, "p")
        val texts = mutableListOf<String>()
        for (i in 0 until paragraphs.length) {
            val text = (paragraphs.item(i) as Element).textContent
            if (text.isNotBlank()) texts += text
        }
        return texts
    }

    /**
     * Every word of a document, cells included. A walk of the top-level
     * paragraphs alone says a document with a table in it holds only the
     * prose around it, and the writers would be free to lose the table.
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
        return out.joinToString(" ")
    }

    /** How many paragraphs a document holds, cells included. */
    private fun paragraphCount(blocks: List<app.morpho.engine.layout.Block>): Int {
        var count = 0
        fun walk(list: List<app.morpho.engine.layout.Block>) {
            for (block in list) when (block) {
                is Paragraph -> count++
                is app.morpho.engine.layout.Table ->
                    for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                else -> {}
            }
        }
        walk(blocks)
        return count
    }

    @Test
    fun `the corpus covers at least eight documents including arabic and mixed-direction ones`() {
        val files = corpusFiles()
        assertTrue(files.size >= 8, "corpus: $files")
        assertTrue(files.any { it.startsWith("ar") }, "corpus needs Arabic documents")
        assertTrue(files.any { it.startsWith("mixed") }, "corpus needs a mixed-direction document")
    }

    @ParameterizedTest(name = "{0}: docx text matches the imported model")
    @MethodSource("corpusFiles")
    fun `written docx preserves the model text almost exactly`(name: String) {
        val model = PlainTextImporter.import(corpusText(name))
        val doc = documentXml(DocxWriter.toByteArray(model))

        val expected = textOf(model.blocks)
        val actual = paragraphTexts(doc).joinToString(" ")
        val similarity = FidelityScorer.textSimilarity(expected, actual)
        assertTrue(similarity >= 0.999, "$name text similarity: $similarity")
        assertTrue(
            paragraphTexts(doc).size >= paragraphCount(model.blocks),
            "$name lost paragraphs: ${paragraphTexts(doc).size} < ${paragraphCount(model.blocks)}"
        )
    }

    @ParameterizedTest(name = "{0}: reader round-trip preserves structure")
    @MethodSource("corpusFiles")
    fun `reading the docx back reproduces the model structure`(name: String) {
        val model = PlainTextImporter.import(corpusText(name))
        val readBack = DocxReader.read(DocxWriter.toByteArray(model))

        val structure = FidelityScorer.structureSimilarity(model, readBack)
        assertTrue(structure >= 0.95, "$name structure similarity: $structure")

        val expectedText = textOf(model.blocks)
        val actualText = textOf(readBack.blocks)
        assertEquals(1.0, FidelityScorer.textSimilarity(expectedText, actualText), 1e-9) {
            "$name reader text drifted"
        }
    }

    @ParameterizedTest(name = "{0}: RTL paragraphs carry w:bidi")
    @MethodSource("corpusFiles")
    fun `models with RTL paragraphs produce bidi markup`(name: String) {
        val model = PlainTextImporter.import(corpusText(name))
        val hasRtl = model.blocks.filterIsInstance<Paragraph>()
            .any { it.style.direction == TextDirection.RTL }
        if (!hasRtl) return

        val doc = documentXml(DocxWriter.toByteArray(model))
        assertTrue(
            doc.getElementsByTagNameNS(W, "bidi").length > 0,
            "$name has RTL paragraphs but no w:bidi in document.xml"
        )
    }
}
