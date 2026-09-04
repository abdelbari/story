package app.morpho.engine.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The pages the recognition fixtures were read from.
 *
 * The hOCR under `layout/src/test/resources` is what Tesseract wrote when it was
 * run over these, with the app's own language packs, at the resolution
 * the app renders a page at, with the page segmentation it asks for.
 * Recognition cannot run in the build, so its output is committed — but
 * the pages it was run over would otherwise exist nowhere, and a fixture
 * nobody can regenerate is a fixture nobody can add to.
 *
 * So they are drawn here, and drawing them is checked. To make another:
 * add it beside these, run this, and read the PNG it leaves in the
 * module's build directory with
 *
 *     TESSDATA_PREFIX=android/pdf/src/main/assets/tessdata \
 *       tesseract page.png page -l ara+eng --psm 3 --dpi 200 \
 *       -c hocr_font_info=1 -c tessedit_create_hocr=1
 *
 * which is exactly what the app asks recognition for.
 */
class FixturePagesTest {

    /** Inside the build, which exists wherever this runs. */
    private val out = File("build/fixture-pages").apply { mkdirs() }
    private val body = PDType1Font.HELVETICA
    private val bold = PDType1Font.HELVETICA_BOLD

    @Test
    fun `the pages the recognition fixtures were read from still draw`() {
        for (name in listOf("prose-and-a-table", "three-columns")) {
            val file = File(out, "$name.png")
            assertTrue(file.isFile, "$name was not drawn")
            // A4 at 200 dpi, near enough: the fixtures' own hOCR records
            // 1653 by 2338, and a page that came out a different size
            // would not be the page they were read from.
            val image = ImageIO.read(file)
            assertEquals(2338, image.height, "$name is not A4 at 200 dpi")
            assertTrue(image.width in 1650..1655, "$name is not A4 at 200 dpi: ${image.width}")
        }
    }

    @org.junit.jupiter.api.BeforeEach
    fun draw() {
        render("prose-and-a-table") { _, _, cs -> proseAndTable(cs) }
        render("three-columns") { _, _, cs -> threeColumns(cs) }
    }

    private fun render(name: String, draw: (PDDocument, PDPage, PDPageContentStream) -> Unit) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs -> draw(doc, page, cs) }
            // 200 dpi is what the app renders a page at before recognition.
            val image = PDFRenderer(doc).renderImageWithDPI(0, 200f)
            ImageIO.write(image, "png", File(out, "$name.png"))
        }
        println("MADE $name")
    }

    private fun text(cs: PDPageContentStream, x: Float, y: Float, size: Float, font: PDType1Font, s: String) {
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(s)
        cs.endText()
    }

    /** An institution's page: a heading, prose, a ruled table, more prose. */
    private fun proseAndTable(cs: PDPageContentStream) {
        text(cs, 56f, 780f, 16f, bold, "Report of the Standing Committee")
        val opening = listOf(
            "The committee met to consider the applications received during the term,",
            "and records below the numbers it agreed at that meeting. The figures are",
            "given by section, and the committee notes that the delivery section had",
            "the fewest applications outstanding at the close of the period.",
        )
        opening.forEachIndexed { at, line -> text(cs, 56f, 745f - at * 16f, 11f, body, line) }

        // A ruled table: three columns, four rows, all rules drawn.
        val left = 56f
        val right = 539f
        val top = 660f
        val rowHeight = 26f
        val rows = 4
        val columns = floatArrayOf(left, 260f, 400f, right)
        cs.setLineWidth(0.9f)
        for (r in 0..rows) {
            val y = top - r * rowHeight
            cs.moveTo(left, y); cs.lineTo(right, y); cs.stroke()
        }
        for (x in columns) {
            cs.moveTo(x, top); cs.lineTo(x, top - rows * rowHeight); cs.stroke()
        }
        val cells = listOf(
            listOf("Section", "Applications", "Outstanding"),
            listOf("Design", "148", "12"),
            listOf("Delivery", "203", "4"),
            listOf("Records", "96", "31"),
        )
        cells.forEachIndexed { r, row ->
            row.forEachIndexed { c, value ->
                val font = if (r == 0) bold else body
                text(cs, columns[c] + 8f, top - r * rowHeight - 18f, 11f, font, value)
            }
        }

        val closing = listOf(
            "The committee agreed that the figures above should be published with the",
            "minutes, and that the records section would be asked for an explanation",
            "of the number outstanding before the next meeting is called.",
        )
        closing.forEachIndexed { at, line -> text(cs, 56f, 520f - at * 16f, 11f, body, line) }
    }

    /** A genuine three-column page, which the flow ordering must keep getting right. */
    private fun threeColumns(cs: PDPageContentStream) {
        text(cs, 56f, 780f, 16f, bold, "Three columns across the measure")
        val columns = listOf(
            listOf(
                "The first column opens", "the argument and runs", "down the left of the",
                "page without any", "reference to what is", "printed beside it, so",
                "that a reading which", "took the lines across", "would be obvious.",
            ),
            listOf(
                "The second column", "carries the middle of", "the page and says",
                "something different", "again, so that any", "interleaving of the",
                "three shows up at", "once in the sentences", "it produces.",
            ),
            listOf(
                "The third column ends", "the page on the right", "and closes the",
                "argument the first", "one opened, which is", "how a reader can",
                "tell the order came", "out right without", "counting anything.",
            ),
        )
        val x = floatArrayOf(56f, 240f, 424f)
        columns.forEachIndexed { c, lines ->
            lines.forEachIndexed { at, line -> text(cs, x[c], 740f - at * 16f, 10.5f, body, line) }
        }
    }
}
