package app.morpho.engine.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup

/**
 * What a page's highlights cover.
 *
 * A reader marking a PDF leaves a highlight annotation: a colour and the
 * quadrilaterals it was drawn over. Nothing ties it to the text underneath
 * — the words do not know they are marked — so the two are joined here by
 * geometry, the way a link is: a glyph whose middle falls inside one of the
 * quadrilaterals was highlighted in that colour.
 *
 * This is what a student's PDF is full of and what every converter drops:
 * the marking is the reader's own reading of the document, and a converted
 * file without it is the document before it was read.
 */
internal class PageHighlights(document: PDDocument) {

    internal class Area(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val rgb: Int,
    ) {
        fun holds(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    /** The highlights of one page, ready to be asked about a glyph. */
    class Page internal constructor(private val areas: List<Area>) {
        /** The colour over the glyph at ([x], [y]) in top-down page points, or null. */
        fun at(x: Float, y: Float): Int? = areas.firstOrNull { it.holds(x, y) }?.rgb
    }

    private val byPage = HashMap<Int, Page>()

    init {
        for ((index, page) in document.pages.withIndex()) {
            val areas = runCatching { areasOf(page) }.getOrDefault(emptyList())
            if (areas.isNotEmpty()) byPage[index] = Page(areas)
        }
    }

    /** The highlights of the page at [pageIndex], or null when it carries none. */
    fun page(pageIndex: Int): Page? = byPage[pageIndex]

    /** The colour over the glyph at ([x], [y]) of page [pageIndex], or null. */
    fun at(pageIndex: Int, x: Float, y: Float): Int? = byPage[pageIndex]?.at(x, y)

    private fun areasOf(page: PDPage): List<Area> {
        // A rotated page's text is measured in a frame of its own; rather
        // than mark the wrong words, mark none.
        if ((page.rotation % 360 + 360) % 360 != 0) return emptyList()
        val box = page.cropBox ?: return emptyList()
        val areas = mutableListOf<Area>()
        for (annotation in page.annotations.orEmpty()) {
            val markup = annotation as? PDAnnotationTextMarkup ?: continue
            if (markup.subtype != PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT) continue
            val rgb = runCatching { markup.color?.toRGB() }.getOrNull() ?: continue
            // A highlight covers a quadrilateral per line of text it was
            // drawn over; a marking that lost them still has its rectangle.
            val quads = runCatching { markup.quadPoints }.getOrNull()
            val boxes = mutableListOf<FloatArray>()
            if (quads != null && quads.size >= POINTS_A_QUAD) {
                var at = 0
                while (at + POINTS_A_QUAD <= quads.size) {
                    val xs = (0 until POINTS_A_QUAD step 2).map { quads[at + it] }
                    val ys = (1 until POINTS_A_QUAD step 2).map { quads[at + it] }
                    boxes += floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                    at += POINTS_A_QUAD
                }
            } else {
                val rect = markup.rectangle ?: continue
                boxes += floatArrayOf(
                    minOf(rect.lowerLeftX, rect.upperRightX),
                    minOf(rect.lowerLeftY, rect.upperRightY),
                    maxOf(rect.lowerLeftX, rect.upperRightX),
                    maxOf(rect.lowerLeftY, rect.upperRightY),
                )
            }
            for (drawn in boxes) {
                // Written from the bottom of the page; the text is measured
                // from the top of the crop box.
                areas += Area(
                    left = drawn[0] - box.lowerLeftX,
                    top = box.upperRightY - drawn[3],
                    right = drawn[2] - box.lowerLeftX,
                    bottom = box.upperRightY - drawn[1],
                    rgb = rgb and 0xFFFFFF,
                )
            }
        }
        return areas
    }

    private companion object {
        /** Four corners, two numbers each, is one quadrilateral. */
        const val POINTS_A_QUAD = 8
    }
}
