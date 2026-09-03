package app.morpho.engine.ooxml

import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.PlainTextImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * The sheet a document with no page of its own is set on, in every writer.
 *
 * A PDF and a Word file both say what page they are on. A text file and a
 * Markdown file do not, and that is a whole direction of the app: text or
 * Markdown in, Word or PDF out. Three writers answered the question three
 * ways — the Word file came out A4 with inch margins, the drawn page A4
 * with two-thirds of an inch, and the preview wrote no page rule at all,
 * which left the print sheet to lay the document out on whatever the
 * framework preferred. The same notes.md was three different documents
 * depending which button was pressed.
 */
class DefaultPageTest {

    private val model = PlainTextImporter.import("# Notes\n\nA paragraph of a text file.\n")

    @Test
    fun `a text file really does arrive with no page of its own`() {
        // The premise: without this the rest of the file tests nothing.
        assertNull(model.pageSetup, "a plain text import has learned to measure a page")
    }

    @Test
    fun `the default lands exactly on the twentieths of a point Word counts in`() {
        // Word stores a page in twips. A default that does not land on a
        // whole one writes a sheet a hair off A4, and the document opens
        // with a page size Word calls Custom.
        with(PageSetup.DEFAULT) {
            assertEquals(11906, (widthPt * 20f).roundToInt())
            assertEquals(16838, (heightPt * 20f).roundToInt())
            for (margin in listOf(marginTopPt, marginBottomPt, marginLeftPt, marginRightPt)) {
                assertEquals(1440, (margin * 20f).roundToInt(), "a margin is not an inch")
            }
            assertEquals(708, (headerDistancePt!! * 20f).roundToInt())
            assertEquals(708, (footerDistancePt!! * 20f).roundToInt())
        }
    }

    @Test
    fun `the Word file is set on the default sheet`() {
        val xml = partOf(DocxWriter.toByteArray(model), "word/document.xml")
        assertTrue(
            xml.contains("""<w:pgSz w:w="11906" w:h="16838"/>"""),
            "the section does not name A4: ${sectionOf(xml)}",
        )
        assertTrue(
            xml.contains("""<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" """),
            "the section does not name inch margins: ${sectionOf(xml)}",
        )
        assertTrue(
            xml.contains("""w:header="708" w:footer="708" w:gutter="0"/>"""),
            "the section does not put the head and foot where Word does: ${sectionOf(xml)}",
        )
    }

    @Test
    fun `what the Word file says is what the default is`() {
        // Written and read back: the one that proves the two halves agree
        // about the same sheet rather than each rounding its own way.
        val read = DocxReader.read(DocxWriter.toByteArray(model)).pageSetup
        assertEquals(PageSetup.DEFAULT, read)
    }

    @Test
    fun `the preview names a sheet rather than leaving the printer to guess`() {
        // The app makes a PDF by printing this very stylesheet. With no
        // rule the print framework chose the page, so the printed file and
        // the written .docx disagreed about what document it was.
        val html = HtmlWriter.write(model, "notes.md")
        with(PageSetup.DEFAULT) {
            assertTrue(
                html.contains("@page{size:${pt(widthPt)} ${pt(heightPt)};margin:${margins()};}"),
                "no page rule for a document with no page: $html",
            )
        }
    }

    private fun PageSetup.margins(): String =
        "${pt(marginTopPt)} ${pt(marginRightPt)} ${pt(marginBottomPt)} ${pt(marginLeftPt)}"

    /** As the preview writes a measurement. */
    private fun pt(points: Float): String = "%.1fpt".format(java.util.Locale.ROOT, points)

    private fun sectionOf(xml: String): String {
        val at = xml.indexOf("<w:sectPr>")
        return if (at < 0) "no section at all" else xml.substring(at, minOf(at + 300, xml.length))
    }

    private fun partOf(docx: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) return String(zip.readBytes(), Charsets.UTF_8)
            }
        }
        error("no $name in the package")
    }
}
