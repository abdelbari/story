package app.morpho.pdf

import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.ExtractedText
import app.morpho.engine.layout.pdf.PdfLine
import app.morpho.engine.layout.pdf.PdfSegment
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Writer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Android twin of the engine's PositionTextStripper (:engine:pdf-read),
 * built on the tom-roush PDFBox port instead of desktop PDFBox. The two
 * mirror each other line for line — change both together until the
 * shared-source split lands. java.io.Writer.nullWriter() is API 33+, hence
 * the explicit no-op writer.
 */
internal class AndroidPositionTextStripper : PDFTextStripper() {

    private val captured = mutableListOf<PdfLine>()

    /** A line as painted, waiting for the document's direction to be known. */
    private class PendingLine(
        val visual: String,
        val x: Float,
        val baselineY: Float,
        val maxFontSize: Float,
        val page: Int,
        val segments: List<PdfSegment>,
    )

    private val pending = mutableListOf<PendingLine>()
    /** Overrules a broken ToUnicode map with the embedded font's own cmap. */
    private val glyphText = AndroidGlyphUnicode()
    private val lineText = StringBuilder()
    private val lineSegments = mutableListOf<PdfSegment>()
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
        pending.clear()
        resetLine()
        writeText(document, NoOpWriter)
        flushLine()
        // Every line is reconstructed against the document's direction —
        // its /Lang, else the direction most of its text runs in — because
        // a line cannot tell its own: an Arabic line whose leftmost word is
        // an email address starts, visually, with a Latin letter.
        val base = Bidi.directionOfLanguage(runCatching { document.documentCatalog.language }.getOrNull())
            ?: Bidi.dominantDirection(pending.joinToString(separator = "\n") { it.visual })
        for (line in pending) {
            val text = ExtractedText.toLogical(line.visual, base).trim()
            if (text.isEmpty()) continue
            captured += PdfLine(
                text = text,
                x = line.x,
                baselineY = line.baselineY,
                maxFontSize = line.maxFontSize,
                page = line.page,
                segments = line.segments,
            )
        }
        pending.clear()
        return captured.toList()
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (text.isBlank() || textPositions.isEmpty()) return
        // The painted glyphs, not PDFBox's direction-corrected word: the
        // line is reconstructed as a whole in flushLine.
        val painted = textPositions.joinToString(separator = "") { glyphText.of(it) }
        if (painted.isEmpty()) return
        val baselineY = textPositions.first().yDirAdj
        if (lineText.isNotEmpty() && abs(baselineY - lineY) > SAME_LINE_TOLERANCE_PT) flushLine()
        if (lineText.isEmpty()) {
            lineY = baselineY
            linePage = currentPageNo
        }
        lineText.append(painted)
        lineX = min(lineX, textPositions.minOf { it.xDirAdj })
        lineFontSize = max(lineFontSize, textPositions.maxOf { it.fontSizeInPt })
        lineSegments += PdfSegment(
            text = painted,
            xStart = textPositions.minOf { it.xDirAdj },
            xEnd = textPositions.maxOf { it.xDirAdj + it.widthDirAdj },
        )
    }

    override fun writeWordSeparator() {
        if (lineText.isNotEmpty() && !lineText.endsWith(' ')) lineText.append(' ')
    }

    override fun writeLineSeparator() = flushLine()

    override fun writeParagraphEnd() = flushLine()

    override fun writePageEnd() = flushLine()

    private fun flushLine() {
        val visual = lineText.toString()
        if (visual.isNotBlank()) {
            pending += PendingLine(
                visual = visual,
                x = lineX,
                baselineY = lineY,
                maxFontSize = lineFontSize,
                page = linePage,
                segments = lineSegments.toList(),
            )
        }
        resetLine()
    }

    private fun resetLine() {
        lineText.setLength(0)
        lineSegments.clear()
        lineX = Float.MAX_VALUE
        lineY = 0f
        lineFontSize = 0f
    }

    private object NoOpWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {}
        override fun flush() {}
        override fun close() {}
    }

    private companion object {
        /** Words further apart vertically than this start a new captured line. */
        const val SAME_LINE_TOLERANCE_PT = 2f
    }
}
