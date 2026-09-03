package app.morpho.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup

/**
 * Android twin of the engine's PageHighlights (:engine:pdf-read), on the
 * tom-roush PDFBox port. Keep the two in step.
 *
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
internal class AndroidPageHighlights(document: PDDocument) {

    /** What the reader did to the words under the area: painted, underlined, struck out. */
    enum class Kind { HIGHLIGHT, UNDERLINE, STRIKE }

    internal class Area(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val rgb: Int,
        val kind: Kind = Kind.HIGHLIGHT,
    ) {
        fun holds(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    /** The markings of one page, ready to be asked about a glyph. */
    class Page internal constructor(private val areas: List<Area>) {
        /** The colour over the glyph at ([x], [y]) in top-down page points, or null. */
        fun at(x: Float, y: Float): Int? =
            areas.firstOrNull { it.kind == Kind.HIGHLIGHT && it.holds(x, y) }?.rgb

        /** Whether a reader drew a line under the glyph at ([x], [y]). */
        fun underlined(x: Float, y: Float): Boolean =
            areas.any { it.kind == Kind.UNDERLINE && it.holds(x, y) }

        /** Whether a reader struck the glyph at ([x], [y]) out. */
        fun struck(x: Float, y: Float): Boolean =
            areas.any { it.kind == Kind.STRIKE && it.holds(x, y) }
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
            // What the reader did, of the three things a marking can be.
            // The first was kept and the other two thrown away, though a
            // line drawn under a term and a clause struck out are the two
            // that change what the document says.
            val kind = when (markup.subtype) {
                PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT -> Kind.HIGHLIGHT
                PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE,
                PDAnnotationTextMarkup.SUB_TYPE_SQUIGGLY -> Kind.UNDERLINE
                PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT -> Kind.STRIKE
                else -> continue
            }
            // A highlight without a colour is nothing to draw; an
            // underline without one is drawn in the reader's own colour
            // and is a marking still.
            val rgb = runCatching { markup.color?.toRGB() }.getOrNull()
                ?: if (kind == Kind.HIGHLIGHT) continue else 0
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
                    kind = kind,
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
