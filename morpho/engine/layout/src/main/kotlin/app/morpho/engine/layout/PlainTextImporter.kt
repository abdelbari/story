package app.morpho.engine.layout

/**
 * Imports plain text and lightweight Markdown structure into a [DocumentModel]:
 * `#`/`##`/`###` headings, `-`/`*`/`•` bullets, `1.`/`1)` numbered items, and
 * blank-line paragraph breaks. Soft-wrapped lines inside a paragraph are
 * unwrapped with a space. Paragraph direction is detected per paragraph from
 * the first strongly-directional character, so mixed Arabic/Latin documents
 * come out with each paragraph tagged correctly.
 *
 * Inline Markdown emphasis — `**bold**`, `*italic*`, `***bold italic***` — is
 * parsed by [InlineEmphasisParser] into styled [TextRun]s inside body
 * paragraphs, headings and list items, with each run's direction detected from
 * its own text. `\*` is a literal asterisk; unmatched or empty markers stay
 * literal, and emphasis never spans a paragraph break. Underscore emphasis
 * (`_text_`) is out of scope and left verbatim.
 */
object PlainTextImporter {

    // 1–2 digits only: "3. item" is a list, "2024. That year…" is a sentence.
    // Western, Arabic-Indic (٠-٩) and Eastern Arabic-Indic (۰-۹) digits count.
    private val numberedItem = Regex("""^[0-9\u0660-\u0669\u06F0-\u06F9]{1,2}[.)]\s+""")

    fun import(text: String): DocumentModel {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val blocks = mutableListOf<Block>()
        val buffer = mutableListOf<String>()

        fun flush() {
            if (buffer.isEmpty()) return
            blocks += paragraph(buffer.joinToString(" "), ParagraphKind.BODY, listMarker = null)
            buffer.clear()
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()

                trimmed.startsWith("### ") -> {
                    flush()
                    blocks += paragraph(trimmed.removePrefix("### ").trim(), ParagraphKind.HEADING_3, null)
                }
                trimmed.startsWith("## ") -> {
                    flush()
                    blocks += paragraph(trimmed.removePrefix("## ").trim(), ParagraphKind.HEADING_2, null)
                }
                trimmed.startsWith("# ") -> {
                    flush()
                    blocks += paragraph(trimmed.removePrefix("# ").trim(), ParagraphKind.HEADING_1, null)
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    flush()
                    blocks += paragraph(trimmed.substring(2).trim(), ParagraphKind.BODY, ListMarker.BULLET)
                }

                numberedItem.containsMatchIn(trimmed) -> {
                    flush()
                    val body = trimmed.replaceFirst(numberedItem, "").trim()
                    blocks += paragraph(body, ParagraphKind.BODY, ListMarker.NUMBERED)
                }

                else -> buffer += trimmed
            }
        }
        flush()

        val rtlCount = blocks.count { it is Paragraph && it.style.direction == TextDirection.RTL }
        val defaultDirection =
            if (rtlCount > blocks.size - rtlCount) TextDirection.RTL else TextDirection.LTR
        // Full UAX #9 pass: split mixed-direction runs so writers can mark
        // direction per run instead of per paragraph.
        return Bidi.refine(DocumentModel(blocks = blocks, defaultDirection = defaultDirection))
    }

    private fun paragraph(text: String, kind: ParagraphKind, listMarker: ListMarker?): Paragraph {
        val direction = Bidi.firstStrongDirection(text)
        return Paragraph(
            runs = InlineEmphasisParser.parse(text),
            style = ParagraphStyle(kind = kind, direction = direction, listMarker = listMarker),
        )
    }
}
