package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.ParagraphKind
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
        val inks = out.map(::sizeOf)
        val rescue = rescueOf(inks)
        return out.mapIndexedNotNull { at, line -> lineOf(line, pointsOf(inks[at] * rescue)) }
    }

    /**
     * What every measurement of the document has to be scaled by first.
     *
     * One, nearly always. [INK_SHARE] is a fact about how type is drawn,
     * not a fit to anything, so the sizes it gives are the document's own
     * — but a recogniser that measured something other than the ink would
     * put every size of the document out together, and there is no real
     * scan in this project to prove one against. The sign of it is a body
     * that is not a size a body is ever set in: no paper is set in six
     * points, and none in thirty.
     *
     * Where that happens the document's own middle is put at the size this
     * converter sets a body at. Every ratio between its sizes survives —
     * a title is still twice its body — and what is given up is the claim
     * to know what the original measured, which in that case was never
     * worth anything.
     */
    private fun rescueOf(inks: List<Float>): Float {
        val body = HeadingSizes.median(inks.filter { it > 0f }) / INK_SHARE
        if (body <= 0f || body in LEAST_BODY..MOST_BODY) return 1f
        return TypeScale.sizePt(ParagraphKind.BODY) / body
    }

    /** Below this, nothing is a document's body text. */
    private const val LEAST_BODY = 8f

    /** And above it, nothing is either. */
    private const val MOST_BODY = 18f

    /**
     * Of a typeface's point size, the share its ink actually covers.
     *
     * What recognition measures is the ink: the top of the ascenders to
     * the foot of the descenders, which is what its own estimate of a
     * line's type reports. That is not a point size. A point size is the
     * body the type is cast on, and a typeface is drawn so its ascenders
     * and descenders together fill about nine tenths of it — a face that
     * filled its body would set solid, with no room between the lines, so
     * a text face outside about 0.85 to 1.0 is unusual.
     *
     * So the point size is what recognition measured divided by this. It
     * is a fact about how type is drawn rather than a number fitted to one
     * document, which matters because there is only one real scan to fit
     * to. On the paper this project was built for, whose sizes the PDF
     * itself spells out as 6, 11, 12 and 15 points, it gives 6, 10, 12 and
     * 14.5 — the document's own scale, back to within a point, where
     * before every run of a scan came out with no size at all and a title
     * converted at the size of a footnote.
     */
    const val INK_SHARE = 0.9f

    /** [ink] as a point size, to the half point Word keeps sizes in. */
    private fun pointsOf(ink: Float): Float =
        (kotlin.math.round(ink / INK_SHARE * 2f) / 2f).coerceIn(LEAST_POINTS, MOST_POINTS)

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
        return measured.maxOf { it.bottom - it.top }.coerceAtLeast(1f)
    }
}
