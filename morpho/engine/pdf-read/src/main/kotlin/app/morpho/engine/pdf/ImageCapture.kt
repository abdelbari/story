package app.morpho.engine.pdf

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
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
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
    private var pageNumber = 0
    private var pageHeight = 0f

    init {
        addOperator(Concatenate())
        addOperator(DrawObject())
        addOperator(SetGraphicsStateParameters())
        addOperator(Save())
        addOperator(Restore())
        addOperator(SetMatrix())
    }

    fun capture(doc: PDDocument): List<PdfImage> {
        captured.clear()
        for ((index, page) in doc.pages.withIndex()) {
            pageNumber = index + 1
            pageHeight = page.cropBox.height
            runCatching { processPage(page) }
        }
        return captured.toList()
    }

    override fun processOperator(operator: Operator, operands: List<COSBase>) {
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
                val png = runCatching {
                    val out = ByteArrayOutputStream()
                    ImageIO.write(xobject.image, "png", out)
                    out.toByteArray()
                }.getOrNull() ?: return
                captured += PdfImage(
                    page = pageNumber,
                    topY = topDownY,
                    bytes = png,
                    mimeType = "image/png",
                    widthPx = xobject.width,
                    heightPx = xobject.height,
                )
            }
            is PDFormXObject -> showForm(xobject)
            else -> {}
        }
    }

    private companion object {
        const val MIN_SIDE_PX = 8
    }
}
