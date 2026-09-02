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
)

/** A stretch of a line's text set in one look. */
data class PdfRun(val text: String, val look: PdfLook?)

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
