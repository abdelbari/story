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
 * A journal sets its two columns on the same grid, so both are painted on
 * the same baselines: every line of such a page reaches from the first
 * column's margin to the second's. Read that way the page has no clear
 * strip down its middle to find, and the alignment of the two columns
 * reads as a table of two — so a paper came back as a grid with half a
 * sentence in every cell, in an order nobody wrote.
 *
 * The marks themselves say where the columns are, and a line that reaches
 * across the strip between them is two lines.
 */
class TwoColumnPageTest {

    private val left = 60f
    private val right = 535f
    private val gutter = 20f
    private val band = (right - left - gutter) / 2

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

    /** A page with a heading and two columns of prose, both on the same grid. */
    private fun paper(columns: List<List<String>>, heading: String?): ByteArray {
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

    private val firstColumn = listOf(
        "The first column opens the argument and carries it down the page in sentences " +
            "long enough to fill the measure they are set in, so that the lines run ragged " +
            "at their ends the way a column of prose does and not otherwise.",
        "It goes on for a second paragraph, still in the first column, saying more of the " +
            "same thing at the same width, because a column that holds a single paragraph " +
            "says nothing about what a reader does at the foot of one.",
    )
    private val secondColumn = listOf(
        "The second column takes up where the first leaves off, and a reader who reads " +
            "across the gutter instead of down the column is handed sentences that nobody " +
            "ever wrote, which is the whole of what this is here to catch.",
        "It closes with a last paragraph, set at the same measure as everything else on " +
            "the page, so that the page ends where a page of a journal paper would end " +
            "and nothing about it is unusual.",
    )

    @Test
    fun `a page in two columns is read column by column`() {
        val model = PdfReader().extract(paper(listOf(firstColumn, secondColumn), "Findings across both columns"))
        val read = model.blocks.filterIsInstance<Paragraph>().map { it.text }
        assertTrue(model.blocks.none { it is Table }, "a page of prose is not a table: $read")
        assertEquals(5, read.size, read.toString())
        assertEquals("Findings across both columns", read[0])
        assertTrue(read[1].startsWith("The first column opens"), read[1])
        assertTrue(read[2].startsWith("It goes on for a second"), read[2])
        assertTrue(read[3].startsWith("The second column takes up"), read[3])
        assertTrue(read[4].startsWith("It closes with a last"), read[4])
    }

    @Test
    fun `every word of both columns is still there, once`() {
        val model = PdfReader().extract(paper(listOf(firstColumn, secondColumn), null))
        val read = model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        for (piece in firstColumn + secondColumn) {
            for (word in listOf(piece.split(" ").first(), piece.split(" ").last().trim('.'))) {
                assertTrue(read.contains(word), "$word missing from $read")
            }
        }
    }

    @Test
    fun `a running head across both columns stays one line`() {
        // A journal puts its title and the author across the top of every
        // page. Cut with the columns it would become two half-headings.
        val bytes = paper(listOf(firstColumn, secondColumn), null).let { _ ->
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 9f)
                    content.newLineAtOffset(left, 790f)
                    content.showText("The Journal of Something    ·    Volume 4, Number 2    ·    page 48")
                    content.endText()
                    var top = 760f
                    for ((index, pieces) in listOf(firstColumn, secondColumn).withIndex()) {
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
                out.toByteArray()
            }
        }
        val model = PdfReader().extract(bytes)
        val everything = (model.blocks + model.header + model.footer).filterIsInstance<Paragraph>()
        assertTrue(
            everything.any { it.text.contains("The Journal of Something") && it.text.contains("page 48") },
            "the running head was cut in two: ${everything.map { it.text }}",
        )
        assertTrue(model.blocks.none { it is Table }, "a page of prose is not a table")
    }

    @Test
    fun `a page in one column is left as it is`() {
        // The same prose, all of it in one column: nothing to cut.
        val model = PdfReader().extract(paper(listOf(firstColumn + secondColumn), "One column only"))
        val read = model.blocks.filterIsInstance<Paragraph>().map { it.text }
        assertEquals(5, read.size, read.toString())
        assertEquals("One column only", read[0])
    }
}
