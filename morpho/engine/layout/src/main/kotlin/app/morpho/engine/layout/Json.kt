package app.morpho.engine.layout

/**
 * Just enough JSON to carry an edit across a bridge, and nothing else.
 *
 * The engine takes no dependency for it because it has none for anything,
 * and because what comes over the bridge is not trusted: the page that
 * sends it renders a document somebody else wrote. So this reads with a
 * depth it will not go past, a length it will not read past, and a
 * [Malformed] it throws for anything it does not like — never a stack
 * overflow, which is what a parser that recurses without counting hands
 * an attacker who nests ten thousand brackets.
 *
 * Values are the plain kinds: a [Map] with string keys, a [List], a
 * [String], a [Double], a [Boolean], or null. Written out, a string is
 * escaped so that it is a JavaScript string too — U+2028 and U+2029 are
 * line ends to JavaScript and not to JSON, and a reply handed to
 * `evaluateJavascript` with one of them in it would break where the
 * document had a paragraph separator.
 */
object Json {

    class Malformed(message: String) : Exception(message)

    /** Nesting past this is not a document's edit, whatever else it is. */
    const val MOST_DEPTH = 64

    /** Longer than this is not an operation on a document, whatever else it is. */
    const val MOST_LENGTH = 4_000_000

    private val FORM_FEED: Char = 0x0C.toChar()
    private val LINE_SEPARATOR: Char = 0x2028.toChar()
    private val PARAGRAPH_SEPARATOR: Char = 0x2029.toChar()

    fun parse(text: String): Any? {
        if (text.length > MOST_LENGTH) throw Malformed("too long")
        val reader = Reader(text)
        val value = reader.value(0)
        reader.skipSpace()
        if (!reader.atEnd) throw Malformed("text after the value")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value, 0) }.toString()

    private fun write(sb: StringBuilder, value: Any?, depth: Int) {
        if (depth > MOST_DEPTH) throw Malformed("too deep")
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value)
            is Double -> writeNumber(sb, value)
            is Float -> writeNumber(sb, value.toDouble())
            is Number -> writeNumber(sb, value.toDouble())
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((key, item) in value) {
                    if (key !is String) throw Malformed("a key that is not a string")
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, key)
                    sb.append(':')
                    write(sb, item, depth + 1)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                for ((at, item) in value.withIndex()) {
                    if (at > 0) sb.append(',')
                    write(sb, item, depth + 1)
                }
                sb.append(']')
            }
            else -> throw Malformed("cannot write a " + value::class.simpleName)
        }
    }

    private fun writeNumber(sb: StringBuilder, value: Double) {
        if (value.isNaN() || value.isInfinite()) throw Malformed("not a number JSON can hold")
        if (value == Math.rint(value) && Math.abs(value) < 1e15) sb.append(value.toLong()) else sb.append(value)
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == FORM_FEED -> sb.append("\\f")
                ch < ' ' || ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR ->
                    sb.append("\\u").append(String.format("%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Reader(private val text: String) {
        private var at = 0
        val atEnd: Boolean get() = at >= text.length

        fun skipSpace() {
            while (at < text.length && (text[at] == ' ' || text[at] == '\n' || text[at] == '\r' || text[at] == '\t')) at++
        }

        fun value(depth: Int): Any? {
            if (depth > MOST_DEPTH) throw Malformed("too deep")
            skipSpace()
            if (atEnd) throw Malformed("nothing where a value should be")
            return when (val ch = text[at]) {
                '{' -> obj(depth)
                '[' -> list(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (ch == '-' || ch in '0'..'9') number() else throw Malformed("unexpected '$ch'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw Malformed("unexpected literal")
            at += word.length
            return value
        }

        private fun obj(depth: Int): Map<String, Any?> {
            at++ // {
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (at < text.length && text[at] == '}') {
                at++
                return out
            }
            while (true) {
                skipSpace()
                if (atEnd || text[at] != '"') throw Malformed("a key must be a string")
                val key = string()
                skipSpace()
                if (atEnd || text[at] != ':') throw Malformed("':' expected")
                at++
                out[key] = value(depth + 1)
                skipSpace()
                if (atEnd) throw Malformed("object not closed")
                when (text[at]) {
                    ',' -> at++
                    '}' -> {
                        at++
                        return out
                    }
                    else -> throw Malformed("',' or '}' expected")
                }
            }
        }

        private fun list(depth: Int): List<Any?> {
            at++ // [
            val out = ArrayList<Any?>()
            skipSpace()
            if (at < text.length && text[at] == ']') {
                at++
                return out
            }
            while (true) {
                out += value(depth + 1)
                skipSpace()
                if (atEnd) throw Malformed("array not closed")
                when (text[at]) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return out
                    }
                    else -> throw Malformed("',' or ']' expected")
                }
            }
        }

        private fun string(): String {
            at++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw Malformed("string not closed")
                val ch = text[at++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (atEnd) throw Malformed("escape not finished")
                        when (val e = text[at++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append(FORM_FEED)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) throw Malformed("\\u escape not finished")
                                val hex = text.substring(at, at + 4)
                                val code = hex.toIntOrNull(16) ?: throw Malformed("bad \\u escape")
                                sb.append(code.toChar())
                                at += 4
                            }
                            else -> throw Malformed("bad escape '\\$e'")
                        }
                    }
                    ch < ' ' -> throw Malformed("a control character in a string")
                    else -> sb.append(ch)
                }
            }
        }

        private fun number(): Double {
            val start = at
            if (text[at] == '-') at++
            if (atEnd) throw Malformed("a number with no digits")
            if (text[at] == '0') {
                at++
            } else if (text[at] in '1'..'9') {
                while (at < text.length && text[at] in '0'..'9') at++
            } else {
                throw Malformed("a number with no digits")
            }
            if (at < text.length && text[at] == '.') {
                at++
                if (atEnd || text[at] !in '0'..'9') throw Malformed("a fraction with no digits")
                while (at < text.length && text[at] in '0'..'9') at++
            }
            if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                at++
                if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
                if (atEnd || text[at] !in '0'..'9') throw Malformed("an exponent with no digits")
                while (at < text.length && text[at] in '0'..'9') at++
            }
            val value = text.substring(start, at).toDouble()
            if (value.isInfinite()) throw Malformed("a number too large to hold")
            return value
        }
    }
}
