package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A Word document's links are relationships, and a relationship target is
 * written into the package verbatim.
 *
 * So an address carried out of a crafted PDF or a crafted .docx ends up in
 * the file the reader opens in Word — and pointed at a share on somebody
 * else's machine, that is a document which reaches a stranger's host on
 * open, from an app that holds no permission to touch a network at all.
 * The address is dropped and the words are kept.
 */
class OutwardLinkTest {

    private fun partsOf(docx: ByteArray): Map<String, String> {
        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return parts
    }

    private fun written(vararg targets: String): Map<String, String> = partsOf(
        DocxWriter.toByteArray(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(TextRun("before ")) +
                            targets.mapIndexed { index, target -> TextRun("words $index", link = target) } +
                            TextRun(" after")
                    )
                )
            )
        )
    )

    @Test
    fun `a share on somebody's machine reaches the package nowhere`() {
        val parts = written("""\\attacker.example\share\x""", "file:///etc/passwd")
        val whole = parts.values.joinToString("\n")
        assertFalse(whole.contains("attacker.example"), whole.take(2000))
        assertFalse(whole.contains("/etc/passwd"), whole.take(2000))
        // The words are still the document's.
        assertTrue(parts["word/document.xml"]!!.contains("words 0"), parts["word/document.xml"])
        assertTrue(parts["word/document.xml"]!!.contains("words 1"), parts["word/document.xml"])
        // And nothing was left half-written: no hyperlink naming a
        // relationship the package does not define.
        assertFalse(parts["word/document.xml"]!!.contains("w:hyperlink"), parts["word/document.xml"])
    }

    @Test
    fun `an ordinary address is still a relationship`() {
        val parts = written("https://example.org/a")
        assertTrue(parts["word/_rels/document.xml.rels"]!!.contains("https://example.org/a"))
        assertTrue(parts["word/document.xml"]!!.contains("w:hyperlink"))
    }

    @Test
    fun `one address of each kind leaves exactly one relationship`() {
        val parts = written("https://example.org/a", "javascript:alert(1)")
        val rels = parts["word/_rels/document.xml.rels"]!!
        assertTrue(rels.contains("https://example.org/a"), rels)
        assertFalse(rels.contains("javascript"), rels)
        assertTrue(
            Regex("relationships/hyperlink").findAll(rels).count() == 1,
            "expected one hyperlink relationship: $rels",
        )
        assertTrue(
            Regex("<w:hyperlink").findAll(parts["word/document.xml"]!!).count() == 1,
            parts["word/document.xml"],
        )
    }
}
