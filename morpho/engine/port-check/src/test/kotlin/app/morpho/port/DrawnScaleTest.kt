package app.morpho.port

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The drawn page's type scale, held to the one the preview uses.
 *
 * The app makes a PDF two ways: it draws one on a canvas, and it hands the
 * preview to the system print sheet. Both were given a scale of their own,
 * and the two had drifted — a first-level heading at 21 points drawn and
 * 20 previewed, a third-level one at 13 and 13.5, a title bold drawn and
 * not previewed, six points of air under a body paragraph drawn and nine
 * previewed. A reader who looked at the preview, saved the file and
 * printed it had three documents, and nothing in the build said so.
 *
 * They are one scale now, and this holds the drawn side to asking for it:
 * the exporter runs only on a phone, so what can be checked from here is
 * that it names no size of its own. A number back in either of these
 * functions is the drift starting again, and the preview would go on
 * showing the reader something else.
 */
class DrawnScaleTest {

    private val exporter =
        File("../../android/app/src/main/kotlin/app/morpho/converter/PdfFileExporter.kt")

    @Test
    fun `the exporter this reads is where it is expected to be`() {
        assertTrue(exporter.isFile, "no exporter at ${exporter.absolutePath}")
    }

    @Test
    fun `the drawn page names no size of its own`() {
        for (name in listOf("paintFor(kind: ParagraphKind)", "spacingAfter(kind: ParagraphKind)")) {
            val body = bodyOf(name)
            assertTrue(body.isNotBlank(), "could not find $name in ${exporter.name}")
            assertTrue(
                body.contains("TypeScale"),
                "$name sets type without asking the scale the preview uses:\n$body",
            )
            val numbers = Regex("(?<![\\w.])\\d+(?:\\.\\d+)?f?(?![\\w(])").findAll(body)
                .map { it.value }.filterNot { it == "0" }.toList()
            assertEquals(
                emptyList<String>(),
                numbers,
                "$name names a measurement of its own, which is how the two scales " +
                    "drifted apart before:\n$body",
            )
        }
    }

    @Test
    fun `the drawn page is set on the sheet the other writers use`() {
        // The same drift, on the other axis: a document with no page of
        // its own — a text file, a Markdown file — was written to Word as
        // A4 with inch margins and drawn as A4 with two-thirds of an inch.
        val body = bodyOf("fun of(measured: PageSetup?): Sheet")
        assertTrue(body.isNotBlank(), "could not find Sheet.of in ${exporter.name}")
        assertTrue(
            body.contains("PageSetup.DEFAULT"),
            "the drawn page invents a sheet for a document that measured none:\n$body",
        )
    }

    /**
     * The body of the function whose declaration contains [signature],
     * from its declaration to the blank line after it — enough to see
     * what it is made of without parsing Kotlin.
     */
    private fun bodyOf(signature: String): String {
        val lines = exporter.readLines()
        val start = lines.indexOfFirst { it.contains(signature) }
        if (start < 0) return ""
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (index in start until lines.size) {
            val line = lines[index]
            out.append(line).append("\n")
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
            // An expression body ends at its own line when no brace opened.
            if (!opened && index > start && !line.trimEnd().endsWith("=")) break
        }
        return out.toString()
    }
}
