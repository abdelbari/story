package app.morpho.engine.layout.pdf

/**
 * The page a picture of a document stands for.
 *
 * A PDF states the size of every page in points, so a reading of one
 * knows how wide the paper is, where its foot is and how far the text
 * sits from its edges. A photograph states nothing: it is a grid of
 * pixels, and how big a thing it is a picture of is exactly what a camera
 * does not record.
 *
 * Something has to be decided, because the reading needs a sheet — the
 * running head is found by where it sits against the foot of the page,
 * the margins are measured against the edges, and a converted document
 * with no page at all opens on whatever Word happens to open with.
 *
 * What is decided is this: the picture IS the page, and it is a page of
 * ordinary size. Its long side is the long side of the paper most
 * documents are set on, and its short side follows from its own shape. A
 * scan of A4 comes out A4 because its shape is A4's. A photograph taken
 * on a phone comes out at the phone's own three-to-four, which is very
 * nearly Letter and is a reasonable page. A panorama comes out long and
 * short, which is what it is.
 *
 * A file that states its own resolution is believed where believing it
 * gives a page of a size paper comes in, which is what a flatbed scanner
 * writes and what a camera does not.
 */
object ImagePage {

    /** The long side of A4, in inches: the paper most documents are set on. */
    const val LONG_SIDE_INCHES = 11.69f

    /** Points to the inch. */
    private const val POINTS_PER_INCH = 72f

    /** Shorter than this on its long side and a stated resolution is not a page's. */
    private const val LEAST_INCHES = 3f

    /** Longer than this and it is not a page either. */
    private const val MOST_INCHES = 40f

    /**
     * The sheet a picture stands for, and the resolution its pixels are to
     * be read at.
     *
     * The resolution is what turns everything recognition reports — every
     * word's box, every line's measure — from pixels of this image into
     * points on that sheet, so the two are one decision and are made
     * together rather than in two places that could disagree.
     */
    data class Of(val sheet: PdfPageSheet, val dpi: Float)

    /**
     * What the picture at [page] stands for, given its size in pixels and
     * whatever resolution the file states for itself.
     *
     * Null where there is no picture to speak of: a decoder that returned
     * nothing, or a file whose header says it is nought pixels across.
     */
    fun of(page: Int, widthPx: Int, heightPx: Int, statedDpi: Float? = null): Of? {
        if (page < 1 || widthPx < 1 || heightPx < 1) return null
        val longest = maxOf(widthPx, heightPx).toFloat()
        val dpi = believable(statedDpi, longest) ?: (longest / LONG_SIDE_INCHES)
        if (!dpi.isFinite() || dpi <= 0f) return null
        return Of(
            sheet = PdfPageSheet(
                page = page,
                widthPt = widthPx / dpi * POINTS_PER_INCH,
                heightPt = heightPx / dpi * POINTS_PER_INCH,
            ),
            dpi = dpi,
        )
    }

    /**
     * [statedDpi] where the file's own claim about itself puts its long
     * side within the range paper comes in, and null where it does not.
     *
     * A scanner writes the resolution it scanned at and is worth
     * believing: a page scanned at 600 dpi is a page, and read at a
     * resolution worked out from its shape instead it would be the same
     * page — but a page scanned at 600 and cropped to a paragraph would
     * not, and the file knows what the reading cannot work out. A camera
     * writes 72, or 1, or nothing at all, and 72 would make a phone
     * photograph a wall chart four feet high.
     */
    private fun believable(statedDpi: Float?, longestPx: Float): Float? {
        val dpi = statedDpi?.takeIf { it.isFinite() && it > 0f } ?: return null
        val inches = longestPx / dpi
        return if (inches in LEAST_INCHES..MOST_INCHES) dpi else null
    }
}
