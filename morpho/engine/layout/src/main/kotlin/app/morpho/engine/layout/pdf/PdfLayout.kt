package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.LineJoiner
import app.morpho.engine.layout.ListLabels
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Untagged-PDF layout heuristics: finds table regions from cell-column
 * alignment ([PdfTableDetector]), clusters the remaining captured [PdfLine]s
 * into [Paragraph] blocks, and promotes oversized short lines to headings.
 *
 * A new paragraph starts on a page break, on a vertical gap larger than
 * [PARAGRAPH_GAP_FACTOR] times the page's median line pitch, on a marked
 * font-size change, or on a marked left-edge indentation shift; every other
 * line break is treated as a soft wrap and unwrapped by [LineJoiner].
 * The indent check reads the edge a line starts at — its left on a
 * left-to-right page, its right on a right-to-left one — and cannot yet
 * tell a first-line indent from a block indent.
 *
 * Headings: the body size is the median font size over the non-table lines,
 * and a cluster of at most [MAX_HEADING_LINES] lines that [HeadingSizes]
 * accepts as short enough and large enough becomes a heading. [HeadingSizes]
 * also ranks the distinct heading sizes onto HEADING_1/2/3, so a document
 * set in a single size keeps every line as body.
 *
 * Paragraph and cell direction comes from the first strongly-directional
 * character, exactly like [app.morpho.engine.layout.PlainTextImporter].
 *
 * Captured [PdfImage]s are interleaved with text and tables by their page
 * and top edge, so figures land between the right paragraphs.
 */
object PdfLayout {

    private const val PARAGRAPH_GAP_FACTOR = 1.6f

    /**
     * The space a producer must have put between two paragraphs, over and
     * above the page's own line pitch, in type sizes.
     *
     * A multiple of the pitch alone will not do. The space a document sets
     * between its paragraphs is a share of its type size — Word's default
     * eight points, a browser's one em — while the pitch is a share of the
     * type size too, so the *ratio* of the two falls as a document is set
     * more openly. Set at a line and a half, a page's paragraphs stand out
     * by two thirds of their pitch; set at one and seven tenths, by less
     * than six tenths — under any fixed multiple that admitted the first.
     * The same page in English split into its paragraphs and in Arabic did
     * not, missing by a seventh of a point, because Arabic is set with the
     * leading its ascenders and its marks need.
     */
    private const val PARAGRAPH_SPACE_SHARE = 0.6f
    private const val FONT_CHANGE_FACTOR = 1.2f
    private const val INDENT_SHIFT_PT = 18f
    private const val MAX_HEADING_LINES = 2
    /** Pitch stand-in for pages without measurable gaps, in font sizes. */
    private const val FALLBACK_PITCH_FACTOR = 1.3f
    /** A line within this share of a page's height of its top or bottom edge sits in the margin. */
    private const val MARGIN_BAND_SHARE = 0.12f
    /** A line repeating in the margin of this many pages is a running header or footer. */
    private const val REPEATS_TO_BE_RUNNING = 3
    /** Baselines this far apart are the same line of the page, on another page. */
    private const val SAME_PLACE_PT = 3f
    /** A line whose middle is within this share of the page width of the block's middle is centred… */
    private const val CENTRE_TOLERANCE = 0.015f
    /** …provided it is shorter than this share of the block. */
    private const val CENTRED_MAX_SHARE = 0.7f
    /** Lines whose edges agree within this are flush — a justified paragraph, or a margin. */
    private const val FLUSH_TOLERANCE_PT = 4f
    /** An edge at least this far in from the margin is an indent; nearer is the margin itself. */
    private const val INDENT_MIN_PT = 6f
    /** An indent past this share of the block is not one: the line is set against the far edge. */
    private const val INDENT_MAX_SHARE = 0.4f
    /** Space after a paragraph past this is a page's worth of gap, not the paragraph's own. */
    private const val SPACE_AFTER_MAX_PT = 60f
    /** Line pitch as a share of type size, for a paragraph of one line. */
    private const val DEFAULT_PITCH_SHARE = 1.2f
    /** A line shorter than this share of the block cannot say whether the document is justified. */
    private const val LONG_LINE_SHARE = 0.5f
    /** A line ending within this share of the column of the end margin has reached it. */
    private const val JUSTIFIED_TOLERANCE_SHARE = 0.02f
    /** A line stopping more than this share of the column short of the end margin ends its paragraph. */
    private const val PARAGRAPH_END_SHARE = 0.06f

    /**
     * How far short of its margin the last line of a page may stop and
     * the paragraph still be taken to carry on over the page.
     *
     * Looser than the share a line within a page is judged by, because a
     * page is judged once and a line many times: a document set ragged
     * ends its lines wherever the next word did not fit, which is up to a
     * word short of the margin, and a page whose last line stops there
     * has almost certainly been cut off in the middle of a paragraph.
     */
    private const val PAGE_END_SHARE = 0.12f
    /** This share of the long lines reaching the end margin means the document is justified. */
    private const val JUSTIFIED_SHARE = 0.55f
    /** A page of fewer lines than this has its margins read at its extremes. */
    private const val MIN_LINES_FOR_PERCENTILE = 5
    /** Fewer long lines than this and the question does not arise. */
    private const val MIN_LINES_TO_JUDGE = 8

    /**
     * How much surer or less sure a block is than the plain reconstruction
     * it starts from, and the ends of the band it may move within — a
     * reconstructed block never claims to have been read from a document's
     * own structure, however well its lines agreed.
     */
    private const val NAMED_BY_THE_DOCUMENT = 0.2f
    private const val REGULAR_ON_THE_PAGE = 0.1f
    private const val GUESSED_FROM_WEIGHT = 0.02f
    private const val GUESSED_FROM_ALIGNMENT = 0.04f
    private const val LEAST_SURE = 0.55f
    private const val SUREST_RECONSTRUCTION = 0.84f

    /** How far a line may start from where the others do and still be one of them. */
    private const val EDGE_TOLERANCE_SHARE = 0.02f

    /** How far the space between two baselines may differ from the rest. */
    private const val PITCH_TOLERANCE_SHARE = 0.15f
    /** A page whose text stopped this many lines short of where it could have run was broken on purpose. */
    private const val EARLY_BREAK_LINES = 2f

    /**
     * The share of a sheet its text must cover for the page to be one the
     * sheet stopped rather than one the writing did.
     */
    private const val FILLED_SHARE = 0.5f

    /** How far a column may shift between two pages and still be the same column. */
    private const val COLUMN_SHIFT_PT = 6f
    /** How far a paragraph looks for a rule of its own, in its own line pitch. */
    private const val RULE_REACH = 1.6f
    /** A rule this much of a line's own height away from a baseline is the paragraph's, not the line's. */
    private const val RULE_CLEARANCE = 0.4f
    /** A rule must cross this share of the text block to be one at all. */
    private const val RULE_LEAST_SHARE = 0.25f

    fun reconstruct(
        lines: List<PdfLine>,
        confidence: Float,
        images: List<PdfImage> = emptyList(),
        sheets: List<PdfPageSheet> = emptyList(),
        rules: List<PdfRule> = emptyList(),
        outline: List<PdfOutlineEntry> = emptyList(),
        crop: PageFurniture.Crop? = null,
    ): DocumentModel {
        // What every page repeats at its head and its foot is the page's
        // own furniture, not text of the document: taken out of the
        // reading, and put back where a document keeps it. Some of it can
        // only be photographed — a head the file will not spell — which is
        // what a reader that holds the pages hands over [crop] for.
        val split = PageFurniture.of(lines, sheets, rules, crop, images)
        // The pictures the page owns are the page's; only the document's
        // own go into the reading.
        val model = reconstructBody(split.body, confidence, split.bodyImages, sheets, rules, outline)
        if (split.header.isEmpty() && split.footer.isEmpty() &&
            split.evenHeader.isEmpty() && split.evenFooter.isEmpty()
        ) {
            return model
        }
        val page = model.pageSetup
        return model.copy(
            header = split.header,
            footer = split.footer,
            evenHeader = split.evenHeader,
            evenFooter = split.evenFooter,
            pageSetup = page?.copy(
                headerDistancePt = split.headerDistancePt ?: page.headerDistancePt,
                footerDistancePt = split.footerDistancePt ?: page.footerDistancePt,
                firstPageNumber = split.firstPageNumber ?: page.firstPageNumber,
                differentFirstPage = split.differentFirstPage,
            ),
        )
    }

    private fun reconstructBody(
        asPainted: List<PdfLine>,
        confidence: Float,
        images: List<PdfImage>,
        sheets: List<PdfPageSheet>,
        rules: List<PdfRule>,
        outline: List<PdfOutlineEntry> = emptyList(),
    ): DocumentModel {
        val blockByPage = asPainted.groupBy { it.page }.mapValues { (_, pageLines) -> blockOf(pageLines) }
        // A page set in two columns paints its lines down the page, not down
        // each column, so they are put into the order they are meant to be
        // read in before anything is made of them. A page in one column is
        // one run of text and comes back as it went in.
        val rightToLeft = Bidi.dominantDirection(asPainted.joinToString(" ") { it.text }) == TextDirection.RTL
        val flows = PdfColumns.flows(asPainted, rightToLeft)
        val lines = asPainted.sortedWith(compareBy({ it.page }, { flows[it] ?: 0 }, { it.baselineY }))
        // The pages that filled up, and so the pages whose break was the
        // producer's doing rather than the page's: a paragraph — and a
        // table — carries over the first kind and not the second.
        val filled = filledPages(lines, sheets)
        val deliberate = deliberateBreaks(lines, filled)
        val regions = joinRunOns(PdfTableDetector.detect(lines), lines, filled)

        // Text stretches between table regions, each remembering its lines.
        val stretches = mutableListOf<List<PdfLine>>()
        val tablesWithAnchor = mutableListOf<Pair<PdfLine, Table>>()
        // The rules a table drew round itself belong to it, and to nothing
        // else: left in the general pile they are read as rules above and
        // below the paragraphs either side of the table, so a bordered
        // table came back with no border and a stray line over the
        // sentence under it.
        val ownRules = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PdfRule, Boolean>())
        var cursor = 0
        for (held in regions) {
            val region = held.region
            if (region.start > cursor) stretches += lines.subList(cursor, region.start)
            val drawn = rulesAround(region, lines, rules)
            ownRules += drawn
            // A table found by the alignment of its columns is the biggest
            // guess this reader makes; it says so.
            tablesWithAnchor += lines[region.start] to
                tableOf(
                    region,
                    (confidence - GUESSED_FROM_ALIGNMENT).coerceAtLeast(LEAST_SURE),
                    held.repeatingHead,
                    ruled = drawn.size >= RULES_OF_A_BORDER,
                )
            cursor = region.end
        }
        val bodyRules = if (ownRules.isEmpty()) rules else rules.filterNot { it in ownRules }
        if (cursor < lines.size) stretches += lines.subList(cursor, lines.size)

        val textLines = stretches.flatten()
        val bodySize = HeadingSizes.median((textLines.ifEmpty { lines }).map { it.maxFontSize })
        val justified = looksJustified(lines, blockByPage)
        val clusters = stretches.map { cluster(it, blockByPage, justified, flows, outline, filled) }
        val kindBySize = headingKinds(clusters.flatten(), bodySize)

        // Every block gets a position anchor (page, y of its first line);
        // captured images join the same stream and a stable sort interleaves
        // them — text added first wins ties at identical coordinates.
        class Positioned(val page: Int, val flow: Int, val y: Float, val block: Block)

        val positioned = mutableListOf<Positioned>()
        for ((anchor, table) in tablesWithAnchor) {
            positioned += Positioned(anchor.page, flows[anchor] ?: 0, anchor.baselineY, table)
        }
        val flatClusters = clusters.flatten()
        // A heading set in bold at the body's own size is invisible to a
        // size comparison, and that is how most hand-made section headings
        // are written; bold means nothing in a document that is mostly bold.
        val boldClusters = flatClusters.count(::isBold)
        val boldLevel =
            if (HeadingSizes.boldIsMeaningful(boldClusters, flatClusters.size)) {
                HeadingSizes.boldLevel(kindBySize)
            } else {
                null
            }
        for ((index, clusterLines) in flatClusters.withIndex()) {
            val bySize =
                if (isHeadingCandidate(clusterLines, bodySize)) {
                    kindBySize[HeadingSizes.sizeKey(fontSize(clusterLines))] ?: ParagraphKind.BODY
                } else {
                    ParagraphKind.BODY
                }
            val first = clusterLines.first()
            // What the document's own outline calls a heading is a heading,
            // whatever size it was set in: a manual's sections are often set
            // in the body's own face, and the outline is the producer saying
            // outright which lines they are.
            val named = PdfOutline.kindOf(outline, first.page, clusterLines.joinToString(" ") { it.text })
            val byBoldnessAlone = named == null && bySize == ParagraphKind.BODY &&
                boldLevel != null && isBold(clusterLines) &&
                clusterLines.sumOf { it.text.length } <= HeadingSizes.MAX_CHARS
            val kind = when {
                named != null -> named
                bySize != ParagraphKind.BODY -> bySize
                byBoldnessAlone -> boldLevel!!
                else -> ParagraphKind.BODY
            }
            // How sure the reader is of what it just built, so the Fidelity
            // Report can put the shakiest blocks in front of a reader
            // instead of listing a whole reconstructed document as equally
            // doubtful. Everything stays inside the band that says it was
            // reconstructed rather than read.
            val sureness = when {
                named != null -> confidence + NAMED_BY_THE_DOCUMENT
                byBoldnessAlone -> confidence - GUESSED_FROM_WEIGHT
                looksRegular(clusterLines, blockByPage) -> confidence + REGULAR_ON_THE_PAGE
                else -> confidence
            }.coerceIn(LEAST_SURE, SUREST_RECONSTRUCTION)
            val next = flatClusters.getOrNull(index + 1)?.firstOrNull()
            positioned += Positioned(
                first.page,
                flows[first] ?: 0,
                first.baselineY,
                paragraph(
                    clusterLines, kind, sureness, blockByPage, next, bodyRules,
                    flatClusters.getOrNull(index - 1)?.lastOrNull(),
                ),
            )
        }
        val textCount = positioned.size
        positioned.sortWith(compareBy({ it.page }, { it.y }))
        val imagesPositioned = images.map { image ->
            Positioned(
                image.page,
                // A picture belongs to the column it stands in.
                PdfColumns.flowOf(flows, image.page, image.topY),
                image.topY,
                ImageBlock(
                    bytes = image.bytes,
                    mimeType = image.mimeType,
                    widthPx = image.widthPx,
                    heightPx = image.heightPx,
                    confidence = confidence,
                ),
            )
        }
        // A page the producer broke to on purpose — a section starting
        // fresh, a list of references — is marked, so the break survives.
        // A page that simply filled up is left to break itself again:
        // whoever opens the file may not have the face it was set in, and a
        // forced break under a wider face leaves a nearly empty page behind
        // every full one.
        var page = Int.MIN_VALUE
        val paged = (positioned + imagesPositioned)
            .sortedWith(compareBy({ it.page }, { it.flow }, { it.y }))
            .map { entry ->
                val starts = page != Int.MIN_VALUE && entry.page > page && entry.page in deliberate
                page = maxOf(page, entry.page)
                val block = entry.block
                entry.page to if (starts && block is Paragraph) {
                    block.copy(style = block.style.copy(pageBreakBefore = true))
                } else {
                    block
                }
            }
        // A link that led to a page of the PDF is pointed at a place in
        // the document instead: nothing outside a PDF knows what page 12
        // means, so a book's contents page would lead nowhere at all.
        val linked = InternalLinks.resolve(paged)
        // A report of portrait pages with one landscape table in it is a
        // portrait report, and its landscape page is landscape: where the
        // sheet changes, so does the section, or the wide page comes back
        // upright with every line set to the wrong width.
        val blocks = withSections(linked, paged.map { it.first }, sheets, blockByPage, lines)
        check(textCount + imagesPositioned.size == blocks.size)

        val paragraphs = blocks.filterIsInstance<Paragraph>()
        val rtlCount = paragraphs.count { it.style.direction == TextDirection.RTL }
        val defaultDirection =
            if (rtlCount > paragraphs.size - rtlCount) TextDirection.RTL else TextDirection.LTR
        // Full UAX #9 pass: split mixed-direction runs so writers can mark
        // direction per run instead of per paragraph.
        return Bidi.refine(
            DocumentModel(
                blocks = blocks,
                defaultDirection = defaultDirection,
                pageSetup = pageSetup(sheets, blockByPage, lines),
            )
        )
    }

    /**
     * The pages whose text ran to the foot of the sheet: the page is what
     * stopped them, not the writing.
     *
     * Two things are asked of a page. What it leaves below its last line
     * must be about what the document's own foot margin is — the least
     * any of its pages leaves — since a page that leaves much more than
     * that stopped before the sheet made it. And its text must cover the
     * sheet the way a page of text does: a document may carry six lines
     * at the top of every page, and each of those is as deep as the
     * deepest while none of them is full.
     *
     * Without a sheet to measure against, or without a page of more than
     * one line to say what a line's step is, nothing is named filled: with
     * no evidence either way, a page ends what stood on it.
     */
    private fun filledPages(lines: List<PdfLine>, sheets: List<PdfPageSheet>): Set<Int> {
        val byPage = lines.groupBy { it.page }
        if (byPage.size < 2) return emptySet()
        val heightByPage = sheets.associate { it.page to it.heightPt }
        val pitch = HeadingSizes.median(
            lines.sortedWith(compareBy({ it.page }, { it.baselineY }))
                .zipWithNext { a, b -> if (a.page == b.page) b.baselineY - a.baselineY else 0f }
                .filter { it > 1f }
        )
        if (pitch <= 0f) return emptySet()
        val known = byPage.filterKeys { heightByPage[it] != null }
        // The document's own foot margin: the least any of its pages
        // leaves below its last line. A page that leaves much more than
        // that stopped before the sheet made it.
        val foot = known.minOfOrNull { (page, pageLines) ->
            heightByPage.getValue(page) - pageLines.maxOf { it.baselineY }
        } ?: return emptySet()
        return known.filterValues { pageLines ->
            val height = heightByPage.getValue(pageLines.first().page)
            val above = pageLines.minOf { it.baselineY }
            val below = height - pageLines.maxOf { it.baselineY }
            below <= foot + EARLY_BREAK_LINES * pitch &&
                // And its text must cover the sheet the way a page of text
                // does. A document may carry six lines at the top of every
                // page, and each of those is then as deep as the deepest
                // while none of them is a page the sheet stopped.
                above + below <= FILLED_SHARE * height
        }.keys
    }

    /**
     * The pages whose text stopped well short of where the document's text
     * could have run: their break was the producer's doing, not the page
     * filling up. The page after each of them begins a new one.
     */
    private fun deliberateBreaks(lines: List<PdfLine>, filled: Set<Int>): Set<Int> {
        if (filled.isEmpty()) return emptySet()
        val pages = lines.map { it.page }.distinct().sorted()
        return pages.drop(1).filterIndexed { index, _ -> pages[index] !in filled }.toSet()
    }


    /**
     * The page the document was set on: the first sheet that drew text,
     * with margins where the kept lines reach nearest each edge.
     */
    /**
     * [blocks] with each block that opens a part of the document set on a
     * different sheet saying so. The first block says nothing: the shape
     * it is set on is the document's own.
     *
     * Only a Paragraph can carry it, which is what Word allows; where a
     * shape changes at a table, the change waits for the paragraph after
     * it rather than being lost.
     */
    private fun withSections(
        blocks: List<Block>,
        pageOf: List<Int>,
        sheets: List<PdfPageSheet>,
        blockByPage: Map<Int, Pair<Float, Float>>,
        lines: List<PdfLine>,
    ): List<Block> {
        val shapeByPage = sheets
            .filter { it.widthPt > 0f && it.heightPt > 0f }
            .associate { it.page to (round(it.widthPt) to round(it.heightPt)) }
        if (shapeByPage.values.distinct().size < 2) return blocks
        val setupByShape = HashMap<Pair<Int, Int>, PageSetup?>()
        fun setupOf(shape: Pair<Int, Int>): PageSetup? = setupByShape.getOrPut(shape) {
            val pages = shapeByPage.filterValues { it == shape }.keys
            pageSetup(
                sheets.filter { it.page in pages },
                blockByPage.filterKeys { it in pages },
                lines.filter { it.page in pages },
            )
        }

        // The shape the document is measured at, which is the one most of
        // its pages have. A document opening on a page of another shape —
        // a cover, a wide table at the front — is set on that page before
        // it is set on its own, so the shape in force at the start is the
        // one the document as a whole is written at, and the first page
        // says so if it differs.
        val dominant = shapeByPage.values.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        var inForce: Pair<Int, Int>? = dominant
        var waiting: PageSetup? = null
        return blocks.mapIndexed { index, block ->
            val shape = shapeByPage[pageOf.getOrNull(index) ?: -1]
            if (shape != null && shape != inForce) {
                waiting = setupOf(shape)
                inForce = shape
            }
            val setup = waiting
            if (setup != null && block is Paragraph) {
                waiting = null
                block.copy(style = block.style.copy(sectionSetup = setup))
            } else {
                block
            }
        }
    }

    private fun round(value: Float): Int = kotlin.math.round(value).toInt()

    private fun pageSetup(
        sheets: List<PdfPageSheet>,
        blockByPage: Map<Int, Pair<Float, Float>>,
        lines: List<PdfLine>,
    ): PageSetup? {
        // The sheet most of the document is written on, not whichever page
        // happens to come first: a report of forty portrait pages with one
        // landscape table in it is a portrait report, and a cover page of
        // its own size does not make the document that size.
        val usable = sheets.filter { it.widthPt > 0f && it.heightPt > 0f }
        val sheet = usable
            .groupBy { it.widthPt to it.heightPt }
            .maxByOrNull { (_, pages) -> pages.size }
            ?.value?.first()
            ?: return null
        if (blockByPage.isEmpty() || lines.isEmpty()) return null
        // The median page's edges, so one runaway line cannot flatten a margin.
        val left = HeadingSizes.median(blockByPage.values.map { it.first })
        val right = HeadingSizes.median(blockByPage.values.map { it.second })
        val top = lines.minOf { line -> line.baselineY - line.maxFontSize }
        val bottom = lines.maxOf { it.baselineY }
        fun margin(value: Float) = value.coerceIn(0f, minOf(sheet.widthPt, sheet.heightPt) / 3)
        return PageSetup(
            widthPt = sheet.widthPt,
            heightPt = sheet.heightPt,
            marginTopPt = margin(top),
            marginBottomPt = margin(sheet.heightPt - bottom),
            marginLeftPt = margin(left),
            marginRightPt = margin(sheet.widthPt - right),
        )
    }

    /** A table's region, with however many of its leading rows repeat on each page it runs onto. */
    private class TableRegion(val region: PdfTableDetector.Region, val repeatingHead: Int = 0)

    /**
     * Table regions with the ones that are the same table joined.
     *
     * A statement of accounts, a schedule, a bibliography: a table longer
     * than a page is painted as a table on each page it runs onto, and
     * the page says nothing to tie them together — so a twenty-page
     * statement came back as twenty tables, each starting again. They are
     * the same table when one ends at the foot of a page the page itself
     * stopped, the next begins at the head of the following one with
     * nothing between them, and their columns stand in the same places.
     *
     * Where the second begins by repeating the first's head, the repeat is
     * dropped and the head is marked as one — so Word, the preview and the
     * exported page all set it again at the top of every page the table
     * runs onto, which is what the original page was doing by printing it
     * twice.
     */
    private fun joinRunOns(
        regions: List<PdfTableDetector.Region>,
        lines: List<PdfLine>,
        filled: Set<Int>,
    ): List<TableRegion> {
        val out = mutableListOf<TableRegion>()
        for (region in regions) {
            val joined = out.lastOrNull()?.let { joinedWith(it, region, lines, filled) }
            if (joined == null) out += TableRegion(region) else out[out.size - 1] = joined
        }
        return out
    }

    private fun joinedWith(
        open: TableRegion,
        next: PdfTableDetector.Region,
        lines: List<PdfLine>,
        filled: Set<Int>,
    ): TableRegion? {
        val before = open.region
        if (before.end != next.start) return null
        val lastPage = lines.getOrNull(before.end - 1)?.page ?: return null
        val nextPage = lines.getOrNull(next.start)?.page ?: return null
        if (nextPage <= lastPage || lastPage !in filled) return null
        val columns = before.rows.firstOrNull()?.size ?: return null
        if (columns == 0) return null
        if (before.rows.any { it.size != columns } || next.rows.any { it.size != columns }) return null
        if (!sameColumns(before, next, columns)) return null
        val head = before.rows.first()
        val repeats = next.rows.firstOrNull()
            ?.let { row -> row.zip(head).all { (one, other) -> one.text == other.text } } == true
        return TableRegion(
            PdfTableDetector.Region(
                start = before.start,
                end = next.end,
                rows = before.rows + if (repeats) next.rows.drop(1) else next.rows,
            ),
            repeatingHead = if (repeats || open.repeatingHead > 0) 1 else 0,
        )
    }

    /** Whether two regions set their columns in the same places across the page. */
    private fun sameColumns(
        one: PdfTableDetector.Region,
        other: PdfTableDetector.Region,
        columns: Int,
    ): Boolean = (0 until columns).all { column ->
        abs(one.rows.minOf { it[column].xStart } - other.rows.minOf { it[column].xStart }) <=
            COLUMN_SHIFT_PT
    }

    private fun tableOf(
        region: PdfTableDetector.Region,
        confidence: Float,
        repeatingHead: Int = 0,
        ruled: Boolean = false,
    ): Table {
        // A table of Arabic is laid out from the right: its first column is
        // the rightmost. The cells were gathered across the page from the
        // left, so they are turned round to stand in the order the table is
        // read in, and the widths with them.
        val rightToLeft =
            Bidi.dominantDirection(region.rows.flatten().joinToString(" ") { it.text }) ==
                TextDirection.RTL
        fun <T> inReadingOrder(row: List<T>): List<T> = if (rightToLeft) row.reversed() else row
        return Table(
            rows = region.rows.mapIndexed { index, row ->
                TableRow(
                    repeatsAsHeader = index < repeatingHead,
                    cells = inReadingOrder(row).map { cell ->
                        val direction = Bidi.firstStrongDirection(cell.text)
                        TableCell(
                            listOf(
                                Paragraph(
                                    runs = listOf(TextRun(cell.text, direction = direction)),
                                    style = ParagraphStyle(direction = direction),
                                    confidence = confidence,
                                )
                            )
                        )
                    }
                )
            },
            confidence = confidence,
            columnWidthsPt = columnWidthsOf(region)?.let(::inReadingOrder),
            // Only where the page drew them. A table found by the
            // alignment of its columns alone is one nothing was drawn
            // around, and ruling it in the conversion would add ink the
            // source never had.
            ruled = ruled,
            direction = if (rightToLeft) TextDirection.RTL else TextDirection.LTR,
        )
    }

    /**
     * Rules the page drew across [region]: its border, the line under its
     * head, the lines between its rows.
     *
     * A table is known here by the alignment of its columns, which says
     * nothing about whether anything was drawn around it — and the rules
     * themselves were left in the pile every paragraph is measured
     * against, so a bordered table came back with no border and the
     * paragraphs either side of it gained a rule they never had. A rule
     * within the table's own band, reaching across the width the table
     * occupies, is the table's.
     *
     * The band is opened out by a line either side: the border above a
     * table is drawn above the ascenders of its head, and the one below is
     * below the descenders of its last row.
     */
    private fun rulesAround(
        region: PdfTableDetector.Region,
        lines: List<PdfLine>,
        rules: List<PdfRule>,
    ): List<PdfRule> {
        if (rules.isEmpty()) return emptyList()
        val own = lines.subList(region.start, region.end)
        val page = own.firstOrNull()?.page ?: return emptyList()
        if (own.any { it.page != page }) return emptyList()
        val size = own.maxOf { it.maxFontSize }
        val top = own.minOf { it.baselineY } - TABLE_BORDER_LINES * size
        val bottom = own.maxOf { it.baselineY } + TABLE_BORDER_LINES * size
        val left = region.rows.minOf { row -> row.minOf { it.xStart } }
        val right = region.rows.maxOf { row -> row.maxOf { it.xEnd } }
        val width = right - left
        if (width <= 0f) return emptyList()
        return rules.filter { rule ->
            rule.page == page && rule.y in top..bottom &&
                rule.right - rule.left >= RULE_LEAST_SHARE * width
        }
    }

    /** How far past its first and last baseline a table's own border may be drawn, in type sizes. */
    private const val TABLE_BORDER_LINES = 1.5f

    /** Rules across a table's band before it counts as a table the page drew lines around. */
    private const val RULES_OF_A_BORDER = 2

    /**
     * The width of each column of [region], in points: the columns are cut
     * apart halfway across the clear space between them, so the widths add
     * up to what the table occupies rather than to the ink inside it.
     */
    private fun columnWidthsOf(region: PdfTableDetector.Region): List<Float>? {
        val columns = region.rows.firstOrNull()?.size ?: return null
        if (columns < 1 || region.rows.any { it.size != columns }) return null
        val starts = (0 until columns).map { column -> region.rows.minOf { it[column].xStart } }
        val ends = (0 until columns).map { column -> region.rows.maxOf { it[column].xEnd } }
        val edges = mutableListOf(starts.first())
        for (column in 1 until columns) edges += (ends[column - 1] + starts[column]) / 2
        edges += ends.last()
        val widths = edges.zipWithNext { left, right -> right - left }
        return widths.takeIf { widths.all { it > 1f } }
    }

    private fun cluster(
        lines: List<PdfLine>,
        blockByPage: Map<Int, Pair<Float, Float>>,
        justified: Boolean,
        flows: Map<PdfLine, Int>,
        outline: List<PdfOutlineEntry> = emptyList(),
        filled: Set<Int> = emptySet(),
    ): List<List<PdfLine>> {
        if (lines.isEmpty()) return emptyList()
        val pitchByPage = lines.groupBy { it.page }.mapValues { (_, pageLines) ->
            HeadingSizes.median(pageLines.zipWithNext { a, b -> b.baselineY - a.baselineY }.filter { it > 0f })
        }

        /** A line the document's outline names is a heading, and stands alone. */
        fun named(line: PdfLine) = PdfOutline.kindOf(outline, line.page, line.text) != null

        val clusters = mutableListOf(mutableListOf(lines.first()))
        for ((previous, line) in lines.zipWithNext()) {
            // A heading set in the body's own size, a line clear of it in
            // the body's own spacing, is invisible to every rule below: only
            // the outline knows it is a heading, so only the outline can
            // keep it out of the paragraph that follows it.
            if (named(line) || named(previous) ||
                startsNewParagraph(
                    previous, line, pitchByPage.getValue(line.page), blockByPage, justified, flows, filled,
                )
            ) {
                clusters += mutableListOf(line)
            } else {
                clusters.last() += line
            }
        }
        return clusters
    }

    /**
     * Whether a cluster's lines sit on the page the way a paragraph's do:
     * every line starting from the same edge, and the space between their
     * baselines the same all the way down. A block assembled out of lines
     * that agree with each other is a safer reading than one made of lines
     * that agree about nothing.
     */
    private fun looksRegular(lines: List<PdfLine>, blockByPage: Map<Int, Pair<Float, Float>>): Boolean {
        if (lines.size < 2) return false
        val rightToLeft = Bidi.dominantDirection(lines.joinToString(" ") { it.text }) == TextDirection.RTL
        val block = blockByPage[lines.first().page] ?: return false
        val width = block.second - block.first
        if (width <= 0f) return false
        // Every line but the first begins where the others do — the first
        // may be indented, which is how a paragraph is often set.
        val edges = lines.drop(1).map { if (rightToLeft) it.xEnd else it.x }
        val edge = edges.first()
        if (edges.any { abs(it - edge) > EDGE_TOLERANCE_SHARE * width }) return false
        val steps = lines.zipWithNext { above, below -> below.baselineY - above.baselineY }
            .filter { it > 0f }
        if (steps.isEmpty()) return false
        val pitch = HeadingSizes.median(steps)
        return pitch > 0f && steps.all { abs(it - pitch) <= PITCH_TOLERANCE_SHARE * pitch }
    }

    /**
     * Whether the document sets its text to both margins. In one that does,
     * a line stopping short of the end margin is the last line of its
     * paragraph — the most reliable paragraph break there is, and the only
     * one that works whichever way the text runs and whether it is indented
     * on its first line or hanging. Lines too short to fill any line are
     * left out of the judgement.
     */
    private fun looksJustified(lines: List<PdfLine>, blockByPage: Map<Int, Pair<Float, Float>>): Boolean {
        var full = 0
        var reaching = 0
        for (line in lines) {
            val block = blockByPage[line.page] ?: continue
            val width = block.second - block.first
            if (width <= 0f || line.xEnd - line.x < LONG_LINE_SHARE * width) continue
            full++
            val rtl = Bidi.firstStrongDirection(line.text) == TextDirection.RTL
            if (endGap(line, block, rtl) <= JUSTIFIED_TOLERANCE_SHARE * width) reaching++
        }
        return full >= MIN_LINES_TO_JUDGE && reaching.toFloat() / full >= JUSTIFIED_SHARE
    }

    /**
     * A page's margins, as the lines on it reach them. Read a tenth of the
     * way in from each end rather than at the extremes: one line that
     * overhangs — a wide bibliography entry, a stray glyph — would otherwise
     * move the margin, and every other line then looks indented from it.
     * A page of a few lines has no distribution to speak of and is measured
     * at its extremes.
     */
    private fun blockOf(lines: List<PdfLine>): Pair<Float, Float> {
        if (lines.size < MIN_LINES_FOR_PERCENTILE) return lines.minOf { it.x } to lines.maxOf { it.xEnd }
        val lefts = lines.map { it.x }.sorted()
        val rights = lines.map { it.xEnd }.sorted()
        val edge = lines.size / 10
        return lefts[edge] to rights[rights.size - 1 - edge]
    }

    /**
     * Whether the paragraph ended with [previous] at the foot of its page
     * rather than carrying on into [line] at the head of the next.
     *
     * What can be asked across a page is what a line looks like and where
     * it stops, not how far below the line before it it sits: the gap
     * between the foot of one page and the head of the next says nothing
     * about either. So a line that stops short of its measure ended its
     * paragraph, and one that runs to the margin stopped because the page
     * did — and the line that follows must begin the way a paragraph's
     * middle does, at the edge its block starts from rather than indented
     * in from it, in the same face and weight, and not with the label of
     * a list item.
     *
     * Asked only of a page that filled up: a page whose text stopped
     * short of where it could have run was ended on purpose, and nothing
     * carries over one of those. Nor over any page of a document that
     * never shows what a full page of it looks like.
     */
    private fun endsWithItsPage(
        previous: PdfLine,
        line: PdfLine,
        blockByPage: Map<Int, Pair<Float, Float>>,
    ): Boolean {
        if (ListLabels.opensWithLabel(line.text)) return true
        val smaller = min(previous.maxFontSize, line.maxFontSize)
        if (smaller > 0f && max(previous.maxFontSize, line.maxFontSize) / smaller >= FONT_CHANGE_FACTOR) {
            return true
        }
        if (isBold(listOf(previous)) != isBold(listOf(line))) return true
        val before = blockByPage[previous.page] ?: return true
        val after = blockByPage[line.page] ?: return true
        val rtl = Bidi.firstStrongDirection(previous.text) == TextDirection.RTL
        // A line that stops on a hyphen stopped in the middle of a word,
        // and so in the middle of a paragraph, however short of its margin
        // it stopped.
        if (!LineJoiner.breaksAWord(previous.text) &&
            endGap(previous, before, rtl) > PAGE_END_SHARE * (before.second - before.first)
        ) {
            return true
        }
        // A first line indented in from its block's edge opens a paragraph
        // wherever it stands.
        val startsIn =
            if (Bidi.firstStrongDirection(line.text) == TextDirection.RTL) after.second - line.xEnd
            else line.x - after.first
        return startsIn > INDENT_SHIFT_PT
    }

    /** How far a line stands in from the edge its block starts at. */
    private fun depthOf(line: PdfLine, block: Pair<Float, Float>?, direction: TextDirection?): Float {
        if (block == null) return 0f
        return (if (direction == TextDirection.RTL) block.second - line.xEnd else line.x - block.first)
            .coerceAtLeast(0f)
    }

    /** Depths within this of each other are the one depth. */
    private const val SAME_DEPTH_PT = 6f

    /** What a sentence stops on, a bracket or a quote closed after it allowed for. */
    private const val SENTENCE_ENDS = ".:!?\u061F\u06D4\u2026"
    private const val CLOSERS = ")]}\u00BB\u203A\u0022\u0027\u201D\u2019"

    /** Whether [text] reaches the end of a sentence rather than stopping mid-way. */
    private fun finishesASentence(text: String): Boolean {
        val trimmed = text.trimEnd().trimEnd { it in CLOSERS }
        return trimmed.lastOrNull()?.let { it in SENTENCE_ENDS } ?: false
    }

    private fun endGap(line: PdfLine, block: Pair<Float, Float>, rtl: Boolean): Float =
        (if (rtl) line.x - block.first else block.second - line.xEnd).coerceAtLeast(0f)

    private fun startsNewParagraph(
        previous: PdfLine,
        line: PdfLine,
        medianPitch: Float,
        blockByPage: Map<Int, Pair<Float, Float>>,
        justified: Boolean,
        flows: Map<PdfLine, Int>,
        filled: Set<Int>,
    ): Boolean {
        // A paragraph does not end because a page did. Every page of a
        // book but the last ends in the middle of one, and breaking there
        // gave a converted document a broken sentence at every page turn
        // — hundreds of them in a book, each missing the space or the
        // hyphen that joined its two halves.
        if (line.page != previous.page) {
            return previous.page !in filled || endsWithItsPage(previous, line, blockByPage)
        }
        // The foot of one column and the head of the next are not one
        // paragraph, however close their baselines happen to fall.
        if ((flows[previous] ?: 0) != (flows[line] ?: 0)) return true
        // A line that opens with the label a page drew for a list item is
        // the next item, whatever the gap above it says. A list is set
        // tight — tighter than the lines within a paragraph, often — so a
        // rule about gaps alone reads a page of items as one paragraph,
        // and a converted checklist comes back as a wall of prose.
        if (ListLabels.opensWithLabel(line.text)) return true
        // And a line that has come back out to the edge its block starts
        // at, under an item that finished what it was saying, is the prose
        // after the list rather than the rest of that item. Without it the
        // sentence after a list is swallowed by the item above it.
        //
        // The geometry alone will not do, and was tried and withdrawn once
        // before: an item that does not hang carries on at the margin too,
        // so the rule split the items of a real Arabic paper after their
        // first line. What tells the two apart is whether the item had
        // finished — a line that stops mid-sentence is being carried on,
        // wherever the line under it begins.
        if (ListLabels.opensWithLabel(previous.text) && finishesASentence(previous.text)) {
            val edge = blockByPage[line.page]
            val direction = Bidi.firstStrongDirection(previous.text)
            if (edge != null &&
                depthOf(line, edge, direction) + SAME_DEPTH_PT < depthOf(previous, edge, direction)
            ) {
                return true
            }
        }
        val size = max(previous.maxFontSize, line.maxFontSize)
        val pitch = if (medianPitch > 0f) medianPitch else FALLBACK_PITCH_FACTOR * size
        // Either reading of "further apart than the lines of a paragraph":
        // half again the pitch, or the pitch and a space a producer chose
        // to add. Whichever is the smaller, since a page that shows either
        // has shown its paragraphs apart.
        val apart = min(PARAGRAPH_GAP_FACTOR * pitch, pitch + PARAGRAPH_SPACE_SHARE * size)
        if (pitch > 0f && line.baselineY - previous.baselineY > apart) {
            return true
        }
        val smaller = min(previous.maxFontSize, line.maxFontSize)
        if (smaller > 0f && max(previous.maxFontSize, line.maxFontSize) / smaller >= FONT_CHANGE_FACTOR) {
            return true
        }
        // A line set wholly in bold after one that is not — or the other
        // way about — is a heading meeting its text, whatever the geometry
        // says: the last line of a justified paragraph fills its column,
        // and the heading under it would otherwise join it.
        if (isBold(listOf(previous)) != isBold(listOf(line))) return true
        val block = blockByPage[line.page]
        if (justified && block != null) {
            // Measured as a share of the column, not in points: Arabic is
            // justified by stretching letters, and a line can fall a few
            // points short of the margin and still be a line in the middle
            // of a paragraph.
            val rtl = Bidi.firstStrongDirection(previous.text) == TextDirection.RTL
            if (LineJoiner.breaksAWord(previous.text)) return false
            return endGap(previous, block, rtl) > PARAGRAPH_END_SHARE * (block.second - block.first)
        }
        // A line set in from the edge its block starts at, under one that
        // was not, is the first line of a paragraph — which is how a book
        // marks its paragraphs where it leaves no space between them.
        //
        // Two things had to be right for that to work. The edge a line
        // starts at is its left on a left-to-right page and its right on a
        // right-to-left one: read as the left on both, this asked where an
        // Arabic line *ended*, which is its ragged side and says nothing.
        // And the indent is measured against the block, not against the
        // line above: measured against the line above it fires twice per
        // paragraph — once going in and once coming back out — and cuts
        // every paragraph after its first line.
        if (block == null) return false
        fun startsIn(held: PdfLine): Float =
            if (Bidi.firstStrongDirection(held.text) == TextDirection.RTL) block.second - held.xEnd
            else held.x - block.first
        return startsIn(line) > INDENT_SHIFT_PT && startsIn(previous) <= INDENT_SHIFT_PT
    }

    private fun headingKinds(
        clusters: List<List<PdfLine>>,
        bodySize: Float,
    ): Map<Int, ParagraphKind> =
        clusters
            .filter { isHeadingCandidate(it, bodySize) }
            .let { candidates -> HeadingSizes.rank(candidates.map(::fontSize)) }

    private fun isHeadingCandidate(cluster: List<PdfLine>, bodySize: Float): Boolean =
        cluster.size <= MAX_HEADING_LINES &&
            HeadingSizes.isCandidate(fontSize(cluster), cluster.sumOf { it.text.length }, bodySize)

    private fun fontSize(cluster: List<PdfLine>): Float = cluster.maxOf { it.maxFontSize }

    /**
     * Whether every letter of the cluster was drawn in a bold face. Judged
     * on letters: "2-تعريف" is a bold heading whose digit is set in a
     * regular Latin face, and the digit must not veto the letters.
     */
    private fun isBold(cluster: List<PdfLine>): Boolean {
        var sawLetter = false
        for (line in cluster) {
            for (run in line.runs) {
                if (run.text.none(Character::isLetter)) continue
                val look = run.look ?: return false
                if (!look.bold) return false
                sawLetter = true
            }
        }
        return sawLetter
    }

    private fun paragraph(
        cluster: List<PdfLine>,
        kind: ParagraphKind,
        confidence: Float,
        blockByPage: Map<Int, Pair<Float, Float>>,
        next: PdfLine?,
        rules: List<PdfRule> = emptyList(),
        previous: PdfLine? = null,
    ): Paragraph {
        val text = LineJoiner.join(cluster.map { it.text })
        val direction = Bidi.firstStrongDirection(text)
        val runs =
            if (cluster.any { it.runs.isNotEmpty() }) PdfRuns.toTextRuns(joinRuns(cluster, text))
            else listOf(TextRun(text = text))
        val block = blockByPage[cluster.first().page]
        val placement = block?.let { placement(cluster, it, direction) }
        val pitch = pitchOf(cluster)
        val last = cluster.last()
        val after = next?.takeIf { it.page == last.page }
            ?.let { (it.baselineY - last.baselineY - (pitch ?: 0f)).coerceIn(0f, SPACE_AFTER_MAX_PT) }
        return Paragraph(
            runs = runs.map { it.copy(direction = direction) },
            style = ParagraphStyle(
                kind = kind,
                direction = direction,
                alignment = placement?.alignment,
                firstLineIndentPt = placement?.firstLineIndentPt,
                startIndentPt = placement?.startIndentPt,
                hangingIndentPt = placement?.hangingIndentPt,
                spaceBeforePt = 0f,
                spaceAfterPt = after ?: 0f,
                linePitchPt = pitch,
                // A rule nearer this paragraph than its neighbour, and clear
                // of its own baselines: the line under a paper's dates, the
                // separator above the note at the foot of a page.
                ruleAbove = hasRule(
                    rules,
                    cluster.first().page,
                    from = midway(previous?.baselineY, cluster.first().baselineY, pitch, above = true),
                    to = cluster.first().baselineY - RULE_CLEARANCE * cluster.first().maxFontSize,
                    block = block,
                ),
                ruleBelow = hasRule(
                    rules,
                    last.page,
                    from = last.baselineY + RULE_CLEARANCE * last.maxFontSize,
                    to = midway(next?.takeIf { it.page == last.page }?.baselineY, last.baselineY, pitch, above = false),
                    block = block,
                ),
            ),
            confidence = confidence,
        )
    }

    /** Halfway between a paragraph's edge and its neighbour's, or a line's room away when it has none. */
    private fun midway(neighbour: Float?, edge: Float, pitch: Float?, above: Boolean): Float {
        val reach = (pitch ?: 0f).takeIf { it > 0f } ?: FALLBACK_PITCH_FACTOR * 12f
        val far = neighbour ?: (if (above) edge - RULE_REACH * reach else edge + RULE_REACH * reach)
        return (far + edge) / 2
    }

    /**
     * Whether a rule crosses the page between [from] and [to], inside the
     * text block: a running header's own rule sits outside it, and is the
     * header's business rather than a paragraph's.
     */
    private fun hasRule(
        rules: List<PdfRule>,
        page: Int,
        from: Float,
        to: Float,
        block: Pair<Float, Float>?,
    ): Boolean {
        if (rules.isEmpty() || from >= to) return false
        val width = block?.let { it.second - it.first } ?: return false
        if (width <= 0f) return false
        return rules.any { rule ->
            rule.page == page && rule.y in from..to &&
                rule.right - rule.left >= RULE_LEAST_SHARE * width
        }
    }

    /**
     * The cluster's runs against the text [LineJoiner] produced: it puts a
     * space between two lines it joins and drops the hyphen of a word
     * broken across them, so the runs are walked in step with the joined
     * text rather than concatenated blindly.
     *
     * Walking them in step means keeping step over those two: a run that
     * does not sit at the cursor is looked for just past it, and only a
     * run that is nowhere near is taken to be a character the joiner
     * dropped. A walk that gave up at the first disagreement lost its
     * place for the rest of the paragraph and handed every line after the
     * first the look of the line before — so a bold word on the second
     * line of a paragraph came back light, and a link on it led wherever
     * the line above led.
     */
    /**
     * How far past the cursor a run may be looked for before it is taken
     * to be a character the joiner dropped: the space it puts between two
     * lines, and no more, so a single character is never matched to some
     * far-off twin of itself.
     */
    private const val JOIN_SLIP = 2

    private fun joinRuns(cluster: List<PdfLine>, joined: String): List<PdfRun> {
        val runs = cluster.flatMap { line -> line.runs.ifEmpty { listOf(PdfRun(line.text, null)) } }
        val out = mutableListOf<PdfRun>()
        var cursor = 0
        for (run in runs) {
            if (cursor >= joined.length) break
            if (run.text.isEmpty()) continue
            // Each run is one character from the stripper.
            val at = (cursor..minOf(cursor + JOIN_SLIP, joined.length))
                .firstOrNull { joined.startsWith(run.text, it) }
                ?: continue
            // What the joiner put between the lines — the space that no
            // glyph painted — belongs to the look before it.
            if (at > cursor) out += PdfRun(joined.substring(cursor, at), out.lastOrNull()?.look)
            out += run
            cursor = at + run.text.length
        }
        if (cursor < joined.length) out += PdfRun(joined.substring(cursor), out.lastOrNull()?.look)
        return out
    }

    /** The distance between the cluster's own baselines, or its type size's share for one line. */
    private fun pitchOf(cluster: List<PdfLine>): Float? {
        val pitches = cluster.zipWithNext { a, b -> b.baselineY - a.baselineY }
            .filter { it > 0f }
        if (pitches.isNotEmpty()) return HeadingSizes.median(pitches)
        return cluster.maxOf { it.maxFontSize }.takeIf { it > 0f }?.let { DEFAULT_PITCH_SHARE * it }
    }

    private class Placement(
        val alignment: Alignment?,
        val firstLineIndentPt: Float?,
        val startIndentPt: Float?,
        val hangingIndentPt: Float?,
    )

    /**
     * How the cluster sits between its page's margins — measured against the
     * block its text occupies, not the sheet, and read from the start edge,
     * which is the right one for a right-to-left paragraph.
     */
    private fun placement(
        cluster: List<PdfLine>,
        block: Pair<Float, Float>,
        direction: TextDirection?,
    ): Placement {
        val (blockLeft, blockRight) = block
        val width = blockRight - blockLeft
        if (width <= 0f) return Placement(null, null, null, null)
        val rtl = direction == TextDirection.RTL
        fun startGap(line: PdfLine) = (if (rtl) blockRight - line.xEnd else line.x - blockLeft).coerceAtLeast(0f)
        fun endGap(line: PdfLine) = endGap(line, block, rtl)
        val centre = (blockLeft + blockRight) / 2
        val centred = cluster.all { line ->
            abs((line.x + line.xEnd) / 2 - centre) <= CENTRE_TOLERANCE * width &&
                line.xEnd - line.x < CENTRED_MAX_SHARE * width &&
                startGap(line) > FLUSH_TOLERANCE_PT && endGap(line) > FLUSH_TOLERANCE_PT
        }
        if (centred) return Placement(Alignment.CENTER, null, null, null)
        var alignment: Alignment? = null
        if (cluster.size >= 3) {
            val full = cluster.dropLast(1)
            val ends = full.map(::endGap)
            val starts = full.drop(1).map(::startGap)
            if (ends.max() - ends.min() <= FLUSH_TOLERANCE_PT &&
                starts.max() - starts.min() <= FLUSH_TOLERANCE_PT
            ) {
                alignment = Alignment.JUSTIFY
            }
        }
        val gaps = cluster.map(::startGap)
        val deepest = INDENT_MAX_SHARE * width
        val first = gaps.first()
        if (gaps.size == 1) {
            if (first > deepest && endGap(cluster.single()) <= FLUSH_TOLERANCE_PT) {
                return Placement(Alignment.END, null, null, null)
            }
            val indent = first.takeIf { it in INDENT_MIN_PT..deepest }
            return Placement(alignment, indent, null, null)
        }
        val rest = HeadingSizes.median(gaps.drop(1))
        val restIndent = if (rest in INDENT_MIN_PT..deepest) rest else 0f
        val extra = first - restIndent
        return Placement(
            alignment = alignment,
            firstLineIndentPt = extra.takeIf { it >= INDENT_MIN_PT && first <= deepest },
            startIndentPt = restIndent.takeIf { it > 0f },
            hangingIndentPt = (-extra).takeIf { extra <= -INDENT_MIN_PT && restIndent > 0f },
        )
    }
}
