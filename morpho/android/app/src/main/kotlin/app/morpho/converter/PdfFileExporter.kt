package app.morpho.converter

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TabStopSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Direct-to-file PDF export (M2): renders a [DocumentModel] into real .pdf
 * bytes with the platform's own text stack — [StaticLayout] shapes and
 * reorders through minikin, so Arabic ligatures, harakat and BiDi come out
 * right with the system Noto fonts, no WebView and no print dialog. The
 * print pipeline ([PdfPrintLauncher]) stays alongside for paper printing.
 *
 * Layout model: the document's own page and margins where a reader
 * measured them and A4 with 48pt margins where it did not; each run set in
 * the face, size and weight it carries, raised or lowered where it is a
 * super- or subscript; paragraph indents, tab stops, the space before and
 * after a paragraph and the pitch of its lines as measured, the pitch
 * exact the way Word sets it; the running head and foot drawn on every
 * page at their distance from the edge, a page-number field counting from
 * where the source did; a page begun where the source began one, so the
 * pages hold what the same pages held; paragraphs split across pages
 * line-by-line. Honest
 * v1 limits, documented rather than hidden: tables take the widths their
 * columns were measured at and are ruled only where the page ruled them,
 * but render only their paragraph content, and a single row never splits
 * across pages (one taller than a page is clipped); images scale
 * into the content box at their measured size, else at the CSS px→pt
 * ratio; list markers are plain text prefixes, so an RTL numbered item
 * shows its number on the right but with Western digits.
 */
internal object PdfFileExporter {

    private const val CELL_PADDING = 4f
    private const val PX_TO_PT = 0.75f
    /** A4 in points, and the margins a document that measured none is given. */
    private const val DEFAULT_WIDTH = 595
    private const val DEFAULT_HEIGHT = 842
    private const val DEFAULT_MARGIN = 48f
    /** Where a running head or foot sits when the source did not say: half an inch in. */
    private const val DEFAULT_FURNITURE_DISTANCE = 36f
    /** However wide the margins claim to be, this much page is kept for text. */
    private const val MIN_CONTENT_PT = 120f
    /** A raised or lowered run is set this much smaller, as Word sets one. */
    private const val RAISED_SCALE = 0.66f
    /** An exact line pitch is never less than this share of the largest type on the line, so no glyph is clipped. */
    private const val LEAST_LINE_SHARE = 1.15f
    /** Of an exact line, at most this share sits below the baseline. */
    private const val MAX_DESCENT_SHARE = 0.4f
    /** Word's default tab interval: half an inch. */
    private const val DEFAULT_TAB_PT = 36f

    /** The sheet a document is laid out on, in points. */
    private class Sheet(
        val width: Int,
        val height: Int,
        val marginTop: Float,
        val marginBottom: Float,
        val marginLeft: Float,
        val marginRight: Float,
        val headerDistance: Float,
        val footerDistance: Float,
        val firstPageNumber: Int,
    ) {
        val contentWidth: Int = (width - marginLeft - marginRight).roundToInt().coerceAtLeast(1)
        val contentHeight: Float = height - marginTop - marginBottom

        companion object {
            fun of(page: PageSetup?): Sheet {
                if (page == null || page.widthPt < 1f || page.heightPt < 1f) {
                    return Sheet(
                        DEFAULT_WIDTH, DEFAULT_HEIGHT,
                        DEFAULT_MARGIN, DEFAULT_MARGIN, DEFAULT_MARGIN, DEFAULT_MARGIN,
                        DEFAULT_FURNITURE_DISTANCE, DEFAULT_FURNITURE_DISTANCE, 1,
                    )
                }
                val width = page.widthPt.roundToInt()
                val height = page.heightPt.roundToInt()
                // A measurement that leaves no room to write is not one to
                // honour; the page keeps its size and takes sane margins.
                val room = { a: Float, b: Float, of: Int -> of - a - b >= MIN_CONTENT_PT }
                val horizontal = room(page.marginLeftPt, page.marginRightPt, width)
                val vertical = room(page.marginTopPt, page.marginBottomPt, height)
                return Sheet(
                    width = width,
                    height = height,
                    marginTop = if (vertical) page.marginTopPt else DEFAULT_MARGIN,
                    marginBottom = if (vertical) page.marginBottomPt else DEFAULT_MARGIN,
                    marginLeft = if (horizontal) page.marginLeftPt else DEFAULT_MARGIN,
                    marginRight = if (horizontal) page.marginRightPt else DEFAULT_MARGIN,
                    headerDistance = page.headerDistancePt?.takeIf { it >= 0f } ?: DEFAULT_FURNITURE_DISTANCE,
                    footerDistance = page.footerDistancePt?.takeIf { it >= 0f } ?: DEFAULT_FURNITURE_DISTANCE,
                    firstPageNumber = page.firstPageNumber,
                )
            }
        }
    }

    fun render(model: DocumentModel): ByteArray {
        val pdf = PdfDocument()
        try {
            val sheet = Sheet.of(model.pageSetup)
            val furniture = Furniture(model, sheet)
            val cursor = Cursor(pdf, sheet) { canvas, ordinal -> furniture.draw(canvas, ordinal) }
            cursor.openPage()
            var numberedCount = 0
            for (block in model.blocks) {
                numberedCount =
                    if (block is Paragraph && block.style.listMarker == ListMarker.NUMBERED) {
                        numberedCount + 1
                    } else {
                        0
                    }
                when (block) {
                    is Paragraph -> paragraph(cursor, block, model.defaultDirection, numberedCount)
                    is ImageBlock -> image(cursor, block)
                    is Table -> table(cursor, block, model.defaultDirection)
                }
            }
            cursor.closePage()
            val out = ByteArrayOutputStream()
            pdf.writeTo(out)
            return out.toByteArray()
        } finally {
            pdf.close()
        }
    }

    /** One open page and a top-down write position within it; [furnish] draws each page's head and foot as it opens. */
    private class Cursor(
        private val pdf: PdfDocument,
        val sheet: Sheet,
        private val furnish: (Canvas, Int) -> Unit,
    ) {
        private var page: PdfDocument.Page? = null
        private var pageCount = 0
        var y = 0f
            private set

        val canvas: Canvas get() = checkNotNull(page).canvas
        val remaining: Float get() = sheet.height - sheet.marginBottom - y
        val atTop: Boolean get() = y <= sheet.marginTop + 0.5f

        fun openPage() {
            closePage()
            pageCount++
            val opened = pdf.startPage(
                PdfDocument.PageInfo.Builder(sheet.width, sheet.height, pageCount).create()
            )
            page = opened
            furnish(opened.canvas, pageCount)
            y = sheet.marginTop
        }

        fun closePage() {
            page?.let(pdf::finishPage)
            page = null
        }

        /** Move to a fresh page unless [height] fits or we are already at the top. */
        fun ensureRoom(height: Float) {
            if (page == null || (remaining < height && !atTop)) openPage()
        }

        fun advance(by: Float) {
            y += by
        }
    }

    /**
     * The running head and foot: drawn on every page as it opens, the head
     * at its distance below the top edge, the foot ending at its distance
     * above the bottom. A furniture paragraph is one line set by hand —
     * its picture, its tabs to the stops the source measured, and its
     * page-number field with this page's number — because that is what it
     * is on the page, and a text layout would wrap or reorder it.
     */
    private class Furniture(private val model: DocumentModel, private val sheet: Sheet) {
        private val bitmaps = IdentityHashMap<ImageBlock, Bitmap?>()

        fun draw(canvas: Canvas, ordinal: Int) {
            val number = sheet.firstPageNumber + ordinal - 1
            if (model.header.isNotEmpty()) {
                var y = sheet.headerDistance
                for (block in model.header) y += drawBlock(canvas, block, y, number)
            }
            if (model.footer.isNotEmpty()) {
                val height = model.footer.sumOf { heightOf(it, number).toDouble() }.toFloat()
                var y = sheet.height - sheet.footerDistance - height
                for (block in model.footer) y += drawBlock(canvas, block, y, number)
            }
        }

        private fun heightOf(block: Block, number: Int): Float = when (block) {
            is ImageBlock -> pictureSize(block, sheet.contentWidth.toFloat()).second
            is Paragraph -> line(block, number).height
            is Table -> 0f
        }

        /** Draws [block] with its top at [y]; returns its height. */
        private fun drawBlock(canvas: Canvas, block: Block, y: Float, number: Int): Float = when (block) {
            is ImageBlock -> {
                val (width, height) = pictureSize(block, sheet.contentWidth.toFloat())
                val bitmap = bitmapOf(block)
                if (bitmap != null) {
                    val x = if (rtl(null)) sheet.width - sheet.marginRight - width else sheet.marginLeft
                    canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                height
            }
            is Paragraph -> {
                val line = line(block, number)
                for (piece in line.pieces) piece.draw(canvas, y + line.baseline)
                line.height
            }
            is Table -> 0f
        }

        private fun rtl(paragraph: Paragraph?): Boolean =
            (paragraph?.style?.direction ?: model.defaultDirection) == TextDirection.RTL

        /** One piece of a furniture line, placed left-to-right on the page. */
        private inner class Piece(
            val x: Float,
            val width: Float,
            val height: Float,
            val text: String? = null,
            val paint: TextPaint? = null,
            val picture: ImageBlock? = null,
        ) {
            fun draw(canvas: Canvas, baseline: Float) {
                if (text != null && paint != null) {
                    canvas.drawText(text, x, baseline, paint)
                } else if (picture != null) {
                    val bitmap = bitmapOf(picture) ?: return
                    canvas.drawBitmap(bitmap, null, RectF(x, baseline - height, x + width, baseline), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
        }

        private class Line(val pieces: List<Piece>, val baseline: Float, val height: Float)

        /**
         * The paragraph as one line: each run measured and placed from the
         * start edge in logical order, a tab jumping to the next stop, a
         * picture standing on the baseline as Word stands one.
         */
        private fun line(paragraph: Paragraph, number: Int): Line {
            val rightToLeft = rtl(paragraph)
            val left = sheet.marginLeft
            val right = sheet.width - sheet.marginRight
            val indent = paragraph.style.startIndentPt?.coerceAtLeast(0f) ?: 0f
            val stops = paragraph.style.tabStopsPt.orEmpty().filter { it > 0f }.sorted()
            class Measured(val offset: Float, val width: Float, val height: Float, val text: String?, val paint: TextPaint?, val picture: ImageBlock?)
            val measured = mutableListOf<Measured>()
            var offset = indent
            var ascent = 0f
            var descent = 0f
            var tallest = 0f
            for (run in paragraph.runs) {
                val picture = run.image
                if (picture != null) {
                    val (width, height) = pictureSize(picture, (right - left - offset).coerceAtLeast(1f))
                    measured += Measured(offset, width, height, null, null, picture)
                    tallest = max(tallest, height)
                    offset += width
                    continue
                }
                if (run.text == "\t") {
                    offset = stops.firstOrNull { it > offset + 0.5f } ?: (offset + DEFAULT_TAB_PT)
                    continue
                }
                val text = if (run.field == RunField.PAGE_NUMBER) number.toString() else run.text
                if (text.isEmpty()) continue
                val paint = paintFor(run)
                val width = paint.measureText(text)
                measured += Measured(offset, width, 0f, text, paint, null)
                ascent = max(ascent, -paint.ascent())
                descent = max(descent, paint.descent())
                offset += width
            }
            val baseline = max(tallest, ascent)
            val reach = measured.maxOfOrNull { it.offset + it.width } ?: 0f
            val shift = if (paragraph.style.alignment == Alignment.CENTER) ((right - left) - reach) / 2 else 0f
            val pieces = measured.map {
                val x = if (rightToLeft) right - shift - it.offset - it.width else left + shift + it.offset
                Piece(x, it.width, it.height, it.text, it.paint, it.picture)
            }
            return Line(pieces, baseline, baseline + descent)
        }

        private fun paintFor(run: TextRun): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = run.colorRgb?.let { 0xFF000000.toInt() or it } ?: 0xFF000000.toInt()
            textSize = run.fontSizePt?.takeIf { it > 0f } ?: 12f
            val style = when {
                run.bold && run.italic -> Typeface.BOLD_ITALIC
                run.bold -> Typeface.BOLD
                run.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            typeface = run.fontFamily?.takeIf { it.isNotBlank() }?.let { Typeface.create(it, style) }
                ?: Typeface.create(Typeface.DEFAULT, style)
        }

        private fun bitmapOf(image: ImageBlock): Bitmap? = bitmaps.getOrPut(image) { decode(image) }
    }

    private fun decode(image: ImageBlock): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) }.getOrNull()

    /** The size a picture is drawn at: what it measured on its page, else its pixels at CSS px→pt, shrunk to [maxWidth]. */
    private fun pictureSize(image: ImageBlock, maxWidth: Float): Pair<Float, Float> {
        val width = image.widthPt?.takeIf { it > 0f } ?: (image.widthPx * PX_TO_PT)
        val height = image.heightPt?.takeIf { it > 0f } ?: (image.heightPx * PX_TO_PT)
        if (width <= 0f || height <= 0f) return 0f to 0f
        val scale = minOf(1f, maxWidth / width)
        return width * scale to height * scale
    }

    private fun paragraph(
        cursor: Cursor,
        block: Paragraph,
        defaultDirection: TextDirection,
        numberedCount: Int,
    ) {
        val text = spannable(block, numberedCount)
        if (text.isEmpty()) {
            cursor.advance(minOf(6f, cursor.remaining))
            return
        }
        // The source began a page here, so the export does too — unless a
        // page has just been opened and is still empty.
        if (block.style.pageBreakBefore && !cursor.atTop) cursor.openPage()
        // The space the page showed before this paragraph — not at the top
        // of a page, where Word drops it too.
        val before = block.style.spaceBeforePt?.takeIf { it > 0f } ?: 0f
        if (before > 0f && !cursor.atTop) cursor.advance(minOf(before, cursor.remaining))
        val direction = block.style.direction ?: defaultDirection
        indent(text, block)
        tabs(text, block)
        val paint = paintFor(block.style.kind)
        val layout = layout(
            text,
            paint,
            direction,
            block.style.alignment,
            cursor.sheet.contentWidth,
            pitchOf(block, paint),
        )
        drawAcrossPages(cursor, layout)
        // The space the page showed after this paragraph, where a reader
        // measured it — none, when it measured none, so the pages break
        // where the source's do; otherwise the type scale's own.
        val after = block.style.spaceAfterPt?.coerceAtLeast(0f) ?: spacingAfter(block.style.kind)
        cursor.advance(minOf(after, cursor.remaining))
    }

    /**
     * The exact pitch of the paragraph's lines where the page showed one,
     * never tighter than the largest type on it can stand; null to let the
     * face's own leading decide.
     */
    private fun pitchOf(block: Paragraph, paint: TextPaint): Float? {
        val pitch = block.style.linePitchPt?.takeIf { it > 0f } ?: return null
        val largest = block.runs.mapNotNull { run -> run.fontSizePt?.takeIf { it > 0f } }.maxOrNull() ?: paint.textSize
        return max(pitch, LEAST_LINE_SHARE * largest)
    }

    /** Draws a layout starting at the cursor, splitting across pages by line. */
    private fun drawAcrossPages(cursor: Cursor, layout: StaticLayout) {
        var first = 0
        while (first < layout.lineCount) {
            cursor.ensureRoom(layout.getLineTop(first + 1) - layout.getLineTop(first).toFloat())
            val top = layout.getLineTop(first)
            var last = first
            while (last < layout.lineCount &&
                layout.getLineTop(last + 1) - top <= cursor.remaining
            ) {
                last++
            }
            // A single line taller than a page: draw it clipped, never loop.
            if (last == first) last = first + 1
            val height = (layout.getLineTop(last) - top).toFloat()
            val canvas = cursor.canvas
            canvas.save()
            canvas.translate(cursor.sheet.marginLeft, cursor.y)
            canvas.clipRect(0f, 0f, cursor.sheet.contentWidth.toFloat(), height)
            canvas.translate(0f, -top.toFloat())
            layout.draw(canvas)
            canvas.restore()
            cursor.advance(height)
            first = last
            if (first < layout.lineCount) cursor.openPage()
        }
    }

    private fun image(cursor: Cursor, block: ImageBlock) {
        val bitmap = decode(block) ?: return
        val maxHeight = cursor.sheet.contentHeight
        // The size it measured on its page, else CSS px→pt (0.75), shrunk
        // further to fit the content box.
        val natural = block.widthPt?.takeIf { it > 0f }?.let { it / bitmap.width } ?: PX_TO_PT
        val scale = minOf(
            natural,
            cursor.sheet.contentWidth.toFloat() / bitmap.width,
            maxHeight / bitmap.height,
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        cursor.ensureRoom(height)
        cursor.canvas.drawBitmap(
            bitmap,
            null,
            RectF(
                cursor.sheet.marginLeft,
                cursor.y,
                cursor.sheet.marginLeft + width,
                cursor.y + height,
            ),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        cursor.advance(minOf(height + 10f, cursor.remaining))
    }

    private fun table(cursor: Cursor, block: Table, defaultDirection: TextDirection) {
        val columns = block.rows.maxOfOrNull { it.cells.size } ?: return
        if (columns == 0) return
        // The widths a reader measured off the page, scaled to the content
        // box; a table nothing measured shares the width equally.
        val measured = block.columnWidthsPt
            ?.takeIf { it.size == columns && it.all { width -> width > 0f } }
        val columnWidths = if (measured != null) {
            val scale = cursor.sheet.contentWidth / measured.sum()
            measured.map { it * scale }
        } else {
            List(columns) { cursor.sheet.contentWidth.toFloat() / columns }
        }
        val offsets = columnWidths.runningFold(0f) { at, width -> at + width }
        // A right-to-left document lays its columns out from the right, so
        // the first cell of a row is drawn in the last column — and is set
        // to that column's width, not the first one's.
        val placed = { index: Int -> if (defaultDirection == TextDirection.RTL) columns - 1 - index else index }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.75f
            color = 0xFF9E9E9E.toInt()
        }
        for (row in block.rows) {
            val cellLayouts = row.cells.mapIndexed { index, cell ->
                val textWidth = (columnWidths[placed(index)] - 2 * CELL_PADDING).toInt().coerceAtLeast(1)
                // Numbered items restart per cell, same contiguity rule as
                // the top-level walk.
                var numbered = 0
                cell.blocks.filterIsInstance<Paragraph>()
                    .filter { it.text.isNotEmpty() }
                    .map { para ->
                        numbered =
                            if (para.style.listMarker == ListMarker.NUMBERED) numbered + 1 else 0
                        val direction = para.style.direction ?: defaultDirection
                        val paint = paintFor(para.style.kind)
                        val text = spannable(para, numbered)
                        tabs(text, para)
                        layout(
                            text,
                            paint,
                            direction,
                            para.style.alignment,
                            textWidth,
                            pitchOf(para, paint),
                        )
                    }
            }
            val rowHeight = (cellLayouts.maxOfOrNull { layouts ->
                layouts.sumOf { it.height } + (layouts.size - 1).coerceAtLeast(0) * 2
            } ?: 0) + 2 * CELL_PADDING
            cursor.ensureRoom(rowHeight)
            val canvas = cursor.canvas
            for ((index, layouts) in cellLayouts.withIndex()) {
                val column = placed(index)
                val x = cursor.sheet.marginLeft + offsets[column]
                val width = columnWidths[column]
                if (block.ruled) canvas.drawRect(x, cursor.y, x + width, cursor.y + rowHeight, border)
                var textY = cursor.y + CELL_PADDING
                for (layout in layouts) {
                    canvas.save()
                    canvas.translate(x + CELL_PADDING, textY)
                    canvas.clipRect(0f, 0f, width - 2 * CELL_PADDING, layout.height.toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                    textY += layout.height + 2f
                }
            }
            cursor.advance(minOf(rowHeight, cursor.remaining))
        }
        cursor.advance(minOf(10f, cursor.remaining))
    }

    /** Paragraph runs as styled text, with an optional plain-text list marker. */
    private fun spannable(block: Paragraph, numberedCount: Int): SpannableStringBuilder {
        val text = SpannableStringBuilder()
        when (block.style.listMarker) {
            ListMarker.BULLET -> text.append("• ")
            ListMarker.NUMBERED -> text.append("$numberedCount. ")
            null -> {}
        }
        for (run in block.runs) {
            val start = text.length
            fun span(what: Any) =
                text.setSpan(what, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // A picture in the line stands on the baseline at the size it
            // measured, in place of the object-replacement character.
            val picture = run.image
            if (picture != null) {
                val bitmap = decode(picture) ?: continue
                val (width, height) = pictureSize(picture, Float.MAX_VALUE)
                val drawable = BitmapDrawable(null as Resources?, bitmap).apply {
                    setBounds(0, 0, width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
                }
                text.append("￼")
                span(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM))
                continue
            }
            if (run.text.isEmpty()) continue
            text.append(run.text)
            val style = when {
                run.bold && run.italic -> Typeface.BOLD_ITALIC
                run.bold -> Typeface.BOLD
                run.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style != Typeface.NORMAL) span(StyleSpan(style))
            if (run.underline) span(UnderlineSpan())
            // The face the source named. An unknown family falls back to the
            // platform's own, which for Arabic is a Noto face that shapes
            // correctly — better than refusing to set a size at all.
            run.fontFamily?.takeIf { it.isNotBlank() }?.let { span(TypefaceSpan(it)) }
            // Sizes are in points because the canvas is: a PDF page is
            // measured in them, so a point of type is a point on the page.
            run.fontSizePt?.takeIf { it > 0f }?.let { span(AbsoluteSizeSpan(it.roundToInt(), false)) }
            // The colour the source set, opaque; a run that named none is
            // left to the page's own black.
            run.colorRgb?.let { span(ForegroundColorSpan(0xFF000000.toInt() or it)) }
            if (run.superscript) {
                span(SuperscriptSpan())
                span(RelativeSizeSpan(RAISED_SCALE))
            } else if (run.subscript) {
                span(SubscriptSpan())
                span(RelativeSizeSpan(RAISED_SCALE))
            }
        }
        return text
    }

    /**
     * The paragraph's indents, as a leading margin over its whole text: the
     * first line in from the margin, the rest in by their own amount, or the
     * first line out where the rest hang past it. Leading is the start edge,
     * so this is the right margin of a right-to-left paragraph.
     */
    private fun indent(text: SpannableStringBuilder, block: Paragraph) {
        if (text.isEmpty()) return
        val start = block.style.startIndentPt?.takeIf { it > 0f } ?: 0f
        val hanging = block.style.hangingIndentPt?.takeIf { it > 0f } ?: 0f
        val firstLine = block.style.firstLineIndentPt?.takeIf { it > 0f } ?: 0f
        val first = (start + firstLine - hanging).coerceAtLeast(0f)
        if (first == 0f && start == 0f) return
        text.setSpan(
            LeadingMarginSpan.Standard(first.roundToInt(), start.roundToInt()),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /** The tab stops the source measured, so a tab in the text lands where it did on the page. */
    private fun tabs(text: SpannableStringBuilder, block: Paragraph) {
        if (text.isEmpty() || !text.contains('\t')) return
        for (stop in block.style.tabStopsPt.orEmpty()) {
            if (stop <= 0f) continue
            text.setSpan(TabStopSpan.Standard(stop.roundToInt()), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Every line exactly [pitch] tall, the way Word sets an "exact" line:
     * the baseline as far above the line's bottom as the face descends,
     * and the rest of the pitch above it — however tall the face is.
     */
    private class ExactLineHeight(private val pitch: Float) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            lineHeight: Int,
            fm: Paint.FontMetricsInt,
        ) {
            val height = pitch.roundToInt().coerceAtLeast(1)
            val descent = fm.descent.coerceIn(0, (height * MAX_DESCENT_SHARE).roundToInt())
            fm.descent = descent
            fm.bottom = descent
            fm.ascent = descent - height
            fm.top = fm.ascent
        }
    }

    private fun layout(
        text: SpannableStringBuilder,
        paint: TextPaint,
        direction: TextDirection,
        alignment: Alignment?,
        width: Int,
        pitch: Float?,
    ): StaticLayout {
        if (pitch != null && text.isNotEmpty()) {
            text.setSpan(ExactLineHeight(pitch), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setTextDirection(
                if (direction == TextDirection.RTL) TextDirectionHeuristics.RTL
                else TextDirectionHeuristics.LTR
            )
            .setAlignment(
                when (alignment) {
                    Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                    Alignment.END -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
            )
            .setLineSpacing(0f, if (pitch != null) 1f else 1.25f)
            .setIncludePad(pitch == null)
        if (alignment == Alignment.JUSTIFY) {
            // The LineBreaker constant is what setJustificationMode is
            // annotated with; it inlines at compile time, so referencing it
            // needs no API 29 at runtime.
            builder.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        }
        return builder.build()
    }

    private fun paintFor(kind: ParagraphKind): TextPaint {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF000000.toInt()
        when (kind) {
            ParagraphKind.TITLE -> {
                paint.textSize = 26f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            ParagraphKind.HEADING_1 -> {
                paint.textSize = 21f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            ParagraphKind.HEADING_2 -> {
                paint.textSize = 16f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            ParagraphKind.HEADING_3 -> {
                paint.textSize = 13f
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            ParagraphKind.BODY -> paint.textSize = 12f
        }
        return paint
    }

    private fun spacingAfter(kind: ParagraphKind): Float =
        if (kind == ParagraphKind.BODY) 6f else 10f
}
