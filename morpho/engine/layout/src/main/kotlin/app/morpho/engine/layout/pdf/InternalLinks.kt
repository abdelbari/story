package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Block
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TextRun

/**
 * A link that leads into the document rather than out of it.
 *
 * A book, a manual, a thesis exported to PDF carries a contents page whose
 * every line jumps to a page of the same file, and a converter with no
 * notion of such a link either drops it or writes it as an address — so a
 * converted manual's contents page reads like a contents page and does
 * nothing at all. Nothing outside a PDF knows what "page 12" means, so
 * the page has to be turned into a place: the first paragraph on it is
 * given a name, and the link is pointed at the name.
 *
 * The reader marks such a link with a scheme of its own, because at the
 * time it is read the pages exist and the paragraphs do not. [resolve]
 * turns every one of them into a real link once the blocks are built, and
 * it is the only thing that ever does: a mark that reaches a converted
 * file would be a link to a scheme no reader has.
 */
object InternalLinks {

    /** The mark a reader leaves on a link that leads to a page of the same document. */
    private const val SCHEME = "morpho:page/"

    /** The mark for a link leading to page [page], counting from one. */
    fun toPage(page: Int): String = "$SCHEME$page"

    /** The page [target] leads to, or null when it leads somewhere else. */
    fun pageOf(target: String): Int? =
        target.removePrefix(SCHEME).takeIf { it != target }?.toIntOrNull()

    /**
     * [paged] — every block with the page it begins on — with each link
     * into the document pointing at a name given to the first paragraph
     * of the page it leads to.
     *
     * A link to a page holding no paragraph has nothing to point at and
     * is dropped: its text stays, as text that leads nowhere, which is
     * what it will look like to a reader anyway.
     */
    fun resolve(paged: List<Pair<Int, Block>>): List<Block> {
        val wanted = mutableSetOf<Int>()
        for ((_, block) in paged) collectWanted(block, wanted)
        if (wanted.isEmpty()) return paged.map { it.second }

        // The first paragraph of a page is where a link to that page
        // lands, which is the nearest thing a page has to a place.
        val anchorOf = HashMap<Int, String>()
        val landing = HashMap<Int, Paragraph>()
        for ((page, block) in paged) {
            if (page !in wanted || page in landing) continue
            if (block is Paragraph) {
                landing[page] = block
                anchorOf[page] = "page$page"
            }
        }
        if (anchorOf.isEmpty()) return paged.map { stripped(it.second) }

        return paged.map { (page, block) ->
            val named = anchorOf[page]?.takeIf { landing[page] === block }
            val pointed = pointed(block, anchorOf)
            if (named != null && pointed is Paragraph) {
                pointed.copy(bookmarks = pointed.bookmarks + named)
            } else {
                pointed
            }
        }
    }

    private fun collectWanted(block: Block, into: MutableSet<Int>) {
        when (block) {
            is Paragraph -> for (run in block.runs) run.link?.let { pageOf(it) }?.let { into += it }
            is Table -> for (row in block.rows) for (cell in row.cells) {
                for (held in cell.blocks) collectWanted(held, into)
            }
            is ImageBlock -> {}
        }
    }

    /** [block] with its marked links turned into names, and the rest dropped. */
    private fun pointed(block: Block, anchorOf: Map<Int, String>): Block = when (block) {
        is Paragraph -> block.copy(runs = block.runs.map { run -> pointed(run, anchorOf) })
        is Table -> block.copy(
            rows = block.rows.map { row ->
                row.copy(cells = row.cells.map { cell -> pointed(cell, anchorOf) })
            }
        )
        is ImageBlock -> block
    }

    private fun pointed(cell: TableCell, anchorOf: Map<Int, String>): TableCell =
        cell.copy(blocks = cell.blocks.map { pointed(it, anchorOf) })

    private fun pointed(run: TextRun, anchorOf: Map<Int, String>): TextRun {
        val page = run.link?.let { pageOf(it) } ?: return run
        val anchor = anchorOf[page] ?: return run.copy(link = null)
        return run.copy(link = "#$anchor")
    }

    /** [block] with every marked link dropped, none of them having anywhere to lead. */
    private fun stripped(block: Block): Block = pointed(block, emptyMap())
}
