package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every reader hands its document to [Bidi] and to [Links] before anyone
 * else sees it, and both walk into a table's cells to do their work. A
 * walk that builds a fresh row and a fresh cell around the words it has
 * refined keeps the words and drops everything else the cell knew: how
 * many columns it covers, how many rows, what colour it is filled with,
 * and whether its row is the head of the table.
 *
 * A heading over two columns then came back over one, a report's coloured
 * header row came back plain, and a long table stopped repeating its head
 * — all of it after the reader had read them correctly.
 */
class TableFieldsTest {

    private fun cell(text: String, columns: Int = 1, rows: Int = 1, fill: Int? = null) =
        TableCell(listOf(Paragraph(listOf(TextRun(text)))), columnSpan = columns, rowSpan = rows, shadingRgb = fill)

    private val table = Table(
        rows = listOf(
            TableRow(listOf(cell("Results for both years", columns = 2, fill = 0xEEEEEE)), repeatsAsHeader = true),
            TableRow(listOf(cell("2019", rows = 2), cell("412"))),
            TableRow(listOf(cell("503"))),
        ),
        columnWidthsPt = listOf(120f, 200f),
        ruled = false,
        direction = TextDirection.RTL,
    )

    private fun check(refined: DocumentModel) {
        val read = refined.blocks.filterIsInstance<Table>().single()
        assertEquals(2, read.rows[0].cells[0].columnSpan, "a heading over two columns covers two")
        assertEquals(0xEEEEEE, read.rows[0].cells[0].shadingRgb, "the head keeps its colour")
        assertEquals(true, read.rows[0].repeatsAsHeader, "the head is still the head")
        assertEquals(2, read.rows[1].cells[0].rowSpan, "a label beside two rows covers two")
        assertEquals(listOf(120f, 200f), read.columnWidthsPt)
        assertEquals(false, read.ruled)
        assertEquals(TextDirection.RTL, read.direction)
        assertEquals("Results for both years", (read.rows[0].cells[0].blocks.single() as Paragraph).text)
    }

    @Test
    fun `the direction pass keeps everything a cell knows`() {
        check(Bidi.refine(DocumentModel(listOf(table))))
    }

    @Test
    fun `the link pass keeps everything a cell knows`() {
        check(Links.refine(DocumentModel(listOf(table))))
    }

    @Test
    fun `both passes together keep everything a cell knows`() {
        check(Links.refine(Bidi.refine(DocumentModel(listOf(table)))))
    }
}
