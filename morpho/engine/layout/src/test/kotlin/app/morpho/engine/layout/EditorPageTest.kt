package app.morpho.engine.layout

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * The page the editor is, and — where a browser is to hand — the page
 * worked in one against the real engine.
 */
class EditorPageTest {

    private fun document() = DocumentModel(
        listOf(
            Paragraph(listOf(TextRun("The form "), TextRun("arrives", bold = true), TextRun(" today."))),
            Paragraph(
                listOf(TextRun("الاستمارة في البحث العلمي"), TextRun(" and English", italic = true)),
                ParagraphStyle(direction = TextDirection.RTL),
            ),
            Table(listOf(TableRow(listOf(TableCell(listOf(Paragraph(listOf(TextRun("cell"))))))))),
            Paragraph(listOf(TextRun("last one")), ParagraphStyle(kind = ParagraphKind.HEADING_2)),
            Paragraph(listOf(TextRun("Name:\tvalue")), ParagraphStyle(tabStopsPt = listOf(100f))),
        ),
        comments = listOf(Comment(1, "a remark")),
    )

    @Test
    fun `the page is locked down, editable, marked, and carries its script`() {
        val html = HtmlWriter.writeEditor(document())
        assertTrue(html.contains("""http-equiv="Content-Security-Policy""""), "no policy")
        assertTrue(html.contains("default-src 'none'"), "the policy allows a source")
        assertTrue(html.contains("""<div id="doc" contenteditable="true""""), "not editable")
        for (at in 0..3) assertTrue(html.contains("""data-block="$at""""), "block $at is not marked")
        assertTrue(html.contains("window.morphoEditor"), "the script is not in the page")
        assertFalse(html.contains("""<sup class="comment-mark""""), "a remark's mark is the page's, not the document's, and would throw every offset off")
        assertFalse(html.contains("""class="page-header""""), "a running head is not a block an edit can name")
    }

    /**
     * SKIPPED where there is no browser to drive. See `src/test/spike`.
     */
    @Test
    fun `the page worked in Chromium agrees with the engine after every action`() {
        val spike = File("src/test/spike")
        val script = File(spike, "editor-spike.mjs")
        val node = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "node").canExecute() }
        assumeTrue(node && script.isFile && File(spike, "node_modules/playwright").exists(), "no node or Playwright to drive a browser with")
        val lock = Any()
        var state = EditorState.open(document())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun respond(ex: HttpExchange, body: String, type: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/page") { ex -> respond(ex, HtmlWriter.writeEditor(synchronized(lock) { state.document }), "text/html") }
        server.createContext("/step") { ex ->
            val json = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val reply = synchronized(lock) {
                val step = EditorProtocol.step(state, json)
                state = step.state
                step.reply
            }
            respond(ex, reply, "application/json")
        }
        server.createContext("/truth") { ex ->
            val truth = synchronized(lock) {
                Json.write(
                    mapOf(
                        "texts" to state.document.blocks.map { block ->
                            when (block) {
                                is Paragraph -> block.text
                                is Table -> block.rows.map { r -> r.cells.map { c -> c.blocks.map { (it as? Paragraph)?.text } } }
                                else -> null
                            }
                        },
                        "selection" to listOf(state.selection.anchor, state.selection.focus).map { c ->
                            val cell = c.cell
                            if (cell == null) listOf(c.block, c.offset) else listOf(c.block, c.offset, cell.row, cell.column, cell.paragraph)
                        },
                        "runs" to state.document.blocks.map { b -> (b as? Paragraph)?.runs?.map { listOf(it.text, it.bold, it.italic) } },
                        "canUndo" to state.canUndo,
                    )
                )
            }
            respond(ex, truth, "application/json")
        }
        server.start()
        try {
            val process = ProcessBuilder("node", script.name, server.address.port.toString())
                .directory(spike).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            File(spike, "spike.out").writeText(out)
            assertEquals(0, code, out)
            assertTrue(out.trimEnd().endsWith("SPIKE OK"), out)
        } finally {
            server.stop(0)
        }
    }
}
