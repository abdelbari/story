package app.morpho.engine.ooxml

import app.morpho.engine.layout.Block
import app.morpho.engine.layout.MarkdownWriter
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * The corpus through Markdown and back.
 *
 * The app offers a document as Markdown, and reads Markdown as a document,
 * so a file it writes is a file it may be asked to convert next — its own
 * output is an input. Every corpus document is written as Markdown and read
 * back, and must come back word for word: a converter whose writer says
 * something its reader cannot hear loses whatever it says that way, and it
 * loses it silently, one conversion later, in somebody's thesis.
 *
 * Adding a document to `src/test/resources/corpus/` extends this gate with
 * the others.
 */
class CorpusMarkdownTest {

    companion object {
        @JvmStatic
        fun corpusFiles(): List<String> {
            val url = CorpusMarkdownTest::class.java.getResource("/corpus")
                ?: error("corpus resource directory missing")
            return File(url.toURI()).list()!!.sorted()
        }
    }

    @ParameterizedTest(name = "{0}: markdown reads back the words it was written from")
    @MethodSource("corpusFiles")
    fun `a document written as markdown reads back word for word`(name: String) {
        val text = CorpusMarkdownTest::class.java.getResourceAsStream("/corpus/$name")!!
            .readBytes().toString(Charsets.UTF_8)
        val model = PlainTextImporter.import(text)
        val back = PlainTextImporter.import(MarkdownWriter.write(model))
        assertEquals(textOf(model.blocks), textOf(back.blocks), name)
    }

    @ParameterizedTest(name = "{0}: markdown keeps what it points at")
    @MethodSource("corpusFiles")
    fun `every link and note survives being written and read`(name: String) {
        val text = CorpusMarkdownTest::class.java.getResourceAsStream("/corpus/$name")!!
            .readBytes().toString(Charsets.UTF_8)
        val model = PlainTextImporter.import(text)
        val back = PlainTextImporter.import(MarkdownWriter.write(model))
        assertEquals(linksOf(model.blocks), linksOf(back.blocks), "$name: links")
        assertEquals(notesOf(model.blocks), notesOf(back.blocks), "$name: notes")
    }

    /** Every word of a document — cells and the words of its notes included. */
    private fun textOf(blocks: List<Block>): String {
        val out = mutableListOf<String>()
        fun walk(list: List<Block>) {
            for (block in list) when (block) {
                is Paragraph -> out += block.text
                is Table -> for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                else -> {}
            }
        }
        walk(blocks)
        out += notesOf(blocks)
        return out.joinToString(" ")
    }

    /** Where the document points, in the order it points there. */
    private fun linksOf(blocks: List<Block>): List<String> = runsOf(blocks).mapNotNull { it.link }

    /** The words of each note, under the mark that calls it. */
    private fun notesOf(blocks: List<Block>): List<String> =
        runsOf(blocks).filter { it.note != null }.map { run ->
            run.text + ": " + run.note!!.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        }

    private fun runsOf(blocks: List<Block>): List<app.morpho.engine.layout.TextRun> {
        val out = mutableListOf<app.morpho.engine.layout.TextRun>()
        fun walk(list: List<Block>) {
            for (block in list) when (block) {
                is Paragraph -> out += block.runs
                is Table -> for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                else -> {}
            }
        }
        walk(blocks)
        return out
    }
}
