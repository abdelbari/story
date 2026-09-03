package app.morpho.engine.ooxml

import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A package that keeps its document somewhere else.
 *
 * OPC names the main part by a relationship rather than by a path, and a
 * reader that knows only the conventional `word/document.xml` refuses a
 * package that names another — the file Word writes after it has repaired
 * one, whose document is `word/document2.xml`, being the case people
 * actually meet: Word opens it without a word and the converter said "not
 * a .docx".
 *
 * Everything a document is made of is then beside its main part rather
 * than under a fixed `word/`: its styles, its numbering, its notes, its
 * running header and footer, its pictures and its own relationships.
 */
class PackagedElsewhereTest {

    // A real 1x1 PNG; what matters here is that it comes back byte for byte.
    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    /** A document that uses every part a .docx has: styles, numbering, notes, furniture, media. */
    private val document = DocumentModel(
        blocks = listOf(
            Paragraph(listOf(TextRun("Method")), ParagraphStyle(kind = ParagraphKind.HEADING_1)),
            Paragraph(
                listOf(
                    TextRun("A claim"),
                    TextRun("1", superscript = true,
                        note = listOf(Paragraph(listOf(TextRun("Board minutes, March."))))),
                    TextRun(" and a "),
                    TextRun("link", link = "https://example.org"),
                ),
            ),
            Paragraph(listOf(TextRun("First aim")), ParagraphStyle(listMarker = ListMarker.NUMBERED)),
            ImageBlock(png, "image/png", 32, 16),
            Table(listOf(TableRow(listOf(TableCell(listOf(Paragraph(listOf(TextRun("cell"))))))))),
        ),
        header = listOf(Paragraph(listOf(TextRun("A running head")))),
        footer = listOf(Paragraph(listOf(TextRun("A running foot")))),
    )

    private fun entries(docx: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out += entry.name to zip.readBytes()
            }
        }
        return out
    }

    private fun repackage(docx: ByteArray, rename: (String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries(docx)) {
                zip.putNextEntry(ZipEntry(rename(name)))
                // The package's own relationships name the document; the
                // content types name every part by path. Both are rewritten
                // the way the parts were.
                val written =
                    if (name == "_rels/.rels" || name == "[Content_Types].xml") {
                        bytes.toString(Charsets.UTF_8)
                            .let { text ->
                                Regex("""(?<=")(/?)(word/[^"]*)(?=")""").replace(text) { match ->
                                    match.groupValues[1] + rename(match.groupValues[2])
                                }
                            }
                            .toByteArray(Charsets.UTF_8)
                    } else {
                        bytes
                    }
                zip.write(written)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Everything the document says, so two readings can be compared whole. */
    private fun saidBy(model: DocumentModel): String {
        val out = mutableListOf<String>()
        fun walk(blocks: List<Block>) {
            for (block in blocks) when (block) {
                is Paragraph -> {
                    out += "${block.style.kind}${block.style.listMarker?.let { "/$it" }.orEmpty()} ${block.text}"
                    for (run in block.runs) {
                        run.link?.let { out += "link $it" }
                        run.note?.let { note ->
                            out += "note " + note.filterIsInstance<Paragraph>().joinToString { it.text }
                        }
                    }
                }
                is Table -> for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                is ImageBlock -> out += "image ${block.mimeType} ${block.bytes.size} bytes"
            }
        }
        walk(model.blocks)
        walk(model.header)
        walk(model.footer)
        return out.joinToString("\n")
    }

    @Test
    fun `a document Word has repaired is read where the package says it is`() {
        val docx = DocxWriter.toByteArray(document)
        val repaired = repackage(docx) { name ->
            when (name) {
                "word/document.xml" -> "word/document2.xml"
                "word/_rels/document.xml.rels" -> "word/_rels/document2.xml.rels"
                else -> name
            }
        }
        assertTrue(
            entries(repaired).any { it.first == "word/document2.xml" },
            "the package under test must be the renamed one",
        )
        assertEquals(saidBy(DocxReader.read(docx)), saidBy(DocxReader.read(repaired)))
    }

    @Test
    fun `a package that keeps its parts in a directory of its own is read there`() {
        val docx = DocxWriter.toByteArray(document)
        val moved = repackage(docx) { name ->
            if (name.startsWith("word/")) "parts/" + name.removePrefix("word/") else name
        }
        assertEquals(saidBy(DocxReader.read(docx)), saidBy(DocxReader.read(moved)))
    }

    @Test
    fun `a package that says nothing is read where a docx keeps its document`() {
        // The relationships part is what names the document; without one,
        // the conventional path is what a .docx means.
        val docx = DocxWriter.toByteArray(document)
        val silent = ByteArrayOutputStream()
        ZipOutputStream(silent).use { zip ->
            for ((name, bytes) in entries(docx)) {
                if (name == "_rels/.rels") continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertEquals(saidBy(DocxReader.read(docx)), saidBy(DocxReader.read(silent.toByteArray())))
    }

    @Test
    fun `a package with no document at all is still refused`() {
        val docx = DocxWriter.toByteArray(document)
        val gutted = ByteArrayOutputStream()
        ZipOutputStream(gutted).use { zip ->
            for ((name, bytes) in entries(docx)) {
                if (name.endsWith("document.xml")) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocxReader.read(gutted.toByteArray())
        }
    }
}
