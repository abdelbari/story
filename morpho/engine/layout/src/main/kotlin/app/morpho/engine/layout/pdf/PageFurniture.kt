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
        /** The pictures that are the document's, the page's own left out. */
        val bodyImages: List<PdfImage> = emptyList(),
        val header: List<Block> = emptyList(),
        val footer: List<Block> = emptyList(),
        /** What the left-hand pages repeat, where they repeat something else. */
        val evenHeader: List<Block> = emptyList(),
        val evenFooter: List<Block> = emptyList(),
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
     * [wordBoxes] is where on the page those words were drawn. Painted out
     * of the band, they say whether they were all of it: a head that comes
     * back blank is an ordinary head, and giving a picture of one in place
     * of the words it says hands the reader a header that cannot be
     * edited, searched, or reflowed onto a page of another size. A head
     * with anything left over — a logo, a banner, the letters of a word
     * drawn as outlines rather than set in type — is a head only a picture
     * accounts for. Empty asks for the picture, as before.
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
        wordBoxes: List<FloatArray> = emptyList(),
        ruleAbove: Boolean = false,
        ruleBelow: Boolean = false,
    ): List<Block> {
        // A line the page drew beside its furniture is drawn again as the
        // paragraph's own border — but only where the words are given
        // instead of a picture. In a picture the line is already there,
        // and drawing it a second time would double it.
        val plain = ParagraphStyle(
            direction = if (rtl) TextDirection.RTL else TextDirection.LTR,
            spaceBeforePt = 0f,
            spaceAfterPt = 0f,
            ruleAbove = ruleAbove,
            ruleBelow = ruleBelow,
        )
        val said = words.isNotEmpty() && wordBoxes.isNotEmpty() &&
            crop.of(page, box[0], box[1], box[2], box[3], wordBoxes, true) == null
        if (number == null) {
            if (said) return listOf(Paragraph(words, plain))
            val picture = crop.of(page, box[0], box[1], box[2], box[3], emptyList(), false)?.image
            if (picture != null) return listOf(described(picture, words))
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
            if (said) return listOf(Paragraph(words, plain), centred)
            val picture = crop.of(page, box[0], box[1], box[2], box[3], listOf(numberBox), false)
                ?: return if (words.isEmpty()) listOf(centred) else listOf(Paragraph(words, plain), centred)
            return listOf(described(picture.image, words), centred)
        }
        // The number at one end: the rest of the furniture as a picture in
        // the line, a tab to where the number sat, and the field — all on
        // the one line, as on the page.
        val cropLeft = if (atLeft) numberBox[2] + FURNITURE_GAP_PT else box[0]
        val cropRight = if (atRight) numberBox[0] - FURNITURE_GAP_PT else box[2]
        // The head beside the number: its picture, or its words where the
        // page would not draw.
        val beside = if (said) {
            words
        } else {
            crop.of(page, cropLeft, box[1], cropRight, box[3], emptyList(), false)
                ?.let { listOf(TextRun("", image = described(it.image, words))) }
                ?: words
        }
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
        val shown = runs.any { it.image != null }
        return listOf(
            Paragraph(
                runs,
                ParagraphStyle(
                    direction = if (rtl) TextDirection.RTL else TextDirection.LTR,
                    startIndentPt = startIndent.takeIf { it > 0.5f },
                    tabStopsPt = listOf(stop).filter { it > 0f },
                    spaceBeforePt = 0f,
                    spaceAfterPt = 0f,
                    ruleAbove = ruleAbove && !shown,
                    ruleBelow = ruleBelow && !shown,
                ),
            )
        )
    }

    /**
     * [picture] told what it shows, from the words the band held.
     *
     * A band is photographed when its ink is not all accounted for by
     * what was read there — a logo beside the running head, a mark the
     * reading has no name for. The words it did hold are then nowhere in
     * the document at all: not searchable, not read aloud, gone. Saying
     * them on the picture is where a reader looks for them.
     *
     * Only where they are words. A band whose text is drawn as outlines
     * gives up its digits and nothing else — the paper this was built for
     * yields "58 48 2022 01 05" from a foot that reads "The Journal of…,
     * volume 05, number 01, June 2022, pp. 48-58". Read aloud that is
     * noise, and noise offered as a description is worse than none: it
     * tells a reader the picture has been accounted for when it has not.
     */
    private fun described(picture: ImageBlock, words: List<TextRun>): ImageBlock {
        val said = words.joinToString(separator = "") { it.text }.trim().takeIf { it.isNotEmpty() }
            ?: return picture
        if (said.none { it.isLetter() }) return picture
        return picture.copy(description = said)
    }

    /** Clear space left around a rule when the band it belongs to is photographed. */
    private const val RULE_MARGIN_PT = 2f

    /**
     * How far past a line the ink it drew may reach: this share of the type
     * size, plus this much again in points. A letter overshoots the
     * estimate its type size gives — an accent, a hamza, the tail of a jim
     * — and a page drawn at three times its own resolution softens every
     * edge by a pixel or two.
     */
    private const val EXPLAINED_SHARE = 0.3f
    private const val EXPLAINED_PAD_PT = 2f

    /**
     * [left]..[right] by [top]..[bottom] grown by as far past it as ink set
     * at [fontSizePt] reaches — what a band is painted with to find out
     * whether anything but the words it was read as was drawn there.
     *
     * Both readers ask for these, so both grow a box by the same amount:
     * a head kept as words by one and photographed by the other would be
     * the same document converted two ways.
     */
    fun mask(left: Float, top: Float, right: Float, bottom: Float, fontSizePt: Float): FloatArray {
        val pad = fontSizePt * EXPLAINED_SHARE + EXPLAINED_PAD_PT
        return floatArrayOf(left - EXPLAINED_PAD_PT, top - pad, right + EXPLAINED_PAD_PT, bottom + pad)
    }

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
        images: List<PdfImage> = emptyList(),
    ): Split {
        val heightByPage = sheets.associate { it.page to it.heightPt }
        val widthByPage = sheets.associate { it.page to it.widthPt }
        if (lines.map { it.page }.distinct().size < REPEATS_TO_BE_RUNNING) return Split(lines, images)

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
        // The same picture drawn in the same margin of page after page is
        // the page's, not the document's: a letterhead, a logo, a banner.
        // Taken for the text it is dropped into the reading once for every
        // page of a report, between paragraphs and in the middle of
        // sentences, wherever on the page it happened to be drawn.
        val pictured = images
            .filter { picture ->
                val height = heightByPage[picture.page]?.takeIf { it > 0f } ?: return@filter false
                picture.topY < MARGIN_BAND_SHARE * height ||
                    picture.topY > (1f - MARGIN_BAND_SHARE) * height
            }
            .groupBy { Triple(it.bytes.size, it.mimeType, (it.topY / SAME_PLACE_PT).toInt()) }
            .filterValues { group -> group.map { it.page }.distinct().size >= REPEATS_TO_BE_RUNNING }
            .values.flatten()
            .toCollection(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PdfImage, Boolean>()))
        val bodyImages = images.filterNot { it in pictured }
        if (running.isEmpty() && ruled.isEmpty() && pictured.isEmpty() && crop == null) {
            return Split(lines, images)
        }
        val body = lines.filterNot { it in running }
        if (body.isEmpty() && running.isNotEmpty()) return Split(lines, images)

        // One page's furniture stands for every page's. The first page is
        // used when it carries any, since that is the page a reader opens.
        // The pages that were seen to carry furniture. A picture counts:
        // a letterhead is furniture, and a reference page that has one is
        // a better page to stand for the rest than one that does not.
        val furnished = (running.map { it.page } + ruled.map { it.page } + pictured.map { it.page })
            .toSortedSet()
        val readPages = body.map { it.page }.distinct().sorted()

        // A printed book puts the title of the book on one side of the
        // opening and the title of the chapter on the other. Both repeat,
        // so both are furniture and both leave the text — and a reader that
        // keeps one page's worth keeps one of them and loses the other
        // outright, then prints the survivor on every page.
        val byPageAll = running.groupBy { it.page }
        fun repeatedOn(page: Int, atTop: Boolean): String {
            val half = (heightByPage[page]?.takeIf { it > 0f } ?: return "") / 2
            val across = widthByPage[page]?.takeIf { it > 0f } ?: return ""
            // What it says and which side of the page it says it on. A
            // book numbers its pages at the outer edge, so its two feet
            // read alike and sit at opposite ends — the side is the whole
            // of the difference between them. Which third rather than
            // which point: a foot that reaches 100 is a digit wider than
            // one that reaches 99, and that is not a different foot.
            return byPageAll[page].orEmpty()
                .filter { (it.baselineY < half) == atTop }
                .sortedBy { it.baselineY }
                .joinToString("\n") { line ->
                    val centre = (line.x + line.xEnd) / 2
                    val where = when {
                        centre < across / 3 -> "start"
                        centre > across * 2 / 3 -> "end"
                        else -> "middle"
                    }
                    DIGITS.replace(line.text, "#") + "@" + where
                }
        }
        val onTheRight = furnished.firstOrNull { it % 2 == 1 }
        val onTheLeft = furnished.firstOrNull { it % 2 == 0 }
        // Each end of the page asked separately: a book whose two sides
        // are headed alike but footed differently needs one head and two
        // feet, and a second head identical to the first is a part of the
        // file that says nothing.
        fun differs(atTop: Boolean): Boolean =
            onTheRight != null && onTheLeft != null &&
                repeatedOn(onTheRight, atTop).isNotEmpty() &&
                repeatedOn(onTheRight, atTop) != repeatedOn(onTheLeft, atTop)
        val mirroredHead = differs(atTop = true)
        val mirroredFoot = differs(atTop = false)
        val mirrored = mirroredHead || mirroredFoot

        val reference = when {
            mirrored -> onTheRight!!
            1 in furnished -> 1
            furnished.isNotEmpty() -> furnished.first()
            else -> readPages.firstOrNull() ?: return Split(body, bodyImages)
        }
        val height = heightByPage[reference]?.takeIf { it > 0f } ?: return Split(body, bodyImages)
        val width = widthByPage[reference]?.takeIf { it > 0f } ?: return Split(body, bodyImages)
        val counted = pageNumber(running)
        val byPage = running.groupBy { it.page }
        val rtl = Bidi.dominantDirection(body.joinToString(" ") { it.text }) == TextDirection.RTL

        /** Where the page's own text begins on [page], which no band may reach. */
        fun textEdge(page: Int, atTop: Boolean): Float? {
            val onPage = body.filter { it.page == page }
            return if (atTop) {
                onPage.minOfOrNull { it.baselineY - ASCENT_SHARE * it.maxFontSize }
            } else {
                onPage.maxOfOrNull { it.baselineY + DESCENT_SHARE * it.maxFontSize }
            }
        }

        fun side(atTop: Boolean, page: Int = reference): Pair<List<Block>, Float?> {
            val own = byPage[page].orEmpty()
                .filter { (it.baselineY < height / 2) == atTop }
                .sortedBy { it.baselineY }
            val ownRules = ruled.filter { it.page == page && (it.y < height / 2) == atTop }
            if (crop != null && ownRules.isNotEmpty()) {
                // The band stops where the page's own text starts. A rule
                // in the margin is usually a head's; a page ruled all round
                // draws one there too, and photographing past it would put
                // the first line of the page in the header and leave it in
                // the body as well.
                photographed(
                    crop, page, own, ownRules, atTop, width, height,
                    textEdge(page, atTop), counted, rtl,
                )?.let { return it }
            }
            if (own.isNotEmpty()) {
                val distance = if (atTop) {
                    own.minOf { it.baselineY - ASCENT_SHARE * it.maxFontSize }
                } else {
                    height - own.maxOf { it.baselineY + DESCENT_SHARE * it.maxFontSize }
                }
                return ruled(own.map { line(it, width, counted) }, ownRules, own) to
                    distance.coerceAtLeast(0f)
            }
            // Nothing in the margin that could be read, and no rule drawn
            // there either. The page itself is the last thing left to ask.
            if (crop != null) {
                val ownPictures = pictured.filter {
                    it.page == page && (it.topY < height / 2) == atTop
                }
                val stopAt = textEdge(page, atTop)
                if (ownPictures.isNotEmpty()) {
                    // Already proved furniture by repeating, so the band
                    // is photographed as it stands with no second page
                    // asked for. A head that carries a page number does not
                    // match itself from page to page, and comparing here
                    // would drop it from the head after already having
                    // taken it out of the text.
                    band(crop, page, atTop, width, height, stopAt)
                        ?.let { return listOf<Block>(it.image) to distanceOf(it, atTop, height) }
                    // The page would not draw. The picture itself is still
                    // the honest answer, even without the place it sat in.
                    return listOf<Block>(
                        ImageBlock(
                            bytes = ownPictures.first().bytes,
                            mimeType = ownPictures.first().mimeType,
                            widthPx = ownPictures.first().widthPx,
                            heightPx = ownPictures.first().heightPx,
                        )
                    ) to null
                }
                val other = readPages.firstOrNull { it != page } ?: return emptyList<Block>() to null
                repeated(
                    crop, page, other, atTop, width, height,
                    minOfNotNull(stopAt, textEdge(other, atTop), atTop),
                )?.let { return it }
            }
            return emptyList<Block>() to null
        }

        val (header, headerDistance) = side(atTop = true)
        val (footer, footerDistance) = side(atTop = false)
        // The left-hand pages' own, read from a left-hand page, and only
        // where the two sides really do repeat something different.
        val (evenHeader, evenHeaderDistance) =
            if (mirroredHead) side(true, onTheLeft!!) else emptyList<Block>() to null
        val (evenFooter, evenFooterDistance) =
            if (mirroredFoot) side(false, onTheLeft!!) else emptyList<Block>() to null
        return Split(
            body = body,
            bodyImages = bodyImages,
            header = header,
            footer = footer,
            evenHeader = evenHeader,
            evenFooter = evenFooter,
            headerDistancePt = headerDistance ?: evenHeaderDistance,
            footerDistancePt = footerDistance ?: evenFooterDistance,
            firstPageNumber = counted?.let { it.offset + 1 },
            // A title page carries no running head, and the reference page
            // is the second one for exactly that reason. Stamping the head
            // it found onto page one would put it on the one page of the
            // document the original deliberately left clear.
            //
            // Only where the pages were compared and page one was found
            // bare, though. A head recovered by photographing a page says
            // nothing whatever about what page one carries, and claiming a
            // title page on that would blank a head the paper printed.
            differentFirstPage = furnished.isNotEmpty() && 1 !in furnished &&
                (header.isNotEmpty() || footer.isNotEmpty()),
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
        // A band the words already account for wants no picture of itself.
        // Most running heads with a rule under them are perfectly ordinary
        // text — a journal's title, a book's chapter — and photographing
        // one hands back a header that cannot be edited, searched, or
        // reflowed onto a page of another size, in place of the words it
        // says. The rule is kept as a rule, which is what it is.
        if (explains(crop, page, own, ownRules, left, top, right, bottom)) return null
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
     * Whether the lines read in a band account for every mark the page
     * drew there: the band trimmed to its ink sits within what the words
     * and the rule beside them occupy.
     *
     * A head no reader can read fails this at once — there are no words to
     * do the accounting — and so does a head whose words are only part of
     * what was drawn, which is the case that matters: the paper whose
     * footer sets its page number in type and the rest of its line in
     * outlines has ink either side of the digits, and only a picture of
     * the band says what it says.
     */
    private fun explains(
        crop: Crop,
        page: Int,
        own: List<PdfLine>,
        rules: List<PdfRule>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        if (own.isEmpty()) return false
        // Painted over where every line and every rule was read, the band
        // is blank if they were all of it. A box around them will not do:
        // a footer that sets its page number at one edge and its date at
        // the other spans the whole width between them, and the words
        // drawn as outlines in the middle sit inside that box while being
        // exactly what a picture is needed for.
        val masks = own.map {
            mask(
                it.x,
                it.baselineY - ASCENT_SHARE * it.maxFontSize,
                it.xEnd,
                it.baselineY + DESCENT_SHARE * it.maxFontSize,
                it.maxFontSize,
            )
        } + rules.map { mask(it.left, it.y, it.right, it.y, 0f) }
        return crop.of(page, left, top, right, bottom, masks, true) == null
    }

    /**
     * [blocks] carrying the rule the page drew between the furniture and
     * its text, where it drew one on that side. A running head with a line
     * under it keeps the line, drawn as a border of the paragraph rather
     * than printed into a picture of the words.
     */
    private fun ruled(
        blocks: List<Paragraph>,
        rules: List<PdfRule>,
        own: List<PdfLine>,
    ): List<Block> {
        if (blocks.isEmpty() || rules.isEmpty()) return blocks
        // Which side of the words the page drew its line on, not which end
        // of the page they sit at: a book rules under its running head and
        // over its foot, and a page that does the other thing is drawn the
        // way it was drawn.
        val above = own.minOf { it.baselineY - ASCENT_SHARE * it.maxFontSize }
        val below = own.maxOf { it.baselineY }
        var over = false
        var under = false
        for (rule in rules) {
            if (rule.y < above) over = true
            if (rule.y > below) under = true
        }
        if (!over && !under) return blocks
        var out = blocks
        if (over) {
            out = listOf(out.first().let { it.copy(style = it.style.copy(ruleAbove = true)) }) +
                out.drop(1)
        }
        if (under) {
            out = out.dropLast(1) +
                out.last().let { it.copy(style = it.style.copy(ruleBelow = true)) }
        }
        return out
    }

    /** The narrower of two edges, or whichever of them there is. */
    private fun minOfNotNull(one: Float?, two: Float?, atTop: Boolean): Float? = when {
        one == null -> two
        two == null -> one
        atTop -> minOf(one, two)
        else -> maxOf(one, two)
    }

    /**
     * One side's furniture from the page itself, for a margin that holds
     * something no reader could see: a head drawn as artwork, a banner, a
     * logo, words in a font the file will not name and that PDFBox drops
     * before a stripper is ever shown them. There is no line to find and
     * no rule to go by — the margin is simply not empty, and nothing in
     * the file says so.
     *
     * What settles it is that a running head is the same drawing on every
     * page. The margin is photographed on two pages and the pictures
     * compared: identical ink in the identical place is furniture, and
     * anything else — a figure that happens to sit high on one page, a
     * blank margin, a head that numbers itself and so differs — is left
     * alone. That is a strict test, and deliberately so: it can only fail
     * to find a head, never invent one.
     *
     * Both bands stop short of either page's own text, so nothing that
     * belongs to the document can be caught in one.
     */
    private fun repeated(
        crop: Crop,
        page: Int,
        other: Int,
        atTop: Boolean,
        pageWidth: Float,
        pageHeight: Float,
        stopAt: Float?,
    ): Pair<List<Block>, Float?>? {
        val here = band(crop, page, atTop, pageWidth, pageHeight, stopAt) ?: return null
        val there = band(crop, other, atTop, pageWidth, pageHeight, stopAt) ?: return null
        val same = here.left == there.left && here.top == there.top &&
            here.right == there.right && here.bottom == there.bottom &&
            here.image.bytes.contentEquals(there.image.bytes)
        if (!same) return null
        return listOf<Block>(here.image) to distanceOf(here, atTop, pageHeight)
    }

    /**
     * One page's margin, photographed and trimmed to whatever it holds.
     *
     * Only the margin is looked at, however far down the text begins: a
     * page whose words start halfway down has a wide top margin, not a
     * head half a page tall.
     */
    private fun band(
        crop: Crop,
        page: Int,
        atTop: Boolean,
        pageWidth: Float,
        pageHeight: Float,
        stopAt: Float?,
    ): Cropped? {
        val edge = MARGIN_BAND_SHARE * pageHeight
        val top = if (atTop) 0f else maxOf(pageHeight - edge, stopAt ?: 0f)
        val bottom = if (atTop) minOf(edge, stopAt ?: edge) else pageHeight
        if (bottom - top < 1f) return null
        return crop.of(page, 0f, top, pageWidth, bottom, emptyList(), true)
    }

    /** How far a photographed band sits from the edge it belongs to. */
    private fun distanceOf(band: Cropped, atTop: Boolean, pageHeight: Float): Float =
        (if (atTop) band.top else pageHeight - band.bottom).coerceAtLeast(0f)

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
