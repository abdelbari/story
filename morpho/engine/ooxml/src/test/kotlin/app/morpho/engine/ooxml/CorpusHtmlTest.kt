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
        val expected = model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        val similarity = FidelityScorer.textSimilarity(expected, rendered)
        assertTrue(similarity >= 0.999, "$name html text similarity: $similarity")
    }
}
