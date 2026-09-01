package app.morpho.pdf

import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ExtractedText
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
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
                            if (!item.unicode.isNullOrBlank() && !isBold(item)) bold = false
                        }
                        is PDMarkedContent -> gather(item)
                    }
                }
            }
            gather(content)
            if (content.mcid >= 0 && glyphs.any { !it.unicode.isNullOrEmpty() }) {
                glyphsByPageAndMcid[key(pageIndex, content.mcid)] = positioned(glyphs)
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
        fun readOffThePage(glyphs: List<Pair<Int, Glyph>>): String {
            if (glyphs.isEmpty()) return ""
            // Pages in order, then lines top to bottom within each; a line
            // never spans a page break however close the baselines land.
            val lines = mutableListOf<MutableList<Glyph>>()
            for ((_, onPage) in glyphs.groupBy { it.first }.toSortedMap()) {
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
                        line = mutableListOf(glyph).also { lines += it }
                        lineSize = size
                    }
                }
            }
            // Not trimmed: the space between two words often belongs to the
            // edge of one run, and runs are joined edge to edge by textOf.
            // Trimming here glued "ربيحة نبار" into one word. The paragraph
            // is trimmed once, where it is emitted.
            return lines.joinToString(separator = " ") { line ->
                val visual = StringBuilder()
                var previous: TextPosition? = null
                // A producer that painted its spaces is trusted on where the
                // words are. Only one that painted none has its word breaks
                // read from the gaps, as PDFBox's own stripper does — a
                // kerning gap inside a word is otherwise easy to mistake for
                // one, and did split الجزائر in two.
                val inferBreaks = line.none { glyphText.of(it.position).let { u -> u.isNotEmpty() && u.isBlank() } }
                for (glyph in line.sortedBy { it.x }) {
                    val position = glyph.position
                    val unicode = ExtractedText.paintedForm(glyphText.of(position))
                    if (inferBreaks && previous != null && previous.widthDirAdj > 0f &&
                        unicode.isNotBlank() && !visual.endsWith(' ')
                    ) {
                        val gap = position.xDirAdj - (previous.xDirAdj + previous.widthDirAdj)
                        if (gap > WORD_GAP_FACTOR * position.fontSizeInPt) visual.append(' ')
                    }
                    visual.append(unicode)
                    previous = position
                }
                ExtractedText.toLogical(visual.toString(), baseDirection)
            }
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
            val paragraphs = blocks.filterIsInstance<Paragraph>()
            val rtl = paragraphs.count { it.style.direction == TextDirection.RTL }
            val defaultDirection =
                if (rtl > paragraphs.size - rtl) TextDirection.RTL else TextDirection.LTR
            // Full UAX #9 pass: split mixed-direction runs so writers can
            // mark direction per run instead of per paragraph.
            return Bidi.refine(
                DocumentModel(blocks = blocks.toList(), defaultDirection = defaultDirection)
            )
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
            val text = textOf(element).trim()
            if (text.isEmpty()) return
            sawText = true
            val direction = Bidi.firstStrongDirection(text)
            sizeByBlockIndex[blocks.size] = sizeOf(element)
            boldByBlockIndex[blocks.size] = boldOf(element)
            blocks += Paragraph(
                runs = listOf(TextRun(text = text, direction = direction)),
                style = ParagraphStyle(kind = kind, direction = direction, listMarker = marker),
                confidence = CONFIDENCE,
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
            val text = (body?.let(::textOf) ?: run {
                // No LBody: take the item's text minus its label.
                val label = childElements(item)
                    .firstOrNull { resolvedType(it) == "Lbl" }?.let(::textOf).orEmpty()
                textOf(item).removePrefix(label)
            }).trim()
            if (text.isEmpty()) return
            sawText = true
            val direction = Bidi.firstStrongDirection(text)
            blocks += Paragraph(
                runs = listOf(TextRun(text = text, direction = direction)),
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
                                val text = textOf(cell).trim()
                                if (text.isNotEmpty()) sawText = true
                                val direction = Bidi.firstStrongDirection(text)
                                TableCell(
                                    listOf(
                                        Paragraph(
                                            runs = listOf(TextRun(text, direction = direction)),
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
        private fun textOf(element: PDStructureElement): String {
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
            // The element's runs are laid out together on the page, so they
            // are read together: see MarkedContentIndex.readOffThePage.
            return texts.readOffThePage(glyphs)
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
