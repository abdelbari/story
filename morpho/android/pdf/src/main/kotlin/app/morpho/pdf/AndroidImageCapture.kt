package app.morpho.pdf

import android.graphics.Bitmap
import app.morpho.engine.layout.pdf.PdfImage
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream

/**
 * Android twin of the engine's ImageCapture (:engine:pdf-read), built on the
 * tom-roush PDFBox port — keep the two in sync until the shared-source split
 * lands. Pixels come back as an [android.graphics.Bitmap] here and are
 * re-encoded as PNG; the desktop twin uses ImageIO for the same job.
 */
internal class AndroidImageCapture : PDFStreamEngine() {

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
                    xobject.image.compress(Bitmap.CompressFormat.PNG, 100, out)
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
