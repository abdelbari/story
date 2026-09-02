package app.morpho.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink

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
 * here", of a shortened citation, of a picture that leads somewhere. Only
 * links that name a web address are kept; one that jumps to a page of the
 * document itself has no meaning in a converted file, and one that launches
 * a program on the reader's machine has no business travelling with it.
 */
internal class AndroidPageLinks(document: PDDocument) {

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
            val uri = (runCatching { link.action }.getOrNull() as? PDActionURI)?.uri?.trim()
            if (uri.isNullOrEmpty()) continue
            val rect = link.rectangle ?: continue
            // The rectangle is written in user space, from the bottom of the
            // page; the text is measured from the top of the crop box.
            areas += Area(
                left = minOf(rect.lowerLeftX, rect.upperRightX) - box.lowerLeftX,
                top = box.upperRightY - maxOf(rect.lowerLeftY, rect.upperRightY),
                right = maxOf(rect.lowerLeftX, rect.upperRightX) - box.lowerLeftX,
                bottom = box.upperRightY - minOf(rect.lowerLeftY, rect.upperRightY),
                target = uri,
            )
        }
        return areas
    }
}
