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
            val page = PDFRenderer(document).renderImage(pageIndex, SCALE)
            val x = (left * SCALE).roundToInt().coerceIn(0, page.width - 1)
            val y = (top * SCALE).roundToInt().coerceIn(0, page.height - 1)
            val w = ((right - left) * SCALE).roundToInt().coerceIn(1, page.width - x)
            val h = ((bottom - top) * SCALE).roundToInt().coerceIn(1, page.height - y)
            val crop = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val graphics = crop.createGraphics()
            try {
                graphics.drawImage(page.getSubimage(x, y, w, h), 0, 0, null)
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
