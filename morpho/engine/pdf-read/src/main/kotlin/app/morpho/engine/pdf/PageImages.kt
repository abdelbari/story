package app.morpho.engine.pdf

import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.pdf.PageFurniture
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * A piece of a page as a picture: what a reader reaches for when a page
 * carries something that is not text — a running header drawn as artwork,
 * a footer whose words are outlines. The crop is rendered at [SCALE] times
 * the page's own resolution and placed at the size it had on the page.
 */
internal object PageImages {

    /** Pixels per point: 216 dots to the inch, enough for small type to stay crisp. */
    private const val SCALE = 3f

    /** How far from white a pixel must be to count as ink drawn on the page. */
    private const val INK_THRESHOLD = 24

    /**
     * The region [left]..[right] by [top]..[bottom] of a page, in top-down
     * page points, as a PNG; [masks] are regions painted white first — a
     * page number the writer will supply itself.
     *
     * With [trim] the blank around the ink is taken off and the result says
     * where what is left sits, so a band asked for generously comes back as
     * the words in it.
     *
     * Null when there is nothing to crop, the page could not be drawn, or
     * the region came back blank. A blank answer is worth refusing rather
     * than returning: a header the renderer failed to draw would otherwise
     * be written into the document as an empty white strip, which reads to
     * everyone as the header having been lost.
     */
    fun crop(
        document: PDDocument,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        masks: List<FloatArray> = emptyList(),
        trim: Boolean = false,
    ): PageFurniture.Cropped? {
        if (right - left < 1f || bottom - top < 1f) return null
        return runCatching {
            val sheet = sheetPixels(document, pageIndex) ?: return@runCatching null
            val x = (left * SCALE).roundToInt().coerceIn(0, sheet[0] - 1)
            val y = (top * SCALE).roundToInt().coerceIn(0, sheet[1] - 1)
            val w = ((right - left) * SCALE).roundToInt().coerceIn(1, sheet[0] - x)
            val h = ((bottom - top) * SCALE).roundToInt().coerceIn(1, sheet[1] - y)
            val crop = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val graphics = crop.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, w, h)
                // The band is drawn, not the page. A whole sheet at this
                // resolution is eighteen megabytes, and a head and a foot
                // want two of them; a phone that runs out of room for that
                // answers with no running head at all, which is the one
                // answer a reader cannot make sense of.
                graphics.translate(-x, -y)
                // The renderer clears the page before drawing it, with the
                // background this was given: unset, that is a transparent
                // black which on an opaque image is simply black, and a
                // header came back as a black strip with its rules on it.
                graphics.background = Color.WHITE
                PDFRenderer(document).renderPageToGraphics(pageIndex, graphics, SCALE)
                graphics.translate(x, y)
                graphics.color = Color.WHITE
                for (mask in masks) {
                    val mx = ((mask[0] - left) * SCALE).roundToInt()
                    val my = ((mask[1] - top) * SCALE).roundToInt()
                    val mw = ((mask[2] - mask[0]) * SCALE).roundToInt()
                    val mh = ((mask[3] - mask[1]) * SCALE).roundToInt()
                    graphics.fillRect(mx, my, mw, mh)
                }
            } finally {
                graphics.dispose()
            }
            val ink = inkBox(crop) ?: return@runCatching null
            val kept = if (trim) ink else intArrayOf(0, 0, w - 1, h - 1)
            val keptWidth = kept[2] - kept[0] + 1
            val keptHeight = kept[3] - kept[1] + 1
            val bytes = ByteArrayOutputStream()
            ImageIO.write(crop.getSubimage(kept[0], kept[1], keptWidth, keptHeight), "png", bytes)
            PageFurniture.Cropped(
                image = ImageBlock(
                    bytes = bytes.toByteArray(),
                    mimeType = "image/png",
                    widthPx = keptWidth,
                    heightPx = keptHeight,
                    // Untrimmed, the picture is placed at exactly the size
                    // asked for: rounding it through the pixel grid instead
                    // would move a header a third of a point for no reason.
                    widthPt = if (trim) keptWidth / SCALE else right - left,
                    heightPt = if (trim) keptHeight / SCALE else bottom - top,
                ),
                left = left + kept[0] / SCALE,
                top = top + kept[1] / SCALE,
                right = left + (kept[2] + 1) / SCALE,
                bottom = top + (kept[3] + 1) / SCALE,
            )
        }.getOrNull()
    }

    /**
     * How wide and how tall the page would be if it were drawn whole, in
     * pixels — what the band's own box is clamped against, since the band
     * is all that is ever drawn. A turned page measures across what it is
     * read across, which is the way round the text was read in too.
     */
    private fun sheetPixels(document: PDDocument, pageIndex: Int): IntArray? {
        if (pageIndex < 0 || pageIndex >= document.numberOfPages) return null
        val page = document.getPage(pageIndex)
        val box = page.cropBox
        val turned = page.rotation == 90 || page.rotation == 270
        val width = ((if (turned) box.height else box.width) * SCALE).roundToInt()
        val height = ((if (turned) box.width else box.height) * SCALE).roundToInt()
        return if (width < 1 || height < 1) null else intArrayOf(width, height)
    }

    /**
     * The smallest box of [image] holding every pixel that is not the white
     * of the paper, as left, top, right and bottom pixels inclusive; null
     * for an image with no ink on it at all.
     */
    private fun inkBox(image: BufferedImage): IntArray? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (255 - r < INK_THRESHOLD && 255 - g < INK_THRESHOLD && 255 - b < INK_THRESHOLD) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < left) null else intArrayOf(left, top, right, bottom)
    }
}
