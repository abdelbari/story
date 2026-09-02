package app.morpho.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.morpho.engine.layout.ImageBlock
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
                val bytes = ByteArrayOutputStream()
                crop.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                crop.recycle()
                ImageBlock(
                    bytes = bytes.toByteArray(),
                    mimeType = "image/png",
                    widthPx = w,
                    heightPx = h,
                    widthPt = right - left,
                    heightPt = bottom - top,
                )
            } finally {
                page.recycle()
            }
        }.getOrNull()
    }
}
