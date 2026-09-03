package app.morpho.engine.layout

/**
 * The pages of a scanned document, read as pages rather than as one long
 * text.
 *
 * Recognition works a page at a time and hands back that page's words
 * with nothing to say what any of them were: the running head is text
 * like any other, the page number is text, and a paragraph that carried
 * on over the turn of the page comes back as two pieces with nothing
 * joining them.
 *
 * Handed to the importer as one string with a blank line between pages —
 * which is what it was — a scanned book came back with "Chapter Three 47"
 * dropped into the middle of a sentence at every page turn, and every
 * paragraph that crossed a turn cut in two. Hundreds of each in a book,
 * every one of them for the reader to repair by hand, and both are
 * exactly what the reading of a laid-out PDF already avoids.
 *
 * Both can be settled from the words alone. What repeats at the same end
 * of page after page belongs to the page and not to the document; and a
 * page whose last words do not finish a sentence did not finish a
 * paragraph either.
 */
object ScannedPages {

    /** What the pages turned out to hold. */
    class Reading(
        /** The document's words, the pages' own furniture taken out. */
        val text: String,
        /** What every page repeated at its head, if anything. */
        val header: List<Block>,
        /** What every page repeated at its foot. */
        val footer: List<Block>,
        /**
         * The number the first of these pages carried, where its furniture
         * said so. A scanned chapter beginning at page 47 must go on
         * numbering itself from 47; told nothing, a converted file starts
         * again at one and every number in it is wrong.
         */
        val firstPageNumber: Int? = null,
    )

    /**
     * How many pages must repeat a line for it to be the pages' own. Two
     * is a coincidence — a scanned chapter can open two pages the same way
     * — and on a document of one or two pages nothing is repeated enough
     * to tell, which is the honest answer there.
     */
    private const val REPEATS_TO_BE_RUNNING = 3

    /** A run of digits, in any of the scripts this app converts. */
    private val DIGITS = Regex("[0-9٠-٩۰-۹]+")

    fun of(pages: List<String>): Reading {
        if (pages.isEmpty()) return Reading("", emptyList(), emptyList())
        val split = pages.map { page -> page.split('\n').toMutableList() }
        val numbered = mutableListOf<Int>()
        val header = takeFurniture(split, atTop = true, firstNumber = numbered::add)
        val footer = takeFurniture(split, atTop = false, firstNumber = numbered::add)
        // A seam can only be judged once it is known what stands either
        // side of it. On too few pages for a repetition to show, a running
        // head cannot be told from the first words of a paragraph — and
        // joining a paragraph onto a head would put "Chapter Three" in the
        // middle of a sentence, which is the very thing this is for. So
        // the seams of a document of one or two pages are left as they
        // were: its paragraph is still cut at the turn, and nothing that
        // belongs to the document is spoiled.
        return Reading(
            joined(split, seams = pages.size >= REPEATS_TO_BE_RUNNING),
            header,
            footer,
            numbered.minOrNull(),
        )
    }

    /**
     * The furniture at one end of the pages, taken out of them.
     *
     * A running head is the same words on page after page, and the number
     * counting the pages is the one thing about it that changes — so the
     * lines are compared with their digits blanked out, and a line whose
     * shape repeats often enough is the page's. Where the digits advance
     * by one from page to page they are the page's number and are written
     * as the field that counts them, so a converted document goes on
     * numbering itself instead of stamping one page's number on all of
     * them.
     */
    private fun takeFurniture(
        pages: List<MutableList<String>>,
        atTop: Boolean,
        firstNumber: (Int) -> Unit,
    ): List<Block> {
        val found = pages.map { endOf(it, atTop) }
        // Counted by shape over the pages that have an end at all, which
        // is every page holding a word.
        val byShape = HashMap<String, MutableList<Int>>()
        for ((page, at) in found.withIndex()) {
            if (at == null) continue
            byShape.getOrPut(shapeOf(pages[page][at])) { mutableListOf() } += page
        }
        val running = byShape.entries
            .filter { it.value.size >= REPEATS_TO_BE_RUNNING }
            .maxByOrNull { it.value.size }
            ?: return emptyList()
        // The line as the first page carrying it wrote it, and where its
        // number is, before the lines are taken away.
        val first = running.value.first()
        val text = pages[first][endOf(pages[first], atTop)!!]
        val counted = counting(running.value.map { pages[it][endOf(pages[it], atTop)!!] })
        // What the first page carrying the head was numbered, so the
        // converted document can go on counting from there.
        counted?.let { valueOf(it) }?.let(firstNumber)
        for (page in running.value.asReversed()) {
            val at = endOf(pages[page], atTop) ?: continue
            pages[page].removeAt(at)
        }
        return listOf(Paragraph(runs = numbered(text, counted), style = ParagraphStyle()))
    }

    /** Where the first or last line holding a word is, or null on a blank page. */
    private fun endOf(page: List<String>, atTop: Boolean): Int? {
        val range = if (atTop) page.indices else page.indices.reversed()
        return range.firstOrNull { page[it].isNotBlank() }
    }

    /** [text] with every number in it blanked out, so two pages' heads can be compared. */
    private fun shapeOf(text: String): String = DIGITS.replace(text.trim(), "#")

    /**
     * Which number in the lines counts the pages: the one that advances by
     * one from each page to the next. Null where none does, which is what
     * a head carrying a date or a volume rather than a page number looks
     * like.
     */
    private fun counting(lines: List<String>): String? {
        if (lines.size < REPEATS_TO_BE_RUNNING) return null
        val numbers = lines.map { line -> DIGITS.findAll(line).map { it.value }.toList() }
        val most = numbers.minOf { it.size }
        for (slot in 0 until most) {
            val values = numbers.map { valueOf(it[slot]) }
            if (values.any { it == null }) continue
            val steps = values.zipWithNext { a, b -> b!! - a!! }
            if (steps.isNotEmpty() && steps.all { it == 1 }) return numbers.first()[slot]
        }
        return null
    }

    /** [digits] as the number it writes, in whichever script wrote it. */
    private fun valueOf(digits: String): Int? {
        val western = digits.map { c ->
            when (c) {
                in '0'..'9' -> c - '0'
                in '٠'..'٩' -> c - '٠'
                in '۰'..'۹' -> c - '۰'
                else -> return null
            }
        }
        if (western.isEmpty() || western.size > MOST_DIGITS) return null
        return western.fold(0) { acc, d -> acc * 10 + d }
    }

    /** A page number of more digits than this is a year, a code, a date — not a page. */
    private const val MOST_DIGITS = 6

    /** [text] as runs, with [counted] — if anything — written as the field that counts pages. */
    private fun numbered(text: String, counted: String?): List<TextRun> {
        val trimmed = text.trim()
        if (counted == null) return listOf(TextRun(trimmed))
        val at = trimmed.indexOf(counted)
        if (at < 0) return listOf(TextRun(trimmed))
        val runs = mutableListOf<TextRun>()
        if (at > 0) runs += TextRun(trimmed.substring(0, at))
        runs += TextRun(counted, field = RunField.PAGE_NUMBER)
        val after = at + counted.length
        if (after < trimmed.length) runs += TextRun(trimmed.substring(after))
        return runs
    }

    /**
     * The pages as one text, each seam settled.
     *
     * A blank line is what the importer reads as the end of a paragraph,
     * so a seam that ends a paragraph gets one and a seam in the middle of
     * a paragraph gets a single newline, which the importer unwraps with a
     * space the way it unwraps any other line of the same paragraph.
     */
    private fun joined(pages: List<MutableList<String>>, seams: Boolean): String {
        val sb = StringBuilder()
        var previous: String? = null
        for (page in pages) {
            val own = page.joinToString("\n").trim('\n')
            if (own.isBlank()) continue
            if (previous != null) sb.append(if (seams && carriesOn(previous, own)) "\n" else "\n\n")
            sb.append(own)
            previous = own
        }
        return sb.toString()
    }

    /**
     * Whether the paragraph [before] ended on carries on into [after].
     *
     * Two things have to hold. The words before the turn must stop
     * mid-sentence — a page that ended on a full stop may well have ended
     * its paragraph there, and joining it to the next would run two
     * paragraphs together, which is the worse mistake of the two. And the
     * words after it must not plainly begin something of their own: a list
     * item, a heading, a stray number no head of the page accounted for.
     */
    private fun carriesOn(before: String, after: String): Boolean {
        val last = before.split('\n').lastOrNull { it.isNotBlank() } ?: return false
        val next = after.split('\n').firstOrNull { it.isNotBlank() } ?: return false
        if (Sentences.finishes(last)) return false
        if (ListLabels.opensWithLabel(next)) return false
        if (next.trimStart().startsWith("#")) return false
        if (DIGITS.matches(next.trim())) return false
        return true
    }
}
