package app.morpho.engine.pdf

import app.morpho.engine.layout.pdf.PdfOutline
import app.morpho.engine.layout.pdf.PdfOutlineEntry
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/**
 * The list of chapters a document carries: what a PDF reader shows in its
 * sidebar, and the only place many an untagged document says outright
 * which of its lines are headings and how deep each one sits.
 *
 * Each entry is read with the page it leads to, so a line is only taken
 * for a chapter's heading where it stands on the page the chapter starts
 * on — a contents page names every chapter in the book and is not the
 * book's headings.
 */
internal object DocumentOutline {

    /** However long an outline is, no more of it than this is read. */
    private const val MOST_ENTRIES = 4096

    /** The document's outline, flattened in order, or empty when it has none. */
    fun read(document: PDDocument): List<PdfOutlineEntry> = runCatching {
        val root = document.documentCatalog?.documentOutline ?: return emptyList()
        val pageNumbers = HashMap<Any, Int>()
        for ((index, page) in document.pages.withIndex()) pageNumbers[page.cosObject] = index + 1
        val entries = mutableListOf<PdfOutlineEntry>()

        fun walk(item: PDOutlineItem?, level: Int) {
            var node = item
            while (node != null && entries.size < MOST_ENTRIES) {
                val title = runCatching { node.title }.getOrNull()?.trim()
                if (!title.isNullOrEmpty()) {
                    val page = runCatching { node.findDestinationPage(document) }.getOrNull()
                    entries += PdfOutlineEntry(
                        title = title,
                        level = level,
                        page = page?.let { pageNumbers[it.cosObject] } ?: 0,
                    )
                }
                if (level < PdfOutline.DEEPEST_LEVEL) {
                    walk(runCatching { node.firstChild }.getOrNull(), level + 1)
                }
                node = runCatching { node?.nextSibling }.getOrNull()
            }
        }

        walk(runCatching { root.firstChild }.getOrNull(), 0)
        entries
    }.getOrDefault(emptyList())
}
