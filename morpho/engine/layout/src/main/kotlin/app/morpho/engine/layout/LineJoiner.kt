package app.morpho.engine.layout

/**
 * Reflows the visual lines of one paragraph back into running text.
 *
 * Readers that work from a rendered page — OCR, and the untagged-PDF
 * heuristics — see where the lines *broke*, not where the sentences run, so
 * the pieces have to be rejoined. Almost always that means a single space.
 * The exception is a line ending in a hyphen, where a space is plainly wrong:
 * "inter-" + "national" must not become "inter- national".
 *
 * The hyphen itself is kept. Deciding whether it was a soft hyphen inserted
 * by justification ("inter-national" → "international") or a real one that
 * happened to land at the line end ("well-known") needs a dictionary for the
 * document's language, and guessing wrong in the second case silently
 * corrupts a word. Keeping the hyphen is wrong in at most one of the two
 * cases and destroys nothing in either — which is the trade this converter
 * makes everywhere it cannot be certain.
 */
object LineJoiner {

    /** U+002D hyphen-minus and U+2010 hyphen; dashes are punctuation, not breaks. */
    private const val HYPHENS = "-‐"

    fun join(lines: List<String>): String {
        val sb = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(trimmed)
                continue
            }
            // A hyphen at the end of the previous line abuts the next one.
            if (sb.last() !in HYPHENS) sb.append(' ')
            sb.append(trimmed)
        }
        return sb.toString()
    }
}
