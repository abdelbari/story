package app.morpho.engine.layout.pdf

/**
 * What a reader means when they type which pages they want.
 *
 * "5-20" is the fifth to the twentieth, "7" is the seventh alone, "5-" is
 * the fifth to the end and "-20" the start to the twentieth. Arabic-Indic
 * digits count as the digits they are, because a reader typing on an
 * Arabic keyboard types ٥-٢٠ and means the same thing. Anything that
 * names no pages at all — an empty box, a word, a dash on its own —
 * means the whole document, which is what converting a file means.
 */
object PageRanges {

    /** The pages [text] names, or null for the whole document. */
    fun parse(text: String): IntRange? {
        val cleaned = digits(text).trim()
        if (cleaned.isEmpty()) return null
        val dash = cleaned.indexOfFirst { it in DASHES }
        if (dash < 0) {
            val only = cleaned.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return only..only
        }
        val first = cleaned.substring(0, dash).trim().toIntOrNull()
        val last = cleaned.substring(dash + 1).trim().toIntOrNull()
        if (first == null && last == null) return null
        val from = (first ?: 1).coerceAtLeast(1)
        val to = (last ?: Int.MAX_VALUE).coerceAtLeast(1)
        // A reader who types it backwards means the pages between.
        return if (from <= to) from..to else to..from
    }

    /** [text] with every digit written as the Western digit it stands for. */
    private fun digits(text: String): String {
        val out = StringBuilder(text.length)
        for (character in text) {
            val digit = Character.digit(character, 10)
            out.append(if (character.isDigit() && digit >= 0) ('0' + digit) else character)
        }
        return out.toString()
    }

    /** Every dash a keyboard offers, and the Arabic comma nobody means as one. */
    private val DASHES = charArrayOf('-', '–', '—', '−', '٫', '…')
}
