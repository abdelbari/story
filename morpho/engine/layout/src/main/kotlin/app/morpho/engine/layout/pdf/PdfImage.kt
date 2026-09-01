package app.morpho.engine.layout.pdf

/**
 * One image placement captured from a PDF content stream, in the same
 * top-down page coordinates as [PdfLine.baselineY] so [PdfLayout] can
 * interleave images with text by position. [widthPx]/[heightPx] are the
 * image's intrinsic pixel dimensions (display scaling is not modeled yet).
 */
class PdfImage(
    /** 1-based page number. */
    val page: Int,
    /** Top edge of the drawn image, growing downwards. */
    val topY: Float,
    val bytes: ByteArray,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    /**
     * The innermost marked-content id active when the image was drawn, or -1
     * when it was drawn outside marked content. Lets the tagged fast path
     * resolve `Figure` structure elements to their images.
     */
    val mcid: Int = -1,
)
