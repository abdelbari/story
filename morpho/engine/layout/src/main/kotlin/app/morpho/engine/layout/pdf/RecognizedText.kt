package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TypeScale

/**
 * A word recognition found, and the box it sat in on the page.
 *
 * Measurements are in points from the top-left of the page, which is the
 * direction a PDF's own text positions run in and the direction
 * recognition reports its pixels in, so nothing is turned over on the way.
 */
data class RecognizedWord(
    val text: String,
    /** 1-based page number. */
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** True where recognition said a new line of the page begins here. */
    val startsLine: Boolean = false,
    /**
     * How big the type of this word's line is, where recognition said so.
     *
     * Its own estimate beats anything worked out from the boxes: a word's
     * box is only as tall as the tallest letter in it, so "man" and
     * "Tagged" in one size box at nearly a factor of two apart. Null where
     * recognition offered none, and the boxes are used instead.
     */
    val sizePt: Float? = null,
    /**
     * Whether recognition called the word bold or slanted. Both are false
     * unless it said otherwise, which with the fast models this app ships
     * is always: the newer recogniser reports no font at all. Carried
     * because it costs nothing and a model that does report it makes the
     * difference between finding a paper's headings and missing them.
     */
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * The words recognition found, as the positioned lines a page's reading
 * takes.
 *
 * A scanned document used to be converted by asking Tesseract for one
 * string per page and handing that to the plain-text importer. Everything
 * recognition knows about the page — where each word sits, how big it is,
 * which words share a line, where the columns are — was thrown away at
 * that call, and the importer, built for text files, could only find
 * structure in the Markdown conventions recognised text never has. A scan
 * came out as body text: no headings, no sizes, no columns, no tables.
 *
 * None of the machinery that reads an untagged PDF is specific to PDFs.
 * It takes positioned lines and works out headings from type size,
 * paragraphs from spacing, columns from gutters and tables from the
 * alignment of words. Recognition can say all of that too; it was only
 * never asked. This turns what it says into the lines that reading takes.
 */
object RecognizedText {

    /**
     * The [words] of one or more pages as lines, in the order they were
     * found.
     *
     * A word marked [RecognizedWord.startsLine] opens a line, as does the
     * first word of a page; recognition segments lines itself and knows
     * better than anything downstream could work out again.
     */
    fun linesOf(words: List<RecognizedWord>): List<PdfLine> {
        val out = mutableListOf<List<RecognizedWord>>()
        var run = mutableListOf<RecognizedWord>()

        fun flush() {
            if (run.isNotEmpty()) out += run
            run = mutableListOf()
        }

        for (word in words) {
            if (word.text.isBlank()) continue
            val opens = word.startsLine ||
                run.isEmpty() ||
                run.last().page != word.page
            if (opens) flush()
            run += word
        }
        flush()
        val measured = out.map(::sizeOf)
        val scales = scalesOf(measured, out.map(::readsRightToLeft))
        return out.mapIndexedNotNull { at, line -> lineOf(line, pointsOf(measured[at] * scales[at])) }
    }

    /** Whether the words of a line are read right to left. */
    /**
     * The cells of a ruled table gathered back into rows.
     *
     * Recognition reads a page that is nothing but a table as a page of
     * text columns, and hands its cells over column by column — the whole
     * of the rightmost column top to bottom, then the next, then the
     * next. The same table with prose above and below it comes back a row
     * at a time, which is why one of them reads and the other does not.
     * This puts the first into the shape the second already has.
     *
     * Only inside the rectangle recognition itself ruled, and that is
     * what makes it safe. Gathering lines by their baselines across a
     * whole page was measured here and is ruinous: it turns a real
     * two-column page into a bogus eight-row table and loses 134 of its
     * 150 words. Confined to a grid the page drew, it cannot reach a page
     * that drew none — such a page has no rules, so it comes back
     * untouched by construction rather than by judgement.
     *
     * The pieces matter as much as the order. A row of a table is one
     * line with a piece of it in each cell, and the ruled reader places
     * the pieces rather than the line — so a row gathered without its
     * pieces lands wholly in whichever column its middle falls in, and
     * every other cell of that row comes back empty.
     */
    fun rowed(lines: List<PdfLine>, rules: List<PdfDrawing>): List<PdfLine> {
        if (lines.isEmpty() || rules.isEmpty()) return lines
        var out = lines
        for ((page, drawn) in rules.groupBy { it.page }) out = rowedOnPage(out, page, drawn)
        return out
    }

    private fun rowedOnPage(lines: List<PdfLine>, page: Int, rules: List<PdfDrawing>): List<PdfLine> {
        val down = rules.filter { it.widthPt > 0f && it.heightPt >= LEAST_GRID_SIDE_PT }
        if (down.size < LEAST_GRID_SIDES) return lines
        val left = down.minOf { it.left }
        val right = down.maxOf { it.right }
        val top = rules.minOf { it.top }
        val bottom = rules.maxOf { it.bottom }
        fun inside(line: PdfLine) = line.page == page &&
            line.x >= left - GRID_SLACK_PT && line.xEnd <= right + GRID_SLACK_PT &&
            line.baselineY >= top - GRID_SLACK_PT && line.baselineY <= bottom + GRID_SLACK_PT
        val within = lines.filter(::inside)
        if (within.size < LEAST_GRID_CELLS) return lines
        val rows = mutableListOf<MutableList<PdfLine>>()
        for (line in within.sortedBy { it.baselineY }) {
            val last = rows.lastOrNull()
            if (last != null && kotlin.math.abs(last.first().baselineY - line.baselineY) <= SAME_ROW_PT) {
                last += line
            } else {
                rows += mutableListOf(line)
            }
        }
        // Already a line a row: recognition read this page the other way,
        // and there is nothing to gather.
        if (rows.size == within.size) return lines
        val gathered = rows.map { row ->
            val across = row.sortedBy { it.x }
            across.first().copy(
                text = across.joinToString(" ") { it.text },
                xEnd = across.maxOf { it.xEnd },
                segments = across.flatMap { line ->
                    line.segments.ifEmpty { listOf(PdfSegment(line.text, line.x, line.xEnd)) }
                },
                runs = across.flatMap { it.runs },
            )
        }
        return (lines.filterNot(::inside) + gathered)
            .sortedWith(compareBy({ it.page }, { it.baselineY }, { it.x }))
    }

    /** Two sides down the page at least, or there is no grid to speak of. */
    private const val LEAST_GRID_SIDES = 2
    private const val LEAST_GRID_SIDE_PT = 20f

    /** Fewer cells than this inside the rules and there is no table to gather. */
    private const val LEAST_GRID_CELLS = 4

    /** How far outside the rules a line may sit and still be in the table. */
    private const val GRID_SLACK_PT = 2f

    /** Baselines this close together are one row of the table. */
    private const val SAME_ROW_PT = 6f

    private fun readsRightToLeft(words: List<RecognizedWord>): Boolean =
        Bidi.dominantDirection(words.joinToString(" ") { it.text }) == TextDirection.RTL

    /**
     * The scale each line's measurement takes, one for each script the
     * document is set in.
     *
     * The ratio between a line's ink and its point size is a fact about
     * the typeface, and the two scripts this converter is most often given
     * disagree about it by a quarter: Arabic set at twelve points measures
     * about fourteen, and Latin at twelve measures about eleven. An Arabic
     * paper with an English abstract — which is what an Arabic paper is —
     * therefore cannot be put right by one number. Read on the Arabic, its
     * English comes out a quarter too small; read on the English, its
     * Arabic comes out a quarter too large.
     *
     * So each script sets its own, where there is enough of it to set one.
     * A handful of words in the other script take the document's, since a
     * middle taken from four lines is not a middle.
     */
    private fun scalesOf(measured: List<Float>, rightToLeft: List<Boolean>): List<Float> {
        val whole = scaleOf(measured)
        val own = listOf(true, false).associateWith { side ->
            val mine = measured.filterIndexed { at, _ -> rightToLeft[at] == side }
            if (mine.size < LEAST_TO_SET_A_SCALE) null else scaleOf(mine)
        }
        return measured.indices.map { own[rightToLeft[it]] ?: whole }
    }

    /** Fewer lines than this in a script do not settle how it is measured. */
    private const val LEAST_TO_SET_A_SCALE = 10

    /**
     * What recognition's measurements have to be multiplied by before they
     * are point sizes.
     *
     * Recognition measures a line's ink — the top of its ascenders to the
     * foot of its descenders — and that is not the point size the type was
     * cast on. It is tempting to say what the difference is: a typeface
     * fills about nine tenths of the body it is cast on, so divide by nine
     * tenths. Measured against the real thing, that is wrong, and wrong by
     * a lot. Two hundred and sixty-seven lines of the paper this project
     * was built for, recognised by the models this app ships and matched
     * back to the lines of the file itself:
     *
     *     the file says   a scan measures   lines   the ratio
     *          12 pt       13.9 pt (10-17)   266      1.16
     *          15 pt       18.4 pt             1      1.23
     *
     * The ink of Arabic set at twelve points runs *wider* than twelve
     * points, not narrower: its ascenders reach and its descenders hang
     * further than a Latin face's, and the marks above and below reach
     * further still. A constant that is right for one script is a quarter
     * out for another, and a converter whose reason to exist is Arabic
     * cannot take the Latin one.
     *
     * So no constant. What recognition measures reliably is the ratio
     * between one line and another, and the ratio is all that is used:
     * the document's middle line is its body, the body is set at the size
     * this converter sets a body at, and every other line takes its own
     * measure against that. On the same paper that puts the body at
     * twelve points, which is what the file says, and the title at sixteen
     * against a real fifteen.
     *
     * What is given up is the claim to know what the original measured.
     * That claim was never true: the ratio between ink and point size
     * moves by a quarter with the script and the face, and nothing in a
     * scan says which face it was.
     */
    private fun scaleOf(measured: List<Float>): Float {
        val body = HeadingSizes.median(measured.filter { it > 0f })
        return if (body <= 0f) 1f else TypeScale.sizePt(ParagraphKind.BODY) / body
    }

    /**
     * [points] as Word writes a size, to the half point it keeps them in
     * — and to the body's own size where it is near enough to be it.
     *
     * A line's measurement is noisy in a way a PDF's stated size never is,
     * and the noise is not small. Of the two hundred and sixty-six lines
     * of the real paper that the file itself sets at twelve points,
     * recognition measured them across a range of ten to seventeen: a
     * fifth either side of the middle. Written out as they came, a
     * document set in one size arrives set in nine, and every line of the
     * body that measured high reads as a heading — four of them did, on a
     * paper with one heading that size can find.
     *
     * So a measurement within the spread of the body is the body. What is
     * outside it is a real difference: the paper's title measured a third
     * above its body and stays a third above it.
     */
    private fun pointsOf(points: Float): Float {
        val body = TypeScale.sizePt(ParagraphKind.BODY)
        val settled = if (points > body / NOISE && points < body * NOISE) body else points
        return (kotlin.math.round(settled * 2f) / 2f).coerceIn(LEAST_POINTS, MOST_POINTS)
    }

    /**
     * How far from the middle a line of the body's own size was measured.
     *
     * A fifth either way, on the one real scan there is to measure. Taken
     * any wider this swallows a heading; any narrower and the body is
     * still written out in nine sizes.
     */
    private const val NOISE = 1.25f

    /** Smaller than this is not type a reader could be meant to read. */
    private const val LEAST_POINTS = 4f

    /** Larger than this is a measurement that has gone wrong, not a heading. */
    private const val MOST_POINTS = 96f

    /** One line set in [size], or null where nothing in [words] is worth a line. */
    private fun lineOf(words: List<RecognizedWord>, size: Float): PdfLine? {
        if (words.isEmpty()) return null
        val text = words.joinToString(" ") { it.text }
        if (text.isBlank()) return null
        return PdfLine(
            text = text,
            x = words.minOf { it.left },
            // The foot of the words is where their baseline nearly is: a
            // descender hangs below it, but every line of a page hangs by
            // about the same amount, and what the reading does with this
            // is compare lines with each other.
            baselineY = words.maxOf { it.bottom },
            maxFontSize = size,
            page = words.first().page,
            xEnd = words.maxOf { it.right },
            // One piece a word, which is what a table's columns are
            // found from: the gaps between them across a run of lines.
            // Left to right, whatever the words do — that is what every
            // other reader hands over, because what these are for is the
            // page rather than the sentence, and recognition gives its
            // words in the order they are read. An Arabic line's pieces
            // therefore arrive in the reverse of the order they sit in.
            segments = words.map { PdfSegment(it.text, it.left, it.right) }
                .sortedBy { it.xStart },
            // Every word carries the size its line was measured at, so a
            // scanned paper's footnotes come out small and its title
            // large. Recognition can name no typeface — the fast models
            // report no font at all — so none is claimed, and the reader
            // gets the converter's own rather than a guess at the
            // original's.
            runs = words.mapIndexed { at, word ->
                PdfRun(
                    text = word.text + if (at < words.size - 1) " " else "",
                    look = PdfLook(fontSizePt = size, bold = word.bold, italic = word.italic),
                )
            },
        )
    }

    /**
     * How big the type of a line is, as recognition measured it.
     *
     * The answer is in the units recognition works in, which are the
     * pixels of the image turned into points at the resolution the page
     * was rendered. That is the ink, not the point size type is cast on;
     * [INK_SHARE] is what turns one into the other.
     *
     * A word's box is as tall as the tallest thing in it, so "man" boxes
     * at about half the height of "Tagged" in the same type — near enough
     * a factor of two, which is more than the factor that tells a heading
     * from body text. Taking the tallest word of the line is what makes
     * the measure comparable between lines: a line of any length is very
     * likely to hold one letter that reaches up and one that hangs down,
     * and the tallest box is then the type's full height rather than an
     * accident of which letters the line happens to use.
     *
     * Words of one character are left out of that: a lone "l" or a full
     * stop is all extreme, and on a short line it decides the answer.
     */
    private fun sizeOf(words: List<RecognizedWord>): Float {
        // What recognition measured, where it measured anything: it knows
        // the line's x-height and its ascenders, and this does not.
        words.firstNotNullOfOrNull { it.sizePt }?.takeIf { it > 0f }?.let { return it }
        val measured = words.filter { it.text.trim().length > 1 }.ifEmpty { words }
        // Never below nothing: a box recognition turned inside out would
        // otherwise drag the document's middle below zero and take every
        // size of it with them. How small is small enough to be nothing is
        // settled once, where the points are written.
        return measured.maxOf { it.bottom - it.top }.coerceAtLeast(0f)
    }
}
