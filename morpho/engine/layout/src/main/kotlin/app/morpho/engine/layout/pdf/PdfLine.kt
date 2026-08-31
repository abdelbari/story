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
)
