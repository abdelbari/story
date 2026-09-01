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
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TextDirection
import java.io.ByteArrayOutputStream

/**
 * Direct-to-file PDF export (M2): renders a [DocumentModel] into real .pdf
 * bytes with the platform's own text stack — [StaticLayout] shapes and
 * reorders through minikin, so Arabic ligatures, harakat and BiDi come out
 * right with the system Noto fonts, no WebView and no print dialog. The
 * print pipeline ([PdfPrintLauncher]) stays alongside for paper printing.
 *
 * Layout model: A4 pages, 48pt margins, per-kind type scale, paragraphs
 * split across pages line-by-line. Honest v1 limits, documented rather than
 * hidden: tables use uniform column widths, render only their paragraph
 * content, and a single row never splits across pages (one taller than a
 * page is clipped); images scale into the content box at CSS px→pt ratio;
 * list markers are plain text prefixes, so an RTL numbered item shows its
 * number on the right but with Western digits.
 */
internal object PdfFileExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 96
    private const val CELL_PADDING = 4f
    private const val PX_TO_PT = 0.75f

    fun render(model: DocumentModel): ByteArray {
        val pdf = PdfDocument()
        try {
            val cursor = Cursor(pdf)
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
    private class Cursor(private val pdf: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var pageCount = 0
        var y = 0f
            private set

        val canvas: Canvas get() = checkNotNull(page).canvas
        val remaining: Float get() = PAGE_HEIGHT - MARGIN - y
        val atTop: Boolean get() = y <= MARGIN + 0.5f

        fun openPage() {
            closePage()
            pageCount++
            page = pdf.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageCount).create()
            )
            y = MARGIN
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
        val layout = layout(text, paintFor(block.style.kind), direction, block.style.alignment,
            CONTENT_WIDTH)
        drawAcrossPages(cursor, layout)
        cursor.advance(minOf(spacingAfter(block.style.kind), cursor.remaining))
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
            canvas.translate(MARGIN, cursor.y)
            canvas.clipRect(0f, 0f, CONTENT_WIDTH.toFloat(), height)
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
        val maxHeight = PAGE_HEIGHT - 2 * MARGIN
        // Natural size is CSS px→pt (0.75), shrunk further to fit the content box.
        val scale = minOf(
            PX_TO_PT,
            CONTENT_WIDTH.toFloat() / bitmap.width,
            maxHeight / bitmap.height,
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        cursor.ensureRoom(height)
        cursor.canvas.drawBitmap(
            bitmap,
            null,
            RectF(MARGIN, cursor.y, MARGIN + width, cursor.y + height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        cursor.advance(minOf(height + 10f, cursor.remaining))
    }

    private fun table(cursor: Cursor, block: Table, defaultDirection: TextDirection) {
        val columns = block.rows.maxOfOrNull { it.cells.size } ?: return
        if (columns == 0) return
        val columnWidth = CONTENT_WIDTH.toFloat() / columns
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
                val x = MARGIN + column * columnWidth
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
            val style = when {
                run.bold && run.italic -> Typeface.BOLD_ITALIC
                run.bold -> Typeface.BOLD
                run.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style != Typeface.NORMAL) {
                text.setSpan(StyleSpan(style), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.underline) {
                text.setSpan(UnderlineSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
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
