package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.TextDirection
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
        /** The first page carried none of it: a title page, left clear. */
        val differentFirstPage: Boolean = false,
    )

    /**
     * How a reader that holds the pages makes a picture of part of one.
     *
     * These heuristics never see the PDF — they work on the lines and the
     * rules a reader hands them — so the reader that does hands them this
     * as well.
     */
    fun interface Crop {
        /**
         * The region of [page] between [left]..[right] and [top]..[bottom],
         * in top-down page points, as a picture, with [masks] painted out.
         * Null where there is nothing to crop, the page could not be drawn,
         * or the region came back blank — a page that drew nothing there is
         * a page that has no picture to give.
         *
         * With [trim], the blank around the ink is taken off and the result
         * says where what is left actually sits. A band asked for generously
         * — everything above the rule under a running head, since nothing in
         * the file says where its words begin — comes back as the words.
         */
        fun of(
            page: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            masks: List<FloatArray>,
            trim: Boolean,
        ): Cropped?
    }

    /** A picture of part of a page, and the part of the page it turned out to cover. */
    class Cropped(
        val image: ImageBlock,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /** A page's own number where it stands in the furniture: what to write, and where it stood. */
    class Numbered(val field: TextRun, val box: FloatArray)

    /** Clear space kept between a page number and the picture of the rest of its line. */
    private const val FURNITURE_GAP_PT = 4f

    /**
     * A page's furniture as the page itself drew it: a picture of the band
     * it occupies, with the page's number masked out of that picture and
     * written as a field where the number stood, so every page goes on
     * numbering itself.
     *
     * [box] is the band in top-down page points; [left] and [right] are the
     * edges the text is set between, which is what a tab stop and an indent
     * are measured from.
     *
     * [words] is what the head says, for a page that will not draw. A phone
     * that runs out of room to render one, or a renderer that cannot draw
     * the font the head is set in, must not answer with no head at all:
     * whatever of it could be read is worth more to a reader than a header
     * that vanished without explanation. Empty only when there is neither a
     * picture nor a word to show.
     *
     * Both readers ask this, because a head is a head whether the producer
     * marked it as one or the pages were compared to find it, and a walk
     * written twice is a walk that goes wrong once.
     */
    fun drawn(
        crop: Crop,
        page: Int,
        box: FloatArray,
        pageWidth: Float,
        left: Float,
        right: Float,
        number: Numbered?,
        rtl: Boolean,
        words: List<TextRun> = emptyList(),
    ): List<Block> {
        val plain = ParagraphStyle(
            direction = if (rtl) TextDirection.RTL else TextDirection.LTR,
            spaceBeforePt = 0f,
            spaceAfterPt = 0f,
        )
        if (number == null) {
            val picture = crop.of(page, box[0], box[1], box[2], box[3], emptyList(), false)?.image
            if (picture != null) return listOf(picture)
            return if (words.isEmpty()) emptyList() else listOf(Paragraph(words, plain))
        }
        val numberBox = number.box
        val centre = (numberBox[0] + numberBox[2]) / 2
        val atLeft = centre < pageWidth / 3
        val atRight = centre > pageWidth * 2 / 3
        if (!atLeft && !atRight) {
            // A number in the middle: the picture with the number masked,
            // and the field on a line of its own beneath.
            val centred = Paragraph(
                listOf(number.field),
                ParagraphStyle(alignment = Alignment.CENTER, spaceBeforePt = 0f, spaceAfterPt = 0f),
            )
            val picture = crop.of(page, box[0], box[1], box[2], box[3], listOf(numberBox), false)
                ?: return if (words.isEmpty()) listOf(centred) else listOf(Paragraph(words, plain), centred)
            return listOf(picture.image, centred)
        }
        // The number at one end: the rest of the furniture as a picture in
        // the line, a tab to where the number sat, and the field — all on
        // the one line, as on the page.
        val cropLeft = if (atLeft) numberBox[2] + FURNITURE_GAP_PT else box[0]
        val cropRight = if (atRight) numberBox[0] - FURNITURE_GAP_PT else box[2]
        // The head beside the number: its picture, or its words where the
        // page would not draw.
        val beside = crop.of(page, cropLeft, box[1], cropRight, box[3], emptyList(), false)
            ?.let { listOf(TextRun("", image = it.image)) }
            ?: words
        val numberFirst = if (rtl) atRight else atLeft
        val stop = when {
            numberFirst -> if (rtl) right - cropRight else cropLeft - left
            rtl -> right - numberBox[2]
            else -> numberBox[0] - left
        }
        val runs = if (numberFirst) {
            listOf(number.field, TextRun("\t")) + beside
        } else {
            beside + listOf(TextRun("\t"), number.field)
        }
        val startIndent = if (numberFirst) 0f else if (rtl) right - cropRight else cropLeft - left
        return listOf(
            Paragraph(
                runs,
                ParagraphStyle(
                    direction = if (rtl) TextDirection.RTL else TextDirection.LTR,
                    startIndentPt = startIndent.takeIf { it > 0.5f },
                    tabStopsPt = listOf(stop).filter { it > 0f },
                    spaceBeforePt = 0f,
                    spaceAfterPt = 0f,
                ),
            )
        )
    }

    /** Clear space left around a rule when the band it belongs to is photographed. */
    private const val RULE_MARGIN_PT = 2f

    /** Rules this far apart, in the same place across pages, are the same rule. */
    private const val SAME_RULE_PT = 1.5f

    /**
     * [lines] with the pages' furniture taken out of the text and made into
     * blocks.
     *
     * [rules] and [crop] are what a head no reader can read is recovered
     * with. A running head set in a font the file does not name paints
     * letters that a person reads and that PDFBox never reports: there is
     * no line there to find, and a converter working from lines alone drops
     * the head without ever knowing it existed. What such a head does leave
     * behind is the rule drawn beside it, in the same place on page after
     * page — and where that repeats, the page itself is photographed, which
     * is the only honest account of what the head says.
     */
    fun of(
        lines: List<PdfLine>,
        sheets: List<PdfPageSheet>,
        rules: List<PdfRule> = emptyList(),
        crop: Crop? = null,
    ): Split {
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

        // A rule drawn in the same place in the margin of page after page
        // is furniture too, and it is all that is left of a head whose
        // words the file will not name.
        val ruled = if (crop == null) emptyList() else rules
            .filter { rule ->
                val height = heightByPage[rule.page]?.takeIf { it > 0f } ?: return@filter false
                rule.y < MARGIN_BAND_SHARE * height || rule.y > (1f - MARGIN_BAND_SHARE) * height
            }
            .groupBy { (it.y / SAME_RULE_PT).toInt() }
            .filterValues { group -> group.map { it.page }.distinct().size >= REPEATS_TO_BE_RUNNING }
            .values.flatten()
        if (running.isEmpty() && ruled.isEmpty()) return Split(lines)
        val body = lines.filterNot { it in running }
        if (body.isEmpty() && running.isNotEmpty()) return Split(lines)

        // One page's furniture stands for every page's. The first page is
        // used when it carries any, since that is the page a reader opens.
        val furnished = (running.map { it.page } + ruled.map { it.page }).toSortedSet()
        val reference = if (1 in furnished) 1 else furnished.firstOrNull() ?: return Split(body)
        val height = heightByPage[reference]?.takeIf { it > 0f } ?: return Split(body)
        val width = widthByPage[reference]?.takeIf { it > 0f } ?: return Split(body)
        val counted = pageNumber(running)
        val byPage = running.groupBy { it.page }

        fun side(atTop: Boolean): Pair<List<Block>, Float?> {
            val own = byPage[reference].orEmpty()
                .filter { (it.baselineY < height / 2) == atTop }
                .sortedBy { it.baselineY }
            val ownRules = ruled.filter { it.page == reference && (it.y < height / 2) == atTop }
            if (own.isEmpty() && ownRules.isEmpty()) return emptyList<Block>() to null
            if (crop != null && ownRules.isNotEmpty()) {
                val rtl = Bidi.dominantDirection(body.joinToString(" ") { it.text }) == TextDirection.RTL
                // The band stops where the page's own text starts. A rule
                // in the margin is usually a head's; a page ruled all round
                // draws one there too, and photographing past it would put
                // the first line of the page in the header and leave it in
                // the body as well.
                val onPage = body.filter { it.page == reference }
                val stopAt = if (atTop) {
                    onPage.minOfOrNull { it.baselineY - ASCENT_SHARE * it.maxFontSize }
                } else {
                    onPage.maxOfOrNull { it.baselineY + DESCENT_SHARE * it.maxFontSize }
                }
                photographed(crop, reference, own, ownRules, atTop, width, height, stopAt, counted, rtl)
                    ?.let { return it }
            }
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
            // A title page carries no running head, and the reference page
            // is the second one for exactly that reason. Stamping the head
            // it found onto page one would put it on the one page of the
            // document the original deliberately left clear.
            differentFirstPage = 1 !in furnished && (header.isNotEmpty() || footer.isNotEmpty()),
        )
    }

    /**
     * One side's furniture as a photograph of the page, for a band that
     * carries a rule: the rule itself, the words beside it whether or not
     * they could be read, and nothing of the page's text.
     *
     * The band is asked for generously — from the page's edge to just past
     * the rule — because nothing in the file says where an unreadable head
     * begins, and it comes back trimmed to the ink, which does. Where the
     * page's own number sits in the band it is masked out and written as a
     * field, so the converted document goes on numbering itself.
     *
     * Null when the page could not be drawn, and the caller falls back to
     * whatever text it did manage to read.
     */
    private fun photographed(
        crop: Crop,
        page: Int,
        own: List<PdfLine>,
        ownRules: List<PdfRule>,
        atTop: Boolean,
        pageWidth: Float,
        pageHeight: Float,
        /** Where the page's own text begins, which the band may not reach. */
        stopAt: Float?,
        counted: Counted?,
        rtl: Boolean,
    ): Pair<List<Block>, Float?>? {
        val left = minOf(
            ownRules.minOf { it.left },
            own.minOfOrNull { it.x } ?: Float.MAX_VALUE,
        )
        val right = maxOf(
            ownRules.maxOf { it.right },
            own.maxOfOrNull { it.xEnd } ?: 0f,
        )
        val top = if (atTop) {
            0f
        } else {
            minOf(
                ownRules.minOf { it.y } - RULE_MARGIN_PT,
                own.minOfOrNull { it.baselineY - ASCENT_SHARE * it.maxFontSize } ?: Float.MAX_VALUE,
            ).coerceAtLeast(stopAt ?: 0f)
        }
        val bottom = if (atTop) {
            maxOf(
                ownRules.maxOf { it.y } + RULE_MARGIN_PT,
                own.maxOfOrNull { it.baselineY + DESCENT_SHARE * it.maxFontSize } ?: 0f,
            ).coerceAtMost(stopAt ?: pageHeight)
        } else {
            pageHeight
        }
        if (bottom - top < 1f) return null
        val number = numberIn(own, counted, page)
        // The generous band, trimmed: what comes back is where the ink is,
        // which is what the band should have been asked for and what the
        // rest of the work is measured against.
        val band = crop.of(page, left, top, right, bottom, listOfNotNull(number?.box), true)
            ?: return null
        val distance = if (atTop) band.top else pageHeight - band.bottom
        // With no number in it the trimmed band is already the picture. With
        // one, the band is cut again either side of where the number sat, so
        // the number can be written as a field beside it rather than printed
        // into the picture as the digits one page happened to show.
        val blocks = if (number == null) {
            listOf(band.image)
        } else {
            drawn(
                crop = crop,
                page = page,
                box = floatArrayOf(band.left, band.top, band.right, band.bottom),
                pageWidth = pageWidth,
                left = band.left,
                right = band.right,
                number = number,
                rtl = rtl,
            )
        }
        return if (blocks.isEmpty()) null else blocks to distance.coerceAtLeast(0f)
    }

    /**
     * The page's own number where it is written in [own], as the field to
     * write in its place and the space to paint out of the photograph.
     *
     * The digits are found by their extent rather than by what they say:
     * the number is the one chunk of a running line that is not the same on
     * every page, and a chunk of an unreadable line says nothing reliable
     * about which digits it holds even when it does report some.
     */
    private fun numberIn(own: List<PdfLine>, counted: Counted?, page: Int): Numbered? {
        if (counted == null) return null
        val wanted = page + counted.offset
        for (line in own) {
            val segment = line.segments.firstOrNull { value(it.text) == wanted }
                ?: line.segments.firstOrNull { DIGIT_RUN.matches(it.text) && it.text.length == wanted.toString().length }
                ?: continue
            return Numbered(
                field = TextRun(wanted.toString(), field = RunField.PAGE_NUMBER),
                box = floatArrayOf(
                    segment.xStart,
                    line.baselineY - ASCENT_SHARE * line.maxFontSize,
                    segment.xEnd,
                    line.baselineY + DESCENT_SHARE * line.maxFontSize,
                ),
            )
        }
        return null
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
