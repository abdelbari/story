package app.morpho.engine.layout

/**
 * The line break a paragraph holds inside itself, and what it is written
 * as: a newline in a run's text.
 *
 * Word calls it shift+Enter and writes `<w:br/>`; it is the break in an
 * address, in a signature, in a stanza of verse, and in the two-line
 * title an Arabic paper puts on its first page. It is not a paragraph —
 * the lines belong to one, share its style, its spacing and its
 * numbering, and a document that turned each into a paragraph of its own
 * would space them apart and number them separately.
 *
 * Nothing carried it before. The Word reader looked at `<w:br/>` only long
 * enough to decide it was not a page break and then dropped it, so an
 * address block converted with its lines run together; no writer emitted
 * one; and the moment a reader could type into a paragraph, one Enter went
 * four ways at once — a space in the preview, a soft break in Markdown,
 * whitespace in the .docx, and a real break only on the drawn page. So the
 * rule is one rule and it lives here.
 *
 * Where a format cannot hold one — a Markdown table cell, a heading, the
 * words describing a picture — it becomes a space rather than being
 * written as something that would end the cell or the heading early.
 */
object LineBreaks {

    /** What a line break is written as inside a run's text. */
    const val MARK: Char = '\n'

    /**
     * [text] split into its lines. A carriage return, alone or before a
     * newline, is the same break: text reaches the model from readers,
     * from files and from a keyboard, and only one of those three is
     * reliably normalized before it arrives.
     */
    fun split(text: String): List<String> {
        if (!breaks(text)) return listOf(text)
        val lines = mutableListOf<String>()
        val piece = StringBuilder()
        var at = 0
        while (at < text.length) {
            val ch = text[at]
            when {
                ch == '\r' -> {
                    lines += piece.toString()
                    piece.setLength(0)
                    if (at + 1 < text.length && text[at + 1] == '\n') at++
                }
                ch == MARK -> {
                    lines += piece.toString()
                    piece.setLength(0)
                }
                else -> piece.append(ch)
            }
            at++
        }
        lines += piece.toString()
        return lines
    }

    /** Whether [text] breaks a line anywhere. */
    fun breaks(text: String): Boolean = text.any { it == MARK || it == '\r' }

    /**
     * [text] with every break made a space — for the places that cannot
     * hold one, which say so by calling this rather than by leaving a
     * newline to be written as whatever the format makes of it.
     */
    fun flattened(text: String): String =
        if (!breaks(text)) text else split(text).joinToString(" ")

    /** [text] with every break written the one way, for comparing two of them. */
    fun normalized(text: String): String =
        if (!breaks(text)) text else split(text).joinToString("\n")
}
