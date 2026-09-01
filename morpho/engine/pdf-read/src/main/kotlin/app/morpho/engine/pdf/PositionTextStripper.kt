package app.morpho.engine.pdf

import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.ExtractedText
import app.morpho.engine.layout.pdf.PdfLine
import app.morpho.engine.layout.pdf.PdfSegment
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.Writer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A [PDFTextStripper] that captures positioned lines instead of emitting
 * text: [capture] returns every output line with its left edge, baseline,
 * largest font size, and page number, in reading order (sort-by-position is
 * always on). Nothing is ever written to the stripper's output.
 *
 * Sorting by position is what makes the layout heuristics possible — they
 * need coordinates — but it orders words strictly left to right, which is
 * the wrong way round for a right-to-left line: its first word is the
 * rightmost one. So each line is captured exactly as painted, from the glyph
 * text of its [TextPosition]s rather than the word text PDFBox has already
 * put through its own direction pass, and [ExtractedText] then reconstructs
 * logical order over the whole line at once. Doing it per line rather than
 * per word is what lets a Latin phrase or a number inside an Arabic sentence
 * keep its own direction.
 */
internal class PositionTextStripper : PDFTextStripper() {

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
    private val glyphText = GlyphUnicode()
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
        writeText(document, Writer.nullWriter())
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
        val painted = buildString {
            for ((index, position) in textPositions.withIndex()) {
                val unicode = ExtractedText.paintedForm(glyphText.of(position))
                // A painted space with no room on the page between its
                // neighbours — Word's Arabic justification leaves one inside
                // a word — is not a word break; the page shows one word.
                if (unicode.isNotEmpty() && unicode.isBlank() && isSwallowed(textPositions, index)) continue
                append(unicode)
            }
        }
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

    private fun isSwallowed(positions: List<TextPosition>, index: Int): Boolean {
        val space = positions[index]
        val before = (index - 1 downTo 0).map { positions[it] }.firstOrNull { !it.unicode.isNullOrBlank() }
            ?: return false
        val after = (index + 1 until positions.size).map { positions[it] }.firstOrNull { !it.unicode.isNullOrBlank() }
            ?: return false
        val clear = after.xDirAdj - (before.xDirAdj + before.widthDirAdj)
        val needed = if (space.widthDirAdj > 0f) VISIBLE_SPACE_SHARE * space.widthDirAdj
        else VISIBLE_SPACE_SHARE * WORD_GAP_FACTOR * space.fontSizeInPt
        return clear < needed
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

    private companion object {
        /** Words further apart vertically than this start a new captured line. */
        const val SAME_LINE_TOLERANCE_PT = 2f
        /** A painted space needs this share of its own width clear between its neighbours to be a word break. */
        const val VISIBLE_SPACE_SHARE = 0.3f
        /** The word gap, as a share of type size, a zero-width space glyph is measured against. */
        const val WORD_GAP_FACTOR = 0.2f
    }
}
