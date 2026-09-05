package app.morpho.engine.layout

/**
 * The inline Markdown a document's words carry beyond emphasis: a link,
 * and the mark that refers to a note.
 *
 * [MarkdownWriter] writes both — `[the site](https://example.org)` for a
 * link, and `[^1]` for a note's mark with the note itself defined at the
 * end — and nothing read either back. A document converted to Markdown
 * and then to Word arrived with the syntax of its own links showing in
 * the middle of its sentences and the words of its notes as stray lines
 * after the last paragraph, which is the same defect its pipe tables had:
 * a converter that cannot read what it writes is one conversion away from
 * nonsense.
 *
 * What is not a link stays what it is. A bracket that opens nothing — a
 * citation in square brackets, "see [note 3] below" — is text; an escaped
 * bracket is a bracket; a picture, which is written `![alt](data:…)` and
 * whose bytes nothing here can put back, is left as the words it is made
 * of; and a mark whose note nobody defined refers to nothing, so it stays
 * the characters it is made of and the line that would have defined it
 * stays a line of the document.
 */
internal object InlineMarkdown {

    /** `[^1]: the note's words`, flush left or nearly so, as a definition is written. */
    private val DEFINITION = Regex("""^ {0,3}\[\^([^\]\s]+)]:\s*(.*)$""")

    /** `[^1]` — a mark referring to a note. */
    private val REFERENCE = Regex("""\[\^([^\]\s]+)]""")

    /**
     * [text] as styled runs, with [notes] giving the words of each note a
     * mark may refer to, under the label the mark carries.
     */
    fun parse(text: String, notes: Map<String, List<Block>> = emptyMap()): List<TextRun> {
        if ('[' !in text) return InlineEmphasisParser.parse(text)
        val runs = mutableListOf<TextRun>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isEmpty()) return
            runs += InlineEmphasisParser.parse(plain.toString())
            plain.clear()
        }
        var i = 0
        while (i < text.length) {
            val here = text[i]
            // An escape is carried through as it stands: undoing it is the
            // emphasis parser's work, so `\[` stays one bracket rather than
            // being read once here and once again there.
            if (here == '\\' && i + 1 < text.length) {
                plain.append(here).append(text[i + 1])
                i += 2
                continue
            }
            if (here == '[' && !(i > 0 && text[i - 1] == '!')) {
                val mark = markAt(text, i)
                val note = mark?.let { notes[it.label] }
                if (mark != null && note != null) {
                    flush()
                    runs += TextRun(
                        text = mark.label,
                        superscript = true,
                        direction = Bidi.firstStrongDirection(mark.label),
                        note = note,
                    )
                    i = mark.end
                    continue
                }
                val link = linkAt(text, i)
                if (link != null) {
                    flush()
                    runs += InlineEmphasisParser.parse(link.words).map { it.copy(link = link.target) }
                    i = link.end
                    continue
                }
            }
            plain.append(here)
            i++
        }
        flush()
        return runs
    }

    /**
     * The notes [lines] define, and the lines with those definitions taken
     * out of them.
     *
     * A definition's own head is not a reference to itself: a line nobody
     * refers to only looks like a note, and is left where it is as the
     * text it is — which is what a document that happens to open a line
     * with a bracket deserves.
     */
    fun notesOf(lines: List<String>): Pair<List<String>, Map<String, List<Block>>> {
        val referred = buildSet {
            for (line in lines) {
                val words = DEFINITION.find(line)?.groupValues?.get(2) ?: line
                for (match in REFERENCE.findAll(words)) add(match.groupValues[1])
            }
        }
        if (referred.isEmpty()) return lines to emptyMap()
        val kept = mutableListOf<String>()
        val notes = LinkedHashMap<String, List<Block>>()
        var i = 0
        while (i < lines.size) {
            val found = DEFINITION.find(lines[i])
            val label = found?.groupValues?.get(1)
            if (found == null || label !in referred || label in notes) {
                kept += lines[i]
                i++
                continue
            }
            // A note's words may wrap, and a wrapped line is indented under
            // the definition — which is how a Markdown that knows notes at
            // all writes one that runs long.
            val words = StringBuilder(found.groupValues[2].trim())
            i++
            while (i < lines.size && lines[i].isNotBlank() && continuesANote(lines[i])) {
                words.append(' ').append(lines[i].trim())
                i++
            }
            val said = words.toString()
            notes[label!!] = listOf(
                Paragraph(
                    runs = parse(said),
                    style = ParagraphStyle(direction = Bidi.firstStrongDirection(said)),
                )
            )
        }
        return kept to notes
    }

    private fun continuesANote(line: String): Boolean =
        line.startsWith("\t") || line.startsWith("    ")

    private class Mark(val label: String, val end: Int)

    /** `[^label]` standing at [at], if that is what stands there. */
    private fun markAt(text: String, at: Int): Mark? {
        if (at + 2 >= text.length || text[at + 1] != '^') return null
        val close = text.indexOf(']', at + 2)
        if (close < 0) return null
        val label = text.substring(at + 2, close)
        if (label.isEmpty() || label.any { it.isWhitespace() || it == '[' }) return null
        return Mark(label, close + 1)
    }

    private class Link(val words: String, val target: String, val end: Int)

    /** `[words](target)` standing at [at], if that is what stands there. */
    private fun linkAt(text: String, at: Int): Link? {
        val close = closingAt(text, at, '[', ']') ?: return null
        if (close + 1 >= text.length || text[close + 1] != '(') return null
        val end = closingAt(text, close + 1, '(', ')') ?: return null
        val words = text.substring(at + 1, close)
        val inside = text.substring(close + 2, end).trim()
        val target = inside.substringBefore(' ')
        // Markdown lets a link carry a title after its target. Anything
        // else between the brackets is not a link's target, and the whole
        // of it stays the text it is rather than being quietly dropped.
        val rest = inside.removePrefix(target).trim()
        if (words.isBlank() || target.isEmpty()) return null
        if (rest.isNotEmpty() && rest.first() != '"' && rest.first() != '\'') return null
        return Link(words, target, end + 1)
    }

    /** Where the bracket opened at [at] closes, counting nesting and passing over escapes. */
    private fun closingAt(text: String, at: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = at
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}
