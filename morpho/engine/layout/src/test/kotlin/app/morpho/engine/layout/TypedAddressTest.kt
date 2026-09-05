package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An address a document merely writes out, found wherever it sits.
 *
 * Most authors type an address rather than insert a link, so the pass that
 * finds them is what makes a converted paper's addresses clickable at all.
 * It reached the body, the running head and the foot — and not the head
 * and foot a book's left-hand pages carry, so an address printed in a
 * running head was a link on one side of the opening and plain text on the
 * other. Nor a note's own words except by luck: on the reading of a PDF
 * this pass runs before the notes are gathered, so they came past as
 * ordinary paragraphs, and a reading that gathered them first would hand
 * over a footnote full of addresses and not one of them a link — which is
 * where an academic paper keeps most of its addresses.
 */
class TypedAddressTest {

    private val sentence = "Write to a.b@example.org for the rest."
    private val wanted = listOf("mailto:a.b@example.org")

    private fun linksIn(blocks: List<Block>): List<String> =
        blocks.filterIsInstance<Paragraph>().flatMap { it.runs }.mapNotNull { it.link }

    private fun line() = Paragraph(listOf(TextRun(sentence)))

    @Test
    fun `an address in the body is a link`() {
        val read = Links.refine(DocumentModel(listOf(line())))
        assertEquals(wanted, linksIn(read.blocks))
    }

    @Test
    fun `an address in either running head or foot is a link`() {
        val read = Links.refine(
            DocumentModel(
                blocks = listOf(Paragraph(listOf(TextRun("plain")))),
                header = listOf(line()),
                footer = listOf(line()),
                evenHeader = listOf(line()),
                evenFooter = listOf(line()),
            )
        )
        assertEquals(wanted, linksIn(read.header), "the right-hand head")
        assertEquals(wanted, linksIn(read.footer), "the right-hand foot")
        assertEquals(wanted, linksIn(read.evenHeader), "the left-hand head")
        assertEquals(wanted, linksIn(read.evenFooter), "the left-hand foot")
    }

    @Test
    fun `an address in a note is a link`() {
        val read = Links.refine(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(
                            TextRun("A claim"),
                            TextRun("1", note = listOf(line())),
                        )
                    )
                )
            )
        )
        val note = (read.blocks.single() as Paragraph).runs.mapNotNull { it.note }.single()
        assertEquals(wanted, linksIn(note))
    }

    @Test
    fun `an address inside a table cell is a link`() {
        val read = Links.refine(
            DocumentModel(listOf(Table(listOf(TableRow(listOf(TableCell(listOf(line()))))))))
        )
        val cell = (read.blocks.single() as Table).rows.single().cells.single()
        assertEquals(wanted, linksIn(cell.blocks))
    }

    @Test
    fun `a link the file itself carried is not overruled`() {
        // An annotation the producer attached says more than the shape of
        // the text does, so the pass leaves such a run alone.
        val read = Links.refine(
            DocumentModel(
                listOf(Paragraph(listOf(TextRun(sentence, link = "https://example.org/elsewhere"))))
            )
        )
        assertEquals(listOf("https://example.org/elsewhere"), linksIn(read.blocks))
    }
}
