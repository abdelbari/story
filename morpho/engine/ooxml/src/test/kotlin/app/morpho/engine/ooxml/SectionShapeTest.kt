package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A report of portrait pages turns one sideways for a wide table. Word
 * says a document's shape once for each section of it, and a converter
 * that says it once for the whole document hands the wide page back
 * upright with every line set to the wrong width.
 */
class SectionShapeTest {

    private fun sheet(width: Float, height: Float) = PageSetup(
        widthPt = width,
        heightPt = height,
        marginTopPt = 72f,
        marginBottomPt = 72f,
        marginLeftPt = 72f,
        marginRightPt = 72f,
    )

    private val portrait = sheet(595f, 842f)
    private val landscape = sheet(842f, 595f)

    private fun line(text: String, setup: PageSetup? = null) =
        Paragraph(listOf(TextRun(text)), ParagraphStyle(sectionSetup = setup))

    private fun documentXml(docx: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no document part")
    }

    @Test
    fun `the page a report turns sideways is turned in Word too`() {
        val xml = documentXml(
            DocxWriter.toByteArray(
                DocumentModel(
                    blocks = listOf(
                        line("Before the wide table."),
                        line("The page of the wide table.", landscape),
                        line("After it, upright again.", portrait),
                    ),
                    pageSetup = portrait,
                )
            )
        )
        val sections = Regex("<w:sectPr>").findAll(xml).count()
        assertEquals(3, sections, xml)
        assertTrue(xml.contains("""w:orient="landscape""""), "the turned page is not turned: $xml")
        // The section's properties sit on the paragraph that ends it —
        // the one before the turn — and a paragraph's properties come
        // before its words, so they are read before that paragraph's text
        // and long before the turned page's.
        val firstSection = xml.indexOf("</w:sectPr>")
        assertTrue(
            firstSection < xml.indexOf("Before the wide table."),
            "the first section's properties belong to the paragraph before the turn",
        )
        assertTrue(
            xml.indexOf("Before the wide table.") < xml.indexOf("The page of the wide table."),
            "the pages stay in the order they were written",
        )
    }

    @Test
    fun `a document of one shape is written as it always was`() {
        val xml = documentXml(
            DocxWriter.toByteArray(
                DocumentModel(listOf(line("One."), line("Two.")), pageSetup = portrait)
            )
        )
        assertEquals(1, Regex("<w:sectPr>").findAll(xml).count(), xml)
        assertFalse(xml.contains("landscape"))
    }

    @Test
    fun `a section that ends on a table is ended by a paragraph of its own`() {
        // Word puts a section's properties on a paragraph; a table cannot
        // carry them, so one is made to carry them.
        val table = Table(listOf(TableRow(listOf(TableCell(listOf(line("a cell")))))))
        val xml = documentXml(
            DocxWriter.toByteArray(
                DocumentModel(
                    blocks = listOf(line("Before."), table, line("After the turn.", landscape)),
                    pageSetup = portrait,
                )
            )
        )
        assertEquals(2, Regex("<w:sectPr>").findAll(xml).count(), xml)
        // One paragraph, not two: the one carrying the section's
        // properties is the paragraph a table must be followed by, so no
        // empty one is written in front of it. Two would put a blank line
        // under the table that the original does not have.
        assertTrue(xml.contains("""</w:tbl><w:p><w:pPr><w:sectPr>"""), xml)
        assertFalse(xml.contains("""</w:tbl><w:p/>"""), xml)
    }

    @Test
    fun `a section keeps the head and foot the document carries`() {
        val xml = documentXml(
            DocxWriter.toByteArray(
                DocumentModel(
                    blocks = listOf(line("Before."), line("After.", landscape)),
                    pageSetup = portrait,
                    header = listOf(line("The running head")),
                )
            )
        )
        // Both sections name it, so nothing has to be inherited across a turn.
        assertEquals(2, Regex("w:headerReference").findAll(xml).count(), xml)
    }

    @Test
    fun `a turned section read back from Word is turned still`() {
        val model = DocumentModel(
            blocks = listOf(
                line("Before the wide table."),
                line("The page of the wide table.", landscape),
                line("After it, upright again.", portrait),
            ),
            pageSetup = portrait,
        )
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        val paragraphs = read.blocks.filterIsInstance<Paragraph>()
        assertEquals(3, paragraphs.size, paragraphs.map { it.text }.toString())
        assertEquals(null, paragraphs[0].style.sectionSetup, "the document opens on its own shape")
        val turned = paragraphs[1].style.sectionSetup
        assertTrue(turned != null && turned.widthPt > turned.heightPt, "the turn was lost: $turned")
        val back = paragraphs[2].style.sectionSetup
        assertTrue(back != null && back.widthPt < back.heightPt, "the turn back was lost: $back")
        // And the document as a whole keeps the shape most of it has.
        assertTrue(read.pageSetup!!.widthPt < read.pageSetup!!.heightPt)
    }

    @Test
    fun `a document of one shape reads back saying nothing about sections`() {
        val model = DocumentModel(listOf(line("One."), line("Two.")), pageSetup = portrait)
        val read = DocxReader.read(DocxWriter.toByteArray(model))
        assertTrue(read.blocks.filterIsInstance<Paragraph>().all { it.style.sectionSetup == null })
    }
}
