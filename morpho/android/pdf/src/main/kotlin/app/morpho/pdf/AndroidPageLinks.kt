package app.morpho.pdf

import app.morpho.engine.layout.pdf.InternalLinks
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination

/**
 * Android twin of the engine's PageLinks (:engine:pdf-read), on the
 * tom-roush PDFBox port. Keep the two in step.
 *
 * Where a page's links point.
 *
 * A PDF carries a link as an annotation: a rectangle on the page and an
 * action that names a target. Nothing ties it to the text underneath — the
 * text does not know it is a link, and the annotation does not know what it
 * covers — so the two are joined here by geometry: a glyph whose middle
 * falls inside the rectangle belongs to that link.
 *
 * This is what a link written by hand cannot give: the target of "click
 * here", of a shortened citation, of a picture that leads somewhere. A
 * link that names a web address is kept as it is; one that jumps to a
 * page of the same document — every line of a book's contents page, every
 * cross-reference in a manual — is marked with the page it leads to, for
 * the layout to turn into a place once there are paragraphs to point at;
 * and one that launches a program on the reader's machine has no business
 * travelling with a converted file.
 */
internal class AndroidPageLinks(private val document: PDDocument) {

    internal class Area(val left: Float, val top: Float, val right: Float, val bottom: Float, val target: String) {
        fun holds(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    /** The links of one page, ready to be asked about a glyph. */
    class Page internal constructor(private val areas: List<Area>) {
        /** Where the glyph at ([x], [y]) in top-down page points points, or null when it points nowhere. */
        fun at(x: Float, y: Float): String? = areas.firstOrNull { it.holds(x, y) }?.target
    }

    /** By page index, the links that page carries. */
    private val byPage = HashMap<Int, Page>()

    init {
        for ((index, page) in document.pages.withIndex()) {
            val areas = runCatching { areasOf(page) }.getOrDefault(emptyList())
            if (areas.isNotEmpty()) byPage[index] = Page(areas)
        }
    }

    /** The links of the page at [pageIndex], or null when it carries none. */
    fun page(pageIndex: Int): Page? = byPage[pageIndex]

    /** Where the glyph at ([x], [y]) of page [pageIndex] points, or null when it points nowhere. */
    fun at(pageIndex: Int, x: Float, y: Float): String? = byPage[pageIndex]?.at(x, y)

    private fun areasOf(page: PDPage): List<Area> {
        // A rotated page's text is measured in a frame of its own; rather
        // than put a link in the wrong place, put none.
        if ((page.rotation % 360 + 360) % 360 != 0) return emptyList()
        val box = page.cropBox ?: return emptyList()
        val areas = mutableListOf<Area>()
        for (annotation in page.annotations.orEmpty()) {
            val link = annotation as? PDAnnotationLink ?: continue
            val action = runCatching { link.action }.getOrNull()
            val uri = (action as? PDActionURI)?.uri?.trim()
            val target = when {
                !uri.isNullOrEmpty() -> uri
                // A link may hold its destination outright or reach it
                // through a Go-To action; both mean a page of this file.
                else -> destinationOf(link, action)?.let { InternalLinks.toPage(it) }
            } ?: continue
            val rect = link.rectangle ?: continue
            // The rectangle is written in user space, from the bottom of the
            // page; the text is measured from the top of the crop box.
            areas += Area(
                left = minOf(rect.lowerLeftX, rect.upperRightX) - box.lowerLeftX,
                top = box.upperRightY - maxOf(rect.lowerLeftY, rect.upperRightY),
                right = maxOf(rect.lowerLeftX, rect.upperRightX) - box.lowerLeftX,
                bottom = box.upperRightY - minOf(rect.lowerLeftY, rect.upperRightY),
                target = target,
            )
        }
        return areas
    }

    /**
     * The page a link leads to, counting from one as the lines of the
     * document do, or null when it leads out of the file or nowhere at
     * all. A destination is written either in the link itself or in the
     * action it carries, and either as a page or as a name the document
     * keeps a list of.
     */
    private fun destinationOf(link: PDAnnotationLink, action: Any?): Int? {
        val destination: PDDestination = when {
            action is PDActionGoTo -> runCatching { action.destination }.getOrNull()
            action == null -> runCatching { link.destination }.getOrNull()
            else -> null
        } ?: return null
        val page = when (destination) {
            is PDPageDestination -> destination
            is PDNamedDestination -> named(destination)
            else -> null
        } ?: return null
        val index = runCatching { page.retrievePageNumber() }.getOrNull() ?: return null
        return if (index >= 0) index + 1 else null
    }

    /** The place a destination's name stands for, from the document's own list of them. */
    private fun named(destination: PDNamedDestination): PDPageDestination? = runCatching {
        val name = destination.namedDestination ?: return null
        val catalog = document.documentCatalog
        catalog.names?.dests?.getValue(name)
            // A document written before name trees existed keeps the same
            // list as a plain dictionary.
            ?: catalog.dests?.getDestination(name) as? PDPageDestination
    }.getOrNull()
}
