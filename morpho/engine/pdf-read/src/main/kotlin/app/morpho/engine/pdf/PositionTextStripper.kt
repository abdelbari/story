package app.morpho.engine.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.Writer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One extracted output line with the geometry the untagged-layout heuristics
 * need. Coordinates are PDFBox direction-adjusted page space: [x] is the left
 * edge in points and [baselineY] grows downwards, so reading order on an
 * unrotated page means increasing [baselineY].
 */
internal data class PdfLine(
    val text: String,
    val x: Float,
    val baselineY: Float,
    val maxFontSize: Float,
    /** 1-based page number. */
    val page: Int,
)

/**
 * A [PDFTextStripper] that captures positioned lines instead of emitting
 * text: [capture] returns every output line with its left edge, baseline,
 * largest font size, and page number, in reading order (sort-by-position is
 * always on). Nothing is ever written to the stripper's output.
 *
 * The word text PDFBox hands to [writeString] has already been through the
 * stripper's own BiDi normalisation, so captured lines are logical-order
 * Unicode and can go straight into the IR.
 */
internal class PositionTextStripper : PDFTextStripper() {

    private val captured = mutableListOf<PdfLine>()
    private val lineText = StringBuilder()
    private var lineX = Float.MAX_VALUE
    private var lineY = 0f
    private var lineFontSize = 0f
    private var linePage = 0

    init {
        sortByPosition = true
    }

    /** Extracts the positioned lines of [document], leaving it open. */
    fun capture(document: PDDocument): List<PdfLine> {
        captured.clear()
        resetLine()
        writeText(document, Writer.nullWriter())
        flushLine()
        return captured.toList()
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (text.isBlank() || textPositions.isEmpty()) return
        val baselineY = textPositions.first().yDirAdj
        if (lineText.isNotEmpty() && abs(baselineY - lineY) > SAME_LINE_TOLERANCE_PT) flushLine()
        if (lineText.isEmpty()) {
            lineY = baselineY
            linePage = currentPageNo
        }
        lineText.append(text)
        lineX = min(lineX, textPositions.minOf { it.xDirAdj })
        lineFontSize = max(lineFontSize, textPositions.maxOf { it.fontSizeInPt })
    }

    override fun writeWordSeparator() {
        if (lineText.isNotEmpty() && !lineText.endsWith(' ')) lineText.append(' ')
    }

    override fun writeLineSeparator() = flushLine()

    override fun writeParagraphEnd() = flushLine()

    override fun writePageEnd() = flushLine()

    private fun flushLine() {
        val text = lineText.toString().trim()
        if (text.isNotEmpty()) {
            captured += PdfLine(
                text = text,
                x = lineX,
                baselineY = lineY,
                maxFontSize = lineFontSize,
                page = linePage,
            )
        }
        resetLine()
    }

    private fun resetLine() {
        lineText.setLength(0)
        lineX = Float.MAX_VALUE
        lineY = 0f
        lineFontSize = 0f
    }

    private companion object {
        /** Words further apart vertically than this start a new captured line. */
        const val SAME_LINE_TOLERANCE_PT = 2f
    }
}
