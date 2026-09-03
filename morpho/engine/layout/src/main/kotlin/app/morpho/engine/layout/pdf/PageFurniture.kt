package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.TextRun

/**
 * What a page repeats at its head and at its foot.
 *
 * A running head — the journal's title, the author, the section — and a
 * foot with the page number are not text of the document: they are the
 * page's own furniture, printed again on every page. Read as text they
 * arrive in the middle of the reading, once per page; dropped, they are
 * gone from the converted file altogether, and a paper that numbered its
 * pages 48, 49, 50 comes back numbering nothing.
 *
 * A page with no tags says nothing about which of its lines are which, so
 * they are known by repetition: a line in the margin, in the same place,
 * on page after page. That is enough to take them out of the text, and it
 * is enough to put them back where they belong — one page's worth of them
 * stands for the rest, with the number that advances from page to page
 * written as a field rather than as the digits one page happened to show.
 */
object PageFurniture {

    /** A line within this share of a page's height of its top or bottom edge sits in the margin. */
    private const val MARGIN_BAND_SHARE = 0.12f

    /** A line repeating in the margin of this many pages is a running header or footer. */
    private const val REPEATS_TO_BE_RUNNING = 3

    /** Baselines this far apart are the same line of the page, on another page. */
    private const val SAME_PLACE_PT = 3f

    /** A line whose middle sits within this share of the page width of its centre is centred. */
    private const val CENTRE_SHARE = 0.06f

    /** Of a line's type size, the share that sits above the baseline. */
    private const val ASCENT_SHARE = 0.8f

    /** Of a line's type size, the share that hangs below it. */
    private const val DESCENT_SHARE = 0.25f

    private val DIGITS = Regex("[0-9٠-٩]")
    private val DIGIT_RUN = Regex("[0-9٠-٩]+")

    /**
     * The document's lines, split into the text and the furniture around
     * it, with the furniture already made into blocks.
     */
    class Split(
        /** The lines that are text of the document. */
        val body: List<PdfLine>,
        val header: List<Block> = emptyList(),
        val footer: List<Block> = emptyList(),
        /** Where the head sits below the top edge, and the foot above the bottom, in points. */
        val headerDistancePt: Float? = null,
        val footerDistancePt: Float? = null,
        /** The number the document's first page carries, when its pages number themselves. */
        val firstPageNumber: Int? = null,
    )

    /** [lines] with the pages' furniture taken out of the text and made into blocks. */
    fun of(lines: List<PdfLine>, sheets: List<PdfPageSheet>): Split {
        val heightByPage = sheets.associate { it.page to it.heightPt }
        val widthByPage = sheets.associate { it.page to it.widthPt }
        if (lines.map { it.page }.distinct().size < REPEATS_TO_BE_RUNNING) return Split(lines)

        fun height(line: PdfLine) = heightByPage[line.page]?.takeIf { it > 0f }
        fun inMargin(line: PdfLine): Boolean {
            val height = height(line) ?: return false
            return line.baselineY < MARGIN_BAND_SHARE * height ||
                line.baselineY > (1f - MARGIN_BAND_SHARE) * height
        }

        // Page numbers differ from page to page, so lines are compared with
        // their digits masked; what repeats in the same place on enough
        // pages is furniture.
        val running = lines.filter(::inMargin)
            .groupBy { DIGITS.replace(it.text, "#") to (it.baselineY / SAME_PLACE_PT).toInt() }
            .filterValues { group -> group.map { it.page }.distinct().size >= REPEATS_TO_BE_RUNNING }
            .values.flatten()
            .toCollection(java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))
        if (running.isEmpty()) return Split(lines)
        val body = lines.filterNot { it in running }
        if (body.isEmpty()) return Split(lines)

        // One page's furniture stands for every page's. The first page is
        // used when it carries any, since that is the page a reader opens.
        val byPage = running.groupBy { it.page }
        val reference = if (byPage.containsKey(1)) 1 else byPage.keys.min()
        val height = heightByPage[reference]?.takeIf { it > 0f } ?: return Split(body)
        val width = widthByPage[reference]?.takeIf { it > 0f } ?: return Split(body)
        val counted = pageNumber(running)

        fun side(atTop: Boolean): Pair<List<Block>, Float?> {
            val own = byPage.getValue(reference)
                .filter { (it.baselineY < height / 2) == atTop }
                .sortedBy { it.baselineY }
            if (own.isEmpty()) return emptyList<Block>() to null
            val distance = if (atTop) {
                own.minOf { it.baselineY - ASCENT_SHARE * it.maxFontSize }
            } else {
                height - own.maxOf { it.baselineY + DESCENT_SHARE * it.maxFontSize }
            }
            return own.map { line(it, width, counted) } to distance.coerceAtLeast(0f)
        }

        val (header, headerDistance) = side(atTop = true)
        val (footer, footerDistance) = side(atTop = false)
        return Split(
            body = body,
            header = header,
            footer = footer,
            headerDistancePt = headerDistance,
            footerDistancePt = footerDistance,
            firstPageNumber = counted?.let { it.offset + 1 },
        )
    }

    /**
     * A number the pages count with: the same digits, in the same place on
     * every page, whose value advances by one from page to page. What is
     * kept is the step, since the page it was read from is not page one.
     */
    private class Counted(val offset: Int)

    private fun pageNumber(running: Set<PdfLine>): Counted? {
        // A page's number may be written anywhere in its furniture, so
        // every number on every furniture line is a candidate, and the one
        // that keeps step with the pages is the number.
        val offsets = running
            .flatMap { line -> DIGIT_RUN.findAll(line.text).mapNotNull { value(it.value) }.map { it - line.page } }
            .groupingBy { it }
            .eachCount()
        val (offset, seen) = offsets.maxByOrNull { it.value } ?: return null
        if (seen < REPEATS_TO_BE_RUNNING) return null
        return Counted(offset)
    }

    /** [text] as a number, whichever digits it is written in. */
    private fun value(text: String): Int? =
        text.map { if (it in '٠'..'٩') it - '٠' + '0'.code else it.code }
            .joinToString("") { it.toChar().toString() }
            .toIntOrNull()

    /**
     * One line of furniture as a paragraph, with the number that counts
     * the pages written as a field: a page has only the digits it showed,
     * and a document numbered from 48 must go on numbering itself.
     */
    private fun line(line: PdfLine, pageWidth: Float, counted: Counted?): Paragraph {
        val runs = PdfRuns.toTextRuns(line.runs).ifEmpty { listOf(TextRun(line.text)) }
        val centre = (line.x + line.xEnd) / 2
        val alignment = if (pageWidth > 0f && kotlin.math.abs(centre - pageWidth / 2) < CENTRE_SHARE * pageWidth) {
            Alignment.CENTER
        } else {
            null
        }
        return Paragraph(
            runs = counted?.let { numbered(runs, it, line.page) } ?: runs,
            style = ParagraphStyle(alignment = alignment, spaceBeforePt = 0f, spaceAfterPt = 0f),
        )
    }

    /** [runs] with the digits that count the pages replaced by a field. */
    private fun numbered(runs: List<TextRun>, counted: Counted, page: Int): List<TextRun> {
        val wanted = (page + counted.offset).toString()
        val out = mutableListOf<TextRun>()
        var done = false
        for (run in runs) {
            val at = if (done) -1 else placeOf(run.text, wanted)
            if (at < 0) {
                out += run
                continue
            }
            done = true
            val found = DIGIT_RUN.findAll(run.text).first { value(it.value) == page + counted.offset }
            if (found.range.first > 0) out += run.copy(text = run.text.substring(0, found.range.first))
            out += run.copy(text = wanted, field = RunField.PAGE_NUMBER)
            if (found.range.last + 1 < run.text.length) {
                out += run.copy(text = run.text.substring(found.range.last + 1))
            }
        }
        return out
    }

    /** Where in [text] the whole number [wanted] is written, or -1 when it is not. */
    private fun placeOf(text: String, wanted: String): Int =
        DIGIT_RUN.findAll(text).firstOrNull { value(it.value)?.toString() == wanted }?.range?.first ?: -1
}
