package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.ParagraphKind

/**
 * One entry of a PDF's own outline: what a reader's sidebar shows when it
 * lists a document's chapters. [level] is how deep the entry sits, 0 for a
 * chapter, 1 for a section inside it; [page] is the 1-based page the entry
 * leads to, or 0 where the document does not say.
 */
data class PdfOutlineEntry(val title: String, val level: Int, val page: Int)

/**
 * A document that names its own chapters.
 *
 * An untagged PDF says nothing about which of its lines are headings, and
 * the reader has to tell from the type: bigger than the body, short, often
 * bold. That reads a paper well and a manual badly — a heading set at the
 * body's own size in the same face is invisible to it. But a great many
 * such documents carry an outline, the list of chapters a reader's sidebar
 * shows, and an outline is the producer saying which lines are headings
 * and how deep each one sits.
 *
 * So where an outline entry names a line, on the page the entry leads to,
 * that line is a heading of the entry's own depth, whatever the type says.
 * A line the outline does not name is left to the type to judge, and a
 * document with no outline is read exactly as before.
 */
object PdfOutline {

    /** However deep an outline goes, no deeper than this is worth reading. */
    const val DEEPEST_LEVEL = 8

    /**
     * Longer than this and a line is a paragraph that happens to open with
     * a chapter's name, not the chapter's heading.
     */
    private const val LONGEST_HEADING = 200

    /**
     * The kind [text] takes from an outline entry naming it on [page], or
     * null where no entry does.
     */
    fun kindOf(entries: List<PdfOutlineEntry>, page: Int, text: String): ParagraphKind? {
        if (entries.isEmpty()) return null
        val line = normalize(text)
        if (line.isEmpty() || line.length > LONGEST_HEADING) return null
        val entry = entries.firstOrNull { entry ->
            (entry.page == 0 || entry.page == page) && names(normalize(entry.title), line)
        } ?: return null
        return kindFor(entry.level)
    }

    /** HEADING_1 for a chapter, HEADING_2 for a section of one, HEADING_3 for anything deeper. */
    fun kindFor(level: Int): ParagraphKind = when (level.coerceAtLeast(0)) {
        0 -> ParagraphKind.HEADING_1
        1 -> ParagraphKind.HEADING_2
        else -> ParagraphKind.HEADING_3
    }

    /**
     * Whether [title] names [line]: the same words, once the number a
     * chapter may be given in one place and not the other is set aside —
     * "1. Introduction" in the outline against "Introduction" on the page.
     *
     * The same words and no more: a contents page carries the number of the
     * page it points at, so its line never reads as the title alone, and a
     * paragraph that merely opens with a chapter's name is not that
     * chapter's heading.
     */
    private fun names(title: String, line: String): Boolean {
        if (title.isEmpty()) return false
        return title == line || withoutNumber(title) == withoutNumber(line)
    }

    /**
     * A name without the number in front of it, where the first word is one
     * — "1", "2.3", "1)" — which a producer writes in the outline or on the
     * page and often not in both. A word is only a number when it has a
     * digit in it: "A Note on Method" is not numbered.
     */
    private fun withoutNumber(text: String): String {
        val space = text.indexOf(' ')
        if (space <= 0) return text
        val first = text.substring(0, space)
        if (first.none { it.isDigit() }) return text
        if (first.any { !it.isDigit() && it != '-' && it != ')' && it != '(' && it != ':' }) return text
        return text.substring(space + 1)
    }

    /**
     * A title as it can be compared with a line: one space between words,
     * no space at either end, the case of neither, and without the leaders
     * a contents page draws between a name and its number.
     */
    private fun normalize(text: String): String {
        val out = StringBuilder()
        var spaced = false
        for (character in text) {
            when {
                character.isWhitespace() -> spaced = out.isNotEmpty()
                // The dots and dashes a contents page rules with are not
                // part of anybody's chapter name.
                character == '.' || character == '…' || character == '·' -> {}
                else -> {
                    if (spaced) out.append(' ')
                    spaced = false
                    out.append(character.lowercaseChar())
                }
            }
        }
        return out.toString()
    }
}
