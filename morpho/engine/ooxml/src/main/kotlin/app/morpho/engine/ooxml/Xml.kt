package app.morpho.engine.ooxml

/**
 * Escapes text for use in XML character data and attribute values, dropping
 * what XML 1.0 cannot represent at all: C0 control characters other than
 * tab/newline/carriage-return, and the non-characters U+FFFE/U+FFFF, are
 * illegal even as character references — left in, they produce a package
 * Word rejects as corrupt, so they are removed. An unpaired surrogate
 * (invalid UTF-16 from a hostile source) becomes U+FFFD.
 */
internal fun xmlEscape(raw: String): String {
    val sb = StringBuilder(raw.length + 16)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch == '\'' -> sb.append("&apos;")
            ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
            ch.code < 0x20 || ch == '\uFFFE' || ch == '\uFFFF' -> {} // dropped: illegal in XML 1.0
            ch.isHighSurrogate() ->
                if (i + 1 < raw.length && raw[i + 1].isLowSurrogate()) {
                    sb.append(ch).append(raw[i + 1])
                    i++
                } else {
                    sb.append('\uFFFD')
                }
            ch.isLowSurrogate() -> sb.append('\uFFFD')
            else -> sb.append(ch)
        }
        i++
    }
    return sb.toString()
}

internal const val XML_DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
