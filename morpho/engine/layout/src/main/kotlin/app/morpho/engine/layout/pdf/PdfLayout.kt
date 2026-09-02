package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.LineJoiner
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
 * The indent check reads the left edge, which for right-to-left lines is the
 * ragged side, so it is skipped when either neighbouring line starts with a
 * right-to-left character; it also cannot tell a first-line indent from a
 * block indent yet.
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
    /** This share of the long lines reaching the end margin means the document is justified. */
    private const val JUSTIFIED_SHARE = 0.55f
    /** A page of fewer lines than this has its margins read at its extremes. */
    private const val MIN_LINES_FOR_PERCENTILE = 5
    /** Fewer long lines than this and the question does not arise. */
    private const val MIN_LINES_TO_JUDGE = 8
    /** A page whose text stopped this many lines short of where it could have run was broken on purpose. */
    private const val EARLY_BREAK_LINES = 2f
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
    ): DocumentModel {
        val body = withoutRunningHeadsAndFeet(lines, sheets)
        return reconstructBody(body.ifEmpty { lines }, confidence, images, sheets, rules, outline)
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
        val regions = PdfTableDetector.detect(lines)

        // Text stretches between table regions, each remembering its lines.
        val stretches = mutableListOf<List<PdfLine>>()
        val tablesWithAnchor = mutableListOf<Pair<PdfLine, Table>>()
        var cursor = 0
        for (region in regions) {
            if (region.start > cursor) stretches += lines.subList(cursor, region.start)
            tablesWithAnchor += lines[region.start] to tableOf(region, confidence)
            cursor = region.end
        }
        if (cursor < lines.size) stretches += lines.subList(cursor, lines.size)

        val textLines = stretches.flatten()
        val bodySize = HeadingSizes.median((textLines.ifEmpty { lines }).map { it.maxFontSize })
        val justified = looksJustified(lines, blockByPage)
        val clusters = stretches.map { cluster(it, blockByPage, justified, flows, outline) }
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
            val kind = when {
                named != null -> named
                bySize != ParagraphKind.BODY -> bySize
                boldLevel != null && isBold(clusterLines) &&
                    clusterLines.sumOf { it.text.length } <= HeadingSizes.MAX_CHARS -> boldLevel
                else -> ParagraphKind.BODY
            }
            val next = flatClusters.getOrNull(index + 1)?.firstOrNull()
            positioned += Positioned(
                first.page,
                flows[first] ?: 0,
                first.baselineY,
                paragraph(clusterLines, kind, confidence, blockByPage, next, rules, flatClusters.getOrNull(index - 1)?.lastOrNull()),
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
        val deliberate = deliberateBreaks(lines)
        var page = Int.MIN_VALUE
        val blocks = (positioned + imagesPositioned)
            .sortedWith(compareBy({ it.page }, { it.flow }, { it.y }))
            .map { entry ->
                val starts = page != Int.MIN_VALUE && entry.page > page && entry.page in deliberate
                page = maxOf(page, entry.page)
                val block = entry.block
                if (starts && block is Paragraph) {
                    block.copy(style = block.style.copy(pageBreakBefore = true))
                } else {
                    block
                }
            }
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
     * The pages whose text stopped well short of where the document's text
     * could have run: their break was the producer's doing, not the page
     * filling up. The page after each of them begins a new one.
     */
    private fun deliberateBreaks(lines: List<PdfLine>): Set<Int> {
        val lastByPage = lines.groupBy { it.page }.mapValues { (_, pageLines) -> pageLines.maxOf { it.baselineY } }
        if (lastByPage.size < 2) return emptySet()
        val bottom = lastByPage.values.max()
        val pitch = HeadingSizes.median(
            lines.sortedWith(compareBy({ it.page }, { it.baselineY }))
                .zipWithNext { a, b -> if (a.page == b.page) b.baselineY - a.baselineY else 0f }
                .filter { it > 1f }
        )
        if (pitch <= 0f) return emptySet()
        val pages = lastByPage.keys.sorted()
        return pages.drop(1)
            .filter { page ->
                val previous = pages[pages.indexOf(page) - 1]
                (lastByPage[previous] ?: return@filter false) < bottom - EARLY_BREAK_LINES * pitch
            }
            .toSet()
    }

    /**
     * The lines without the page's furniture: a running header or footer
     * repeats in the same place in the margin of page after page, and is
     * not part of the text. Page numbers differ from page to page, so lines
     * are compared with their digits masked; a document of one or two pages
     * has nothing to compare and keeps everything.
     */
    private fun withoutRunningHeadsAndFeet(
        lines: List<PdfLine>,
        sheets: List<PdfPageSheet>,
    ): List<PdfLine> {
        val heightByPage = sheets.associate { it.page to it.heightPt }
        val pages = lines.map { it.page }.distinct().size
        if (pages < REPEATS_TO_BE_RUNNING) return lines
        fun inMargin(line: PdfLine): Boolean {
            val height = heightByPage[line.page]?.takeIf { it > 0f } ?: return false
            return line.baselineY < MARGIN_BAND_SHARE * height ||
                line.baselineY > (1f - MARGIN_BAND_SHARE) * height
        }
        val running = lines.filter(::inMargin)
            .groupBy { DIGITS.replace(it.text, "#") to (it.baselineY / SAME_PLACE_PT).toInt() }
            .filterValues { group -> group.map { it.page }.distinct().size >= REPEATS_TO_BE_RUNNING }
            .values.flatten()
            .toCollection(java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))
        return lines.filterNot { it in running }
    }

    private val DIGITS = Regex("[0-9\u0660-\u0669]")

    /**
     * The page the document was set on: the first sheet that drew text,
     * with margins where the kept lines reach nearest each edge.
     */
    private fun pageSetup(
        sheets: List<PdfPageSheet>,
        blockByPage: Map<Int, Pair<Float, Float>>,
        lines: List<PdfLine>,
    ): PageSetup? {
        val sheet = sheets.firstOrNull { it.widthPt > 0f && it.heightPt > 0f } ?: return null
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

    private fun tableOf(region: PdfTableDetector.Region, confidence: Float): Table =
        Table(
            rows = region.rows.map { row ->
                TableRow(
                    row.map { cell ->
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
            columnWidthsPt = columnWidthsOf(region),
            // A table found by the alignment of its columns is one nothing
            // was drawn around: the page shows no rules, so neither does
            // the conversion.
            ruled = false,
        )

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
                startsNewParagraph(previous, line, pitchByPage.getValue(line.page), blockByPage, justified, flows)
            ) {
                clusters += mutableListOf(line)
            } else {
                clusters.last() += line
            }
        }
        return clusters
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

    private fun endGap(line: PdfLine, block: Pair<Float, Float>, rtl: Boolean): Float =
        (if (rtl) line.x - block.first else block.second - line.xEnd).coerceAtLeast(0f)

    private fun startsNewParagraph(
        previous: PdfLine,
        line: PdfLine,
        medianPitch: Float,
        blockByPage: Map<Int, Pair<Float, Float>>,
        justified: Boolean,
        flows: Map<PdfLine, Int>,
    ): Boolean {
        if (line.page != previous.page) return true
        // The foot of one column and the head of the next are not one
        // paragraph, however close their baselines happen to fall.
        if ((flows[previous] ?: 0) != (flows[line] ?: 0)) return true
        val pitch =
            if (medianPitch > 0f) medianPitch
            else FALLBACK_PITCH_FACTOR * max(previous.maxFontSize, line.maxFontSize)
        if (pitch > 0f && line.baselineY - previous.baselineY > PARAGRAPH_GAP_FACTOR * pitch) {
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
            return endGap(previous, block, rtl) > PARAGRAPH_END_SHARE * (block.second - block.first)
        }
        val anyRtl = Bidi.firstStrongDirection(previous.text) == TextDirection.RTL ||
            Bidi.firstStrongDirection(line.text) == TextDirection.RTL
        return !anyRtl && abs(line.x - previous.x) > INDENT_SHIFT_PT
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
     * The cluster's runs against the text [LineJoiner] produced: it drops a
     * hyphen and joins the halves of a broken word, so the runs are walked
     * in step with the joined text rather than concatenated blindly.
     */
    private fun joinRuns(cluster: List<PdfLine>, joined: String): List<PdfRun> {
        val runs = cluster.flatMap { line -> line.runs.ifEmpty { listOf(PdfRun(line.text, null)) } }
        val out = mutableListOf<PdfRun>()
        var cursor = 0
        for (run in runs) {
            if (cursor >= joined.length) break
            // Each run is one character from the stripper; where the joined
            // text agrees, it keeps its look, and where a character was
            // dropped or added the look of the run beside it carries.
            if (run.text.isNotEmpty() && joined.startsWith(run.text, cursor)) {
                out += run
                cursor += run.text.length
            }
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
