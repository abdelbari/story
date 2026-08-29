package app.morpho.engine.ooxml

/** Escapes text for use in XML character data and attribute values. */
internal fun xmlEscape(raw: String): String {
    val sb = StringBuilder(raw.length + 16)
    for (ch in raw) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

internal const val XML_DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
