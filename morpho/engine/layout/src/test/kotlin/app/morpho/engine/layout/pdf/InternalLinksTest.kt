package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Block
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InternalLinksTest {

    private fun line(text: String, link: String? = null) =
        Paragraph(listOf(TextRun(text, link = link)))

    private fun linksOf(blocks: List<Block>): List<String?> =
        blocks.filterIsInstance<Paragraph>().flatMap { it.runs }.map { it.link }

    @Test
    fun `a contents line leads to the first paragraph of the page it names`() {
        val blocks = InternalLinks.resolve(
            listOf(
                1 to line("Chapter one .......... 4", InternalLinks.toPage(4)),
                1 to line("Chapter two .......... 9", InternalLinks.toPage(9)),
                4 to line("Chapter one"),
                4 to line("Its first paragraph."),
                9 to line("Chapter two"),
            )
        )
        assertEquals(listOf("#page4", "#page9", null, null, null), linksOf(blocks))
        val paragraphs = blocks.filterIsInstance<Paragraph>()
        assertEquals(listOf("page4"), paragraphs[2].bookmarks)
        assertEquals(emptyList<String>(), paragraphs[3].bookmarks, "only the first paragraph of a page")
        assertEquals(listOf("page9"), paragraphs[4].bookmarks)
    }

    @Test
    fun `a page nobody links to is given no name`() {
        val blocks = InternalLinks.resolve(
            listOf(1 to line("A line."), 2 to line("Another."))
        )
        assertTrue(blocks.filterIsInstance<Paragraph>().all { it.bookmarks.isEmpty() })
    }

    @Test
    fun `a link to a page with nothing on it leads nowhere rather than to a scheme`() {
        // The words stay; only the link goes. A link a reader cannot
        // follow is better than one that opens "morpho:page/7".
        val blocks = InternalLinks.resolve(
            listOf(
                1 to line("See page 7", InternalLinks.toPage(7)),
                7 to ImageBlock(byteArrayOf(1), "image/png", 10, 10),
            )
        )
        assertEquals(listOf<String?>(null), linksOf(blocks))
        assertEquals("See page 7", blocks.filterIsInstance<Paragraph>().single().text)
    }

    @Test
    fun `no mark of the reader's own ever survives`() {
        // The one thing that must always hold: whatever the document, a
        // link left over from the reader would open a scheme no program
        // has. Every shape the resolver can meet, in one document.
        val blocks = InternalLinks.resolve(
            listOf(
                1 to line("to a page with words", InternalLinks.toPage(3)),
                1 to line("to a page with none", InternalLinks.toPage(8)),
                1 to line("to a page that is not there", InternalLinks.toPage(99)),
                1 to line("to the web", "https://example.org"),
                2 to Table(
                    listOf(
                        TableRow(listOf(TableCell(listOf(line("in a cell", InternalLinks.toPage(3))))))
                    )
                ),
                3 to line("The place itself."),
                8 to ImageBlock(byteArrayOf(1), "image/png", 10, 10),
            )
        )
        val everyLink = mutableListOf<String?>()
        fun walk(list: List<Block>) {
            for (block in list) when (block) {
                is Paragraph -> block.runs.forEach { everyLink += it.link }
                is Table -> block.rows.forEach { row -> row.cells.forEach { walk(it.blocks) } }
                is ImageBlock -> {}
            }
        }
        walk(blocks)
        assertTrue(everyLink.none { it != null && InternalLinks.pageOf(it) != null }, everyLink.toString())
        assertEquals(listOf("#page3", null, null, "https://example.org", "#page3", null), everyLink)
    }

    @Test
    fun `a link inside a table is pointed too`() {
        val blocks = InternalLinks.resolve(
            listOf(
                1 to Table(
                    listOf(
                        TableRow(listOf(TableCell(listOf(line("Appendix A", InternalLinks.toPage(5))))))
                    )
                ),
                5 to line("Appendix A"),
            )
        )
        val cell = (blocks.first() as Table).rows.single().cells.single()
        assertEquals("#page5", (cell.blocks.single() as Paragraph).runs.single().link)
    }

    @Test
    fun `the mark says which page it means, and nothing else does`() {
        assertEquals(12, InternalLinks.pageOf(InternalLinks.toPage(12)))
        assertNull(InternalLinks.pageOf("https://example.org"))
        assertNull(InternalLinks.pageOf("#page12"))
    }
}
