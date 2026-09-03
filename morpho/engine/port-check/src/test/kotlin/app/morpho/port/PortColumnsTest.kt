package app.morpho.port

import app.morpho.pdf.AndroidPositionTextStripper
import com.tom_roush.pdfbox.pdmodel.PDDocument as PortDocument
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A page of three columns, read by the app's own reader on the port.
 *
 * The engine finds every gutter of a page by asking each side of the one
 * it found the same question again: a page of three columns is a page of
 * two, one of which is a page of two. The engine's own tests prove that.
 * They cannot prove the phone does it, and for a while the phone did not
 * — the reader that ships found one gutter and stopped, so the two
 * columns left on the other side of it were read as one, a line of each
 * in turn, and a newspaper, a dictionary or a conference paper converted
 * on a laptop came out right and converted on a phone came out shuffled.
 *
 * This reads the fixture with the shipped reader and asks the one question
 * that separates the two behaviours: does every line it found lie inside a
 * single column? A line spanning two of them is a line the reader never
 * cut, and every word after it is in the wrong order.
 */
class PortColumnsTest {

    private val left = 60f
    private val right = 535f
    private val gutter = 20f

    private fun bandOf(columns: Int) = (right - left - gutter * (columns - 1)) / columns

    @Test
    fun `every line of a page of three columns lies inside one column`() {
        eachLineInItsOwnColumn(3)
    }

    @Test
    fun `and of a page of four, which needs the question asked twice over`() {
        // Three columns are found by asking one side of the first gutter
        // again; four need both sides asked, and then a side of those.
        eachLineInItsOwnColumn(4)
    }

    private fun eachLineInItsOwnColumn(columns: Int) {
        val band = bandOf(columns)
        val lines = PortDocument.load(pageOf(columns)).use { doc ->
            AndroidPositionTextStripper().capture(doc)
        }
        assertTrue(lines.size > columns, "the page was not read at all: ${lines.size} lines")
        // Where each column stands across the page.
        val bands = (0 until columns).map { index ->
            val start = left + index * (band + gutter)
            start to start + band
        }
        val spanning = lines.filter { line ->
            bands.none { (start, end) -> line.x >= start - SLACK && line.xEnd <= end + SLACK }
        }
        assertTrue(
            spanning.isEmpty(),
            "${spanning.size} of ${lines.size} lines cross a gutter, so the columns were " +
                "not all found: " + spanning.take(3).joinToString(" | ") {
                    "%.0f..%.0f %s".format(it.x, it.xEnd, it.text.take(40))
                },
        )
    }

    private fun wrap(text: String, font: PDFont, size: Float, width: Float): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.getStringWidth(candidate) / 1000f * size > width && line.isNotEmpty()) {
                out += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    /** A column's worth of prose, long enough to fill the measure it is set in. */
    private fun column(name: String): List<String> = (1..5).map { number ->
        "$name paragraph $number opens the argument and carries it down the page in " +
            "sentences long enough to fill the measure they are set in, so the lines run " +
            "ragged at their ends the way a column of prose does and not otherwise at all, " +
            "going on far enough that the foot of the column is reached in the ordinary way."
    }

    private fun pageOf(columns: Int): ByteArray {
        val band = bandOf(columns)
        val text = listOf("First", "Second", "Third", "Fourth").take(columns).map(::column)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                for ((index, pieces) in text.withIndex()) {
                    val x = left + index * (band + gutter)
                    var y = 760f
                    for (piece in pieces) {
                        for (line in wrap(piece, PDType1Font.HELVETICA, 10f, band)) {
                            content.beginText()
                            content.setFont(PDType1Font.HELVETICA, 10f)
                            content.newLineAtOffset(x, y)
                            content.showText(line)
                            content.endText()
                            y -= 13f
                        }
                        y -= 8f
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private companion object {
        /** A glyph's ink may sit a shade outside the measure it was set to. */
        const val SLACK = 4f
    }
}
