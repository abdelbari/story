package app.morpho.pdf

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ExtractedText
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import app.morpho.engine.layout.pdf.HeadingSizes
import app.morpho.engine.layout.pdf.PdfImage
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.OperatorProcessor
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import com.tom_roush.pdfbox.text.PDFMarkedContentExtractor
import com.tom_roush.pdfbox.text.TextPosition
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.IdentityHashMap

/**
 * Android twin of the engine's StructureTreeReader (:engine:pdf-read), built
 * on the tom-roush PDFBox port — keep the two in sync until the shared-source
 * split lands.
 *
 * The tagged-PDF fast path (plan §5.3 step 1): when a PDF carries a structure
 * tree — as PDFs exported from Word, LibreOffice, and accessible authoring
 * tools do — headings, paragraphs, lists, tables, and the logical reading
 * order are read directly from the tags instead of being re-guessed from
 * glyph positions. Tag order is logical order, which is exactly what makes
 * right-to-left documents come out right. Most competitors ignore this free
 * structure entirely.
 *
 * Mapping: P → body paragraph; H/H1 → HEADING_1, H2 → HEADING_2, H3–H6 →
 * HEADING_3; L/LI → list items (numbered when the item labels carry digits,
 * bullets otherwise); Table/TR/TH/TD → tables; grouping types (Document,
 * Part, Sect, Div, Art) recurse; Figure resolves to its captured image via
 * the marked-content id its draw was wrapped in (images the tree never
 * references are appended at the end); inline types (Span, Link, Quote,
 * Lbl, LBody, …) contribute text. Non-standard structure types are
 * resolved once through the role map.
 *
 * Returns null — so callers fall back to the position heuristics — when the
 * tree exists but yields no text (some producers write empty shells), or is
 * nested beyond [MAX_DEPTH].
 */
/**
 * A painted glyph with the position it sorts by. Usually its own x; for a
 * glyph a kerning hair to the left of the one painted just before it, a
 * point past that one instead, so the two keep their painting order.
 */
private class Glyph(val position: TextPosition, val x: Float)

/**
 * The look of one painted character — what a paragraph's runs are split
 * by. [raised] is +1 for a superscript, -1 for a subscript, 0 on the line.
 */
private data class Look(
    val bold: Boolean,
    val italic: Boolean,
    val family: String?,
    val sizePt: Float,
    val raised: Int,
)

/** An element's text in logical order with its looks, and the tab stops its lines were set to. */
private class StyledText(val logical: ExtractedText.Logical<Look>, val tabStopsPt: List<Float>)

/** [styled] without the blank characters at either end, painters kept in step. */
private fun trimmed(styled: ExtractedText.Logical<Look>): ExtractedText.Logical<Look> {
    val text = styled.text
    var start = 0
    var end = text.length
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start == 0 && end == text.length) return styled
    return ExtractedText.Logical(text.substring(start, end), styled.painters.subList(start, end))
}

/** Where an element's lines sit on the page, measured so a writer can put them back the same way. */
private class Placement(
    val firstPage: Int,
    val lastPage: Int,
    val alignment: Alignment?,
    val firstLineIndentPt: Float?,
    val startIndentPt: Float?,
    val hangingIndentPt: Float?,
    val firstBaseline: Float,
    val lastBaseline: Float,
    /** Distance between the element's own baselines, or null for a single line. */
    val pitchPt: Float?,
)

/** The extent of a page's text — every glyph the structure tree can reach, so headers and footers stay out. */
private class InkBox {
    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    val isEmpty get() = left > right || top > bottom
    fun add(glyph: TextPosition) {
        left = minOf(left, glyph.xDirAdj)
        right = maxOf(right, glyph.xDirAdj + glyph.widthDirAdj)
        top = minOf(top, glyph.yDirAdj - glyph.heightDir)
        bottom = maxOf(bottom, glyph.yDirAdj + DESCENT_SHARE_OF_SIZE * glyph.fontSizeInPt)
    }

    private companion object {
        const val DESCENT_SHARE_OF_SIZE = 0.25f
    }
}

internal object AndroidStructureTreeReader {

    private const val MAX_DEPTH = 128

    /** Glyphs further apart vertically than this sit on different lines. */

    private const val SAME_LINE_TOLERANCE_PT = 2f
    /** A horizontal gap wider than this share of the type size is a word break. */
    private const val WORD_GAP_FACTOR = 0.2f
    /** How far above its baseline, as a share of type size, a glyph still belongs to a line. */
    private const val SUPERSCRIPT_REACH = 0.5f
    /** A backward step no wider than this, right after the previous glyph, is kerning, not a new word. */
    private const val KERNING_OVERLAP_PT = 1.5f
    /** A line whose middle is within this share of the page width of the text block's middle is centred… */
    private const val CENTRE_TOLERANCE = 0.015f
    /** …provided it is shorter than this share of the block; a full line is not centred, just full. */
    private const val CENTRED_MAX_SHARE = 0.7f
    /** Lines whose edges agree within this are flush — a justified paragraph, or a margin. */
    private const val FLUSH_TOLERANCE_PT = 4f
    /** An edge at least this far in from the margin is an indent; nearer is the margin itself. */
    private const val INDENT_MIN_PT = 6f
    /** An indent past this share of the block is not one: the line is set against the far edge. */
    private const val INDENT_MAX_SHARE = 0.4f
    /** A smaller glyph raised or lowered by this share of the line's type size is a super- or subscript. */
    private const val RAISED_SHARE = 0.2f
    /** A painted space needs this share of its own width clear between its neighbours to be a word break. */
    private const val VISIBLE_SPACE_SHARE = 0.3f
    /** This many painted spaces in a row, or a gap as wide as them, is a tab, not spacing. */
    private const val TAB_MIN_SPACES = 3
    /** Space after a paragraph past this is a page's worth of gap, not the paragraph's own. */
    private const val SPACE_AFTER_MAX_PT = 60f
    /** Line pitch as a share of type size, for a face no two-line paragraph could measure. */
    private const val DEFAULT_PITCH_SHARE = 1.2f
    /** How far below its baseline a glyph reaches, as a share of type size, for want of font metrics. */
    private const val DESCENT_SHARE = 0.25f
    private const val CONFIDENCE = 0.9f

    fun read(doc: PDDocument, images: List<PdfImage> = emptyList()): DocumentModel? {
        val root = doc.documentCatalog.structureTreeRoot ?: return null
        val texts = MarkedContentIndex(doc)
        val roleMap: Map<String, Any> = runCatching { root.roleMap }.getOrNull().orEmpty()
        val builder = Builder(texts, roleMap, images)
        return try {
            for (kid in root.kids.orEmpty()) {
                if (kid is PDStructureElement) builder.walk(kid, depth = 0)
            }
            builder.result()
        } catch (_: TooDeepException) {
            null
        }
    }

    private class TooDeepException : RuntimeException()

    private fun imageKey(pageNumber: Int, mcid: Int): Long =
        pageNumber.toLong() shl 32 or (mcid.toLong() and 0xFFFFFFFFL)

    /**
     * PDFBox's stock extractor drops the MCID when a BDC operator uses the
     * named-resource form (`/P /Prop0 BDC`): the second COSName overwrites the
     * tag and the properties stay null. This subclass resolves named property
     * lists through the page resources, so both forms carry their MCID.
     */
    private class ResolvingMarkedContentExtractor : PDFMarkedContentExtractor() {
        init {
            addOperator(object : OperatorProcessor() {
                override fun getName() = "BDC"

                override fun process(operator: Operator, operands: List<COSBase>) {
                    if (operands.size < 2) return
                    val tag = operands[0] as? COSName ?: return
                    val properties = when (val raw = operands[1]) {
                        is COSDictionary -> raw
                        is COSName ->
                            runCatching { context.resources?.getProperties(raw)?.cosObject }
                                .getOrNull()
                        else -> null
                    }
                    context.beginMarkedContentSequence(tag, properties)
                }
            })
        }
    }

    /**
     * Text of every marked-content id, indexed by page. Pages are keyed by
     * their underlying COS dictionary: PDStructureElement.getPage() builds a
     * fresh PDPage wrapper on every call, so wrapper identity never matches.
     */
    private class MarkedContentIndex(doc: PDDocument) {
        private val pageIndexByPage = IdentityHashMap<COSDictionary, Int>()
        private val pageWidthByIndex = HashMap<Int, Float>()
        private val pageHeightByIndex = HashMap<Int, Float>()
        /** The reach of each page's tagged text: the block the margins and indents are measured from. */
        private val inkByPageIndex = HashMap<Int, InkBox>()
        private val glyphsByPageAndMcid = HashMap<Long, List<Glyph>>()
        private val textByPageAndMcid = HashMap<Long, String>()
        private val sizeByPageAndMcid = HashMap<Long, Float>()
        private val boldByPageAndMcid = HashMap<Long, Boolean>()
        /** Overrules a broken ToUnicode map with the embedded font's own cmap. */
        private val glyphText = AndroidGlyphUnicode()

        /**
         * The direction the document is written in: what its /Lang says,
         * else the direction most of its text runs in. Every line is
         * reconstructed against it, because a line cannot tell its own —
         * an Arabic line whose leftmost word is an email address starts,
         * visually, with a Latin letter.
         */
        private val baseDirection: TextDirection?

        init {
            for ((index, page) in doc.pages.withIndex()) {
                pageIndexByPage[page.cosObject] = index
                pageWidthByIndex[index] = runCatching { page.mediaBox.width }.getOrDefault(0f)
                pageHeightByIndex[index] = runCatching { page.mediaBox.height }.getOrDefault(0f)
                val extractor = ResolvingMarkedContentExtractor()
                runCatching { extractor.processPage(page) }
                for (content in extractor.markedContents.orEmpty()) {
                    collect(content, index)
                }
            }
            baseDirection = Bidi.directionOfLanguage(runCatching { doc.documentCatalog.language }.getOrNull())
                ?: Bidi.dominantDirection(buildString {
                    for (glyphs in glyphsByPageAndMcid.values) for (glyph in glyphs) append(glyph.position.unicode.orEmpty())
                })
        }

        private fun collect(content: PDMarkedContent, pageIndex: Int) {
            val glyphs = mutableListOf<TextPosition>()
            var size = 0f
            var bold = true
            fun gather(mc: PDMarkedContent) {
                for (item in mc.contents.orEmpty()) {
                    when (item) {
                        is TextPosition -> {
                            glyphs += item
                            size = maxOf(size, item.fontSizeInPt)
                            // Judged on letters: "2-تعريف" is a bold heading
                            // whose digit is set in a regular Latin face, and
                            // a digit or bracket must not veto the letters.
                            if (hasLetter(item.unicode) && !isBold(item)) bold = false
                        }
                        is PDMarkedContent -> gather(item)
                    }
                }
            }
            gather(content)
            if (content.mcid >= 0 && glyphs.any { !it.unicode.isNullOrEmpty() }) {
                glyphsByPageAndMcid[key(pageIndex, content.mcid)] = positioned(glyphs)
                val ink = inkByPageIndex.getOrPut(pageIndex) { InkBox() }
                for (glyph in glyphs) if (!glyph.unicode.isNullOrBlank()) ink.add(glyph)
                sizeByPageAndMcid[key(pageIndex, content.mcid)] = size
                boldByPageAndMcid[key(pageIndex, content.mcid)] = bold
            }
            // Nested marked content carries its own MCIDs too.
            for (item in content.contents.orEmpty()) {
                if (item is PDMarkedContent) collect(item, pageIndex)
            }
        }

        /**
         * The run's glyphs with the position each sorts by.
         *
         * Sorting strictly by x is right for everything but a kerning
         * overlap: in الجزائر the ا was painted after the ز and sits 0.4pt to
         * its left, and sorted by x the two swapped. A glyph painted right
         * after another and a hair to its left is not to its left in any
         * sense that matters, so it takes a position just past it. A real
         * step backwards — the next word of a line positioned right to left
         * — is many points wide and keeps its own x.
         */
        private fun positioned(glyphs: List<TextPosition>): List<Glyph> {
            val out = ArrayList<Glyph>(glyphs.size)
            var previous = Float.NEGATIVE_INFINITY
            for (glyph in glyphs) {
                val x = glyph.xDirAdj
                val sortsAt = if (x < previous && previous - x <= KERNING_OVERLAP_PT) previous + 0.01f else x
                out += Glyph(glyph, sortsAt)
                previous = sortsAt
            }
            return out
        }

        /**
         * The text of one marked-content run, taken from where its glyphs
         * sit on the page rather than from the order they were painted.
         *
         * Painting order cannot be trusted for right-to-left text, and not
         * in any single way: one Word-produced paper positions its short
         * runs word by word from right to left, so their content order is
         * already logical, and paints its long paragraphs as one block from
         * left to right, so theirs is visual — in the same document. Any
         * rule about content order is right for one and backwards for the
         * other, which is how an abstract came out with every word spelled
         * correctly and the sentence reversed while the bibliography beside
         * it read fine.
         *
         * Position does not have that problem. The glyphs are grouped into
         * lines by baseline and sorted left to right, which is visual order
         * whatever the producer did, and each line is then reconstructed
         * into logical order — the same treatment the untagged reader gives
         * every line.
         *
         * A whole structure element is read at once, not one run at a time.
         * The tree decides which runs belong to the element; the page decides
         * everything inside it. Reconstructing runs separately loses their
         * neighbours: a space at the edge of a Latin run in an Arabic line is
         * neutral, and which side of the run it belongs on is only knowable
         * with the Arabic beside it in view — alone, it stays put and ends up
         * doubled on one side of the word and missing on the other.
         */
        fun readOffThePage(glyphs: List<Pair<Int, Glyph>>): String = readStyled(glyphs).logical.text

        /**
         * [readOffThePage] with the look of every character beside it, so
         * the paragraph can be split into runs: the bold label at the head
         * of an abstract, the raised footnote mark after an author's name.
         * Lines are joined with a space that no glyph painted.
         */
        fun readStyled(glyphs: List<Pair<Int, Glyph>>): StyledText {
            if (glyphs.isEmpty()) return StyledText(ExtractedText.Logical("", emptyList()), emptyList())
            val text = StringBuilder()
            val looks = ArrayList<Look?>()
            val tabStops = sortedSetOf<Float>()
            for ((page, line) in linesByPage(glyphs)) {
                // Each line trimmed on its own: a line's last glyph is often
                // a space, and joined edge to edge it would double up.
                val logical = trimmed(lineText(page, line, tabStops))
                if (logical.text.isEmpty()) continue
                if (text.isNotEmpty()) {
                    text.append(' ')
                    looks += null
                }
                text.append(logical.text)
                looks += logical.painters
            }
            return StyledText(ExtractedText.Logical(text.toString(), looks), tabStops.toList())
        }

        /**
         * Pages in order, then lines top to bottom within each; a line never
         * spans a page break however close the baselines land.
         */
        fun linesOf(glyphs: List<Pair<Int, Glyph>>): List<List<Glyph>> =
            linesByPage(glyphs).map { it.second }

        private fun linesByPage(glyphs: List<Pair<Int, Glyph>>): List<Pair<Int, List<Glyph>>> {
            val lines = mutableListOf<Pair<Int, MutableList<Glyph>>>()
            for ((page, onPage) in glyphs.groupBy { it.first }.toSortedMap()) {
                var line: MutableList<Glyph>? = null
                var lineSize = 0f
                for (glyph in onPage.map { it.second }.sortedBy { it.position.yDirAdj }) {
                    val current = line
                    // A superscript sits a third of an em above its line's
                    // baseline; a fixed two points would make it a line of
                    // its own, read before the name it annotates. Reach is
                    // relative to type size — the larger of the line's and
                    // the glyph's, since top-down order meets the small
                    // raised glyph before the line it belongs to — and stays
                    // well short of a real line pitch.
                    val size = glyph.position.fontSizeInPt
                    val reach = maxOf(SAME_LINE_TOLERANCE_PT, SUPERSCRIPT_REACH * maxOf(lineSize, size))
                    if (current != null && abs(glyph.position.yDirAdj - current.first().position.yDirAdj) <= reach) {
                        current += glyph
                        lineSize = maxOf(lineSize, size)
                    } else {
                        line = mutableListOf(glyph).also { lines += page to it }
                        lineSize = size
                    }
                }
            }
            return lines
        }

        /**
         * One line, read left to right off the page and put back into
         * logical order, each character carrying the look of the glyph that
         * painted it. Not trimmed: the space between two words often
         * belongs to the edge of one run, and runs are joined edge to edge.
         * The paragraph is trimmed once, where it is emitted.
         */
        private fun lineText(page: Int, line: List<Glyph>, tabStops: MutableSet<Float>): ExtractedText.Logical<Look> {
            val visual = StringBuilder()
            val painters = ArrayList<Look?>()
            val ordered = line.sortedBy { it.x }
            val block = inkByPageIndex[page]
            val baseline = dominantBaseline(ordered)
            val lineSize = ordered.filter { abs(it.position.yDirAdj - baseline) <= SAME_LINE_TOLERANCE_PT }
                .maxOfOrNull { it.position.fontSizeInPt } ?: 0f
            var previous: TextPosition? = null
            // A producer that painted its spaces is trusted on where the
            // words are. Only one that painted none has its word breaks
            // read from the gaps, as PDFBox's own stripper does — a
            // kerning gap inside a word is otherwise easy to mistake for
            // one, and did split الجزائر in two.
            val inferBreaks = ordered.none { glyphText.of(it.position).let { u -> u.isNotEmpty() && u.isBlank() } }
            var spaces = 0
            for ((index, glyph) in ordered.withIndex()) {
                val position = glyph.position
                val unicode = ExtractedText.paintedForm(glyphText.of(position))
                if (unicode.isNotEmpty() && unicode.isBlank() && isSwallowed(ordered, index)) continue
                // A stretch of spaces wide enough to be a tab — the three
                // dates Word spread across a line with two — is one, and the
                // text after it is where a tab stop sits: measured from the
                // block's start edge to the edge nearest it, which for a
                // right-to-left line is its right edge.
                if (unicode.isNotEmpty() && unicode.isBlank()) {
                    spaces++
                } else {
                    if (spaces >= TAB_MIN_SPACES && visual.isNotBlank() && block != null && !block.isEmpty) {
                        repeat(spaces) { visual.setLength(visual.length - 1); painters.removeAt(painters.size - 1) }
                        visual.append('\t')
                        painters += null
                        // The text the tab leads to is the ink on its far
                        // side in reading order: to the right of the gap on
                        // a left-to-right line, to the left of it on a
                        // right-to-left one.
                        val stop = if (baseDirection == TextDirection.RTL) {
                            block.right - inkExtent(ordered.subList(0, index - spaces)).second
                        } else {
                            inkExtent(ordered.subList(index, ordered.size)).first - block.left
                        }
                        if (stop > 0f) tabStops += (stop * 2f).roundToInt() / 2f
                    }
                    spaces = 0
                }
                if (inferBreaks && previous != null && previous.widthDirAdj > 0f &&
                    unicode.isNotBlank() && !visual.endsWith(' ')
                ) {
                    val gap = position.xDirAdj - (previous.xDirAdj + previous.widthDirAdj)
                    if (gap > WORD_GAP_FACTOR * position.fontSizeInPt) {
                        visual.append(' ')
                        painters += null
                    }
                }
                val look = lookOf(position, raised(position, baseline, lineSize))
                visual.append(unicode)
                repeat(unicode.length) { painters += look }
                previous = position
            }
            return ExtractedText.toLogical(visual.toString(), painters, baseDirection)
        }

        /**
         * A painted space with no room on the page: Word's Arabic
         * justification leaves one inside a word — خطوات painted as خط, a
         * space, and وات with the و and the ط touching — and the page shows
         * one word, so the text holds one word. The space's own advance
         * does not count; only what is clear between the glyphs either
         * side of it.
         */
        private fun isSwallowed(ordered: List<Glyph>, index: Int): Boolean {
            val space = ordered[index].position
            val before = (index - 1 downTo 0).map { ordered[it].position }.firstOrNull { !it.unicode.isNullOrBlank() }
                ?: return false
            val after = (index + 1 until ordered.size).map { ordered[it].position }.firstOrNull { !it.unicode.isNullOrBlank() }
                ?: return false
            val clear = after.xDirAdj - (before.xDirAdj + before.widthDirAdj)
            val needed = if (space.widthDirAdj > 0f) VISIBLE_SPACE_SHARE * space.widthDirAdj
            else VISIBLE_SPACE_SHARE * WORD_GAP_FACTOR * space.fontSizeInPt
            return clear < needed
        }

        /** The baseline most of the line's glyphs sit on, to the half point. */
        private fun dominantBaseline(line: List<Glyph>): Float {
            val counts = HashMap<Int, Int>()
            for (glyph in line) {
                if (glyph.position.unicode.isNullOrBlank()) continue
                val bucket = (glyph.position.yDirAdj * 2f).toInt()
                counts[bucket] = (counts[bucket] ?: 0) + 1
            }
            val bucket = counts.maxByOrNull { it.value }?.key ?: return line.first().position.yDirAdj
            return line.filter { (it.position.yDirAdj * 2f).toInt() == bucket }.maxOf { it.position.yDirAdj }
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

        private fun lookOf(position: TextPosition, raised: Int): Look = Look(
            bold = isBold(position),
            italic = isItalic(position),
            family = position.font?.name?.let(::familyName),
            sizePt = position.fontSizeInPt,
            raised = raised,
        )

        /**
         * How the element sits on its page, measured against the page's
         * text block rather than the sheet — a journal's margins are not
         * symmetric, and a line flush to the right margin with a first-line
         * indent is not a centred line however close to the middle its
         * midpoint lands.
         *
         * Centred when every line's middle is the block's middle and none
         * touches a margin; justified when a paragraph of several lines has
         * every line but the last flush to the same two edges; set against
         * the far margin when a single line starts too far in to be indented
         * and ends on that margin. Indents are read off the start edge: the
         * first line's own, the rest's, and — for a bibliography entry —
         * the rest hanging in past a first line on the margin.
         */
        fun placementOf(glyphs: List<Pair<Int, Glyph>>, direction: TextDirection?): Placement? {
            val lines = linesByPage(glyphs).filter { line -> line.second.any { !it.position.unicode.isNullOrBlank() } }
            if (lines.isEmpty()) return null
            val firstPage = lines.first().first
            val block = inkByPageIndex[firstPage]?.takeIf { !it.isEmpty } ?: return null
            val pageWidth = pageWidthByIndex[firstPage]?.takeIf { it > 0f } ?: (block.right - block.left)
            val extents = lines.map { (_, line) -> inkExtent(line) }
            val baselines = lines.map { (_, line) -> dominantBaseline(line.sortedBy { it.x }) }
            val rtl = direction == TextDirection.RTL
            val blockWidth = block.right - block.left
            val blockCentre = (block.left + block.right) / 2
            fun startGap(extent: Pair<Float, Float>) = if (rtl) block.right - extent.second else extent.first - block.left
            fun endGap(extent: Pair<Float, Float>) = if (rtl) extent.first - block.left else block.right - extent.second

            var alignment: Alignment? = null
            var firstLine: Float? = null
            var start: Float? = null
            var hanging: Float? = null
            val centred = extents.all { (left, right) ->
                abs((left + right) / 2 - blockCentre) <= CENTRE_TOLERANCE * pageWidth &&
                    right - left < CENTRED_MAX_SHARE * blockWidth &&
                    startGap(left to right) > FLUSH_TOLERANCE_PT && endGap(left to right) > FLUSH_TOLERANCE_PT
            }
            if (centred) {
                alignment = Alignment.CENTER
            } else {
                if (extents.size >= 3) {
                    // Every full line ends on the end margin, and every full
                    // line after the first — which may carry an indent —
                    // starts on the start margin.
                    val full = extents.dropLast(1)
                    val ends = full.map(::endGap)
                    val starts = full.drop(1).map(::startGap)
                    val flush = ends.max() - ends.min() <= FLUSH_TOLERANCE_PT &&
                        starts.max() - starts.min() <= FLUSH_TOLERANCE_PT
                    if (flush) alignment = Alignment.JUSTIFY
                }
                val gaps = extents.map(::startGap)
                val first = gaps.first()
                val deepest = INDENT_MAX_SHARE * blockWidth
                if (gaps.size == 1) {
                    if (first > deepest && endGap(extents.single()) <= FLUSH_TOLERANCE_PT) alignment = Alignment.END
                    else if (first in INDENT_MIN_PT..deepest) firstLine = first
                } else {
                    val rest = HeadingSizes.median(gaps.drop(1))
                    val restIndent = if (rest in INDENT_MIN_PT..deepest) rest else 0f
                    if (restIndent > 0f) start = restIndent
                    val extra = first - restIndent
                    if (extra >= INDENT_MIN_PT && first <= deepest) firstLine = extra
                    else if (extra <= -INDENT_MIN_PT && restIndent > 0f) hanging = -extra
                }
            }
            val pitches = lines.indices.drop(1)
                .filter { lines[it].first == lines[it - 1].first }
                .map { baselines[it] - baselines[it - 1] }
                .filter { it > 0f }
            return Placement(
                firstPage = firstPage,
                lastPage = lines.last().first,
                alignment = alignment,
                firstLineIndentPt = firstLine,
                startIndentPt = start,
                hangingIndentPt = hanging,
                firstBaseline = baselines.first(),
                lastBaseline = baselines.last(),
                pitchPt = pitches.takeIf { it.isNotEmpty() }?.let { HeadingSizes.median(it) },
            )
        }

        /** Left and right edge of a line's ink; spaces do not count. */
        private fun inkExtent(line: List<Glyph>): Pair<Float, Float> {
            val ink = line.filter { !it.position.unicode.isNullOrBlank() }.ifEmpty { line }
            return ink.minOf { it.position.xDirAdj } to ink.maxOf { it.position.xDirAdj + it.position.widthDirAdj }
        }

        /**
         * The page the document was set on: the first page's sheet, with
         * margins where its tagged text reaches nearest each edge across
         * all pages. Running headers and page numbers are artifacts, not
         * structure, so they do not pull the margins out.
         */
        fun pageSetup(): PageSetup? {
            val width = pageWidthByIndex[0]?.takeIf { it > 0f } ?: return null
            val height = pageHeightByIndex[0]?.takeIf { it > 0f } ?: return null
            val boxes = inkByPageIndex.values.filter { !it.isEmpty }
            if (boxes.isEmpty()) return null
            fun margin(value: Float) = value.coerceIn(0f, minOf(width, height) / 3)
            return PageSetup(
                widthPt = width,
                heightPt = height,
                marginTopPt = margin(boxes.minOf { it.top }),
                marginBottomPt = margin(height - boxes.maxOf { it.bottom }),
                marginLeftPt = margin(boxes.minOf { it.left }),
                marginRightPt = margin(width - boxes.maxOf { it.right }),
            )
        }

        fun textFor(page: PDPage?, mcid: Int): String {
            val pageIndex = page?.cosObject?.let(pageIndexByPage::get) ?: return ""
            return textByPageAndMcid.getOrPut(key(pageIndex, mcid)) {
                readOffThePage(glyphsFor(page, mcid))
            }
        }

        /** The glyphs painted under [mcid], each tagged with its page index. */
        fun glyphsFor(page: PDPage?, mcid: Int): List<Pair<Int, Glyph>> {
            val pageIndex = page?.cosObject?.let(pageIndexByPage::get) ?: return emptyList()
            return glyphsByPageAndMcid[key(pageIndex, mcid)]?.map { pageIndex to it }.orEmpty()
        }

        /** Largest type size drawn under [mcid], or 0 when it drew no text. */
        fun sizeFor(page: PDPage?, mcid: Int): Float {
            val pageIndex = page?.cosObject?.let(pageIndexByPage::get) ?: return 0f
            return sizeByPageAndMcid[key(pageIndex, mcid)] ?: 0f
        }

        /** True when every visible glyph under [mcid] was drawn in a bold face. */
        fun boldFor(page: PDPage?, mcid: Int): Boolean {
            val pageIndex = page?.cosObject?.let(pageIndexByPage::get) ?: return false
            return boldByPageAndMcid[key(pageIndex, mcid)] ?: false
        }

        /**
         * Whether [position] was drawn in a bold face. PDFs carry no weight
         * of their own, so this reads the embedded font's name — the same
         * evidence a reader has, and what the producer wrote there when the
         * author pressed bold. Subset prefixes ("ABCDEE+") do not interfere.
         */
        private fun isBold(position: TextPosition): Boolean {
            val name = position.font?.name ?: return false
            return name.contains("Bold", ignoreCase = true)
        }

        /** Whether [position] was drawn in an italic face, by the same evidence as [isBold]. */
        private fun isItalic(position: TextPosition): Boolean {
            val name = position.font?.name ?: return false
            return name.contains("Italic", ignoreCase = true) || name.contains("Oblique", ignoreCase = true)
        }

        private fun hasLetter(text: String?): Boolean =
            text != null && text.any { Character.isLetter(it) }

        /**
         * "ABCDEE+Simplified Arabic,Bold" → "Simplified Arabic": the subset
         * tag and the style suffix are the PDF's, not the typeface's.
         */
        private fun familyName(fontName: String): String? =
            fontName.substringAfter('+', fontName).substringBefore(',').trim().ifEmpty { null }

        /** 1-based page number of a structure element's page, if known. */
        fun pageNumberOf(page: PDPage?): Int? =
            page?.cosObject?.let(pageIndexByPage::get)?.plus(1)

        private fun key(pageIndex: Int, mcid: Int): Long =
            pageIndex.toLong() shl 32 or (mcid.toLong() and 0xFFFFFFFFL)
    }

    private class Builder(
        private val texts: MarkedContentIndex,
        private val roleMap: Map<String, Any>,
        private val images: List<PdfImage>,
    ) {
        val blocks = mutableListOf<Block>()
        private var sawText = false
        /** Type size of each paragraph block, by its index in [blocks]. */
        private val sizeByBlockIndex = HashMap<Int, Float>()
        /** Whether each paragraph block was set wholly in bold. */
        private val boldByBlockIndex = HashMap<Int, Boolean>()
        /** Where each paragraph block sits on its page, when it could be measured. */
        private val placementByBlockIndex = HashMap<Int, Placement>()
        /** The face most of each paragraph block is set in. */
        private val familyByBlockIndex = HashMap<Int, String?>()
        private val imageByPageAndMcid = HashMap<Long, PdfImage>().apply {
            for (image in images) {
                if (image.mcid >= 0) putIfAbsent(imageKey(image.page, image.mcid), image)
            }
        }
        private val usedImages =
            Collections.newSetFromMap(IdentityHashMap<PdfImage, Boolean>())

        fun result(): DocumentModel? {
            // A tree that yielded nothing (an empty shell) must not claim
            // the document, images or not: the position heuristics see text
            // and images alike, so falling back can only gain information.
            if (!sawText) return null
            // Images the structure tree never referenced (drawn outside any
            // Figure) still belong to the document — appended at the end,
            // since the tagged path has no geometry to interleave them by.
            val leftovers = images.filter { it !in usedImages }
                .sortedWith(compareBy({ it.page }, { it.topY }))
            for (image in leftovers) {
                blocks += ImageBlock(
                    bytes = image.bytes,
                    mimeType = image.mimeType,
                    widthPx = image.widthPx,
                    heightPx = image.heightPx,
                    confidence = CONFIDENCE,
                )
            }
            if (blocks.none { it is Paragraph && it.style.kind != ParagraphKind.BODY }) {
                rankHeadingsBySize()
            }
            applySpacing()
            val paragraphs = blocks.filterIsInstance<Paragraph>()
            val rtl = paragraphs.count { it.style.direction == TextDirection.RTL }
            val defaultDirection =
                if (rtl > paragraphs.size - rtl) TextDirection.RTL else TextDirection.LTR
            // Full UAX #9 pass: split mixed-direction runs so writers can
            // mark direction per run instead of per paragraph.
            return Bidi.refine(
                DocumentModel(
                    blocks = blocks.toList(),
                    defaultDirection = defaultDirection,
                    pageSetup = texts.pageSetup(),
                )
            )
        }

        /**
         * Gives every paragraph the spacing the page shows: the distance
         * between its own baselines as its line pitch, and the room left
         * below it — the drop from its last baseline to the next
         * paragraph's first, less that paragraph's pitch — as its space
         * after. A single line has no pitch of its own and takes its face's,
         * measured on the document's longer paragraphs in that face, so a
         * Times abstract does not inherit the looser pitch of the Arabic
         * body around it. Nothing is measured across a page break, and a
         * page's worth of gap is not a paragraph's spacing.
         */
        private fun applySpacing() {
            val ratiosByFamily = HashMap<String?, MutableList<Float>>()
            for ((index, placement) in placementByBlockIndex) {
                val pitch = placement.pitchPt ?: continue
                val size = sizeByBlockIndex[index]?.takeIf { it > 0f } ?: continue
                ratiosByFamily.getOrPut(familyByBlockIndex[index]) { mutableListOf() } += pitch / size
            }
            val ratioByFamily = ratiosByFamily.mapValues { HeadingSizes.median(it.value) }
            fun pitchOf(index: Int): Float? {
                val placement = placementByBlockIndex[index] ?: return null
                placement.pitchPt?.let { return it }
                val size = sizeByBlockIndex[index]?.takeIf { it > 0f } ?: return null
                // A face no paragraph could measure gets the generic share
                // rather than the document's: an Arabic body face sits
                // half again as tall on its line as a Latin heading face,
                // and a pitch written as a minimum only ever adds space.
                return (ratioByFamily[familyByBlockIndex[index]] ?: DEFAULT_PITCH_SHARE) * size
            }
            for (index in blocks.indices) {
                val paragraph = blocks[index] as? Paragraph ?: continue
                val placement = placementByBlockIndex[index] ?: continue
                val pitch = pitchOf(index)
                val next = placementByBlockIndex[index + 1]
                val after = if (next != null && next.firstPage == placement.lastPage) {
                    pitchOf(index + 1)?.let { (next.firstBaseline - placement.lastBaseline - it).coerceIn(0f, SPACE_AFTER_MAX_PT) }
                } else {
                    null
                }
                blocks[index] = paragraph.copy(
                    style = paragraph.style.copy(
                        spaceBeforePt = 0f,
                        spaceAfterPt = after ?: 0f,
                        linePitchPt = pitch,
                    )
                )
            }
        }

        fun walk(element: PDStructureElement, depth: Int) {
            if (depth > MAX_DEPTH) throw TooDeepException()
            when (val type = resolvedType(element)) {
                "Document", "Part", "Sect", "Div", "Art", "Aside",
                "TOC", "TOCI", "BlockQuote", "Index", "NonStruct" ->
                    walkChildren(element, depth)

                "P", "Caption", "Note" -> emitParagraph(element, ParagraphKind.BODY, null)

                "H", "H1" -> emitParagraph(element, ParagraphKind.HEADING_1, null)
                "H2" -> emitParagraph(element, ParagraphKind.HEADING_2, null)
                "H3", "H4", "H5", "H6" -> emitParagraph(element, ParagraphKind.HEADING_3, null)

                "L" -> emitList(element, depth)
                "LI" -> emitListItem(element, marker = ListMarker.BULLET)

                "Table" -> emitTable(element, depth)

                "Figure" -> emitFigure(element)

                else -> {
                    // Unknown grouping types recurse; unknown leaves keep text.
                    if (childElements(element).isNotEmpty()) {
                        walkChildren(element, depth)
                    } else {
                        emitParagraph(element, ParagraphKind.BODY, null)
                    }
                }
            }
        }

        private fun walkChildren(element: PDStructureElement, depth: Int) {
            for (child in childElements(element)) walk(child, depth + 1)
        }

        private fun emitParagraph(
            element: PDStructureElement,
            kind: ParagraphKind,
            marker: ListMarker?,
        ) {
            val glyphs = glyphsOf(element)
            val read = texts.readStyled(glyphs)
            val styled = trimmed(read.logical)
            val text = styled.text
            if (text.isEmpty()) return
            sawText = true
            val direction = Bidi.firstStrongDirection(text)
            val size = sizeOf(element)
            sizeByBlockIndex[blocks.size] = size
            boldByBlockIndex[blocks.size] = boldOf(element)
            // What the page shows, carried across: the face, size and
            // weight of every run, and where the element sits. A heading's
            // kind comes from the tags or the size pass; its look from here.
            val runs = runsOf(styled)
            familyByBlockIndex[blocks.size] = runs.maxByOrNull { it.text.length }?.fontFamily
            val placement = texts.placementOf(glyphs, direction)
            if (placement != null) placementByBlockIndex[blocks.size] = placement
            blocks += Paragraph(
                runs = runs,
                style = ParagraphStyle(
                    kind = kind,
                    direction = direction,
                    listMarker = marker,
                    alignment = placement?.alignment,
                    firstLineIndentPt = placement?.firstLineIndentPt,
                    startIndentPt = placement?.startIndentPt,
                    hangingIndentPt = placement?.hangingIndentPt,
                    tabStopsPt = read.tabStopsPt.takeIf { it.isNotEmpty() && '\t' in text },
                ),
                confidence = CONFIDENCE,
            )
        }

        /**
         * The paragraph's runs: one per stretch of characters that share a
         * look. A space no glyph painted — between two lines — belongs to
         * the run before it.
         */
        private fun runsOf(styled: ExtractedText.Logical<Look>): List<TextRun> {
            val runs = mutableListOf<TextRun>()
            val text = StringBuilder()
            var current: Look? = null
            fun flush() {
                if (text.isEmpty()) return
                val look = current
                runs += TextRun(
                    text = text.toString(),
                    bold = look?.bold ?: false,
                    italic = look?.italic ?: false,
                    fontFamily = look?.family,
                    fontSizePt = look?.sizePt?.takeIf { it > 0f },
                    superscript = look?.raised == 1,
                    subscript = look?.raised == -1,
                )
                text.setLength(0)
            }
            for ((index, c) in styled.text.withIndex()) {
                val look = styled.painters[index]
                if (look != null && current != null && look != current) flush()
                if (look != null) current = look
                text.append(c)
            }
            flush()
            return runs
        }

        /** [styled] without [prefix] at its head, when it starts with it. */
        private fun withoutPrefix(styled: ExtractedText.Logical<Look>, prefix: String): ExtractedText.Logical<Look> {
            if (prefix.isEmpty() || !styled.text.startsWith(prefix)) return styled
            return ExtractedText.Logical(
                styled.text.substring(prefix.length),
                styled.painters.subList(prefix.length, styled.painters.size),
            )
        }

        /** A Figure resolves to its image through the marked-content ids. */
        private fun emitFigure(element: PDStructureElement) {
            val image = figureImage(element) ?: return
            usedImages += image
            sawText = true
            blocks += ImageBlock(
                bytes = image.bytes,
                mimeType = image.mimeType,
                widthPx = image.widthPx,
                heightPx = image.heightPx,
                confidence = CONFIDENCE,
            )
        }

        private fun figureImage(element: PDStructureElement): PdfImage? {
            val ids = mutableListOf<Pair<PDPage?, Int>>()
            fun gather(node: PDStructureElement, depth: Int) {
                if (depth > MAX_DEPTH) throw TooDeepException()
                for (kid in node.kids.orEmpty()) {
                    when (kid) {
                        is PDStructureElement -> gather(kid, depth + 1)
                        is Int -> ids += node.page to kid
                        is COSInteger -> ids += node.page to kid.intValue()
                        is PDMarkedContentReference -> ids += (kid.page ?: node.page) to kid.mcid
                        is PDMarkedContent -> ids += node.page to kid.mcid
                    }
                }
            }
            gather(element, 0)
            for ((page, mcid) in ids) {
                val pageNumber = texts.pageNumberOf(page) ?: continue
                imageByPageAndMcid[imageKey(pageNumber, mcid)]?.let { return it }
            }
            return null
        }

        private fun emitList(list: PDStructureElement, depth: Int) {
            if (depth > MAX_DEPTH) throw TooDeepException()
            val items = childElements(list).filter { resolvedType(it) == "LI" }
            if (items.isEmpty()) {
                walkChildren(list, depth)
                return
            }
            // Numbered when the item labels carry digits ("1.", "١."), else bullets.
            val labels = items.mapNotNull { item ->
                childElements(item).firstOrNull { resolvedType(it) == "Lbl" }?.let(::textOf)
            }
            val marker =
                if (labels.isNotEmpty() && labels.all { label -> label.any(Character::isDigit) }) {
                    ListMarker.NUMBERED
                } else {
                    ListMarker.BULLET
                }
            for (item in items) emitListItem(item, marker)
        }

        private fun emitListItem(item: PDStructureElement, marker: ListMarker) {
            val body = childElements(item).firstOrNull { resolvedType(it) == "LBody" }
            val styled = trimmed(
                body?.let { texts.readStyled(glyphsOf(it)).logical } ?: run {
                    // No LBody: take the item's text minus its label.
                    val label = childElements(item)
                        .firstOrNull { resolvedType(it) == "Lbl" }?.let(::textOf).orEmpty()
                    withoutPrefix(texts.readStyled(glyphsOf(item)).logical, label)
                }
            )
            val text = styled.text
            if (text.isEmpty()) return
            sawText = true
            val direction = Bidi.firstStrongDirection(text)
            blocks += Paragraph(
                runs = runsOf(styled),
                style = ParagraphStyle(direction = direction, listMarker = marker),
                confidence = CONFIDENCE,
            )
        }

        private fun emitTable(table: PDStructureElement, depth: Int) {
            if (depth > MAX_DEPTH) throw TooDeepException()
            val rows = childElements(table)
                .filter { resolvedType(it) == "TR" }
                .map { row ->
                    TableRow(
                        childElements(row)
                            .filter { resolvedType(it) in setOf("TD", "TH") }
                            .map { cell ->
                                val styled = trimmed(texts.readStyled(glyphsOf(cell)).logical)
                                val text = styled.text
                                if (text.isNotEmpty()) sawText = true
                                val direction = Bidi.firstStrongDirection(text)
                                TableCell(
                                    listOf(
                                        Paragraph(
                                            runs = runsOf(styled).ifEmpty { listOf(TextRun("")) },
                                            style = ParagraphStyle(direction = direction),
                                            confidence = CONFIDENCE,
                                        )
                                    )
                                )
                            }
                    )
                }
                .filter { it.cells.isNotEmpty() }
            if (rows.isEmpty()) {
                walkChildren(table, depth)
                return
            }
            blocks += Table(rows = rows, confidence = CONFIDENCE)
        }

        /** All text under an element, in tag (logical) order. */
        private fun textOf(element: PDStructureElement): String =
            texts.readOffThePage(glyphsOf(element))

        /** Every glyph painted under [element], tagged with its page, in tree order. */
        private fun glyphsOf(element: PDStructureElement): List<Pair<Int, Glyph>> {
            val glyphs = mutableListOf<Pair<Int, Glyph>>()
            fun gather(node: PDStructureElement, depth: Int) {
                if (depth > MAX_DEPTH) throw TooDeepException()
                for (kid in node.kids.orEmpty()) {
                    when (kid) {
                        is PDStructureElement -> gather(kid, depth + 1)
                        is Int -> glyphs += texts.glyphsFor(node.page, kid)
                        is COSInteger -> glyphs += texts.glyphsFor(node.page, kid.intValue())
                        is PDMarkedContentReference ->
                            glyphs += texts.glyphsFor(kid.page ?: node.page, kid.mcid)
                        is PDMarkedContent -> glyphs += texts.glyphsFor(node.page, kid.mcid)
                    }
                }
            }
            gather(element, 0)
            return glyphs
        }

        /** True when every marked-content run under [element] is bold. */
        private fun boldOf(element: PDStructureElement): Boolean {
            var sawRun = false
            var bold = true
            fun gather(node: PDStructureElement, depth: Int) {
                if (depth > MAX_DEPTH) throw TooDeepException()
                fun mark(page: PDPage?, mcid: Int) {
                    if (texts.textFor(page, mcid).isBlank()) return
                    sawRun = true
                    if (!texts.boldFor(page, mcid)) bold = false
                }
                for (kid in node.kids.orEmpty()) {
                    when (kid) {
                        is PDStructureElement -> gather(kid, depth + 1)
                        is Int -> mark(node.page, kid)
                        is COSInteger -> mark(node.page, kid.intValue())
                        is PDMarkedContentReference -> mark(kid.page ?: node.page, kid.mcid)
                        is PDMarkedContent -> mark(node.page, kid.mcid)
                    }
                }
            }
            gather(element, 0)
            return sawRun && bold
        }

        /** Largest type size drawn anywhere under [element]. */
        private fun sizeOf(element: PDStructureElement): Float {
            var size = 0f
            fun gather(node: PDStructureElement, depth: Int) {
                if (depth > MAX_DEPTH) throw TooDeepException()
                for (kid in node.kids.orEmpty()) {
                    when (kid) {
                        is PDStructureElement -> gather(kid, depth + 1)
                        is Int -> size = maxOf(size, texts.sizeFor(node.page, kid))
                        is COSInteger -> size = maxOf(size, texts.sizeFor(node.page, kid.intValue()))
                        is PDMarkedContentReference ->
                            size = maxOf(size, texts.sizeFor(kid.page ?: node.page, kid.mcid))
                        is PDMarkedContent -> size = maxOf(size, texts.sizeFor(node.page, kid.mcid))
                    }
                }
            }
            gather(element, 0)
            return size
        }

        /**
         * Ranks paragraphs onto heading levels by type size, for a structure
         * tree that tagged no headings at all.
         *
         * Word tags a heading as H1 only when the author used a heading
         * style. Plenty of real documents — an academic paper whose headings
         * were made by hand, with bold and a larger size — carry none, and
         * arrive as a flat run of P elements. The tags are then silent rather
         * than authoritative, and size is the only evidence left, so it is
         * read the same way an untagged file's would be.
         *
         * Applied only when the tree named no heading of its own: a document
         * that does tag headings has said what it means, and a large first
         * paragraph there is a large paragraph, not an unmarked title.
         */
        private fun rankHeadingsBySize() {
            val sizes = blocks.indices.mapNotNull { sizeByBlockIndex[it] }.filter { it > 0f }
            if (sizes.isEmpty()) return
            val bodySize = HeadingSizes.median(sizes)
            val candidates = blocks.indices.filter { index ->
                val paragraph = blocks[index] as? Paragraph ?: return@filter false
                val size = sizeByBlockIndex[index] ?: return@filter false
                HeadingSizes.isCandidate(size, paragraph.text.length, bodySize)
            }
            if (candidates.isEmpty()) return
            val kindBySize = HeadingSizes.rank(candidates.mapNotNull { sizeByBlockIndex[it] })
            for (index in candidates) {
                val paragraph = blocks[index] as? Paragraph ?: continue
                val size = sizeByBlockIndex[index] ?: continue
                val kind = kindBySize[HeadingSizes.sizeKey(size)] ?: continue
                blocks[index] = paragraph.copy(style = paragraph.style.copy(kind = kind))
            }
            rankBoldHeadings(kindBySize)
        }

        /**
         * Promotes short, wholly bold paragraphs that type size could not
         * reach. A heading set in bold at the body's own size is invisible to
         * a size comparison, and that is how most hand-formatted section
         * headings are made.
         */
        private fun rankBoldHeadings(sizeRanked: Map<Int, ParagraphKind>) {
            val paragraphIndices = blocks.indices.filter { blocks[it] is Paragraph }
            val boldIndices = paragraphIndices.filter { boldByBlockIndex[it] == true }
            if (!HeadingSizes.boldIsMeaningful(boldIndices.size, paragraphIndices.size)) return
            val level = HeadingSizes.boldLevel(sizeRanked)
            for (index in boldIndices) {
                val paragraph = blocks[index] as? Paragraph ?: continue
                if (paragraph.style.kind != ParagraphKind.BODY) continue
                if (paragraph.text.length > HeadingSizes.MAX_CHARS) continue
                blocks[index] = paragraph.copy(style = paragraph.style.copy(kind = level))
            }
        }

        private fun childElements(element: PDStructureElement): List<PDStructureElement> =
            element.kids.orEmpty().filterIsInstance<PDStructureElement>()

        private fun resolvedType(element: PDStructureElement): String {
            val type = element.structureType ?: return ""
            return (roleMap[type] as? String) ?: type
        }
    }
}
