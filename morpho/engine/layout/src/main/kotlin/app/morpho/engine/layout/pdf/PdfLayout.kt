package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
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
import kotlin.math.roundToInt

/**
 * Untagged-PDF layout heuristics: finds table regions from cell-column
 * alignment ([PdfTableDetector]), clusters the remaining captured [PdfLine]s
 * into [Paragraph] blocks, and promotes oversized short lines to headings.
 *
 * A new paragraph starts on a page break, on a vertical gap larger than
 * [PARAGRAPH_GAP_FACTOR] times the page's median line pitch, on a marked
 * font-size change, or on a marked left-edge indentation shift; every other
 * line break is treated as a soft wrap and unwrapped with a single space.
 * The indent check reads the left edge, which for right-to-left lines is the
 * ragged side, so it is skipped when either neighbouring line starts with a
 * right-to-left character; it also cannot tell a first-line indent from a
 * block indent yet.
 *
 * Headings: the body size is the median font size over the non-table lines;
 * a cluster of at most [MAX_HEADING_LINES] lines totalling at most
 * [SHORT_LINE_MAX_CHARS] characters whose font size reaches
 * [HEADING_SIZE_FACTOR] times the body size becomes a heading. Distinct
 * heading sizes rank descending onto HEADING_1/2/3; smaller heading sizes —
 * and every line of a document set in a single size — stay body.
 *
 * Paragraph and cell direction comes from the first strongly-directional
 * character, exactly like [app.morpho.engine.layout.PlainTextImporter].
 */
object PdfLayout {

    private const val PARAGRAPH_GAP_FACTOR = 1.6f
    private const val FONT_CHANGE_FACTOR = 1.2f
    private const val HEADING_SIZE_FACTOR = 1.2f
    private const val INDENT_SHIFT_PT = 18f
    private const val SHORT_LINE_MAX_CHARS = 80
    private const val MAX_HEADING_LINES = 2
    /** Pitch stand-in for pages without measurable gaps, in font sizes. */
    private const val FALLBACK_PITCH_FACTOR = 1.3f

    fun reconstruct(lines: List<PdfLine>, confidence: Float): DocumentModel {
        val regions = PdfTableDetector.detect(lines)

        // Interleave text stretches and table regions in document order.
        val stretches = mutableListOf<Pair<Int, List<PdfLine>>>() // insertion slot, lines
        val orderedTables = mutableListOf<Pair<Int, Table>>()
        var cursor = 0
        var slot = 0
        for (region in regions) {
            if (region.start > cursor) {
                stretches += slot++ to lines.subList(cursor, region.start)
            }
            orderedTables += slot++ to tableOf(region, confidence)
            cursor = region.end
        }
        if (cursor < lines.size) stretches += slot++ to lines.subList(cursor, lines.size)

        val textLines = stretches.flatMap { it.second }
        val bodySize = median((textLines.ifEmpty { lines }).map { it.maxFontSize })
        val clustersByStretch = stretches.map { (at, stretchLines) -> at to cluster(stretchLines) }
        val kindBySize = headingKinds(clustersByStretch.flatMap { it.second }, bodySize)

        val bySlot = HashMap<Int, List<Block>>()
        for ((at, table) in orderedTables) bySlot[at] = listOf(table)
        for ((at, clusters) in clustersByStretch) {
            bySlot[at] = clusters.map { clusterLines ->
                val kind =
                    if (isHeadingCandidate(clusterLines, bodySize)) {
                        kindBySize[sizeKey(fontSize(clusterLines))] ?: ParagraphKind.BODY
                    } else {
                        ParagraphKind.BODY
                    }
                paragraph(clusterLines.joinToString(" ") { it.text }, kind, confidence)
            }
        }
        val blocks = (0 until slot).flatMap { bySlot[it].orEmpty() }

        val paragraphs = blocks.filterIsInstance<Paragraph>()
        val rtlCount = paragraphs.count { it.style.direction == TextDirection.RTL }
        val defaultDirection =
            if (rtlCount > paragraphs.size - rtlCount) TextDirection.RTL else TextDirection.LTR
        return DocumentModel(blocks = blocks, defaultDirection = defaultDirection)
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
        )

    private fun cluster(lines: List<PdfLine>): List<List<PdfLine>> {
        if (lines.isEmpty()) return emptyList()
        val pitchByPage = lines.groupBy { it.page }.mapValues { (_, pageLines) ->
            median(pageLines.zipWithNext { a, b -> b.baselineY - a.baselineY }.filter { it > 0f })
        }
        val clusters = mutableListOf(mutableListOf(lines.first()))
        for ((previous, line) in lines.zipWithNext()) {
            if (startsNewParagraph(previous, line, pitchByPage.getValue(line.page))) {
                clusters += mutableListOf(line)
            } else {
                clusters.last() += line
            }
        }
        return clusters
    }

    private fun startsNewParagraph(previous: PdfLine, line: PdfLine, medianPitch: Float): Boolean {
        if (line.page != previous.page) return true
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
            .map { sizeKey(fontSize(it)) }
            .distinct()
            .sortedDescending()
            .zip(listOf(ParagraphKind.HEADING_1, ParagraphKind.HEADING_2, ParagraphKind.HEADING_3))
            .toMap()

    private fun isHeadingCandidate(cluster: List<PdfLine>, bodySize: Float): Boolean =
        bodySize > 0f &&
            cluster.size <= MAX_HEADING_LINES &&
            cluster.sumOf { it.text.length } <= SHORT_LINE_MAX_CHARS &&
            fontSize(cluster) >= HEADING_SIZE_FACTOR * bodySize

    private fun fontSize(cluster: List<PdfLine>): Float = cluster.maxOf { it.maxFontSize }

    /** Half-point buckets so float noise cannot multiply heading levels. */
    private fun sizeKey(size: Float): Int = (size * 2).roundToInt()

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    private fun paragraph(text: String, kind: ParagraphKind, confidence: Float): Paragraph {
        val direction = Bidi.firstStrongDirection(text)
        return Paragraph(
            runs = listOf(TextRun(text = text, direction = direction)),
            style = ParagraphStyle(kind = kind, direction = direction),
            confidence = confidence,
        )
    }
}
