package app.morpho.engine.pdf

import app.morpho.engine.layout.Reading
import app.morpho.engine.layout.pdf.PdfImage
import org.apache.pdfbox.contentstream.PDFStreamEngine
import org.apache.pdfbox.contentstream.operator.DrawObject
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.state.Concatenate
import org.apache.pdfbox.contentstream.operator.state.Restore
import org.apache.pdfbox.contentstream.operator.state.Save
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import org.apache.pdfbox.contentstream.operator.state.SetMatrix
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import javax.imageio.ImageIO

/**
 * Captures every image drawn on every page, with its top edge in the same
 * top-down coordinates [PositionTextStripper] uses, so images interleave
 * correctly with text. The standard content-stream recipe: track the CTM
 * through q/Q/cm and catch `Do` on image XObjects (recursing into forms).
 *
 * Pixels are re-encoded as PNG — exact, if not always the smallest; images
 * with an intrinsic side under [MIN_SIDE_PX] are skipped as decorations
 * (bullet dots, rules). Sizes are intrinsic pixels; display scaling is not
 * modeled yet.
 */
internal class ImageCapture : PDFStreamEngine() {

    private val captured = mutableListOf<PdfImage>()
    /**
     * Every picture already encoded, by the object the page draws. A logo
     * in a running header is one picture drawn on every page: encoding it
     * once and handing the same bytes to each page is the difference
     * between a document that converts and one that runs out of memory.
     */
    private val encoded = IdentityHashMap<Any, ByteArray>()
    private val failed = HashSet<Any>()
    private var pageNumber = 0
    private var pageHeight = 0f
    private val mcidStack = ArrayDeque<Int>()

    init {
        addOperator(Concatenate())
        addOperator(DrawObject())
        addOperator(SetGraphicsStateParameters())
        addOperator(Save())
        addOperator(Restore())
        addOperator(SetMatrix())
    }

    /**
     * [reading] stops this where it stands if the reader asks. It reports
     * no page: this pass is a small part of the work, and a count that ran
     * once here and again over the reading proper would run twice.
     */
    fun capture(doc: PDDocument, reading: Reading = Reading.UNWATCHED): List<PdfImage> {
        captured.clear()
        encoded.clear()
        failed.clear()
        for ((index, page) in doc.pages.withIndex()) {
            reading.carryOn()
            pageNumber = index + 1
            pageHeight = page.cropBox.height
            mcidStack.clear()
            attempt { processPage(page) }
        }
        return captured.toList()
    }

    override fun processOperator(operator: Operator, operands: List<COSBase>) {
        when (operator.name) {
            // Marked-content nesting: figures wrap their image draw in a
            // /Figure <</MCID n>> BDC ... EMC block.
            "BDC" -> { mcidStack.addLast(mcidOf(operands)); return }
            "BMC" -> { mcidStack.addLast(-1); return }
            "EMC" -> { mcidStack.removeLastOrNull(); return }
        }
        if (operator.name != "Do") {
            super.processOperator(operator, operands)
            return
        }
        val objectName = operands.firstOrNull() as? COSName ?: return
        when (val xobject = resources.getXObject(objectName)) {
            is PDImageXObject -> {
                if (xobject.width < MIN_SIDE_PX || xobject.height < MIN_SIDE_PX) return
                val ctm = graphicsState.currentTransformationMatrix
                val topDownY = pageHeight - (ctm.translateY + ctm.scalingFactorY)
                val key: Any = xobject.cosObject
                if (key in failed) return
                val png = encoded[key] ?: attempt {
                    val out = ByteArrayOutputStream()
                    ImageIO.write(xobject.image, "png", out)
                    out.toByteArray()
                }?.also { encoded[key] = it }
                if (png == null) {
                    failed += key
                    return
                }
                captured += PdfImage(
                    page = pageNumber,
                    topY = topDownY,
                    bytes = png,
                    mimeType = "image/png",
                    widthPx = xobject.width,
                    heightPx = xobject.height,
                    mcid = mcidStack.lastOrNull { it >= 0 } ?: -1,
                )
            }
            is PDFormXObject -> showForm(xobject)
            else -> {}
        }
    }

    private fun mcidOf(operands: List<COSBase>): Int {
        if (operands.size < 2) return -1
        val properties = when (val raw = operands[1]) {
            is COSDictionary -> raw
            is COSName ->
                runCatching { resources?.getProperties(raw)?.cosObject }.getOrNull()
            else -> null
        }
        return properties?.getInt(COSName.MCID, -1) ?: -1
    }

    private companion object {
        const val MIN_SIDE_PX = 8
    }
}
