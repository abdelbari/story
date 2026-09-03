package app.morpho.engine.layout.pdf

/**
 * One extracted PDF output line with the geometry the untagged-layout
 * heuristics need. Coordinates are direction-adjusted page space: [x] is the
 * left edge in points and [baselineY] grows downwards, so reading order on an
 * unrotated page means increasing [baselineY].
 *
 * Lives in :engine:layout because it is PDF-library-agnostic: both the JVM
 * stripper (:engine:pdf-read, desktop PDFBox) and the Android stripper
 * (tom-roush port) produce it, and [PdfLayout] consumes it.
 */
data class PdfLine(
    val text: String,
    val x: Float,
    val baselineY: Float,
    val maxFontSize: Float,
    /** 1-based page number. */
    val page: Int,
    /** Right edge of the line's ink in points; [x] when the stripper measured none. */
    val xEnd: Float = x,
    /**
     * The line's text as runs of one look, in logical order. Empty means the
     * stripper captured no looks and [text] is all there is.
     */
    val runs: List<PdfRun> = emptyList(),
    /**
     * The line's text chunks with their horizontal extents, in reading order.
     * Strippers emit one segment per extracted word/chunk; [PdfTableDetector]
     * merges them into cells by gap analysis. Empty means "no geometry
     * captured" and the line is treated as a single cell.
     */
    val segments: List<PdfSegment> = emptyList(),
)

/** One extracted chunk of a line: its text and horizontal extent in points. */
data class PdfSegment(
    val text: String,
    val xStart: Float,
    val xEnd: Float,
)

/**
 * The look of one painted character: the typeface it was set in, its size,
 * its weight and slant, whether it sits raised (+1) or lowered (-1) off
 * its line's baseline, and the colour it was painted in. Both PDF readers
 * describe a glyph with this, so a document's runs mean the same thing
 * whichever path read it.
 */
data class PdfLook(
    val fontFamily: String? = null,
    val fontSizePt: Float = 0f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val raised: Int = 0,
    /** Packed 0xRRGGBB, or null for the black a page paints with unless it says otherwise. */
    val colorRgb: Int? = null,
    /** Where the glyph points, when a link annotation covers it. */
    val link: String? = null,
    /** Packed 0xRRGGBB of the highlight drawn over the glyph, or null for none. */
    val highlightRgb: Int? = null,
    /**
     * Whether the page drew a line under the glyph, or through it. A PDF
     * has no underline and no strike: a producer draws one, as a hair of
     * a rule where the words are, and a reader that keeps only the words
     * loses both.
     */
    val underline: Boolean = false,
    val struck: Boolean = false,
)

/** A stretch of a line's text set in one look. */
data class PdfRun(val text: String, val look: PdfLook?)

/**
 * Ink a page draws rather than places: the box a painted path covers, in
 * top-down page points.
 *
 * A chart, a diagram, an organisation tree, a signature — a spreadsheet,
 * a word processor and every drawing tool export one as paths, not as a
 * picture the file holds. A reader that collects pictures finds none of
 * it, and the text of a report converts while every figure in it
 * vanishes without a word.
 *
 * A path is not a figure on its own: a rule is one, so is the shading
 * behind a table's head, so is the border round a page. What tells them
 * apart is what they hold — a figure holds no words of the document,
 * because it is not behind anything.
 */
data class PdfDrawing(
    /** 1-based page number. */
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /**
     * How many painted paths this box is the reach of. One filled
     * rectangle is shading, a highlight or a box round a paragraph; a
     * chart is dozens of strokes, and the difference is what says whether
     * words standing inside it were drawn behind or merely labelled.
     */
    val paths: Int = 1,
) {
    val widthPt: Float get() = right - left
    val heightPt: Float get() = bottom - top

    /** This box grown to hold [other] as well. */
    fun with(other: PdfDrawing) = PdfDrawing(
        page = page,
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
        paths = paths + other.paths,
    )

    /** Whether [other] overlaps this box, or comes within [gap] of it. */
    fun near(other: PdfDrawing, gap: Float): Boolean =
        page == other.page &&
            left - gap <= other.right && other.left - gap <= right &&
            top - gap <= other.bottom && other.top - gap <= bottom
}

/**
 * A line drawn across a page: a stroked rule or a filled sliver, in
 * top-down page points, with the page it was drawn on. Under a paper's
 * dates, above the note at its foot, between the rows of a table — a
 * conversion that keeps only the words loses all of it.
 */
data class PdfRule(
    /** 1-based page number. */
    val page: Int,
    val y: Float,
    val left: Float,
    val right: Float,
    /**
     * How thick the line is, in points. A hair under a word is an
     * underline; a band as deep as the type is a colour drawn behind it,
     * and reading that as a line struck through the words would say the
     * document had withdrawn what it had in fact emphasised.
     */
    val thicknessPt: Float = 0f,
)

/**
 * The sheet a page was drawn on, in points. The text box is not part of
 * it: the heuristics measure it from the lines they keep, so a running
 * header does not widen the block that alignment and margins are read
 * against.
 */
data class PdfPageSheet(
    /** 1-based page number. */
    val page: Int,
    val widthPt: Float,
    val heightPt: Float,
)
