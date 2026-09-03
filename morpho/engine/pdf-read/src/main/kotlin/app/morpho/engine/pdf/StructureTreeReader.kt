package app.morpho.engine.pdf

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
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import app.morpho.engine.layout.pdf.HeadingSizes
import app.morpho.engine.layout.pdf.PdfImage
import app.morpho.engine.layout.pdf.PdfLook
import app.morpho.engine.layout.pdf.PdfRuns
import org.apache.pdfbox.contentstream.operator.DrawObject
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.OperatorProcessor
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDTableAttributeObject
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFMarkedContentExtractor
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.util.Matrix
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.IdentityHashMap

/**
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

/** An element's text in logical order with its looks, and the tab stops its lines were set to. */
private class StyledText(val logical: ExtractedText.Logical<PdfLook>, val tabStopsPt: List<Float>)

/** [styled] without the blank characters at either end, painters kept in step. */
private fun trimmed(styled: ExtractedText.Logical<PdfLook>): ExtractedText.Logical<PdfLook> {
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

/** A rule drawn across a page: a stroked horizontal line or a filled sliver, in top-down page points. */
private class Rule(val y: Float, val left: Float, val right: Float)

/**
 * What a page repeats in its margin — a running head, a footer with the
 * page number — as the producer marked it: a pagination artifact, with the
 * glyphs it drew and the boxes of the rules and pictures it drew, in
 * top-down page points.
 */
private class Furniture(val page: Int, val atTop: Boolean, val glyphs: List<TextPosition>, val boxes: List<FloatArray>)

/** The running header and footer a document was read with, ready for a writer. */
private class Furnishings(
    val header: List<Block>,
    val footer: List<Block>,
    val headerDistancePt: Float?,
    val footerDistancePt: Float?,
    val firstPageNumber: Int,
) {
    companion object {
        val NONE = Furnishings(emptyList(), emptyList(), null, null, 1)
    }
}

/** A run of digits drawn in a margin, with its value and where it sits. */
private class NumberToken(val value: Int, val box: FloatArray, val positions: List<TextPosition>)

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

internal object StructureTreeReader {

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
    /** Room kept around a crop of the page's furniture, so no stroke is cut. */
    private const val FURNITURE_PAD_PT = 1.5f
    /** Clear space kept between a page number and the picture of the rest of its line. */
    private const val FURNITURE_GAP_PT = 4f
    /** A page number sits within this of the same place on every page. */
    private const val PAGE_NUMBER_DRIFT_PT = 12f
    /** A gap wider than this share of the type size splits a run of digits into two. */
    private const val TOKEN_GAP_SHARE = 0.6f
    /** However many a cell claims to cover, no more than this: a broken file will not build a table of millions. */
    private const val MOST_SPANNED_CELLS = 256
    /** A page whose text stopped this many lines short of where it could have run was broken on purpose. */
    private const val EARLY_BREAK_LINES = 2f
    /** How far outside a table's text a rule still counts as the table's. */
    private const val TABLE_RULE_REACH_PT = 8f
    /** A rule must cross this share of a table's width to be one of its own. */
    private const val TABLE_RULE_SHARE = 0.6f
    /** Rules across a table before it counts as one the page ruled. */
    private const val TABLE_RULES_TO_BE_RULED = 2
    /** The characters a producer draws as a list marker. */
    private const val MARKER_CHARACTERS = "\u2022\u00B7\u2219\u2212\u2013\u2014-*\u25AA\u25AB\u25CF\u25CB\u25E6\u2023\u2043\u00BB\u203A"
    /** What an enumerator ends with: "3.", "أ-", "a)". */
    private const val LABEL_TERMINATORS = "-.)\u2013\u061B:"
    /** An enumerator is this many characters at most, its terminator included. */
    private const val LONGEST_LABEL = 3
    /** A filled rectangle no taller than this is a rule, not a box. */
    private const val RULE_MAX_THICKNESS_PT = 4f
    /** A rule shorter than this is a dash or a tick, not a rule. */
    private const val RULE_MIN_LENGTH_PT = 20f
    /** A rule this many type sizes below a paragraph's last baseline, or above its first, belongs to it. */
    private const val RULE_REACH = 1.6f
    /** A rule nearer than this share of the type size to a baseline is that line's own underscoring. */
    private const val RULE_CLEARANCE = 0.4f
    /** The type size assumed of a paragraph that measured none. */
    private const val DEFAULT_SIZE_PT = 12f
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
    private class ResolvingMarkedContentExtractor(
        private val page: PDPage,
        private val pageLinks: PageLinks.Page? = null,
        private val pageHighlights: PageHighlights.Page? = null,
    ) : PDFMarkedContentExtractor() {
        /** The rules drawn on the page outside any artifact — a running header's own do not count. */
        val rules = mutableListOf<Rule>()
        /** The colour each glyph was painted in, where it was not the plain black a page paints with. */
        val colors = IdentityHashMap<TextPosition, Int>()
        /** Where each glyph points, for the few a link annotation covers. */
        val links = IdentityHashMap<TextPosition, String>()
        /** The colour marked over each glyph a highlight annotation covers. */
        val highlights = IdentityHashMap<TextPosition, Int>()
        /**
         * What each top-level artifact drew besides text — rules and
         * pictures, as boxes — by the order the artifact was opened in,
         * which is the order the marked contents are listed in afterwards;
         * and whether the producer said it was pagination, and where.
         */
        val artifactBoxes = HashMap<Int, MutableList<FloatArray>>()
        val artifactPlaces = HashMap<Int, String?>()
        private var artifactsOpened = 0
        private var currentArtifact: Int? = null
        private val openTags = ArrayDeque<String>()
        private var subpathStart: FloatArray? = null
        private var current: FloatArray? = null
        private val pendingSegments = mutableListOf<Rule>()
        private val pendingSlivers = mutableListOf<Rule>()

        init {
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
                    openArtifact(tag, properties)
                    openTags.addLast(tag.name)
                    context.beginMarkedContentSequence(tag, properties)
                }
            })
            addOperator(object : OperatorProcessor() {
                override fun getName() = "BMC"

                override fun process(operator: Operator, operands: List<COSBase>) {
                    val tag = operands.firstOrNull() as? COSName ?: return
                    openArtifact(tag, null)
                    openTags.addLast(tag.name)
                    context.beginMarkedContentSequence(tag, null)
                }
            })
            addOperator(object : OperatorProcessor() {
                override fun getName() = "EMC"

                override fun process(operator: Operator, operands: List<COSBase>) {
                    openTags.removeLastOrNull()
                    if (openTags.isEmpty()) currentArtifact = null
                    context.endMarkedContentSequence()
                }
            })
            // A picture drawn inside an artifact is part of the furniture;
            // the stock operator still runs, so text inside a form is read.
            addOperator(object : DrawObject() {
                override fun process(operator: Operator, operands: List<COSBase>) {
                    val artifact = currentArtifact
                    if (artifact != null) {
                        val name = operands.firstOrNull() as? COSName
                        val xobject = name?.let { runCatching { context.resources?.getXObject(it) }.getOrNull() }
                        val corners = when (xobject) {
                            is PDImageXObject -> listOf(transform(0f, 0f), transform(1f, 0f), transform(0f, 1f), transform(1f, 1f))
                            is PDFormXObject -> xobject.bBox?.let { box ->
                                listOf(
                                    transform(box.lowerLeftX, box.lowerLeftY), transform(box.upperRightX, box.lowerLeftY),
                                    transform(box.lowerLeftX, box.upperRightY), transform(box.upperRightX, box.upperRightY),
                                )
                            }
                            else -> null
                        }
                        if (corners != null) {
                            artifactBoxes.getOrPut(artifact) { mutableListOf() } += floatArrayOf(
                                corners.minOf { it[0] }, corners.minOf { it[1] }, corners.maxOf { it[0] }, corners.maxOf { it[1] },
                            )
                        }
                    }
                    super.process(operator, operands)
                }
            })
            // Path construction: enough of it to see a horizontal line or a
            // thin rectangle. Curves and anything else end the subpath.
            addOperator(pathOperator("m") { operands ->
                val point = point(operands, 0) ?: return@pathOperator
                subpathStart = point
                current = point
            })
            addOperator(pathOperator("l") { operands ->
                val from = current
                val to = point(operands, 0) ?: return@pathOperator
                if (from != null) segment(from, to)
                current = to
            })
            addOperator(pathOperator("h") { _ ->
                val from = current
                val to = subpathStart
                if (from != null && to != null) segment(from, to)
                current = to
            })
            addOperator(pathOperator("re") { operands ->
                if (operands.size < 4) return@pathOperator
                val x = number(operands[0]) ?: return@pathOperator
                val y = number(operands[1]) ?: return@pathOperator
                val w = number(operands[2]) ?: return@pathOperator
                val h = number(operands[3]) ?: return@pathOperator
                val a = transform(x, y)
                val b = transform(x + w, y + h)
                val top = minOf(a[1], b[1])
                val bottom = maxOf(a[1], b[1])
                if (bottom - top <= RULE_MAX_THICKNESS_PT) {
                    pendingSlivers += Rule((top + bottom) / 2, minOf(a[0], b[0]), maxOf(a[0], b[0]))
                }
                current = null
                subpathStart = null
            })
            for (name in listOf("S", "s", "B", "B*", "b", "b*")) {
                addOperator(pathOperator(name) { _ -> paint(strokes = true, fills = name != "S" && name != "s") })
            }
            for (name in listOf("f", "F", "f*")) {
                addOperator(pathOperator(name) { _ -> paint(strokes = false, fills = true) })
            }
            addOperator(pathOperator("n") { _ -> paint(strokes = false, fills = false) })
        }

        override fun processTextPosition(text: TextPosition) {
            PaintColor.of(graphicsState)?.let { colors[text] = it }
            pageLinks?.at(text.xDirAdj + text.widthDirAdj / 2, text.yDirAdj - text.heightDir / 2)
                ?.let { links[text] = it }
            pageHighlights?.at(text.xDirAdj + text.widthDirAdj / 2, text.yDirAdj - text.heightDir / 2)
                ?.let { highlights[text] = it }
            super.processTextPosition(text)
        }

        /** A top-level artifact opens: remember what kind the producer said it was, if any. */
        private fun openArtifact(tag: COSName, properties: COSDictionary?) {
            if (tag.name != "Artifact" || openTags.isNotEmpty()) return
            val ordinal = artifactsOpened++
            val type = properties?.getCOSName(COSName.TYPE)?.name
            val place = when {
                type != "Pagination" -> null
                else -> (properties.getDictionaryObject(COSName.getPDFName("Attached")) as? COSArray)
                    ?.firstOrNull()?.let { (it as? COSName)?.name }
                    ?: properties.getCOSName(COSName.SUBTYPE)?.name
                    ?: "Pagination"
            }
            artifactPlaces[ordinal] = place
            currentArtifact = ordinal
        }

        private fun pathOperator(name: String, body: (List<COSBase>) -> Unit) = object : OperatorProcessor() {
            override fun getName() = name
            override fun process(operator: Operator, operands: List<COSBase>) = body(operands)
        }

        private fun number(operand: COSBase): Float? = (operand as? org.apache.pdfbox.cos.COSNumber)?.floatValue()

        private fun point(operands: List<COSBase>, index: Int): FloatArray? {
            if (operands.size < index + 2) return null
            val x = number(operands[index]) ?: return null
            val y = number(operands[index + 1]) ?: return null
            return transform(x, y)
        }

        /** User space through the current transformation, then top-down page points as glyphs are measured. */
        private fun transform(x: Float, y: Float): FloatArray {
            val ctm: Matrix = runCatching { graphicsState.currentTransformationMatrix }.getOrNull() ?: Matrix()
            val p = ctm.transformPoint(x, y)
            val box = page.cropBox
            return floatArrayOf(p.x - box.lowerLeftX, box.upperRightY - p.y)
        }

        private fun segment(from: FloatArray, to: FloatArray) {
            if (abs(from[1] - to[1]) > 0.5f) return
            pendingSegments += Rule((from[1] + to[1]) / 2, minOf(from[0], to[0]), maxOf(from[0], to[0]))
        }

        private fun paint(strokes: Boolean, fills: Boolean) {
            val inArtifact = openTags.any { it == "Artifact" }
            if (!inArtifact) {
                if (strokes) rules += pendingSegments.filter { it.right - it.left >= RULE_MIN_LENGTH_PT }
                if (fills) rules += pendingSlivers.filter { it.right - it.left >= RULE_MIN_LENGTH_PT }
            } else {
                // The furniture's own rules: kept as the boxes they cover,
                // so the crop reaches them.
                val artifact = currentArtifact
                if (artifact != null) {
                    val painted = (if (strokes) pendingSegments else emptyList()) + (if (fills) pendingSlivers else emptyList())
                    for (rule in painted) {
                        artifactBoxes.getOrPut(artifact) { mutableListOf() } +=
                            floatArrayOf(rule.left, rule.y - RULE_MAX_THICKNESS_PT, rule.right, rule.y + RULE_MAX_THICKNESS_PT)
                    }
                }
            }
            pendingSegments.clear()
            pendingSlivers.clear()
            current = null
            subpathStart = null
        }
    }

    /**
     * Text of every marked-content id, indexed by page. Pages are keyed by
     * their underlying COS dictionary: PDStructureElement.getPage() builds a
     * fresh PDPage wrapper on every call, so wrapper identity never matches.
     */
    private class MarkedContentIndex(private val doc: PDDocument) {
        private val pageIndexByPage = IdentityHashMap<COSDictionary, Int>()
        /** The running header and footer of each page, as the producer marked them. */
        private val furnitureByPage = HashMap<Int, MutableList<Furniture>>()
        private val pageWidthByIndex = HashMap<Int, Float>()
        private val pageHeightByIndex = HashMap<Int, Float>()
        /** The reach of each page's tagged text: the block the margins and indents are measured from. */
        private val inkByPageIndex = HashMap<Int, InkBox>()
        /** The rules drawn on each page, outside its running header and footer. */
        private val rulesByPageIndex = HashMap<Int, List<Rule>>()
        /** The colour each glyph was painted in, gathered from every page's extractor before it is let go. */
        private val colorByPosition = IdentityHashMap<TextPosition, Int>()
        /** Where each glyph points, for the few a link annotation covers. */
        private val linkByPosition = IdentityHashMap<TextPosition, String>()
        private val highlightByPosition = IdentityHashMap<TextPosition, Int>()
        private val glyphsByPageAndMcid = HashMap<Long, List<Glyph>>()
        private val textByPageAndMcid = HashMap<Long, String>()
        private val sizeByPageAndMcid = HashMap<Long, Float>()
        private val boldByPageAndMcid = HashMap<Long, Boolean>()
        /** Overrules a broken ToUnicode map with the embedded font's own cmap. */
        private val glyphText = GlyphUnicode()

        /**
         * The direction the document is written in: what its /Lang says,
         * else the direction most of its text runs in. Every line is
         * reconstructed against it, because a line cannot tell its own —
         * an Arabic line whose leftmost word is an email address starts,
         * visually, with a Latin letter.
         */
        private val baseDirection: TextDirection?

        init {
            val pageLinks = runCatching { PageLinks(doc) }.getOrNull()
            val pageHighlights = runCatching { PageHighlights(doc) }.getOrNull()
            for ((index, page) in doc.pages.withIndex()) {
                pageIndexByPage[page.cosObject] = index
                // A page may be written portrait and turned a quarter turn
                // to be read: the text is measured in the frame it is read
                // in, so the sheet is the one the reader sees.
                val turned = ((runCatching { page.rotation }.getOrDefault(0) % 360) + 360) % 360 % 180 != 0
                val width = runCatching { page.mediaBox.width }.getOrDefault(0f)
                val height = runCatching { page.mediaBox.height }.getOrDefault(0f)
                pageWidthByIndex[index] = if (turned) height else width
                pageHeightByIndex[index] = if (turned) width else height
                val extractor =
                    ResolvingMarkedContentExtractor(page, pageLinks?.page(index), pageHighlights?.page(index))
                attempt { extractor.processPage(page) }
                var artifacts = 0
                for (content in extractor.markedContents.orEmpty()) {
                    collect(content, index)
                    if (content.tag == "Artifact") {
                        collectFurniture(content, index, artifacts, extractor)
                        artifacts++
                    }
                }
                rulesByPageIndex[index] = extractor.rules.toList()
                colorByPosition.putAll(extractor.colors)
                linkByPosition.putAll(extractor.links)
                highlightByPosition.putAll(extractor.highlights)
            }
            baseDirection = Bidi.directionOfLanguage(runCatching { doc.documentCatalog.language }.getOrNull())
                ?: Bidi.dominantDirection(buildString {
                    for (glyphs in glyphsByPageAndMcid.values) for (glyph in glyphs) append(glyph.position.unicode.orEmpty())
                })
        }

        /**
         * A pagination artifact — a running header or footer — with what
         * it drew, kept for the furniture the writer will repeat. Which
         * margin it belongs to is what the producer said, else where it
         * sits on the page.
         */
        private fun collectFurniture(content: PDMarkedContent, pageIndex: Int, ordinal: Int, extractor: ResolvingMarkedContentExtractor) {
            val place = extractor.artifactPlaces[ordinal] ?: return
            val glyphs = mutableListOf<TextPosition>()
            fun gather(mc: PDMarkedContent) {
                for (item in mc.contents.orEmpty()) {
                    when (item) {
                        is TextPosition -> if (!item.unicode.isNullOrBlank()) glyphs += item
                        is PDMarkedContent -> gather(item)
                    }
                }
            }
            gather(content)
            val boxes = extractor.artifactBoxes[ordinal].orEmpty()
            if (glyphs.isEmpty() && boxes.isEmpty()) return
            val height = pageHeightByIndex[pageIndex] ?: 0f
            val atTop = when (place) {
                "Top", "Header" -> true
                "Bottom", "Footer" -> false
                else -> {
                    val middle = (glyphs.map { it.yDirAdj } + boxes.map { (it[1] + it[3]) / 2 }).average().toFloat()
                    height <= 0f || middle < height / 2
                }
            }
            furnitureByPage.getOrPut(pageIndex) { mutableListOf() } += Furniture(pageIndex, atTop, glyphs, boxes)
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
            val looks = ArrayList<PdfLook?>()
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
        private fun lineText(page: Int, line: List<Glyph>, tabStops: MutableSet<Float>): ExtractedText.Logical<PdfLook> {
            val visual = StringBuilder()
            val painters = ArrayList<PdfLook?>()
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

        private fun lookOf(position: TextPosition, raised: Int): PdfLook = PdfLook(
            fontFamily = position.font?.name?.let(::familyName),
            fontSizePt = position.fontSizeInPt,
            bold = isBold(position),
            italic = isItalic(position),
            raised = raised,
            colorRgb = colorByPosition[position],
            highlightRgb = highlightByPosition[position],
            link = linkByPosition[position],
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

        /**
         * Whether the page draws rules around a table — the box [glyphs]
         * occupy. Two rules across it is a ruled table; a table found
         * without any is one the producer set by alignment alone, and
         * drawing lines around it would add ink the page never had.
         */
        fun ruledLike(glyphs: List<Pair<Int, Glyph>>): Boolean {
            val ink = glyphs.filter { !it.second.position.unicode.isNullOrBlank() }
            if (ink.isEmpty()) return false
            val page = ink.first().first
            if (ink.any { it.first != page }) return false
            val left = ink.minOf { it.second.position.xDirAdj }
            val right = ink.maxOf { it.second.position.xDirAdj + it.second.position.widthDirAdj }
            val top = ink.minOf { it.second.position.yDirAdj - it.second.position.heightDir }
            val bottom = ink.maxOf { it.second.position.yDirAdj }
            val width = right - left
            if (width <= 0f) return false
            val across = rulesByPageIndex[page].orEmpty().count { rule ->
                rule.y >= top - TABLE_RULE_REACH_PT && rule.y <= bottom + TABLE_RULE_REACH_PT &&
                    minOf(rule.right, right) - maxOf(rule.left, left) >= TABLE_RULE_SHARE * width
            }
            return across >= TABLE_RULES_TO_BE_RULED
        }

        /**
         * Whether a rule is drawn across the page between [top] and
         * [bottom] on [page], inside the page's text block — a running
         * footer's rule below the block is not a paragraph's.
         */
        fun hasRuleBetween(page: Int, top: Float, bottom: Float): Boolean {
            val block = inkByPageIndex[page]?.takeIf { !it.isEmpty } ?: return false
            val width = block.right - block.left
            return rulesByPageIndex[page].orEmpty().any { rule ->
                rule.y in top..bottom &&
                    rule.y >= block.top - RULE_REACH * 12f && rule.y <= block.bottom + RULE_REACH * 12f &&
                    rule.right - rule.left >= 0.25f * width
            }
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
        fun pageSetup(bodyTopByPage: Map<Int, Float> = emptyMap()): PageSetup? {
            val width = pageWidthByIndex[0]?.takeIf { it > 0f } ?: return null
            val height = pageHeightByIndex[0]?.takeIf { it > 0f } ?: return null
            val boxes = inkByPageIndex.values.filter { !it.isEmpty }
            if (boxes.isEmpty()) return null
            fun margin(value: Float) = value.coerceIn(0f, minOf(width, height) / 3)
            // The top margin is where the first line's box begins, as Word
            // will place it — its baseline less the line's pitch, plus the
            // descent below the baseline — not where the tallest glyph's
            // ink starts, which is lower by the line's leading and would
            // push every page's text down by that much.
            val top = bodyTopByPage.values.minOrNull() ?: boxes.minOf { it.top }
            return PageSetup(
                widthPt = width,
                heightPt = height,
                marginTopPt = margin(top),
                marginBottomPt = margin(height - boxes.maxOf { it.bottom }),
                marginLeftPt = margin(boxes.minOf { it.left }),
                marginRightPt = margin(width - boxes.maxOf { it.right }),
            )
        }

        /**
         * The running header and footer, from the pagination artifacts the
         * producer marked: each as a crop of the page rendered at the size
         * it had, placed against the same margins as the text. A page number
         * — a run of digits whose value advances by one from page to page —
         * is masked out of the crop and written as a field, at a tab stop
         * where the number sat, so every page numbers itself; the number
         * the first page carries is reported for the document to start at.
         * The second page stands for the rest when there is one: a first
         * page can carry a title where the others carry the running head.
         */
        fun furnishings(marginLeft: Float, marginRight: Float, rtl: Boolean): Furnishings {
            val width = pageWidthByIndex[0]?.takeIf { it > 0f } ?: return Furnishings.NONE
            val height = pageHeightByIndex[0]?.takeIf { it > 0f } ?: return Furnishings.NONE
            var firstPageNumber = 1
            fun side(atTop: Boolean): Pair<List<Block>, Float?> {
                val byPage = furnitureByPage.mapValues { (_, list) -> list.filter { it.atTop == atTop } }
                    .filterValues { it.isNotEmpty() }
                if (byPage.isEmpty()) return emptyList<Block>() to null
                val reference = if (byPage.containsKey(1)) 1 else byPage.keys.min()
                val pieces = byPage.getValue(reference)
                val box = boundsOf(pieces) ?: return emptyList<Block>() to null
                val distance = if (atTop) box[1] else height - box[3]
                val number = pageNumber(byPage, reference)
                val left = minOf(marginLeft, box[0])
                val right = maxOf(marginRight, box[2])
                val top = box[1]
                val bottom = box[3]
                if (number == null) {
                    val crop = PageImages.crop(doc, reference, left, top, right, bottom) ?: return emptyList<Block>() to null
                    return listOf<Block>(crop) to distance
                }
                firstPageNumber = number.value - reference
                val numberBox = number.box
                val numberLook = number.positions.firstOrNull()?.let { lookOf(it, 0) }
                val field = TextRun(
                    text = firstPageNumber.toString(),
                    field = RunField.PAGE_NUMBER,
                    bold = numberLook?.bold ?: false,
                    italic = numberLook?.italic ?: false,
                    fontFamily = numberLook?.fontFamily,
                    fontSizePt = numberLook?.fontSizePt?.takeIf { it > 0f },
                )
                val centre = (numberBox[0] + numberBox[2]) / 2
                val atLeft = centre < width / 3
                val atRight = centre > width * 2 / 3
                if (!atLeft && !atRight) {
                    // A number in the middle: the picture with the number
                    // masked, and the field on a line of its own beneath.
                    val crop = PageImages.crop(doc, reference, left, top, right, bottom, listOf(numberBox))
                        ?: return listOf<Block>(Paragraph(listOf(field), ParagraphStyle(alignment = Alignment.CENTER))) to distance
                    val centred = Paragraph(listOf(field), ParagraphStyle(alignment = Alignment.CENTER, spaceBeforePt = 0f, spaceAfterPt = 0f))
                    return listOf(crop, centred) to distance
                }
                // The number at one end: the rest of the furniture as a
                // picture in the line, a tab to where the number sat, and
                // the field — all on the one line, as on the page.
                val cropLeft = if (atLeft) numberBox[2] + FURNITURE_GAP_PT else left
                val cropRight = if (atRight) numberBox[0] - FURNITURE_GAP_PT else right
                val crop = PageImages.crop(doc, reference, cropLeft, top, cropRight, bottom)
                val picture = crop?.let { TextRun("", image = it) }
                val direction = if (rtl) TextDirection.RTL else TextDirection.LTR
                val numberFirst = if (rtl) atRight else atLeft
                val stop = when {
                    numberFirst -> if (rtl) right - cropRight else cropLeft - left
                    rtl -> right - numberBox[2]
                    else -> numberBox[0] - left
                }
                val runs = if (numberFirst) listOfNotNull(field, TextRun("\t"), picture) else listOfNotNull(picture, TextRun("\t"), field)
                val startIndent = if (numberFirst) 0f else if (rtl) right - cropRight else cropLeft - left
                val style = ParagraphStyle(
                    direction = direction,
                    startIndentPt = startIndent.takeIf { it > 0.5f },
                    tabStopsPt = listOf(stop).filter { it > 0f },
                    spaceBeforePt = 0f,
                    spaceAfterPt = 0f,
                )
                return listOf<Block>(Paragraph(runs, style)) to distance
            }
            val (header, headerDistance) = side(atTop = true)
            val (footer, footerDistance) = side(atTop = false)
            return Furnishings(header, footer, headerDistance, footerDistance, firstPageNumber)
        }

        /** The box, in top-down page points, that a page's furniture occupies. */
        private fun boundsOf(pieces: List<Furniture>): FloatArray? {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (piece in pieces) {
                for (glyph in piece.glyphs) {
                    left = minOf(left, glyph.xDirAdj)
                    right = maxOf(right, glyph.xDirAdj + glyph.widthDirAdj)
                    top = minOf(top, glyph.yDirAdj - glyph.heightDir)
                    bottom = maxOf(bottom, glyph.yDirAdj + DESCENT_SHARE * glyph.fontSizeInPt)
                }
                for (box in piece.boxes) {
                    left = minOf(left, box[0]); top = minOf(top, box[1])
                    right = maxOf(right, box[2]); bottom = maxOf(bottom, box[3])
                }
            }
            if (left > right || top > bottom) return null
            return floatArrayOf(left - FURNITURE_PAD_PT, top - FURNITURE_PAD_PT, right + FURNITURE_PAD_PT, bottom + FURNITURE_PAD_PT)
        }

        /**
         * The page number among a margin's runs of digits: the run, in the
         * same place on every page, whose value is one more on each page
         * than on the page before. Null when no run keeps step — a date, a
         * volume number and a year all stay put.
         */
        private fun pageNumber(byPage: Map<Int, List<Furniture>>, reference: Int): NumberToken? {
            val tokensByPage = byPage.mapValues { (_, pieces) -> numberTokens(pieces.flatMap { it.glyphs }) }
            val referenceTokens = tokensByPage[reference] ?: return null
            var best: NumberToken? = null
            var bestPages = 1
            for (candidate in referenceTokens) {
                val pages = tokensByPage.count { (page, tokens) ->
                    page == reference || tokens.any { token ->
                        abs((token.box[0] + token.box[2]) / 2 - (candidate.box[0] + candidate.box[2]) / 2) < PAGE_NUMBER_DRIFT_PT &&
                            token.value - candidate.value == page - reference
                    }
                }
                if (pages >= 2 && pages > bestPages) {
                    best = candidate
                    bestPages = pages
                }
            }
            return best
        }

        /** Runs of digits among [glyphs], each with its value and box. */
        private fun numberTokens(glyphs: List<TextPosition>): List<NumberToken> {
            val tokens = mutableListOf<NumberToken>()
            val lines = glyphs.groupBy { (it.yDirAdj / SAME_LINE_TOLERANCE_PT).toInt() }
            for (line in lines.values) {
                var run = mutableListOf<TextPosition>()
                fun flush() {
                    if (run.isEmpty()) return
                    val text = run.joinToString("") { glyphText.of(it) }.map { digitValue(it) }
                    if (text.isNotEmpty() && text.all { it != null }) {
                        val value = text.fold(0L) { acc, d -> acc * 10 + d!! }
                        if (value in 0..99999) {
                            tokens += NumberToken(
                                value.toInt(),
                                floatArrayOf(
                                    run.minOf { it.xDirAdj }, run.minOf { it.yDirAdj - it.heightDir },
                                    run.maxOf { it.xDirAdj + it.widthDirAdj }, run.maxOf { it.yDirAdj + DESCENT_SHARE * it.fontSizeInPt },
                                ),
                                run.toList(),
                            )
                        }
                    }
                    run = mutableListOf()
                }
                var previous: TextPosition? = null
                for (glyph in line.sortedBy { it.xDirAdj }) {
                    val text = glyphText.of(glyph)
                    val gap = previous?.let { glyph.xDirAdj - (it.xDirAdj + it.widthDirAdj) } ?: 0f
                    if (text.isBlank() || gap > TOKEN_GAP_SHARE * glyph.fontSizeInPt) flush()
                    if (!text.isBlank()) run += glyph
                    previous = glyph
                }
                flush()
            }
            return tokens.sortedBy { it.box[0] }
        }

        private fun digitValue(c: Char): Int? = when (c) {
            in '0'..'9' -> c - '0'
            in '\u0660'..'\u0669' -> c - '\u0660'
            in '\u06F0'..'\u06F9' -> c - '\u06F0'
            else -> null
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
        /** Where each page's first line box begins, as a writer will place it: the top margin. */
        private val bodyTopByPage = HashMap<Int, Float>()
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
            markPageBreaks()
            val paragraphs = blocks.filterIsInstance<Paragraph>()
            val rtl = paragraphs.count { it.style.direction == TextDirection.RTL }
            val defaultDirection =
                if (rtl > paragraphs.size - rtl) TextDirection.RTL else TextDirection.LTR
            val page = texts.pageSetup(bodyTopByPage)
            val furnishings = page?.let {
                texts.furnishings(it.marginLeftPt, it.widthPt - it.marginRightPt, defaultDirection == TextDirection.RTL)
            } ?: Furnishings.NONE
            // Full UAX #9 pass: split mixed-direction runs so writers can
            // mark direction per run instead of per paragraph.
            return Bidi.refine(
                DocumentModel(
                    blocks = blocks.toList(),
                    defaultDirection = defaultDirection,
                    pageSetup = page?.copy(
                        headerDistancePt = furnishings.headerDistancePt,
                        footerDistancePt = furnishings.footerDistancePt,
                        firstPageNumber = furnishings.firstPageNumber,
                    ),
                    header = furnishings.header,
                    footer = furnishings.footer,
                )
            )
        }

        /**
         * Marks the paragraphs that begin a page the source broke to on
         * purpose, so a converted document keeps the breaks that carry
         * meaning: a section that starts on a fresh page, a list of
         * references, a title page.
         *
         * Only those. A page that simply filled up is left to break itself
         * again, because whoever opens the file may not have the face the
         * page was set in — a substituted face sets the same words a little
         * wider, and a break forced under it would leave a nearly empty
         * page behind every full one. A page that stopped well short of
         * where its text could have run cannot be explained that way: the
         * producer broke it, and the break is part of the document.
         */
        private fun markPageBreaks() {
            val lastBaselineByPage = HashMap<Int, Float>()
            val pitches = mutableListOf<Float>()
            for (placement in placementByBlockIndex.values) {
                lastBaselineByPage.merge(placement.lastPage, placement.lastBaseline, ::maxOf)
                placement.pitchPt?.let { pitches += it }
            }
            if (lastBaselineByPage.size < 2) return
            // The step from one line to the next: within a paragraph where
            // one has more than a line, and from the end of a paragraph to
            // the start of the next where none has — a document of
            // one-line paragraphs still has a line's worth of step.
            val ordered = placementByBlockIndex.values
                .sortedWith(compareBy({ it.firstPage }, { it.firstBaseline }))
            for ((above, below) in ordered.zipWithNext()) {
                if (above.lastPage == below.firstPage) pitches += below.firstBaseline - above.lastBaseline
            }
            // How far down the page text was allowed to run, taken from the
            // page that ran furthest, and the line it would have run in.
            val bottom = lastBaselineByPage.values.max()
            val pitch = HeadingSizes.median(pitches.filter { it > 1f }).takeIf { it > 0f } ?: return
            var page = placementByBlockIndex.values.minOfOrNull { it.firstPage } ?: return
            for (index in blocks.indices) {
                val placement = placementByBlockIndex[index] ?: continue
                val previousEnd = lastBaselineByPage[page]
                if (placement.firstPage > page && previousEnd != null &&
                    previousEnd < bottom - EARLY_BREAK_LINES * pitch
                ) {
                    val paragraph = blocks[index] as? Paragraph
                    if (paragraph != null) {
                        blocks[index] = paragraph.copy(style = paragraph.style.copy(pageBreakBefore = true))
                    }
                }
                page = maxOf(page, placement.lastPage)
            }
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
                // The first paragraph on a page says where the page's text
                // begins: its first baseline less its pitch, plus what hangs
                // below the baseline — the top of its line box.
                if (pitch != null) {
                    val size = sizeByBlockIndex[index]?.takeIf { it > 0f } ?: DEFAULT_SIZE_PT
                    val boxTop = placement.firstBaseline - pitch + DESCENT_SHARE * size
                    bodyTopByPage.merge(placement.firstPage, boxTop, ::minOf)
                }
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
            applyRules()
        }

        /**
         * Gives a rule drawn across the page to the paragraph it belongs to:
         * the nearer of the two it sits between — a line under a paper's
         * dates belongs under them, the separator above a footnote belongs
         * above it — and to the one paragraph beside it at the top or bottom
         * of a page's text.
         */
        private fun applyRules() {
            fun size(index: Int) = sizeByBlockIndex[index]?.takeIf { it > 0f } ?: DEFAULT_SIZE_PT
            for (index in blocks.indices) {
                val placement = placementByBlockIndex[index] ?: continue
                val above = index - 1 downTo 0
                val previous = above.firstOrNull { placementByBlockIndex[it] != null }
                    ?.let { placementByBlockIndex[it] }
                    ?.takeIf { it.lastPage == placement.firstPage }
                val next = placementByBlockIndex[index + 1]?.takeIf { it.firstPage == placement.lastPage }
                val paragraph = blocks[index] as? Paragraph ?: continue
                val top = previous?.lastBaseline ?: (placement.firstBaseline - RULE_REACH * size(index))
                val bottom = next?.firstBaseline ?: (placement.lastBaseline + RULE_REACH * size(index))
                // Nearer to this paragraph than to its neighbour, and clear
                // of its own baselines by a hair.
                val ruleAbove = texts.hasRuleBetween(
                    placement.firstPage,
                    maxOf(top + 1f, (top + placement.firstBaseline) / 2),
                    placement.firstBaseline - RULE_CLEARANCE * size(index),
                )
                val ruleBelow = texts.hasRuleBetween(
                    placement.lastPage,
                    placement.lastBaseline + RULE_CLEARANCE * size(index),
                    minOf(bottom - 1f, (bottom + placement.lastBaseline) / 2),
                )
                if (!ruleAbove && !ruleBelow) continue
                blocks[index] = paragraph.copy(
                    style = paragraph.style.copy(ruleAbove = ruleAbove, ruleBelow = ruleBelow)
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

                "L" -> emitList(element, depth, level = 0)
                "LI" -> emitListItem(element, marker = ListMarker.BULLET, depth = depth, level = 0)

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
            // A picture tagged inside the paragraph — a logo in a heading, a
            // formula in a line — has no glyphs, so reading the paragraph
            // off its text passes it by. It follows the words it was tagged
            // among rather than falling to the end of the document with the
            // pictures nothing referenced at all.
            emitFiguresIn(element)
        }

        /** Every Figure under [element], in tag order, as pictures after the text they belong to. */
        private fun emitFiguresIn(element: PDStructureElement) {
            for (child in childElements(element)) {
                if (resolvedType(child) == "Figure") emitFigure(child) else emitFiguresIn(child)
            }
        }

        /**
         * The paragraph's runs: one per stretch of characters that share a
         * look. A space no glyph painted — between two lines — belongs to
         * the run before it.
         */
        private fun runsOf(styled: ExtractedText.Logical<PdfLook>): List<TextRun> {
            val runs = mutableListOf<TextRun>()
            val text = StringBuilder()
            var current: PdfLook? = null
            fun flush() {
                if (text.isEmpty()) return
                val look = current
                runs += PdfRuns.toTextRun(text.toString(), current)
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
        private fun withoutPrefix(styled: ExtractedText.Logical<PdfLook>, prefix: String): ExtractedText.Logical<PdfLook> {
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

        /**
         * [level] is how many lists this one sits inside: a document's lists
         * are nested more often than not, and an item of a list inside an
         * item belongs neither to the outer list nor to a list of its own
         * but to a level of the same one.
         */
        private fun emitList(list: PDStructureElement, depth: Int, level: Int) {
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
            for (item in items) emitListItem(item, marker, depth, level)
        }

        private fun emitListItem(
            item: PDStructureElement,
            marker: ListMarker,
            depth: Int,
            level: Int,
        ) {
            val body = childElements(item).firstOrNull { resolvedType(it) == "LBody" }
            val container = body ?: item
            // A list inside an item is a list of its own, not more words of
            // the item: its glyphs would otherwise be read as the tail of the
            // sentence the item begins.
            val nested = childElements(container).filter { resolvedType(it) == "L" }
            val glyphs = glyphsOf(container, except = nested)
            val styled = trimmed(
                body?.let { texts.readStyled(glyphs).logical } ?: run {
                    // No LBody: take the item's text minus its label.
                    val label = childElements(item)
                        .firstOrNull { resolvedType(it) == "Lbl" }?.let(::textOf).orEmpty()
                    withoutPrefix(texts.readStyled(glyphs).logical, label)
                }
            )
            val text = styled.text
            if (text.isEmpty()) {
                for (inner in nested) emitList(inner, depth + 1, level + 1)
                return
            }
            sawText = true
            val direction = Bidi.firstStrongDirection(text)
            val placement = texts.placementOf(glyphs, direction)
            if (placement != null) placementByBlockIndex[blocks.size] = placement
            blocks += Paragraph(
                runs = runsOf(styled),
                style = ParagraphStyle(
                    direction = direction,
                    // A producer draws some labels into the item itself rather
                    // than into a label of its own: Word writes the bullet
                    // before an Arabic item as a glyph at the head of its text.
                    // An item that carries its label keeps it — a marker from
                    // the writer as well would show two — and the page's own
                    // label says more than a bullet does anyway: "أ-", "3-",
                    // and the dash of a second level are all lost by one.
                    listMarker = marker.takeIf { !drawsItsOwnLabel(text) },
                    listLevel = level,
                    alignment = placement?.alignment,
                    firstLineIndentPt = placement?.firstLineIndentPt,
                    startIndentPt = placement?.startIndentPt,
                    hangingIndentPt = placement?.hangingIndentPt,
                ),
                confidence = CONFIDENCE,
            )
            for (inner in nested) emitList(inner, depth + 1, level + 1)
        }

        /**
         * Whether a list item's text opens with the label the page drew for
         * it: a marker character — a bullet, a dash, a star — or a short
         * enumerator such as "أ-", "3." or "a)", either of them followed by
         * a space, which is what separates a label from a sentence that
         * merely begins with a dash.
         */
        private fun drawsItsOwnLabel(text: String): Boolean {
            val space = text.indexOfFirst { it == ' ' || it == '\t' }
            if (space <= 0) return false
            val label = text.substring(0, space)
            if (label.length == 1) return label[0] in MARKER_CHARACTERS
            if (label.length > LONGEST_LABEL) return false
            if (label.last() !in LABEL_TERMINATORS) return false
            return label.dropLast(1).all { it.isLetterOrDigit() }
        }

        private fun emitTable(table: PDStructureElement, depth: Int) {
            if (depth > MAX_DEPTH) throw TooDeepException()
            // Each cell's glyphs are kept: they say what the cell holds and
            // also where it sits, which is what the table's columns are.
            val cellElements = childElements(table)
                .filter { resolvedType(it) == "TR" }
                .map { row -> childElements(row).filter { resolvedType(it) in setOf("TD", "TH") } }
            val cellGlyphs = cellElements.map { row -> row.map(::glyphsOf) }
            val rows = cellElements.withIndex()
                .map { (rowIndex, row) ->
                    TableRow(
                        row.withIndex().map { (columnIndex, element) ->
                            val glyphs = cellGlyphs[rowIndex][columnIndex]
                            val styled = trimmed(texts.readStyled(glyphs).logical)
                            val text = styled.text
                            if (text.isNotEmpty()) sawText = true
                            val direction = Bidi.firstStrongDirection(text)
                            TableCell(
                                blocks = listOf(
                                    Paragraph(
                                        runs = runsOf(styled).ifEmpty { listOf(TextRun("")) },
                                        style = ParagraphStyle(direction = direction),
                                        confidence = CONFIDENCE,
                                    )
                                ),
                                columnSpan = spanOf(element, across = true),
                                rowSpan = spanOf(element, across = false),
                            )
                        }
                    )
                }
                .filter { it.cells.isNotEmpty() }
            if (rows.isEmpty()) {
                walkChildren(table, depth)
                return
            }
            // A table of Arabic is laid out from the right: the producer
            // tags its rightmost cell first, which is the order it is read
            // in, so the cells stand as they are and the widths are
            // measured the same way round.
            val rightToLeft =
                Bidi.dominantDirection(rows.joinToString(" ") { row ->
                    row.cells.joinToString(" ") { cell ->
                        cell.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
                    }
                }) == TextDirection.RTL
            blocks += Table(
                rows = rows,
                confidence = CONFIDENCE,
                columnWidthsPt = columnWidthsOf(cellGlyphs, rightToLeft),
                ruled = texts.ruledLike(cellGlyphs.flatten().flatten()),
                direction = if (rightToLeft) TextDirection.RTL else TextDirection.LTR,
            )
        }

        /**
         * How many columns or rows a cell covers, as the producer wrote it
         * in the cell's own attributes. One, when it says nothing — which
         * is what a cell that covers only its own place says.
         */
        private fun spanOf(cell: PDStructureElement, across: Boolean): Int {
            val attributes = runCatching { cell.attributes?.getObject(0) }.getOrNull()
            val table = attributes as? PDTableAttributeObject ?: return 1
            val span = runCatching { if (across) table.colSpan else table.rowSpan }.getOrDefault(1)
            return span.coerceIn(1, MOST_SPANNED_CELLS)
        }

        /**
         * The width of each column, in points: the columns are cut apart
         * halfway across the clear space between them, so the widths add up
         * to what the table occupies rather than to the ink inside it. Null
         * when the rows do not agree on how many columns there are, or when
         * a column drew nothing to measure.
         */
        private fun columnWidthsOf(
            cells: List<List<List<Pair<Int, Glyph>>>>,
            rightToLeft: Boolean = false,
        ): List<Float>? {
            // The columns are cut apart across the page from the left; a
            // table read from the right is measured left to right all the
            // same and its widths turned round at the end.
            val cellGlyphs = if (rightToLeft) cells.map { it.reversed() } else cells
            val columns = cellGlyphs.firstOrNull()?.size ?: return null
            if (columns < 1 || cellGlyphs.any { it.size != columns }) return null
            val starts = FloatArray(columns) { Float.POSITIVE_INFINITY }
            val ends = FloatArray(columns) { Float.NEGATIVE_INFINITY }
            for (row in cellGlyphs) {
                for ((column, glyphs) in row.withIndex()) {
                    for ((_, glyph) in glyphs) {
                        if (glyph.position.unicode.isNullOrBlank()) continue
                        starts[column] = minOf(starts[column], glyph.position.xDirAdj)
                        ends[column] = maxOf(ends[column], glyph.position.xDirAdj + glyph.position.widthDirAdj)
                    }
                }
            }
            if (starts.any { !it.isFinite() } || ends.any { !it.isFinite() }) return null
            val edges = mutableListOf(starts.first())
            for (column in 1 until columns) edges += (ends[column - 1] + starts[column]) / 2
            edges += ends.last()
            val widths = edges.zipWithNext { left, right -> right - left }
            return widths.takeIf { widths.all { it > 1f } }
                ?.let { if (rightToLeft) it.reversed() else it }
        }

        /** All text under an element, in tag (logical) order. */
        private fun textOf(element: PDStructureElement): String =
            texts.readOffThePage(glyphsOf(element))

        /** Every glyph painted under [element], tagged with its page, in tree order. */
        /** Every glyph under [element], leaving the subtrees in [except] alone. */
        private fun glyphsOf(
            element: PDStructureElement,
            except: List<PDStructureElement> = emptyList(),
        ): List<Pair<Int, Glyph>> {
            val glyphs = mutableListOf<Pair<Int, Glyph>>()
            // The tree hands out a fresh wrapper for an element every time it
            // is asked, so two of them are the same element when they stand
            // for the same dictionary, never when they are the same object.
            val skip = except.map { it.cosObject }
            fun gather(node: PDStructureElement, depth: Int) {
                if (depth > MAX_DEPTH) throw TooDeepException()
                for (kid in node.kids.orEmpty()) {
                    when (kid) {
                        is PDStructureElement ->
                            if (skip.none { it === kid.cosObject }) gather(kid, depth + 1)
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
