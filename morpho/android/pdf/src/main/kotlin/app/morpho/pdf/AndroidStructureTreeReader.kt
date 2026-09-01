package app.morpho.pdf

import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
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
internal object AndroidStructureTreeReader {

    private const val MAX_DEPTH = 128
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
        private val textByPageAndMcid = HashMap<Long, String>()

        init {
            for ((index, page) in doc.pages.withIndex()) {
                pageIndexByPage[page.cosObject] = index
                val extractor = ResolvingMarkedContentExtractor()
                runCatching { extractor.processPage(page) }
                for (content in extractor.markedContents.orEmpty()) {
                    collect(content, index)
                }
            }
        }

        private fun collect(content: PDMarkedContent, pageIndex: Int) {
            val text = StringBuilder()
            fun gather(mc: PDMarkedContent) {
                for (item in mc.contents.orEmpty()) {
                    when (item) {
                        is TextPosition -> text.append(item.unicode)
                        is PDMarkedContent -> gather(item)
                    }
                }
            }
            gather(content)
            if (content.mcid >= 0 && text.isNotEmpty()) {
                textByPageAndMcid[key(pageIndex, content.mcid)] = text.toString()
            }
            // Nested marked content carries its own MCIDs too.
            for (item in content.contents.orEmpty()) {
                if (item is PDMarkedContent) collect(item, pageIndex)
            }
        }

        fun textFor(page: PDPage?, mcid: Int): String {
            val pageIndex = page?.cosObject?.let(pageIndexByPage::get) ?: return ""
            return textByPageAndMcid[key(pageIndex, mcid)].orEmpty()
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
            val sb = StringBuilder()
            fun gather(node: PDStructureElement, depth: Int) {
                if (depth > MAX_DEPTH) throw TooDeepException()
                for (kid in node.kids.orEmpty()) {
                    when (kid) {
                        is PDStructureElement -> gather(kid, depth + 1)
                        is Int -> sb.append(texts.textFor(node.page, kid))
                        is COSInteger -> sb.append(texts.textFor(node.page, kid.intValue()))
                        is PDMarkedContentReference ->
                            sb.append(texts.textFor(kid.page ?: node.page, kid.mcid))
                        is PDMarkedContent -> sb.append(texts.textFor(node.page, kid.mcid))
                    }
                }
            }
            gather(element, 0)
            return sb.toString()
        }

        private fun childElements(element: PDStructureElement): List<PDStructureElement> =
            element.kids.orEmpty().filterIsInstance<PDStructureElement>()

        private fun resolvedType(element: PDStructureElement): String {
            val type = element.structureType ?: return ""
            return (roleMap[type] as? String) ?: type
        }
    }
}
