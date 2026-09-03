package app.morpho.engine.layout.pdf

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
        val out = mutableListOf<PdfLine>()
        var run = mutableListOf<RecognizedWord>()

        fun flush() {
            val line = lineOf(run)
            if (line != null) out += line
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
        return out
    }

    /** One line, or null where nothing in [words] is worth a line. */
    private fun lineOf(words: List<RecognizedWord>): PdfLine? {
        if (words.isEmpty()) return null
        val text = words.joinToString(" ") { it.text }
        if (text.isBlank()) return null
        val looked = words.any { it.bold || it.italic }
        return PdfLine(
            text = text,
            x = words.minOf { it.left },
            // The foot of the words is where their baseline nearly is: a
            // descender hangs below it, but every line of a page hangs by
            // about the same amount, and what the reading does with this
            // is compare lines with each other.
            baselineY = words.maxOf { it.bottom },
            maxFontSize = sizeOf(words),
            page = words.first().page,
            xEnd = words.maxOf { it.right },
            // One segment a word, which is what a table's columns are
            // found from: the gaps between them across a run of lines.
            segments = words.map { PdfSegment(it.text, it.left, it.right) },
            // Runs only where recognition said something about the type.
            // Empty means "nothing was captured", which is not the same
            // as "every word is plain", and the reading treats them
            // differently.
            runs = if (!looked) emptyList() else words.mapIndexed { at, word ->
                PdfRun(
                    text = word.text + if (at < words.size - 1) " " else "",
                    look = PdfLook(bold = word.bold, italic = word.italic),
                )
            },
        )
    }

    /**
     * How big the type of a line is, from the boxes its words came in.
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
