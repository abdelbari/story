package app.morpho.engine.pdf

import app.morpho.engine.layout.ImageBlock
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

    /**
     * The region [left]..[right] by [top]..[bottom] of a page, in top-down
     * page points, as a PNG; [masks] are regions painted white first — a
     * page number the writer will supply itself. Null when there is nothing
     * to crop or the page could not be drawn.
     */
    fun crop(
        document: PDDocument,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        masks: List<FloatArray> = emptyList(),
    ): ImageBlock? {
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
            val bytes = ByteArrayOutputStream()
            ImageIO.write(crop, "png", bytes)
            ImageBlock(
                bytes = bytes.toByteArray(),
                mimeType = "image/png",
                widthPx = w,
                heightPx = h,
                widthPt = right - left,
                heightPt = bottom - top,
            )
        }.getOrNull()
    }
}
