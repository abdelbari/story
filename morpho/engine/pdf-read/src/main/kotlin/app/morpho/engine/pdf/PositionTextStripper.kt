package app.morpho.engine.pdf

import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.ExtractedText
import app.morpho.engine.layout.pdf.PdfLine
import app.morpho.engine.layout.pdf.PdfLook
import app.morpho.engine.layout.pdf.PdfPageSheet
import app.morpho.engine.layout.pdf.PdfRun
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
        val painters: List<PdfLook?>,
        val x: Float,
        val xEnd: Float,
        val baselineY: Float,
        val maxFontSize: Float,
        val page: Int,
        val segments: List<PdfSegment>,
    )

    /** A glyph's look before its line knows which baseline is the line's own. */
    private class PendingLook(val baselineY: Float, val look: PdfLook)

    private val pending = mutableListOf<PendingLine>()
    /** The sheet of every page that drew text, filled as the pages are read. */
    private val sheets = HashMap<Int, FloatArray>()
    /** Overrules a broken ToUnicode map with the embedded font's own cmap. */
    private val glyphText = GlyphUnicode()
    private val lineText = StringBuilder()
    private val linePainters = mutableListOf<PendingLook?>()
    private var lineXEnd = 0f
    /** A word break the stripper offered, held until the next chunk says whether the page shows one. */
    private var separatorPending = false
    private var chunkEnd = Float.NEGATIVE_INFINITY
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
        sheets.clear()
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
            val logical = ExtractedText.toLogical(line.visual, line.painters, base)
            val text = logical.text.trim()
            if (text.isEmpty()) continue
            // Trimming the text moves the run boundaries with it.
            val start = logical.text.indexOfFirst { !it.isWhitespace() }
            captured += PdfLine(
                text = text,
                x = line.x,
                xEnd = line.xEnd,
                baselineY = line.baselineY,
                maxFontSize = line.maxFontSize,
                page = line.page,
                runs = text.mapIndexed { index, c ->
                    PdfRun(c.toString(), logical.painters[start + index])
                },
                segments = line.segments,
            )
        }
        pending.clear()
        return captured.toList()
    }

    /** The sheet of every page that drew text in the last [capture]. */
    fun pages(): List<PdfPageSheet> =
        sheets.toSortedMap().map { (page, sheet) -> PdfPageSheet(page, sheet[0], sheet[1]) }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (text.isBlank() || textPositions.isEmpty()) return
        // The painted glyphs, not PDFBox's direction-corrected word: the
        // line is reconstructed as a whole in flushLine.
        val paintedText = StringBuilder()
        val paintedLooks = mutableListOf<PendingLook?>()
        for ((index, position) in textPositions.withIndex()) {
            val unicode = ExtractedText.paintedForm(glyphText.of(position))
            // A painted space with no room on the page between its
            // neighbours — Word's Arabic justification leaves one inside
            // a word — is not a word break; the page shows one word.
            if (unicode.isNotEmpty() && unicode.isBlank() && isSwallowed(textPositions, index)) continue
            paintedText.append(unicode)
            val look = PendingLook(position.yDirAdj, lookOf(position))
            repeat(unicode.length) { paintedLooks += look }
        }
        val painted = paintedText.toString()
        if (painted.isEmpty()) return
        rememberSheet()
        val baselineY = textPositions.first().yDirAdj
        if (lineText.isNotEmpty() && abs(baselineY - lineY) > SAME_LINE_TOLERANCE_PT) flushLine()
        if (lineText.isEmpty()) {
            lineY = baselineY
            linePage = currentPageNo
        }
        // A word break offered between two chunks that do not clear each
        // other on the page is a kerning step, not a space: in الجزائر the
        // ا is painted a hair to the left of the ز and the two arrive as
        // separate chunks.
        if (separatorPending) {
            separatorPending = false
            val start = textPositions.minOf { it.xDirAdj }
            val size = textPositions.first().fontSizeInPt
            if (lineText.isNotEmpty() && !lineText.endsWith(' ') &&
                start - chunkEnd > VISIBLE_SPACE_SHARE * WORD_GAP_FACTOR * size
            ) {
                lineText.append(' ')
                linePainters += null
            }
        }
        lineText.append(painted)
        linePainters += paintedLooks
        chunkEnd = textPositions.maxOf { it.xDirAdj + it.widthDirAdj }
        lineX = min(lineX, textPositions.minOf { it.xDirAdj })
        lineXEnd = max(lineXEnd, textPositions.maxOf { it.xDirAdj + it.widthDirAdj })
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
        separatorPending = true
    }

    /** The typeface, size, weight and slant a glyph was drawn in. */
    private fun lookOf(position: TextPosition): PdfLook {
        val name = position.font?.name
        return PdfLook(
            fontFamily = name?.substringAfter('+', name)?.substringBefore(',')?.trim()?.ifEmpty { null },
            fontSizePt = position.fontSizeInPt,
            bold = name?.contains("Bold", ignoreCase = true) ?: false,
            italic = name?.let { it.contains("Italic", ignoreCase = true) || it.contains("Oblique", ignoreCase = true) } ?: false,
        )
    }

    /** Remembers the sheet a page was drawn on, the first time it draws text. */
    private fun rememberSheet() {
        sheets.getOrPut(currentPageNo) {
            val box = runCatching { document.getPage(currentPageNo - 1).cropBox }.getOrNull()
            floatArrayOf(box?.width ?: 0f, box?.height ?: 0f)
        }
    }

    override fun writeLineSeparator() = flushLine()

    override fun writeParagraphEnd() = flushLine()

    override fun writePageEnd() = flushLine()

    private fun flushLine() {
        val visual = lineText.toString()
        if (visual.isNotBlank()) {
            pending += PendingLine(
                visual = visual,
                painters = raisedResolved(),
                x = lineX,
                xEnd = lineXEnd,
                baselineY = lineY,
                maxFontSize = lineFontSize,
                page = linePage,
                segments = lineSegments.toList(),
            )
        }
        resetLine()
    }

    /**
     * The line's looks with each glyph told whether it is raised: a smaller
     * glyph off the baseline most of the line sits on is a superscript or a
     * subscript, which only the whole line can say.
     */
    private fun raisedResolved(): List<PdfLook?> {
        val baseline = linePainters.filterNotNull()
            .groupingBy { (it.baselineY * 2f).toInt() }.eachCount()
            .maxByOrNull { it.value }?.key?.let { it / 2f } ?: lineY
        val lineSize = linePainters.filterNotNull()
            .filter { abs(it.baselineY - baseline) <= SAME_LINE_TOLERANCE_PT }
            .maxOfOrNull { it.look.fontSizePt } ?: 0f
        return linePainters.map { pending ->
            if (pending == null) return@map null
            val look = pending.look
            if (lineSize <= 0f || look.fontSizePt >= lineSize) return@map look
            val lift = baseline - pending.baselineY
            when {
                lift > RAISED_SHARE * lineSize -> look.copy(raised = 1)
                lift < -RAISED_SHARE * lineSize -> look.copy(raised = -1)
                else -> look
            }
        }
    }

    private fun resetLine() {
        lineText.setLength(0)
        linePainters.clear()
        lineXEnd = 0f
        separatorPending = false
        chunkEnd = Float.NEGATIVE_INFINITY
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
        /** A smaller glyph off the line's baseline by this share of its type size is raised or lowered. */
        const val RAISED_SHARE = 0.2f
    }
}
