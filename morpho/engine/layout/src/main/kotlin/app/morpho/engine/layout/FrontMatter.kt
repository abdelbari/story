package app.morpho.engine.layout

/**
 * What a Markdown file says about itself, at the top of it.
 *
 * A PDF keeps its title and its author in an information dictionary and a
 * Word file in `docProps/core.xml`; both readers pick them up, and the
 * Word writer and the preview both put them back. Markdown had nowhere to
 * put them, so the Markdown route dropped all four outright — a paper
 * converted for a notebook or a static site arrived with no title and no
 * author, which are the two things a static site needs most.
 *
 * Markdown does have a place for them, and every tool that reads Markdown
 * as a document rather than as a fragment reads it: a block of YAML
 * fenced by `---` before anything else. Pandoc, Jekyll, Hugo, Obsidian
 * and mdBook all take a title from there.
 *
 * The values are written as double-quoted YAML scalars whatever they hold,
 * so that a title with a colon in it, an Arabic title, or one a producer
 * left a tab inside stays one scalar and stays readable. Reading undoes
 * exactly what writing did, so a document converted to Markdown and back
 * carries the same four fields it started with.
 *
 * Recognising a block is deliberately strict: an opening fence, a closing
 * one, and nothing between them that is not `key: value`. A Markdown file
 * that opens with a horizontal rule is text, and is left as text.
 */
internal object FrontMatter {

    /** The fence that opens a block, and the two that close one. */
    private const val OPENS = "---"
    private val CLOSES = setOf("---", "...")

    /** The keys this writes, in the order it writes them. */
    private const val TITLE = "title"
    private const val AUTHOR = "author"
    private const val SUBJECT = "subject"
    private const val KEYWORDS = "keywords"

    /** A block that was read: what it said, and the lines under it. */
    class Read(val properties: DocumentProperties, val rest: List<String>)

    /**
     * [properties] as a block, or "" where the document said nothing about
     * itself — a file with no title gets no empty fence.
     *
     * The block carries no newline of its own at the end: what follows it
     * puts in the blank line, the way every other block of the file does.
     */
    fun of(properties: DocumentProperties): String {
        if (properties.isEmpty) return ""
        val out = StringBuilder(OPENS)
        fun say(key: String, value: String?) {
            if (value != null) out.append("\n").append(key).append(": ").append(quoted(value))
        }
        say(TITLE, properties.title)
        say(AUTHOR, properties.author)
        say(SUBJECT, properties.subject)
        say(KEYWORDS, properties.keywords)
        return out.append("\n").append(OPENS).toString()
    }

    /**
     * The block [lines] opens with and what is left under it, or null when
     * the file does not open with one.
     *
     * Keys this does not know are read and dropped: another tool's `date`
     * or `layout` is the file's metadata either way, and letting it fall
     * through into the document would put "layout: post" in the reader's
     * first paragraph.
     */
    fun read(lines: List<String>): Read? {
        if (lines.firstOrNull()?.trimEnd() != OPENS) return null
        val closes = (1 until lines.size).firstOrNull { lines[it].trimEnd() in CLOSES } ?: return null
        val said = mutableMapOf<String, String>()
        for (index in 1 until closes) {
            val line = lines[index].trim()
            if (line.isEmpty()) continue
            // Anything that is not a field makes this not a block of
            // fields, and the whole thing text again: a document whose
            // first line is a horizontal rule keeps its rule.
            val at = line.indexOf(':')
            if (at <= 0) return null
            val key = line.substring(0, at).trim()
            if (key.isEmpty() || key.any { it.isWhitespace() }) return null
            said[key.lowercase()] = unquoted(line.substring(at + 1).trim())
        }
        return Read(
            DocumentProperties.of(said[TITLE], said[AUTHOR], said[SUBJECT], said[KEYWORDS]),
            lines.drop(closes + 1),
        )
    }

    /**
     * [value] as a double-quoted YAML scalar. A raw newline or tab inside
     * one is not valid YAML, so those go in as the escapes YAML defines
     * for them — and any other control character as `\xNN`, since a value
     * comes from a file this converter did not write.
     */
    private fun quoted(value: String): String {
        val out = StringBuilder("\"")
        for (c in value) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '"' -> out.append("\\\"")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 || c.code == 0x7F -> out.append("\\x%02x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append("\"").toString()
    }

    /** [said] with the quoting [quoted] added taken back off. */
    private fun unquoted(said: String): String {
        if (said.length < 2 || !said.startsWith("\"") || !said.endsWith("\"")) return said
        val body = said.substring(1, said.length - 1)
        val out = StringBuilder()
        var index = 0
        while (index < body.length) {
            val c = body[index]
            if (c != '\\' || index + 1 >= body.length) {
                out.append(c)
                index++
                continue
            }
            when (val escaped = body[index + 1]) {
                'n' -> { out.append('\n'); index += 2 }
                'r' -> { out.append('\r'); index += 2 }
                't' -> { out.append('\t'); index += 2 }
                '\\', '"' -> { out.append(escaped); index += 2 }
                'x' -> {
                    val hex = body.substring(index + 2).take(2)
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 2 && code != null) {
                        out.append(code.toChar())
                        index += 4
                    } else {
                        out.append(c)
                        index++
                    }
                }
                // Not an escape this wrote; the backslash is a backslash.
                else -> { out.append(c); index++ }
            }
        }
        return out.toString()
    }
}
