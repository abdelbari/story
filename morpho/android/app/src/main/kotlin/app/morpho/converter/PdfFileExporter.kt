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
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
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
import app.morpho.engine.layout.ListCounts
import app.morpho.engine.layout.ListLabels
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableGrid
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import app.morpho.engine.layout.pdf.StackedLines
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
 * columns were measured at, are ruled only where the page ruled them, and
 * spread a cell across the columns it covers, hold pictures as well as
 * words, and carry a row longer than the page left over the page, cut
 * between lines, but they draw a cell that covers several rows in the
 * first of them alone and skip a table inside a cell; images scale
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
    /** The clear space between a page's text and the rule above its notes. */
    private const val NOTE_GAP_PT = 6f
    /** How far across the page the rule above the notes runs. */
    private const val NOTE_RULE_SHARE = 0.33f
    /** A note is set this much smaller than the text that refers to it. */
    private const val NOTE_SCALE = 0.85f

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
            val counts = ListCounts()
            for (block in model.blocks) {
                val numberedCount = if (block is Paragraph) {
                    counts.next(block.style)
                } else {
                    counts.clear()
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
        /** What the notes of this page take at its foot, kept clear of the text. */
        private var reserved = 0f
        /** The notes whose marks have landed on this page, drawn when it closes. */
        private val notes = mutableListOf<Pair<StaticLayout, Float>>()

        val canvas: Canvas get() = checkNotNull(page).canvas
        val remaining: Float get() = sheet.height - sheet.marginBottom - reserved - y
        val atTop: Boolean get() = y <= sheet.marginTop + 0.5f

        /** Keeps [height] at the foot of this page for a note, if there is room to. */
        fun reserve(layout: StaticLayout): Boolean {
            val height = layout.height + NOTE_GAP_PT
            if (height > remaining - 1f) return false
            reserved += height
            notes += layout to height
            return true
        }

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
            val open = page ?: return
            drawNotes(open.canvas)
            pdf.finishPage(open)
            page = null
        }

        /**
         * The page's notes at its foot, under a short rule, in the order
         * their marks appeared — which is where a page puts them.
         */
        private fun drawNotes(canvas: Canvas) {
            if (notes.isEmpty()) return
            val bottom = sheet.height - sheet.marginBottom
            var top = bottom - notes.sumOf { it.second.toDouble() }.toFloat()
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 0.75f
                color = 0xFF000000.toInt()
            }
            canvas.drawLine(
                sheet.marginLeft, top,
                sheet.marginLeft + sheet.contentWidth * NOTE_RULE_SHARE, top,
                rule,
            )
            top += NOTE_GAP_PT
            for ((layout, _) in notes) {
                canvas.save()
                canvas.translate(sheet.marginLeft, top)
                layout.draw(canvas)
                canvas.restore()
                top += layout.height
            }
            notes.clear()
            reserved = 0f
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
            val indent = (paragraph.style.startIndentPt?.coerceAtLeast(0f) ?: 0f) +
                ListLabels.indentPt(paragraph.style)
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
        // The notes this paragraph's marks carry belong at the foot of the
        // page it lands on, so the room they need is kept before its text
        // is laid out against what is left.
        reserveNotes(cursor, block, direction)
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
     * Keeps room at the foot of the page for each note the paragraph's
     * marks carry, and hands the note to the page to draw as it closes. A
     * note that will not fit goes to the next page, as a note does when
     * the line that calls it turns the page.
     */
    private fun reserveNotes(cursor: Cursor, block: Paragraph, direction: TextDirection) {
        for (run in block.runs) {
            val note = run.note?.takeIf { it.isNotEmpty() } ?: continue
            val paint = paintFor(ParagraphKind.BODY).apply { textSize *= NOTE_SCALE }
            val text = SpannableStringBuilder()
            val mark = run.text.trim()
            if (mark.isNotEmpty()) text.append("$mark ")
            for (paragraph in note.filterIsInstance<Paragraph>()) {
                if (text.isNotEmpty() && !text.endsWith(" ")) text.append(' ')
                text.append(paragraph.text)
            }
            if (text.isBlank()) continue
            val layout = layout(
                text,
                paint,
                direction,
                Alignment.START,
                cursor.sheet.contentWidth,
                pitch = null,
            )
            if (!cursor.reserve(layout)) {
                cursor.openPage()
                cursor.reserve(layout)
            }
        }
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

    /** What a table cell holds, drawn one under the other: words, or a picture. */
    private sealed interface Piece {
        val height: Float
        fun draw(canvas: Canvas)

        /** A paragraph of the cell, laid out to the cell's width. */
        class Text(val layout: StaticLayout) : Piece {
            override val height: Float get() = layout.height.toFloat()
            override fun draw(canvas: Canvas) = layout.draw(canvas)
        }

        /** A picture of the cell, at the size it is drawn. */
        class Picture(
            private val bitmap: Bitmap,
            private val width: Float,
            override val height: Float,
        ) : Piece {
            override fun draw(canvas: Canvas) {
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width, height), null)
            }
        }
    }

    /** A cell's picture at the size it is drawn, or null when it cannot be. */
    private fun picture(image: ImageBlock, maxWidth: Float, sheet: Sheet): Piece.Picture? {
        val bitmap = decode(image) ?: return null
        val (width, height) = pictureSize(image, maxWidth)
        if (width <= 0f || height <= 0f) return null
        // Never taller than a page can hold, or the row it sits in could
        // never be finished on any page.
        val shrink = minOf(1f, (sheet.contentHeight - 2 * CELL_PADDING) / height)
        return Piece.Picture(bitmap, width * shrink, height * shrink)
    }

    /**
     * The bottom edge of every line a cell holds, measured from the top of
     * the cell's content and including the two points of clear space set
     * between one piece and the next. A picture is one line: it is drawn
     * whole or carried to the next page, never cut in half.
     */
    private fun pieceBottoms(pieces: List<Piece>): List<Float> {
        val bottoms = mutableListOf<Float>()
        var base = 0f
        for (piece in pieces) {
            when (piece) {
                is Piece.Text ->
                    for (line in 0 until piece.layout.lineCount) {
                        bottoms += base + piece.layout.getLineBottom(line)
                    }
                is Piece.Picture -> bottoms += base + piece.height
            }
            base += piece.height + 2f
        }
        return bottoms
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
        // The places of the grid, worked out by the engine and shared with
        // the Word writer: a cell that covers several columns leaves the
        // places beside it empty, and one that covers several rows leaves
        // the places under it covered.
        val grid = TableGrid.of(block)
        val columns = grid.columns
        if (columns == 0) return
        // The widths a reader measured off the page, scaled to the content
        // box; a table nothing measured shares the width equally.
        val measured = block.columnWidthsPt
            ?.takeIf { it.size == columns && it.all { width -> width > 0f } }
        val logicalWidths = if (measured != null) {
            val scale = cursor.sheet.contentWidth / measured.sum()
            measured.map { it * scale }
        } else {
            List(columns) { cursor.sheet.contentWidth.toFloat() / columns }
        }
        // A table of Arabic lays its columns out from the right, whatever
        // the document around it does: its first cell is drawn in the last
        // column — and is set to that column's width, not the first one's,
        // so the widths are turned round with the columns.
        val rightToLeft = (block.direction ?: defaultDirection) == TextDirection.RTL
        val columnWidths = if (rightToLeft) logicalWidths.reversed() else logicalWidths
        val offsets = columnWidths.runningFold(0f) { at, width -> at + width }
        val placed = { index: Int, span: Int ->
            if (rightToLeft) columns - index - span else index
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.75f
            color = 0xFF9E9E9E.toInt()
        }
        for (row in grid.rows) {
            // Only the places a cell begins are drawn; a covered place is
            // the cell above still going, and an empty one is nothing.
            val places = row.filterIsInstance<TableGrid.Filled>()
            val cellPieces = places.map { place ->
                val cell = place.cell
                val start = placed(place.column, place.span)
                val width = (start until start + place.span).sumOf { columnWidths[it].toDouble() }.toFloat()
                val textWidth = (width - 2 * CELL_PADDING).toInt().coerceAtLeast(1)
                // Numbered items restart per cell, same contiguity rule as
                // the top-level walk.
                val counts = ListCounts()
                cell.blocks.mapNotNull { held ->
                    when (held) {
                        is Paragraph -> held.takeIf { it.text.isNotEmpty() }?.let { para ->
                            val numbered = counts.next(para.style)
                            val direction = para.style.direction ?: defaultDirection
                            val paint = paintFor(para.style.kind)
                            val text = spannable(para, numbered)
                            tabs(text, para)
                            Piece.Text(
                                layout(
                                    text,
                                    paint,
                                    direction,
                                    para.style.alignment,
                                    textWidth,
                                    pitchOf(para, paint),
                                )
                            )
                        }
                        // A picture in a cell is part of the table: the
                        // logo on a letterhead, the photo on a CV, the
                        // product beside its price.
                        is ImageBlock -> picture(held, textWidth.toFloat(), cursor.sheet)
                        // A table inside a table is a stated gap.
                        is Table -> null
                    }
                }
            }
            // The bottom edge of every line of every cell, so that a row
            // too tall for what is left of the page can be cut between
            // lines and carry on over the page instead of being drawn off
            // the edge of it and lost.
            val lines = cellPieces.map(::pieceBottoms)
            val drawnTo = FloatArray(places.size)
            do {
                val tallest = lines.indices.maxOfOrNull {
                    (lines[it].lastOrNull() ?: 0f) - drawnTo[it]
                } ?: 0f
                cursor.ensureRoom(tallest + 2 * CELL_PADDING)
                val room = (cursor.remaining - 2 * CELL_PADDING).coerceAtLeast(0f)
                val cuts = lines.indices.map { StackedLines.cut(lines[it], drawnTo[it], room) }
                val bandHeight = (cuts.indices.maxOfOrNull { cuts[it] - drawnTo[it] } ?: 0f) +
                    2 * CELL_PADDING
                val canvas = cursor.canvas
                for ((index, pieces) in cellPieces.withIndex()) {
                    val place = places[index]
                    val column = placed(place.column, place.span)
                    val x = cursor.sheet.marginLeft + offsets[column]
                    val width = (column until column + place.span)
                        .sumOf { columnWidths[it].toDouble() }.toFloat()
                    // The colour first, then the rule over it, then the words:
                    // a table's head is read by its colour as much as its rules.
                    place.cell.shadingRgb?.let { fill ->
                        canvas.drawRect(
                            x, cursor.y, x + width, cursor.y + bandHeight,
                            Paint().apply { color = 0xFF000000.toInt() or fill },
                        )
                    }
                    if (block.ruled) canvas.drawRect(x, cursor.y, x + width, cursor.y + bandHeight, border)
                    // The cell's whole content is drawn with the part
                    // belonging to earlier pages lifted above the band and
                    // clipped away, which is what puts a long cell's next
                    // lines at the top of the next page.
                    canvas.save()
                    canvas.clipRect(
                        x + CELL_PADDING,
                        cursor.y + CELL_PADDING,
                        x + width - CELL_PADDING,
                        cursor.y + bandHeight - CELL_PADDING,
                    )
                    canvas.translate(x + CELL_PADDING, cursor.y + CELL_PADDING - drawnTo[index])
                    var pieceY = 0f
                    for (piece in pieces) {
                        canvas.save()
                        canvas.translate(0f, pieceY)
                        piece.draw(canvas)
                        canvas.restore()
                        pieceY += piece.height + 2f
                    }
                    canvas.restore()
                }
                for (index in cuts.indices) drawnTo[index] = cuts[index]
                cursor.advance(minOf(bandHeight, cursor.remaining))
                // Every cut moves each unfinished cell past at least one
                // more line, so a row is always finished in the end.
                val unfinished = lines.indices.any { StackedLines.more(lines[it], drawnTo[it]) }
                if (unfinished) cursor.openPage()
            } while (unfinished)
        }
        cursor.advance(minOf(10f, cursor.remaining))
    }

    /** Paragraph runs as styled text, with an optional plain-text list marker. */
    private fun spannable(block: Paragraph, numberedCount: Int): SpannableStringBuilder {
        val text = SpannableStringBuilder()
        // A page has no numbering to draw from, so the marker its list would
        // have been given is written into the line.
        text.append(ListLabels.markerFor(block.style, numberedCount))
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
            // A word struck through in the source is struck through here.
            if (run.strikethrough) span(StrikethroughSpan())
            // The colour the source set, opaque; a run that named none is
            // left to the page's own black.
            run.colorRgb?.let { span(ForegroundColorSpan(0xFF000000.toInt() or it)) }
            // A marking a reader made over the words is drawn behind them,
            // as it was drawn on the page it came from.
            run.highlightRgb?.let { span(BackgroundColorSpan(0xFF000000.toInt() or it)) }
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
        val start = (block.style.startIndentPt?.takeIf { it > 0f } ?: 0f) +
            ListLabels.indentPt(block.style)
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
