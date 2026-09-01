package app.morpho.converter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TextDirection
import java.io.ByteArrayOutputStream
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
 * super- or subscript; paragraph indents and the space after a paragraph as
 * measured; paragraphs split across pages line-by-line. Honest v1 limits,
 * documented rather than hidden: tables use uniform column widths, render only their paragraph
 * content, and a single row never splits across pages (one taller than a
 * page is clipped); images scale into the content box at CSS px→pt ratio;
 * list markers are plain text prefixes, so an RTL numbered item shows its
 * number on the right but with Western digits.
 */
internal object PdfFileExporter {

    private const val CELL_PADDING = 4f
    private const val PX_TO_PT = 0.75f
    /** A4 in points, and the margins a document that measured none is given. */
    private const val DEFAULT_WIDTH = 595
    private const val DEFAULT_HEIGHT = 842
    private const val DEFAULT_MARGIN = 48f
    /** However wide the margins claim to be, this much page is kept for text. */
    private const val MIN_CONTENT_PT = 120f
    /** A raised or lowered run is set this much smaller, as Word sets one. */
    private const val RAISED_SCALE = 0.66f

    /** The sheet a document is laid out on, in points. */
    private class Sheet(
        val width: Int,
        val height: Int,
        val marginTop: Float,
        val marginBottom: Float,
        val marginLeft: Float,
        val marginRight: Float,
    ) {
        val contentWidth: Int = (width - marginLeft - marginRight).roundToInt().coerceAtLeast(1)
        val contentHeight: Float = height - marginTop - marginBottom

        companion object {
            fun of(page: PageSetup?): Sheet {
                if (page == null || page.widthPt < 1f || page.heightPt < 1f) {
                    return Sheet(
                        DEFAULT_WIDTH, DEFAULT_HEIGHT,
                        DEFAULT_MARGIN, DEFAULT_MARGIN, DEFAULT_MARGIN, DEFAULT_MARGIN,
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
                )
            }
        }
    }

    fun render(model: DocumentModel): ByteArray {
        val pdf = PdfDocument()
        try {
            val cursor = Cursor(pdf, Sheet.of(model.pageSetup))
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

    /** One open page and a top-down write position within it. */
    private class Cursor(private val pdf: PdfDocument, val sheet: Sheet) {
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
            page = pdf.startPage(
                PdfDocument.PageInfo.Builder(sheet.width, sheet.height, pageCount).create()
            )
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
        val direction = block.style.direction ?: defaultDirection
        indent(text, block)
        val layout = layout(
            text,
            paintFor(block.style.kind),
            direction,
            block.style.alignment,
            cursor.sheet.contentWidth,
        )
        drawAcrossPages(cursor, layout)
        // The space the page showed after this paragraph, where a reader
        // measured it; otherwise the type scale's own.
        val after = block.style.spaceAfterPt?.takeIf { it > 0f } ?: spacingAfter(block.style.kind)
        cursor.advance(minOf(after, cursor.remaining))
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
        val bitmap: Bitmap =
            runCatching { BitmapFactory.decodeByteArray(block.bytes, 0, block.bytes.size) }
                .getOrNull() ?: return
        val maxHeight = cursor.sheet.contentHeight
        // Natural size is CSS px→pt (0.75), shrunk further to fit the content box.
        val scale = minOf(
            PX_TO_PT,
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
        val columnWidth = cursor.sheet.contentWidth.toFloat() / columns
        val textWidth = (columnWidth - 2 * CELL_PADDING).toInt().coerceAtLeast(1)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.75f
            color = 0xFF9E9E9E.toInt()
        }
        for (row in block.rows) {
            val cellLayouts = row.cells.map { cell ->
                // Numbered items restart per cell, same contiguity rule as
                // the top-level walk.
                var numbered = 0
                cell.blocks.filterIsInstance<Paragraph>()
                    .filter { it.text.isNotEmpty() }
                    .map { para ->
                        numbered =
                            if (para.style.listMarker == ListMarker.NUMBERED) numbered + 1 else 0
                        val direction = para.style.direction ?: defaultDirection
                        layout(
                            spannable(para, numbered),
                            paintFor(para.style.kind),
                            direction,
                            para.style.alignment,
                            textWidth,
                        )
                    }
            }
            val rowHeight = (cellLayouts.maxOfOrNull { layouts ->
                layouts.sumOf { it.height } + (layouts.size - 1).coerceAtLeast(0) * 2
            } ?: 0) + 2 * CELL_PADDING
            cursor.ensureRoom(rowHeight)
            val canvas = cursor.canvas
            for ((index, layouts) in cellLayouts.withIndex()) {
                // RTL documents lay their columns out right-to-left.
                val column = if (defaultDirection == TextDirection.RTL) {
                    columns - 1 - index
                } else {
                    index
                }
                val x = cursor.sheet.marginLeft + column * columnWidth
                canvas.drawRect(x, cursor.y, x + columnWidth, cursor.y + rowHeight, border)
                var textY = cursor.y + CELL_PADDING
                for (layout in layouts) {
                    canvas.save()
                    canvas.translate(x + CELL_PADDING, textY)
                    canvas.clipRect(0f, 0f, textWidth.toFloat(), layout.height.toFloat())
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
            if (run.text.isEmpty()) continue
            val start = text.length
            text.append(run.text)
            fun span(what: Any) =
                text.setSpan(what, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    private fun layout(
        text: CharSequence,
        paint: TextPaint,
        direction: TextDirection,
        alignment: Alignment?,
        width: Int,
    ): StaticLayout {
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
            .setLineSpacing(0f, 1.25f)
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
