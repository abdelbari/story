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
 * Whether that hyphen was justification's or the word's own is the hard
 * part, and it is decided by [Vocabulary] — the words the document itself
 * uses. Given nothing to go on, the hyphen is kept: keeping it is wrong in
 * at most one of the two cases and destroys nothing in either, which is the
 * trade this converter makes everywhere it cannot be certain.
 */
object LineJoiner {

    /** U+002D hyphen-minus and U+2010 hyphen; dashes are punctuation, not breaks. */
    private const val HYPHENS = "-‐"

    /**
     * Whether [line] stops in the middle of a word rather than at the end
     * of one: it ends on a hyphen with a word in front of it, so whatever
     * follows is the rest of that word and cannot be the start of
     * anything else.
     *
     * The word in front of it is the whole of the test, and leaving it
     * out was costing every Arabic list its items. An Arabic page writes
     * a list item's label at the start of the line, which is its
     * right-hand end, and reading order puts that label last — so every
     * item of every such list ends on a dash with a space before it.
     * Taken for a broken word, the item lost the space before the line
     * after it and, worse, told the reading that a paragraph could not
     * have ended there: the items were swallowed into the prose that
     * followed them. A hyphen with nothing but a space in front of it
     * breaks no word, in any script.
     */
    fun breaksAWord(line: String): Boolean = breaksAWord(line as CharSequence)

    private fun breaksAWord(line: CharSequence): Boolean {
        val end = line.indexOfLast { !it.isWhitespace() }
        if (end < 1 || line[end] !in HYPHENS) return false
        val before = line[end - 1]
        // A hyphen with nothing but a space in front of it breaks no word:
        // there is no word in front of it to break.
        if (!before.isLetterOrDigit()) return false
        // And breaking a word at a line's end is a habit of the scripts
        // that have it. Arabic and Hebrew do not: they fill a line by
        // stretching the letters, never by cutting a word in half, so a
        // hyphen standing after an Arabic letter is something other than
        // a break. Reading it as one cost every Arabic list its items —
        // a page sets an item's label against the item, the reading finds
        // that label at the line's end, and a line taken to have stopped
        // mid-word cannot be the end of a paragraph, so the items were
        // swallowed into the prose that followed them.
        return when (Character.getDirectionality(before)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> false
            else -> true
        }
    }

    /**
     * The words a document spells out, for settling the hyphens it breaks
     * words on.
     *
     * A hyphen at a line's end is either one justification put there —
     * "inter-" "national" — or one the word carries wherever it falls —
     * "well-" "known". Dropping it corrupts the second and keeping it
     * corrupts the first, and there is no dictionary here to say which:
     * this app carries no word lists, converts every language, and never
     * reaches the network.
     *
     * The document is the dictionary. A paper that breaks "international"
     * at one line almost always writes it whole at another, and a paper
     * that writes "well-known" writes it with its hyphen everywhere. So
     * the words of every line are collected once and each broken word is
     * looked up both ways: written whole somewhere, the hyphen goes;
     * written with its hyphen somewhere, the hyphen stays; written
     * neither way, the hyphen stays, as it always did.
     */
    class Vocabulary private constructor(private val words: Set<String>) {

        /** Whether the document writes [word] somewhere, however it is cased. */
        fun holds(word: String): Boolean = word.isNotEmpty() && word.lowercase() in words

        companion object {
            /** What a reading with no document behind it knows: nothing. */
            val NONE = Vocabulary(emptySet())

            /**
             * Every word [lines] spell out.
             *
             * A line's last word is left out where the line breaks a word:
             * it is half of one, and half a word taken for a whole one
             * would answer for the very question this is asked to settle.
             */
            fun of(lines: Iterable<String>): Vocabulary {
                val words = HashSet<String>()
                for (line in lines) {
                    val tokens = line.trim().split(' ', '\t', ' ', '\n')
                    for ((index, token) in tokens.withIndex()) {
                        if (index == tokens.lastIndex && breaksAWord(token)) continue
                        val word = wordIn(token)
                        if (word.isNotEmpty()) words += word.lowercase()
                    }
                }
                return if (words.isEmpty()) NONE else Vocabulary(words)
            }
        }
    }

    fun join(
        lines: List<String>,
        known: Vocabulary = Vocabulary.NONE,
        /**
         * Whether a line ending in Markdown's hard break is one — true
         * where the lines came out of a file written in Markdown, false
         * where they came off a page. A PDF has no escaping convention,
         * so a line of one that happens to end on a backslash means a
         * backslash and nothing else.
         */
        hardBreaks: Boolean = false,
    ): String {
        val sb = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(trimmed)
                continue
            }
            // A line that stopped mid-word abuts the line that finishes
            // it, and loses its hyphen altogether where the document
            // spells that word without one. Every other line, a hyphen at
            // its end or not, gets the space between two lines of prose —
            // unless the line before asked to end where it ended.
            if (hardBreaks && endsInAHardBreak(sb)) {
                sb.setLength(sb.length - 1)
                sb.append(LineBreaks.MARK)
            } else if (breaksAWord(sb)) {
                if (justifiedHyphen(sb, trimmed, known)) sb.setLength(sb.length - 1)
            } else {
                sb.append(' ')
            }
            sb.append(trimmed)
        }
        return sb.toString()
    }

    /**
     * Whether [text] ends on the backslash Markdown breaks a line with.
     *
     * Counted rather than looked at, because a document whose own words
     * end a line on a backslash writes it escaped, as two. An odd number
     * ends in a break; an even number ends in the document's own
     * backslashes and nothing more. This is CommonMark's own rule, and it
     * is why the break is written this way rather than as the two trailing
     * spaces every editor that tidies whitespace throws away.
     */
    private fun endsInAHardBreak(text: CharSequence): Boolean {
        var at = text.length
        while (at > 0 && text[at - 1] == '\\') at--
        return (text.length - at) % 2 == 1
    }

    /**
     * Whether the hyphen [text] ends on is one justification put there,
     * rather than one the broken word carries.
     *
     * Both halves are needed to ask, and either half missing — a line of
     * nothing but a hyphen, a next line opening on punctuation — leaves
     * the question unanswerable and the hyphen where it is.
     */
    private fun justifiedHyphen(text: CharSequence, next: String, known: Vocabulary): Boolean {
        val left = wordEnding(text.subSequence(0, text.length - 1))
        val right = wordIn(next)
        if (left.isEmpty() || right.isEmpty()) return false
        // Asked in this order because keeping the hyphen is the safe
        // answer: a document that writes the word hyphenated has settled
        // it, whatever else it also writes.
        if (known.holds(left + "-" + right)) return false
        return known.holds(left + right)
    }

    /** Whether [c] is part of a word rather than what stands around one. */
    private fun ofAWord(c: Char): Boolean = c.isLetterOrDigit() || c in HYPHENS

    /** The word [token] holds, without the brackets, quotes or stops around it. */
    private fun wordIn(token: String): String =
        token.dropWhile { !ofAWord(it) }.takeWhile(::ofAWord).trim { it in HYPHENS }

    /** The word [text] ends on, without what follows or precedes it. */
    private fun wordEnding(text: CharSequence): String =
        text.takeLastWhile(::ofAWord).toString().trim { it in HYPHENS }
}
