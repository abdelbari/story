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
            blocks += paragraph(LineJoiner.join(buffer), ParagraphKind.BODY, listMarker = null)
            buffer.clear()
        }

        // What each open level of the current list is indented by. A file
        // may nest with two spaces, four, or a tab; what says a list is
        // inside another is that it starts further in than the one above,
        // not any particular number of spaces.
        val indents = mutableListOf<Int>()

        fun levelOf(line: String): Int {
            val indent = indentOf(line)
            while (indents.isNotEmpty() && indent < indents.last()) indents.removeAt(indents.size - 1)
            if (indents.isEmpty() || indent > indents.last()) indents.add(indent)
            return (indents.size - 1).coerceIn(0, ListLabels.DEEPEST_LEVEL)
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()

                trimmed.startsWith("### ") -> {
                    flush()
                    indents.clear()
                    blocks += paragraph(trimmed.removePrefix("### ").trim(), ParagraphKind.HEADING_3, null)
                }
                trimmed.startsWith("## ") -> {
                    flush()
                    indents.clear()
                    blocks += paragraph(trimmed.removePrefix("## ").trim(), ParagraphKind.HEADING_2, null)
                }
                trimmed.startsWith("# ") -> {
                    flush()
                    indents.clear()
                    blocks += paragraph(trimmed.removePrefix("# ").trim(), ParagraphKind.HEADING_1, null)
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    flush()
                    blocks += paragraph(
                        trimmed.substring(2).trim(),
                        ParagraphKind.BODY,
                        ListMarker.BULLET,
                        levelOf(line),
                    )
                }

                numberedItem.containsMatchIn(trimmed) -> {
                    flush()
                    val body = trimmed.replaceFirst(numberedItem, "").trim()
                    blocks += paragraph(body, ParagraphKind.BODY, ListMarker.NUMBERED, levelOf(line))
                }

                else -> {
                    indents.clear()
                    buffer += trimmed
                }
            }
        }
        flush()

        val rtlCount = blocks.count { it is Paragraph && it.style.direction == TextDirection.RTL }
        val defaultDirection =
            if (rtlCount > blocks.size - rtlCount) TextDirection.RTL else TextDirection.LTR
        // Full UAX #9 pass: split mixed-direction runs so writers can mark
        // direction per run instead of per paragraph.
        // An address typed into a text file is an address; a reader who
        // converts one to Word expects to be able to click it.
        return Links.refine(Bidi.refine(DocumentModel(blocks = blocks, defaultDirection = defaultDirection)))
    }

    /** How far a line is written in, a tab standing for four spaces. */
    private fun indentOf(line: String): Int {
        var spaces = 0
        for (character in line) {
            when (character) {
                ' ' -> spaces += 1
                '\t' -> spaces += SPACES_A_TAB_STANDS_FOR
                else -> return spaces
            }
        }
        return spaces
    }

    private const val SPACES_A_TAB_STANDS_FOR = 4

    private fun paragraph(
        text: String,
        kind: ParagraphKind,
        listMarker: ListMarker?,
        listLevel: Int = 0,
    ): Paragraph {
        val direction = Bidi.firstStrongDirection(text)
        return Paragraph(
            runs = InlineEmphasisParser.parse(text),
            style = ParagraphStyle(
                kind = kind,
                direction = direction,
                listMarker = listMarker,
                listLevel = if (listMarker == null) 0 else listLevel,
            ),
        )
    }
}
