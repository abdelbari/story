package app.morpho.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every Android reader against the engine reader it mirrors.
 *
 * Nine of the app's readers exist twice: once in the engine against
 * desktop PDFBox, once in the app against the tom-roush port. The two are
 * meant to be the same code with the library's name changed, and keeping
 * them so has been left to whoever remembers. It was not remembered: the
 * engine learned to find every column gutter of a page by asking each side
 * of the first one again — a page of three columns being a page of two,
 * one of which is a page of two — and the twin was never given it. So a
 * newspaper, a dictionary or a conference paper converted on a laptop came
 * out right and converted on a phone came out with two of its columns
 * interleaved line by line. Half the lines of a three-column page.
 *
 * Nothing in the build said so, which is why this exists. The engine
 * source is read, the library's name changed the way the twin changes it,
 * and the two compared — as code, not as text: the imports are sorted,
 * since renaming a package reorders them, and comments are left out, since
 * a twin says in its own words that it is one. What is left is what the
 * reader does, and it has to match.
 */
class TwinParityTest {

    /** The readers that exist twice, by the name they share. */
    private val twinned = listOf(
        "Attempts",
        "DocumentOutline",
        "GlyphUnicode",
        "PageHighlights",
        "PageLinks",
        "PaintColor",
        "PositionTextStripper",
        "RuleCatcher",
        "StructureTreeReader",
    )

    /**
     * Every name the transform rewrites: the twins refer to each other by
     * their own names, so each has to be renamed inside all of them.
     */
    private val renamed = twinned + "PageImages"

    private val engine = File("../pdf-read/src/main/kotlin/app/morpho/engine/pdf")
    private val app = File("../../android/pdf/src/main/kotlin/app/morpho/pdf")

    @Test
    fun `the sources this compares are where they are expected to be`() {
        assertTrue(engine.isDirectory, "no engine readers at ${engine.absolutePath}")
        assertTrue(app.isDirectory, "no app readers at ${app.absolutePath}")
    }

    @Test
    fun `every Android reader is its engine reader with the library renamed`() {
        val adrift = mutableListOf<String>()
        for (name in twinned) {
            val original = File(engine, "$name.kt")
            val twin = File(app, "Android$name.kt")
            if (!original.isFile || !twin.isFile) {
                adrift += "$name: expected both ${original.name} and ${twin.name}"
                continue
            }
            val wanted = code(ported(original.readText()))
            val found = code(twin.readText())
            if (wanted != found) {
                adrift += "$name differs: " + firstDifference(wanted, found)
            }
        }
        assertEquals(emptyList<String>(), adrift, "an Android reader has drifted from its engine twin")
    }

    /** [source] with the library, the package and the readers' names as the twin writes them. */
    private fun ported(source: String): String {
        var s = source.replace("package app.morpho.engine.pdf", "package app.morpho.pdf")
        s = s.replace("org.apache.pdfbox", "com.tom_roush.pdfbox")
            .replace("org.apache.fontbox", "com.tom_roush.fontbox")
        for (name in renamed) s = Regex("\\b$name\\b").replace(s, "Android$name")
        return s
    }

    /**
     * What [source] does, with what it says about itself left out: no
     * comments, no blank lines, and the imports in order, since renaming a
     * package moves them and a twin explains in its own words that it is
     * one.
     */
    private fun code(source: String): List<String> {
        val kept = mutableListOf<String>()
        val imports = mutableListOf<String>()
        var inBlockComment = false
        for (raw in source.split("\n")) {
            var line = raw
            if (inBlockComment) {
                val closes = line.indexOf("*/")
                if (closes < 0) continue
                line = line.substring(closes + 2)
                inBlockComment = false
            }
            // A block comment opening on this line takes the rest of it,
            // and possibly the lines after.
            while (true) {
                val opens = line.indexOf("/*")
                if (opens < 0) break
                val closes = line.indexOf("*/", opens + 2)
                if (closes < 0) {
                    line = line.substring(0, opens)
                    inBlockComment = true
                    break
                }
                line = line.substring(0, opens) + line.substring(closes + 2)
            }
            val without = stripLineComment(line).trim()
            if (without.isEmpty()) continue
            if (without.startsWith("import ")) imports += without else kept += without
        }
        return imports.sorted() + kept
    }

    /**
     * [line] up to a `//` that is not inside a string. A URL in a comment
     * is a comment; a "//" inside quotes is the document's own text.
     */
    private fun stripLineComment(line: String): String {
        var quote: Char? = null
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                quote != null && c == '\\' -> index++
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == '/' && index + 1 < line.length && line[index + 1] == '/' ->
                    return line.substring(0, index)
            }
            index++
        }
        return line
    }

    /** The first line the two do not share, in the words a reader would use. */
    private fun firstDifference(wanted: List<String>, found: List<String>): String {
        val at = wanted.indices.firstOrNull { it >= found.size || wanted[it] != found[it] }
            ?: return "the twin has ${found.size - wanted.size} lines the engine does not"
        val theirs = found.getOrNull(at) ?: "<nothing: the twin stops here>"
        return "at line ${at + 1} of the code the engine has \"${wanted[at].take(70)}\" " +
            "and the twin has \"${theirs.take(70)}\""
    }
}
