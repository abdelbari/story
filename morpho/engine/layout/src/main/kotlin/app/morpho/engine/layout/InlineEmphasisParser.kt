package app.morpho.engine.layout

/**
 * Parses inline Markdown emphasis into styled [TextRun]s: `**bold**`,
 * `*italic*`, `***bold italic***` and `~~struck through~~`. This is a
 * deliberately small subset of CommonMark, with predictable rules and honest
 * limitations:
 *
 * - Only asterisk and double-tilde markers are recognized. Underscore
 *   emphasis (`_text_`) is out of scope and left verbatim, and a single
 *   tilde is a tilde.
 * - `\*`, `\~`, `\\` and `\|` are literal `*`, `~`, `\` and `|` — exactly the
 *   set [MarkdownWriter] escapes, so write→import round-trips; a backslash
 *   before any other character stays a literal backslash.
 * - A marker opens only when immediately followed by non-whitespace and closes
 *   only when immediately preceded by non-whitespace (a simplified flanking
 *   rule), so `a * b` and `2 * 3 * 4` stay literal.
 * - An opener pairs with the next closer of the same marker length; unmatched
 *   or empty markers (`**`, `**oops`) and runs of four or more asterisks stay
 *   literal text.
 * - Nesting combines flags (`**a *b* c**` yields a bold-italic `b`), but
 *   mixed-length sharing of one delimiter run (`***a* b**`) is not resolved
 *   and stays literal.
 *
 * Each produced run's direction comes from [Bidi.firstStrongDirection] of that
 * run's own text (null when it has no strongly-directional character), so a
 * bold Latin word inside an Arabic paragraph carries LTR run metadata.
 */
internal object InlineEmphasisParser {

    /**
     * Splits [text] into styled runs. Adjacent runs with identical styling are
     * merged; an empty [text] yields no runs.
     */
    fun parse(text: String): List<TextRun> {
        val tokens = tokenize(text)
        val spans = mutableListOf<Span>()
        parseInto(tokens, 0, tokens.size, bold = false, italic = false, strike = false, out = spans)
        return merge(spans).map { span ->
            TextRun(
                text = span.text,
                bold = span.bold,
                italic = span.italic,
                strikethrough = span.strike,
                direction = Bidi.firstStrongDirection(span.text),
            )
        }
    }

    private data class Span(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val strike: Boolean = false,
    )

    private sealed interface Token
    private data class Text(val value: String) : Token

    /** A run of one marker character: [count] asterisks, or two tildes. */
    private data class Marker(val character: Char, val count: Int) : Token

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                tokens += Text(literal.toString())
                literal.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '\\' && i + 1 < text.length && text[i + 1] in "*~\\|" -> {
                    literal.append(text[i + 1])
                    i += 2
                }
                text[i] == '*' || text[i] == '~' -> {
                    val character = text[i]
                    var n = 0
                    while (i + n < text.length && text[i + n] == character) n++
                    flush()
                    tokens += Marker(character, n)
                    i += n
                }
                else -> {
                    literal.append(text[i])
                    i++
                }
            }
        }
        flush()
        return tokens
    }

    private fun parseInto(
        tokens: List<Token>,
        from: Int,
        to: Int,
        bold: Boolean,
        italic: Boolean,
        strike: Boolean,
        out: MutableList<Span>,
    ) {
        // Markers already known to have no closer before [to]: failed closer
        // scans are monotonic within one frame, so a single full failed scan
        // per marker keeps stray-asterisk floods linear, not quadratic.
        val exhausted = HashSet<Marker>()
        var i = from
        while (i < to) {
            when (val token = tokens[i]) {
                is Text -> {
                    out += Span(token.value, bold, italic, strike)
                    i++
                }
                is Marker -> {
                    val n = token.count
                    // Two tildes strike text through, as every Markdown that
                    // knows the idea writes it; one tilde is a tilde.
                    val opens = if (token.character == '~') n == 2 else n in 1..3
                    val closer =
                        if (opens && canOpen(tokens, i, to) && token !in exhausted) {
                            findCloser(tokens, i + 1, to, token)
                                .also { if (it == -1) exhausted.add(token) }
                        } else {
                            -1
                        }
                    if (closer == -1) {
                        out += Span(token.character.toString().repeat(n), bold, italic, strike)
                        i++
                    } else {
                        val struck = strike || token.character == '~'
                        val stars = token.character == '*'
                        parseInto(
                            tokens, i + 1, closer,
                            bold = bold || (stars && n >= 2),
                            italic = italic || (stars && n != 2),
                            strike = struck,
                            out = out,
                        )
                        i = closer + 1
                    }
                }
            }
        }
    }

    // A marker against another marker is against no whitespace, which is
    // what these rules are really asking: `~~**gone**~~` opens twice over.
    private fun canOpen(tokens: List<Token>, at: Int, to: Int): Boolean {
        if (at + 1 >= to) return false
        return when (val next = tokens[at + 1]) {
            is Text -> !next.value.first().isWhitespace()
            is Marker -> true
        }
    }

    private fun canClose(tokens: List<Token>, at: Int): Boolean =
        when (val previous = tokens[at - 1]) {
            is Text -> !previous.value.last().isWhitespace()
            is Marker -> true
        }

    private fun findCloser(tokens: List<Token>, from: Int, to: Int, marker: Marker): Int {
        for (j in from until to) {
            val token = tokens[j]
            if (token == marker && canClose(tokens, j)) return j
        }
        return -1
    }

    /** Buffer-based so a flood of tiny same-style spans merges in linear time. */
    private fun merge(spans: List<Span>): List<Span> {
        val merged = mutableListOf<Span>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var strike = false
        var open = false
        fun flush() {
            if (open) {
                merged += Span(buffer.toString(), bold, italic, strike)
                buffer.setLength(0)
                open = false
            }
        }
        for (span in spans) {
            if (!open || span.bold != bold || span.italic != italic || span.strike != strike) {
                flush()
                bold = span.bold
                italic = span.italic
                strike = span.strike
                open = true
            }
            buffer.append(span.text)
        }
        flush()
        return merged
    }
}
