package app.morpho.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.pdf.PageFurniture
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Android twin of the engine's PageImages (:engine:pdf-read), on the
 * tom-roush PDFBox port: the page is drawn to a [Bitmap] and cropped on a
 * [Canvas] where the desktop crops a BufferedImage. Keep the two in step.
 *
 * A piece of a page as a picture: what a reader reaches for when a page
 * carries something that is not text — a running header drawn as artwork,
 * a footer whose words are outlines. The crop is rendered at [SCALE] times
 * the page's own resolution and placed at the size it had on the page.
 */
internal object AndroidPageImages {

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
            try {
                val x = (left * SCALE).roundToInt().coerceIn(0, page.width - 1)
                val y = (top * SCALE).roundToInt().coerceIn(0, page.height - 1)
                val w = ((right - left) * SCALE).roundToInt().coerceIn(1, page.width - x)
                val h = ((bottom - top) * SCALE).roundToInt().coerceIn(1, page.height - y)
                val crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(crop)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(page, -x.toFloat(), -y.toFloat(), null)
                val white = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                for (mask in masks) {
                    canvas.drawRect(
                        (mask[0] - left) * SCALE, (mask[1] - top) * SCALE,
                        (mask[2] - left) * SCALE, (mask[3] - top) * SCALE,
                        white,
                    )
                }
                val ink = inkBox(crop)
                if (ink == null) {
                    crop.recycle()
                    return@runCatching null
                }
                val kept = if (trim) ink else intArrayOf(0, 0, w - 1, h - 1)
                val keptWidth = kept[2] - kept[0] + 1
                val keptHeight = kept[3] - kept[1] + 1
                val bytes = ByteArrayOutputStream()
                if (keptWidth == w && keptHeight == h) {
                    crop.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                } else {
                    val cut = Bitmap.createBitmap(crop, kept[0], kept[1], keptWidth, keptHeight)
                    cut.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                    cut.recycle()
                }
                crop.recycle()
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
            } finally {
                page.recycle()
            }
        }.getOrNull()
    }

    /**
     * The smallest box of [image] holding every pixel that is not the white
     * of the paper, as left, top, right and bottom pixels inclusive; null
     * for an image with no ink on it at all.
     *
     * The rows are read one at a time rather than pixel by pixel: a call
     * across the JNI boundary for each of half a million pixels is slow
     * enough on a phone to be felt.
     */
    private fun inkBox(image: Bitmap): IntArray? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            for (x in 0 until image.width) {
                val pixel = row[x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
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
