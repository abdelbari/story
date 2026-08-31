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
