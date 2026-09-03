package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A page set in more than two columns.
 *
 * A newspaper, a dictionary, a programme, a conference paper: three
 * columns and four are as ordinary as two. Asked for one gutter, the
 * reader found the second of a page of three and cut there, leaving the
 * first two columns on the other side of it to be read as a single
 * column, a line of each in turn — or worse, as a table of two with half
 * a sentence in every cell, which is exactly the failure that finding a
 * gutter at all was for.
 *
 * A page of three columns is a page of two, one of which is a page of
 * two, so each side is asked the same question again — where the marks
 * are cut apart, and again where the lines are put in the order they are
 * read.
 */
class ThreeColumnPageTest {

    private val left = 60f
    private val right = 535f
    private val gutter = 20f

    private fun wrap(text: String, font: PDFont, size: Float, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.getStringWidth(candidate) / 1000f * size > width && line.isNotEmpty()) {
                lines += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    /** A page of however many columns, all on the same grid, under an optional heading. */
    private fun paper(columns: List<List<String>>, heading: String?): ByteArray {
        val band = (right - left - gutter * (columns.size - 1)) / columns.size
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                var top = 760f
                if (heading != null) {
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA_BOLD, 15f)
                    content.newLineAtOffset(left, top)
                    content.showText(heading)
                    content.endText()
                    top -= 30f
                }
                for ((index, pieces) in columns.withIndex()) {
                    val x = left + index * (band + gutter)
                    var y = top
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

    /** A column's worth of prose: paragraphs long enough to fill the measure they are set in. */
    private fun column(name: String, paragraphs: Int = 5): List<String> =
        (1..paragraphs).map { number ->
            "$name paragraph $number opens the argument and carries it down the page in " +
                "sentences long enough to fill the measure they are set in, so the lines run " +
                "ragged at their ends the way a column of prose does and not otherwise at all, " +
                "going on far enough that the foot of the column is reached in the ordinary way."
        }

    private fun read(columns: List<List<String>>, heading: String? = null): List<String> =
        PdfReader().extract(paper(columns, heading))
            .blocks.filterIsInstance<Paragraph>()
            .map { it.text }

    private fun opening(text: String): String = text.split(" ").take(3).joinToString(" ")

    @Test
    fun `a page in three columns is read column by column`() {
        val columns = listOf(column("First"), column("Second"), column("Third"))
        val read = read(columns, "Findings across the page")
        assertEquals("Findings across the page", read.first())
        assertEquals(
            columns.flatten().map(::opening),
            read.drop(1).map(::opening),
            "the columns were not read one after another",
        )
    }

    @Test
    fun `a page in four columns is read column by column`() {
        val columns = listOf(column("First"), column("Second"), column("Third"), column("Fourth"))
        val read = read(columns)
        assertEquals(columns.flatten().map(::opening), read.map(::opening))
    }

    @Test
    fun `a page of three columns is not a table`() {
        // Two columns read as one look like a table of two: the alignment
        // is there, and only the gutter says otherwise.
        val model = PdfReader().extract(
            paper(listOf(column("First"), column("Second"), column("Third")), null)
        )
        assertTrue(model.blocks.none { it is Table }, "a page of prose came back as a table")
    }

    @Test
    fun `a heading over some of the columns does not hide the gutter under it`() {
        // A heading set over the first two columns of three crosses the
        // gutter between them and no other. A gutter no line at all may
        // cross is one such heading away from not being found, and the
        // columns under it are then read as one, a line of each in turn.
        val columns = listOf(column("First"), column("Second"), column("Third"))
        val read = read(columns, "A heading set over the first two columns of the page")
        assertTrue(read.first().startsWith("A heading set over"), read.first())
        assertEquals(columns.flatten().map(::opening), read.drop(1).map(::opening))
    }

    @Test
    fun `every word of every column is still there, once`() {
        val columns = listOf(column("First"), column("Second"), column("Third"))
        val read = read(columns).joinToString(" ")
        for (piece in columns.flatten()) {
            val opening = opening(piece)
            assertEquals(1, Regex(Regex.escape(opening)).findAll(read).count(), "$opening in $read")
        }
    }

    @Test
    fun `a page in one column is read as one column`() {
        // The question is asked of every page, and a page that is not set
        // in columns must not be cut into any.
        val one = column("Only", paragraphs = 7)
        val read = read(listOf(one), "Findings across the page")
        assertEquals(listOf("Findings across the page") + one.map(::opening), listOf(read.first()) + read.drop(1).map(::opening))
    }
}
