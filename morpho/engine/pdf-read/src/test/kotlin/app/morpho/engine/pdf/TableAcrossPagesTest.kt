package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A table longer than a page.
 *
 * A statement of accounts, a schedule, a bibliography: the page paints
 * such a table again on every page it runs onto, and says nothing to tie
 * the parts together — so a twenty-page statement came back as twenty
 * tables, each starting again, and the head the page printed on each of
 * them was twenty rows of data.
 *
 * Joined, and the repeat dropped, the head is marked as one instead, so
 * Word, the preview and the exported page each set it again at the top of
 * every page the table runs onto — which is what the original page was
 * doing by printing it twice.
 */
class TableAcrossPagesTest {

    private val columns = listOf(72f, 170f, 400f, 480f)
    private val head = listOf("Date", "Description", "Debit", "Credit")

    private fun row(number: Int) =
        listOf("%02d Jan".format(number), "Payment %02d".format(number), "%02d,200".format(number), "0.00")

    /**
     * A statement of so many rows a page, each page opening with the head
     * unless [repeatHead] says otherwise. Forty-six rows fill an A4 page
     * at this step, which is what makes the page, rather than the writing,
     * the thing that stopped it.
     */
    private fun statement(
        rowsPerPage: List<Int>,
        repeatHead: Boolean = true,
        shiftLastPageBy: Float = 0f,
    ): ByteArray {
        PDDocument().use { doc ->
            var number = 0
            for ((index, rows) in rowsPerPage.withIndex()) {
                val shift = if (index == rowsPerPage.lastIndex) shiftLastPageBy else 0f
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    var y = 770f
                    fun line(cells: List<String>, bold: Boolean) {
                        for ((column, cell) in cells.withIndex()) {
                            content.beginText()
                            content.setFont(
                                if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA,
                                10f,
                            )
                            content.newLineAtOffset(columns[column] + shift, y)
                            content.showText(cell)
                            content.endText()
                        }
                        y -= if (bold) 18f else 15f
                    }
                    if (index == 0 || repeatHead) line(head, bold = true)
                    repeat(rows) { line(row(++number), bold = false) }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun tables(bytes: ByteArray): List<Table> =
        PdfReader().extract(bytes).blocks.filterIsInstance<Table>()

    private fun cell(table: Table, row: Int, column: Int): String =
        (table.rows[row].cells[column].blocks.first() as Paragraph).text

    @Test
    fun `a table that runs onto the next page is one table`() {
        val read = tables(statement(listOf(46, 20)))
        assertEquals(1, read.size, "one table, not one for each page")
        val table = read.single()
        assertEquals(67, table.rows.size, "the head once, then every row")
        assertEquals("Date", cell(table, 0, 0))
        assertEquals("01 Jan", cell(table, 1, 0), "the first row follows the head")
        assertEquals("66 Jan", cell(table, 66, 0), "and the last is the last")
        assertTrue(
            table.rows.drop(1).none { cell(table, table.rows.indexOf(it), 0) == "Date" },
            "the head the second page printed was dropped",
        )
    }

    @Test
    fun `the head the page printed twice is marked as one that repeats`() {
        val table = tables(statement(listOf(46, 20))).single()
        assertTrue(table.rows.first().repeatsAsHeader, "the head does not repeat")
        assertTrue(table.rows.drop(1).none { it.repeatsAsHeader }, "only the head repeats")
    }

    @Test
    fun `a table that does not repeat its head is joined all the same`() {
        val read = tables(statement(listOf(46, 20), repeatHead = false))
        assertEquals(1, read.size)
        assertEquals(67, read.single().rows.size, "the head once, then every row")
        assertTrue(
            read.single().rows.none { it.repeatsAsHeader },
            "nothing was printed twice, so nothing is marked as repeating",
        )
    }

    @Test
    fun `a table on a page that ended early is not joined to the next`() {
        // The writing stopped, not the page: two tables that happen to
        // follow one another are not one table.
        val read = tables(statement(listOf(6, 20)))
        assertEquals(2, read.size, read.map { it.rows.size }.toString())
    }

    @Test
    fun `a table whose columns stand elsewhere is a table of its own`() {
        val read = tables(statement(listOf(46, 20), shiftLastPageBy = 40f))
        assertEquals(2, read.size, read.map { it.rows.size }.toString())
    }
}
