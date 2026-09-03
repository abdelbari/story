package app.morpho.engine.pdf

import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.ExtractedText
import app.morpho.engine.layout.pdf.PdfColumns
import app.morpho.engine.layout.pdf.PdfDrawing
import app.morpho.engine.layout.pdf.PdfLine
import app.morpho.engine.layout.pdf.PdfLook
import app.morpho.engine.layout.pdf.PdfMarks
import app.morpho.engine.layout.pdf.PdfPageSheet
import app.morpho.engine.layout.pdf.PdfRule
import app.morpho.engine.layout.pdf.PdfRun
import app.morpho.engine.layout.pdf.PdfSegment
import app.morpho.engine.layout.pdf.PdfSlant
import app.morpho.engine.layout.pdf.PdfWeight
import java.io.Writer
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/**
 * A [PDFTextStripper] that captures positioned lines instead of emitting
 * text: [capture] returns every output line with its edges, baseline,
 * largest font size, page number, and the look of each of its characters,
 * in reading order. Nothing is ever written to the stripper's output.
 *
 * PDFBox is left to decide what a line is — that is what gives the layout
 * heuristics their coordinates — but not what a line says. Its sort orders
 * glyphs strictly left to right, which is the wrong way round for a
 * right-to-left line, and its word breaks fall wherever that sort jumps. So
 * each line is rebuilt here from its own glyphs: put back into the order
 * they were painted, sorted left to right with a kerning step counted as
 * the step it is, and handed to [ExtractedText] to reconstruct logical
 * order over the whole line at once. Doing it per line rather than per word
 * is what lets a Latin phrase or a number inside an Arabic sentence keep
 * its own direction, and counting kerning is what keeps الجزائر one word
 * with its letters in order.
 */
internal class PositionTextStripper : PDFTextStripper() {

    private val captured = mutableListOf<PdfLine>()

    /** A line as painted, waiting for the document's direction to be known. */
    private class PendingLine(
        val visual: String,
        val painters: List<PdfLook?>,
        /** Where each character of [visual] was painted, left edge and right. */
        val starts: List<Float>,
        val ends: List<Float>,
        val x: Float,
        val xEnd: Float,
        val baselineY: Float,
        val maxFontSize: Float,
        val page: Int,
        val segments: List<PdfSegment>,
    ) {
        /** The line's own marks, ignoring the spaces no glyph painted. */
        fun marks(): List<Pair<Float, Float>> =
            visual.indices.filter { !visual[it].isWhitespace() }.map { starts[it] to ends[it] }

        /**
         * The line cut across [strip], as the two lines it turns out to be
         * — or null when it is one line after all. Visual order runs left
         * to right, so the cut is one place in the line.
         *
         * A line whose ink reaches into the strip runs across it and is
         * one line whatever else the page does: a title, a heading over
         * both columns, the running head at the top of the page. Cutting
         * one in half would leave two half-headings.
         */
        fun cutAt(strip: Pair<Float, Float>): Pair<PendingLine, PendingLine>? {
            val ink = visual.indices.filter { !visual[it].isWhitespace() }
            val middle = (strip.first + strip.second) / 2
            // The line's own clear space at the gutter. A line set in a
            // column stops at its margin and starts again at the next
            // column's, leaving the whole gutter clear; a line that runs
            // across the page leaves a word space at most.
            val before = ink.filter { ends[it] <= middle }.maxOfOrNull { ends[it] } ?: return null
            val after = ink.filter { starts[it] >= middle }.minOfOrNull { starts[it] } ?: return null
            if (after - before < CROSSES_THE_GUTTER * (strip.second - strip.first)) return null
            val at = ink.first { starts[it] >= after - 0.01f }
            val left = slice(0, at) ?: return null
            val right = slice(at, visual.length) ?: return null
            return left to right
        }

        /**
         * The line as the pieces a page's gutters cut it into, in the
         * order they stand across the page. A piece that lies wholly in
         * one column meets no gutter but its own and stays whole.
         */
        fun cutInto(strips: List<Pair<Float, Float>>): List<PendingLine> {
            var pieces = listOf(this)
            for (strip in strips) {
                pieces = pieces.flatMap { piece ->
                    piece.cutAt(strip)?.let { listOf(it.first, it.second) } ?: listOf(piece)
                }
            }
            return pieces
        }

        private fun slice(from: Int, to: Int): PendingLine? {
            val text = visual.substring(from, to)
            if (text.isBlank()) return null
            val ink = (from until to).filter { !visual[it].isWhitespace() }
            if (ink.isEmpty()) return null
            return PendingLine(
                visual = text,
                painters = painters.subList(from, to),
                starts = starts.subList(from, to),
                ends = ends.subList(from, to),
                x = ink.minOf { starts[it] },
                xEnd = ink.maxOf { ends[it] },
                baselineY = baselineY,
                maxFontSize = maxFontSize,
                page = page,
                segments = segments.filter {
                    val middle = (it.xStart + it.xEnd) / 2
                    middle >= ink.minOf { i -> starts[i] } && middle <= ink.maxOf { i -> ends[i] }
                },
            )
        }
    }

    private val pending = mutableListOf<PendingLine>()
    /** The sheet of every page that drew text, filled as the pages are read. */
    private val sheets = HashMap<Int, FloatArray>()
    /** Overrules a broken ToUnicode map with the embedded font's own cmap. */
    private val glyphText = GlyphUnicode()
    /** When each glyph was painted: the order PDFBox's own sort throws away. */
    private val paintOrder = IdentityHashMap<TextPosition, Int>()
    /** The colour each glyph was painted in, where it was not the plain black a page paints with. */
    private val colors = IdentityHashMap<TextPosition, Int>()

    /** The glyphs the page thickened by stroking round them, which is a bold nothing names. */
    private val stroked = IdentityHashMap<TextPosition, Boolean>()
    /** Where the pages' link annotations point, when the document has any. */
    private var links: PageLinks? = null
    private var highlights: PageHighlights? = null
    /** The rules the pages draw: a text engine is not given the path operators unless it asks. */
    private val ruleCatcher = RuleCatcher({ runCatching { currentPage }.getOrNull() }, { currentPageNo })

    init {
        ruleCatcher.installOn(this)
        // A text engine is given the operators it needs, and colour is not
        // among them: without these the graphics state stays the black a
        // page starts in, and every heading a producer set in its own
        // colour reads as black.
        addOperator(SetNonStrokingColorSpace())
        addOperator(SetNonStrokingColor())
        addOperator(SetNonStrokingColorN())
        addOperator(SetNonStrokingDeviceGrayColor())
        addOperator(SetNonStrokingDeviceRGBColor())
        addOperator(SetNonStrokingDeviceCMYKColor())
    }
    private var paintedSoFar = 0

    private val lineGlyphs = mutableListOf<TextPosition>()
    private val lineSegments = mutableListOf<PdfSegment>()
    private var lineY = 0f
    private var linePage = 0

    init {
        sortByPosition = true
    }

    /** Extracts the positioned lines of [document], leaving it open. */
    fun capture(document: PDDocument): List<PdfLine> {
        captured.clear()
        pending.clear()
        sheets.clear()
        paintOrder.clear()
        colors.clear()
        stroked.clear()
        ruleCatcher.rules.clear()
        ruleCatcher.marks.clear()
        paintedSoFar = 0
        links = runCatching { PageLinks(document) }.getOrNull()
        highlights = runCatching { PageHighlights(document) }.getOrNull()
        resetLine()
        writeText(document, Writer.nullWriter())
        flushLine()
        cutColumns()
        // Every line is reconstructed against the document's direction —
        // its /Lang, else the direction most of its text runs in — because
        // a line cannot tell its own: an Arabic line whose leftmost word is
        // an email address starts, visually, with a Latin letter.
        val base = Bidi.directionOfLanguage(runCatching { document.documentCatalog.language }.getOrNull())
            ?: Bidi.dominantDirection(pending.joinToString(separator = "\n") { it.visual })
        val marks = ruleCatcher.marks.groupBy { it.page }
        for (line in pending) {
            val logical = ExtractedText.toLogical(line.visual, marked(line, marks), base)
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
                runs = text.mapIndexed { index, c -> PdfRun(c.toString(), logical.painters[start + index]) },
                segments = line.segments,
            )
        }
        pending.clear()
        return captured.toList()
    }

    /**
     * [line]'s glyphs, each told whether the page drew a line under it or
     * through it.
     *
     * The rules are known only once the page has been read — a producer
     * may draw them before its text or after it — so this runs at the end
     * rather than as each glyph is measured, which is where the look of a
     * glyph is otherwise settled.
     */
    private fun marked(line: PendingLine, byPage: Map<Int, List<PdfRule>>): List<PdfLook?> {
        val rules = byPage[line.page] ?: return line.painters
        val ink = line.visual.indices.filter { !line.visual[it].isWhitespace() }
        if (ink.isEmpty()) return line.painters
        val inkLeft = ink.minOf { line.starts[it] }
        val inkRight = ink.maxOf { line.ends[it] }
        // Every rule that marks this line at all, before any glyph is
        // asked about: a page of rules is otherwise walked once per glyph.
        val marking = rules.mapNotNull { rule ->
            PdfMarks.of(rule, line.baselineY, line.maxFontSize, inkLeft, inkRight)?.let { rule to it }
        }
        if (marking.isEmpty()) return line.painters
        return line.painters.mapIndexed { index, look ->
            if (look == null) return@mapIndexed null
            val left = line.starts[index]
            val right = line.ends[index]
            var underline = look.underline
            var struck = look.struck
            for ((rule, mark) in marking) {
                if (!PdfMarks.covers(rule, left, right)) continue
                if (mark == PdfMarks.Mark.UNDERLINE) underline = true else struck = true
            }
            if (underline == look.underline && struck == look.struck) look
            else look.copy(underline = underline, struck = struck)
        }
    }

    /** The rules drawn on the pages of the last [capture]. */
    fun rules(): List<PdfRule> = ruleCatcher.rules.toList()

    /** The box every painted path of the last [capture] covered. */
    fun drawings(): List<PdfDrawing> = ruleCatcher.drawings.toList()

    /** The sheet of every page that drew text in the last [capture]. */
    fun pages(): List<PdfPageSheet> =
        sheets.toSortedMap().map { (page, sheet) -> PdfPageSheet(page, sheet[0], sheet[1]) }

    override fun processTextPosition(text: TextPosition) {
        paintOrder[text] = paintedSoFar++
        PaintColor.of(graphicsState)?.let { colors[text] = it }
        // A producer with no bold cut of the typeface strokes round each
        // letter to thicken it. The state that says so is gone by the time
        // the line is assembled, so it is noted here with the glyph.
        if (thickened()) stroked[text] = true
        super.processTextPosition(text)
    }

    /** Whether the state the current glyph is drawn in strokes round it to embolden it. */
    private fun thickened(): Boolean = runCatching {
        val state = graphicsState
        PdfWeight.strokes(state.textState.renderingMode.intValue(), state.lineWidth)
    }.getOrDefault(false)

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isEmpty()) return
        rememberSheet()
        val baselineY = textPositions.first().yDirAdj
        if (lineGlyphs.isNotEmpty() && abs(baselineY - lineY) > sameLineTolerance(textPositions)) flushLine()
        if (lineGlyphs.isEmpty()) {
            lineY = baselineY
            linePage = currentPageNo
        }
        lineGlyphs += textPositions
        val ink = textPositions.filter { !it.unicode.isNullOrBlank() }
        if (ink.isNotEmpty()) {
            lineSegments += PdfSegment(
                text = text.trim(),
                xStart = ink.minOf { it.xDirAdj },
                xEnd = ink.maxOf { it.xDirAdj + it.widthDirAdj },
            )
        }
    }

    /**
     * Cuts every page set in columns into its columns.
     *
     * A journal sets its two columns on the same grid, so both are painted
     * on the same baselines and every line reaches from the first column's
     * margin to the second's. Read that way the page has no clear strip
     * down its middle at all: [PdfColumns] can find nothing to work with,
     * and the alignment of the two columns reads as a table of two, so a
     * paper comes back as a grid with half a sentence in every cell.
     *
     * The marks themselves say where the columns are — a strip no letter
     * of the page crosses — and a line that reaches across it is two
     * lines. Cut here, where every character still knows where it was
     * painted, and everything downstream sees a page it understands.
     */
    private fun cutColumns() {
        val byPage = pending.groupBy { it.page }
        if (byPage.isEmpty()) return
        val cut = mutableListOf<PendingLine>()
        for ((_, lines) in byPage.entries.sortedBy { it.key }) {
            val strips = gutters(lines, depth = 0)
            if (strips.isEmpty()) {
                cut += lines
                continue
            }
            for (line in lines) cut += line.cutInto(strips)
        }
        pending.clear()
        pending += cut
    }

    /**
     * Every strip the lines of a page agree on leaving clear, in the order
     * they stand across it.
     *
     * A page of three columns is a page of two, one of which is a page of
     * two — so each side is asked the same question again. Asked once, a
     * page of three gave up its second gutter alone, and the two columns
     * left on the other side of it were read as a table of two with half a
     * sentence in every cell, which is the very thing finding the first
     * gutter was for.
     *
     * A line that runs across a strip rather than stopping at it — a
     * title, a heading over the columns, the running head — is left out of
     * the question the columns under it are asked, having nothing to say
     * about where they divide.
     */
    private fun gutters(lines: List<PendingLine>, depth: Int): List<Pair<Float, Float>> {
        if (depth >= PdfColumns.DEEPEST_SPLIT) return emptyList()
        val strip = PdfColumns.gutterOfMarks(lines.map { it.marks() }) ?: return emptyList()
        val halves = lines.mapNotNull { it.cutAt(strip) }
        return (
            gutters(halves.map { it.first }, depth + 1) +
                listOf(strip) +
                gutters(halves.map { it.second }, depth + 1)
            ).sortedBy { it.first }
    }

    /**
     * How far off a line's baseline a glyph may sit and still belong to it.
     * A footnote mark is set small and raised a third of the line's height,
     * and a fixed hair of tolerance leaves it stranded as a line of its own
     * — the mark then reads as a paragraph, and the note it calls has
     * nothing to attach to. Measured against the type of the line, it is
     * still nowhere near the step to the next line, which is at least a
     * whole line's height away.
     */
    private fun sameLineTolerance(incoming: List<TextPosition>): Float {
        val sizes = (lineGlyphs + incoming).mapNotNull { it.fontSizeInPt.takeIf { size -> size > 0f } }
        val largest = sizes.maxOrNull() ?: return SAME_LINE_TOLERANCE_PT
        return max(SAME_LINE_TOLERANCE_PT, RAISED_TOLERANCE_SHARE * largest)
    }

    /** Word breaks come from the page, so PDFBox's own are not needed. */
    override fun writeWordSeparator() = Unit

    override fun writeLineSeparator() = flushLine()

    override fun writeParagraphEnd() = flushLine()

    override fun writePageEnd() {
        flushLine()
        // The order glyphs were painted in and the colours they were painted
        // with are wanted only while the line they belong to is being built,
        // and a line does not cross a page. Keeping them for the whole
        // document keeps every glyph of it in memory as well: a book runs
        // out of room on a phone long before it runs out of pages.
        paintOrder.clear()
        colors.clear()
        stroked.clear()
    }

    private fun flushLine() {
        if (lineGlyphs.isEmpty()) {
            resetLine()
            return
        }
        val ordered = inVisualOrder(lineGlyphs)
        val visual = StringBuilder()
        val painters = mutableListOf<PdfLook?>()
        val starts = mutableListOf<Float>()
        val ends = mutableListOf<Float>()
        val baseline = dominantBaseline(ordered)
        val lineSize = ordered.filter { abs(it.yDirAdj - baseline) <= SAME_LINE_TOLERANCE_PT }
            .maxOfOrNull { it.fontSizeInPt } ?: 0f
        // A producer that painted its spaces is trusted on where the words
        // are. Only one that painted none has its word breaks read from the
        // gaps, as PDFBox's own stripper does — a kerning gap inside a word
        // is otherwise easy to mistake for one.
        val inferBreaks = ordered.none { glyphText.of(it).let { u -> u.isNotEmpty() && u.isBlank() } }
        var previous: TextPosition? = null
        for ((index, position) in ordered.withIndex()) {
            val unicode = ExtractedText.paintedForm(glyphText.of(position))
            if (unicode.isEmpty()) continue
            // A painted space with no room on the page between its
            // neighbours — Word's Arabic justification leaves one inside a
            // word — is not a word break; the page shows one word.
            if (unicode.isBlank() && isSwallowed(ordered, index)) continue
            if (previous != null && previous.widthDirAdj > 0f &&
                unicode.isNotBlank() && !visual.endsWith(' ')
            ) {
                val gap = position.xDirAdj - (previous.xDirAdj + previous.widthDirAdj)
                val enough =
                    if (inferBreaks) WORD_GAP_FACTOR else WIDE_GAP_FACTOR
                if (gap > enough * position.fontSizeInPt) {
                    visual.append(' ')
                    painters += null
                    starts += previous.xDirAdj + previous.widthDirAdj
                    ends += position.xDirAdj
                }
            }
            val look = lookOf(position, raised(position, baseline, lineSize))
            visual.append(unicode)
            repeat(unicode.length) {
                painters += look
                // Where the character was painted, kept alongside its look:
                // a page set in columns is cut apart by these, and nothing
                // else on the page says where one column ends.
                starts += position.xDirAdj
                ends += position.xDirAdj + position.widthDirAdj
            }
            previous = position
        }
        val ink = ordered.filter { !it.unicode.isNullOrBlank() }
        if (visual.isNotBlank() && ink.isNotEmpty()) {
            pending += PendingLine(
                visual = visual.toString(),
                painters = painters,
                starts = starts.toList(),
                ends = ends.toList(),
                x = ink.minOf { it.xDirAdj },
                xEnd = ink.maxOf { it.xDirAdj + it.widthDirAdj },
                baselineY = baseline,
                maxFontSize = ordered.maxOf { it.fontSizeInPt },
                page = linePage,
                segments = lineSegments.toList(),
            )
        }
        resetLine()
    }

    /**
     * The line's glyphs left to right, with a kerning step counted as the
     * step it is: in الجزائر the ا is painted right after the ز and a hair
     * to its left, and sorted strictly by x the two come back swapped. A
     * glyph painted right after another and barely to its left is not to
     * its left in any sense that matters, so it takes a position just past
     * it. A real step backwards — the next word of a right-to-left line —
     * is many points wide and keeps its own place.
     */
    private fun inVisualOrder(glyphs: List<TextPosition>): List<TextPosition> {
        val painted = glyphs.sortedBy { paintOrder[it] ?: 0 }
        val sortsAt = IdentityHashMap<TextPosition, Float>()
        var previous = Float.NEGATIVE_INFINITY
        for (glyph in painted) {
            val x = glyph.xDirAdj
            val at = if (x < previous && previous - x <= KERNING_OVERLAP_PT) previous + 0.01f else x
            sortsAt[glyph] = at
            previous = at
        }
        return painted.sortedBy { sortsAt[it] ?: it.xDirAdj }
    }

    /** The baseline most of the line's glyphs sit on, to the half point. */
    private fun dominantBaseline(line: List<TextPosition>): Float {
        val counts = HashMap<Int, Int>()
        for (glyph in line) {
            if (glyph.unicode.isNullOrBlank()) continue
            val bucket = (glyph.yDirAdj * 2f).toInt()
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }
        val bucket = counts.maxByOrNull { it.value }?.key ?: return lineY
        return line.filter { (it.yDirAdj * 2f).toInt() == bucket }.maxOf { it.yDirAdj }
    }

    /** +1 for a smaller glyph raised off the line's baseline, -1 for one lowered, else 0. */
    private fun raised(position: TextPosition, baseline: Float, lineSize: Float): Int {
        if (lineSize <= 0f || position.fontSizeInPt >= lineSize) return 0
        val lift = baseline - position.yDirAdj
        return when {
            lift > RAISED_SHARE * lineSize -> 1
            lift < -RAISED_SHARE * lineSize -> -1
            else -> 0
        }
    }

    private fun isSwallowed(ordered: List<TextPosition>, index: Int): Boolean {
        val space = ordered[index]
        val before = (index - 1 downTo 0).map { ordered[it] }.firstOrNull { !it.unicode.isNullOrBlank() }
            ?: return false
        val after = (index + 1 until ordered.size).map { ordered[it] }.firstOrNull { !it.unicode.isNullOrBlank() }
            ?: return false
        val clear = after.xDirAdj - (before.xDirAdj + before.widthDirAdj)
        val needed = if (space.widthDirAdj > 0f) VISIBLE_SPACE_SHARE * space.widthDirAdj
        else VISIBLE_SPACE_SHARE * WORD_GAP_FACTOR * space.fontSizeInPt
        return clear < needed
    }

    /** The typeface, size, weight and slant a glyph was drawn in. */
    private fun lookOf(position: TextPosition, raised: Int): PdfLook {
        val name = position.font?.name
        return PdfLook(
            fontFamily = name?.substringAfter('+', name)?.substringBefore(',')?.trim()?.ifEmpty { null },
            fontSizePt = position.fontSizeInPt,
            bold = heavy(position),
            italic = leans(position),
            raised = raised,
            colorRgb = colors[position],
            highlightRgb = highlightAt(position),
            underline = markedAt(position, under = true),
            struck = markedAt(position, under = false),
            link = linkAt(position),
        )
    }

    /**
     * Whether a reader drew a line under [position], or through it.
     *
     * A marking is the reader's own reading of the document, and the two
     * kinds that are not a highlight are the two that change what it
     * says. The lines a page itself draws are read elsewhere, from the
     * rules; these are the ones somebody added afterwards.
     */
    private fun markedAt(position: TextPosition, under: Boolean): Boolean {
        val page = highlights?.page(currentPageNo - 1) ?: return false
        val x = position.xDirAdj + position.widthDirAdj / 2
        val y = position.yDirAdj - position.heightDir / 2
        return if (under) page.underlined(x, y) else page.struck(x, y)
    }

    /**
     * Whether [position] was drawn heavy, and so reads as bold.
     *
     * The font's name is what a producer with the bold cut of a typeface
     * writes there. A subset with a made-up name says it in its
     * descriptor instead, and a producer with no bold cut says it in
     * neither — it strokes round each letter to thicken it, exactly as it
     * skews the matrix to fake a lean.
     */
    private fun heavy(position: TextPosition): Boolean {
        if (stroked[position] == true) return true
        val font = position.font
        if (PdfWeight.named(font?.name)) return true
        val descriptor = runCatching { font?.fontDescriptor }.getOrNull() ?: return false
        return runCatching { PdfWeight.declares(descriptor.fontWeight, descriptor.flags) }.getOrDefault(false)
    }

    /**
     * Whether [position] was drawn leaning, and so reads as italic.
     *
     * Three things can say so and any one of them is enough: the font's
     * name, the font's own declared angle, and the matrix the glyph was
     * drawn with. The last is the one that matters for a document set in
     * a typeface with no italic cut — every Arabic one Word ships — where
     * the producer fakes the lean by skewing what it draws with and names
     * the upright font it started from.
     */
    private fun leans(position: TextPosition): Boolean {
        val font = position.font
        if (PdfSlant.named(font?.name)) return true
        val declared = runCatching { font?.fontDescriptor?.italicAngle }.getOrNull()
        if (declared != null && PdfSlant.declares(declared)) return true
        val matrix = runCatching { position.textMatrix }.getOrNull() ?: return false
        return PdfSlant.leansIn(matrix.scaleX, matrix.shearY, matrix.shearX, matrix.scaleY)
    }

    /** The colour of the highlight over [position], if one covers it. */
    private fun highlightAt(position: TextPosition): Int? {
        val highlights = highlights ?: return null
        return highlights.at(
            currentPageNo - 1,
            position.xDirAdj + position.widthDirAdj / 2,
            position.yDirAdj - position.heightDir / 2,
        )
    }

    /** Where the annotation over [position] points, if one covers it. */
    private fun linkAt(position: TextPosition): String? {
        val links = links ?: return null
        return links.at(
            currentPageNo - 1,
            position.xDirAdj + position.widthDirAdj / 2,
            position.yDirAdj - position.heightDir / 2,
        )
    }

    /** Remembers the sheet a page was drawn on, the first time it draws text. */
    private fun rememberSheet() {
        sheets.getOrPut(currentPageNo) {
            val page = runCatching { document.getPage(currentPageNo - 1) }.getOrNull()
            val box = page?.cropBox
            // A page may be written portrait and turned a quarter turn to be
            // read: the text is measured in the frame it is read in, so the
            // sheet is the one the reader sees, not the one it was written on.
            val turned = ((page?.rotation ?: 0) % 360 + 360) % 360 % 180 != 0
            if (turned) {
                floatArrayOf(box?.height ?: 0f, box?.width ?: 0f)
            } else {
                floatArrayOf(box?.width ?: 0f, box?.height ?: 0f)
            }
        }
    }

    private fun resetLine() {
        lineGlyphs.clear()
        lineSegments.clear()
        lineY = 0f
        linePage = 0
    }

    private companion object {
        /**
         * Of the page's gutter, the share a line must leave clear to count
         * as two lines. A line that leaves less runs across the page — a
         * title, a heading over both columns, the running head — and is
         * one line.
         */
        const val CROSSES_THE_GUTTER = 0.6f


        /** Glyphs further apart vertically than this sit on different lines. */
        const val SAME_LINE_TOLERANCE_PT = 2f
        /** …unless they are within this share of the line's own type of it, which a raised mark is. */
        const val RAISED_TOLERANCE_SHARE = 0.45f
        /** A painted space needs this share of its own width clear between its neighbours to be a word break. */
        const val VISIBLE_SPACE_SHARE = 0.3f
        /** A gap wider than this share of the type size is a word break, where no space was painted. */
        const val WORD_GAP_FACTOR = 0.2f

        /**
         * A gap this many type sizes wide is a word break whatever else the
         * line holds.
         *
         * A producer that paints its own spaces is trusted on where the
         * words are, and a line of its is read exactly as painted — which
         * is right until the "line" is a row of a table. Its cells stand
         * apart by tens of points, and one cell holding a space of its own
         * is enough to have every one of those gaps read as nothing: a
         * head reading "Item Respondents Share" in English came back as
         * three words and in Arabic as one, because the Arabic for
         * "respondents" is two words and the English is one.
         *
         * Nothing inside a word is ever this wide. Word stretches the
         * spaces of a justified Arabic line and draws a kashida through
         * its letters; neither reaches two whole type sizes, and both are
         * painted rather than left as a gap.
         */
        const val WIDE_GAP_FACTOR = 2f
        /** A smaller glyph off the line's baseline by this share of its type size is raised or lowered. */
        const val RAISED_SHARE = 0.2f
        /** A backward step no wider than this, right after the previous glyph, is kerning, not a new word. */
        const val KERNING_OVERLAP_PT = 1.5f
    }
}
