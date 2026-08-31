package app.morpho.engine.layout

/**
 * Parses inline Markdown emphasis into styled [TextRun]s: `**bold**`,
 * `*italic*`, and `***bold italic***`. This is a deliberately small subset of
 * CommonMark, with predictable rules and honest limitations:
 *
 * - Only asterisk markers are recognized. Underscore emphasis (`_text_`) is
 *   out of scope and left verbatim.
 * - `\*`, `\\` and `\|` are literal `*`, `\` and `|` — exactly the set
 *   [MarkdownWriter] escapes, so write→import round-trips; a backslash before
 *   any other character stays a literal backslash.
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
        parseInto(tokens, 0, tokens.size, bold = false, italic = false, out = spans)
        return merge(spans).map { span ->
            TextRun(
                text = span.text,
                bold = span.bold,
                italic = span.italic,
                direction = Bidi.firstStrongDirection(span.text),
            )
        }
    }

    private data class Span(val text: String, val bold: Boolean, val italic: Boolean)

    private sealed interface Token
    private data class Text(val value: String) : Token
    private data class Stars(val count: Int) : Token

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
                text[i] == '\\' && i + 1 < text.length && text[i + 1] in "*\\|" -> {
                    literal.append(text[i + 1])
                    i += 2
                }
                text[i] == '*' -> {
                    var n = 0
                    while (i + n < text.length && text[i + n] == '*') n++
                    flush()
                    tokens += Stars(n)
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
        out: MutableList<Span>,
    ) {
        // Marker lengths already known to have no closer before [to]: failed
        // closer scans are monotonic within one frame, so a single full failed
        // scan per length keeps stray-asterisk floods linear, not quadratic.
        val exhausted = HashSet<Int>()
        var i = from
        while (i < to) {
            when (val token = tokens[i]) {
                is Text -> {
                    out += Span(token.value, bold, italic)
                    i++
                }
                is Stars -> {
                    val n = token.count
                    val closer =
                        if (n in 1..3 && canOpen(tokens, i, to) && n !in exhausted) {
                            findCloser(tokens, i + 1, to, n).also { if (it == -1) exhausted.add(n) }
                        } else {
                            -1
                        }
                    if (closer == -1) {
                        out += Span("*".repeat(n), bold, italic)
                        i++
                    } else {
                        parseInto(tokens, i + 1, closer, bold || n >= 2, italic || n != 2, out)
                        i = closer + 1
                    }
                }
            }
        }
    }

    private fun canOpen(tokens: List<Token>, at: Int, to: Int): Boolean {
        if (at + 1 >= to) return false
        val next = tokens[at + 1]
        return next is Text && !next.value.first().isWhitespace()
    }

    private fun canClose(tokens: List<Token>, at: Int): Boolean {
        val prev = tokens[at - 1]
        return prev is Text && !prev.value.last().isWhitespace()
    }

    private fun findCloser(tokens: List<Token>, from: Int, to: Int, n: Int): Int {
        for (j in from until to) {
            val token = tokens[j]
            if (token is Stars && token.count == n && canClose(tokens, j)) return j
        }
        return -1
    }

    /** Buffer-based so a flood of tiny same-style spans merges in linear time. */
    private fun merge(spans: List<Span>): List<Span> {
        val merged = mutableListOf<Span>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var open = false
        fun flush() {
            if (open) {
                merged += Span(buffer.toString(), bold, italic)
                buffer.setLength(0)
                open = false
            }
        }
        for (span in spans) {
            if (!open || span.bold != bold || span.italic != italic) {
                flush()
                bold = span.bold
                italic = span.italic
                open = true
            }
            buffer.append(span.text)
        }
        flush()
        return merged
    }
}
