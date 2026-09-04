package app.morpho.engine.layout

/**
 * Imports plain text and lightweight Markdown structure into a [DocumentModel]:
 * `#`/`##`/`###` headings, `-`/`*`/`•` bullets, `1.`/`1)` numbered items, and
 * blank-line paragraph breaks. Soft-wrapped lines inside a paragraph are
 * unwrapped with a space. Paragraph direction is detected per paragraph from
 * the first strongly-directional character, so mixed Arabic/Latin documents
 * come out with each paragraph tagged correctly.
 *
 * A pipe table — a header row, a row of dashes under it, and the rows
 * themselves — is read as a [Table], which is what [MarkdownWriter] writes
 * one as: without this, a document's tables come back from Markdown as
 * paragraphs full of pipe characters, and the app cannot read its own
 * output. The row of dashes may say how each column is set (`:---`,
 * `---:`, `:---:`), and the header row is marked as one, so a long table
 * repeats it. Lines that look like a table but carry no row of dashes are
 * left as the text they are.
 *
 * A link — `[the site](https://example.org)` — and a note's mark — `[^1]`,
 * with `[^1]: the words` defining it — are read by [InlineMarkdown], which
 * is the other half of what [MarkdownWriter] writes: without it a document
 * converted to Markdown and back arrived with the syntax of its own links
 * showing in its sentences and its notes as stray lines at the end.
 *
 * Inline Markdown emphasis — `**bold**`, `*italic*`, `***bold italic***` — is
 * parsed by [InlineEmphasisParser] into styled [TextRun]s inside body
 * paragraphs, headings and list items, with each run's direction detected from
 * its own text. `\*` is a literal asterisk; unmatched or empty markers stay
 * literal, and emphasis never spans a paragraph break. Underscore emphasis
 * (`_text_`) is out of scope and left verbatim.
 *
 * A fenced block of YAML above everything else is what the file says about
 * itself, not text of it: its title, author, subject and keywords are read
 * as the document's own, which is the other half of what [MarkdownWriter]
 * writes. See [FrontMatter].
 */
object PlainTextImporter {

    // 1–2 digits only: "3. item" is a list, "2024. That year…" is a sentence.
    // Western, Arabic-Indic (٠-٩) and Eastern Arabic-Indic (۰-۹) digits count.
    private val numberedItem = Regex("""^[0-9\u0660-\u0669\u06F0-\u06F9]{1,2}[.)]\s+""")

    /**
     * The recognised pages of a scanned document, one string per page.
     *
     * A page's words come back from recognition with nothing to say what
     * they were, so what belongs to the page rather than the document —
     * its running head, its number — is taken out and kept as the head and
     * foot of the converted file, and a paragraph that carried on over the
     * turn of a page is joined back up. See [ScannedPages].
     *
     * [page] is the sheet those pages were rendered from, where the caller
     * knows it; the number its running head counts from is taken from the
     * pages themselves.
     */
    fun importPages(pages: List<String>, page: PageSetup? = null): DocumentModel {
        val read = ScannedPages.of(pages)
        val model = import(read.text)
        return model.copy(
            header = read.header,
            footer = read.footer,
            // The sheet, where the caller knows it — a page rendered for
            // recognition was rendered from something with a size. Nothing
            // here invents one: an invented sheet lays every line of the
            // document out to the wrong width.
            pageSetup = page?.copy(firstPageNumber = read.firstPageNumber ?: page.firstPageNumber),
        )
    }

    fun import(text: String): DocumentModel {
        val whole = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        // What the file says about itself comes off the top before anything
        // reads the text: left on, its fence and its fields would be the
        // document's first paragraph.
        val said = FrontMatter.read(whole)
        val written = said?.rest ?: whole
        // The notes come out first: a mark in the middle of a sentence
        // refers to words defined at the end of the document, which a walk
        // that reads one line at a time has not reached yet.
        val (lines, notes) = InlineMarkdown.notesOf(written)
        val blocks = mutableListOf<Block>()
        val buffer = mutableListOf<String>()
        val tableLines = mutableListOf<String>()
        // The words this text uses, for the hyphens it broke words on. A
        // page run through OCR, or a document saved as plain text, breaks
        // them exactly as the page did.
        val spelling = LineJoiner.Vocabulary.of(lines)

        fun flush() {
            if (buffer.isEmpty()) return
            blocks += paragraph(
                LineJoiner.join(buffer, spelling, hardBreaks = true),
                ParagraphKind.BODY, listMarker = null, notes = notes,
            )
            buffer.clear()
        }

        /** The rows gathered so far, as a table — or as the text they turned out to be. */
        fun flushTable() {
            if (tableLines.isEmpty()) return
            val gathered = tableLines.toList()
            tableLines.clear()
            val table = tableOf(gathered, notes)
            if (table != null) {
                blocks += table
            } else {
                gathered.forEach { buffer += it }
                flush()
            }
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
            // A run of rows is gathered whole: whether it is a table at all
            // is decided by the row of dashes under its head, which is one
            // line further on than a walk of single lines can see.
            if (looksLikeRow(trimmed)) {
                flush()
                tableLines += trimmed
                continue
            }
            flushTable()
            when {
                trimmed.isEmpty() -> flush()

                trimmed.startsWith("### ") -> {
                    flush()
                    indents.clear()
                    blocks += paragraph(trimmed.removePrefix("### ").trim(), ParagraphKind.HEADING_3, null, notes = notes)
                }
                trimmed.startsWith("## ") -> {
                    flush()
                    indents.clear()
                    blocks += paragraph(trimmed.removePrefix("## ").trim(), ParagraphKind.HEADING_2, null, notes = notes)
                }
                trimmed.startsWith("# ") -> {
                    flush()
                    indents.clear()
                    blocks += paragraph(trimmed.removePrefix("# ").trim(), ParagraphKind.HEADING_1, null, notes = notes)
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    flush()
                    blocks += paragraph(
                        trimmed.substring(2).trim(),
                        ParagraphKind.BODY,
                        ListMarker.BULLET,
                        levelOf(line),
                        notes,
                    )
                }

                numberedItem.containsMatchIn(trimmed) -> {
                    flush()
                    val body = trimmed.replaceFirst(numberedItem, "").trim()
                    blocks += paragraph(body, ParagraphKind.BODY, ListMarker.NUMBERED, levelOf(line), notes)
                }

                else -> {
                    indents.clear()
                    buffer += trimmed
                }
            }
        }
        flushTable()
        flush()

        val rtlCount = blocks.count { it is Paragraph && it.style.direction == TextDirection.RTL }
        val defaultDirection =
            if (rtlCount > blocks.size - rtlCount) TextDirection.RTL else TextDirection.LTR
        // Full UAX #9 pass: split mixed-direction runs so writers can mark
        // direction per run instead of per paragraph.
        // An address typed into a text file is an address; a reader who
        // converts one to Word expects to be able to click it.
        return Links.refine(
            Bidi.refine(
                DocumentModel(
                    blocks = blocks,
                    defaultDirection = defaultDirection,
                    properties = said?.properties ?: DocumentProperties(),
                )
            )
        )
    }

    /** Whether a line is shaped like a row of a pipe table. */
    private fun looksLikeRow(trimmed: String): Boolean =
        trimmed.startsWith("|") && trimmed.length > 1 && trimmed.indexOf('|', 1) > 0

    /**
     * The gathered rows as a table, or null when they are not one: a table
     * is a head, a row of dashes saying how many columns it has, and the
     * rows themselves.
     */
    private fun tableOf(lines: List<String>, notes: Map<String, List<Block>>): Table? {
        if (lines.size < 2) return null
        val head = cellsOf(lines[0])
        val dashes = cellsOf(lines[1])
        if (head.isEmpty() || dashes.size != head.size) return null
        val isDashes = dashes.all { cell ->
            cell.isNotEmpty() && cell.all { it == '-' || it == ':' } && cell.contains('-')
        }
        if (!isDashes) return null
        val alignments = dashes.map(::alignmentOf)
        val rows = (listOf(head) + lines.drop(2).map(::cellsOf)).mapIndexed { index, cells ->
            TableRow(
                cells = cells.mapIndexed { column, text ->
                    TableCell(listOf(cell(text, alignments.getOrNull(column), notes)))
                },
                // A Markdown table's first row is its head, by construction.
                repeatsAsHeader = index == 0,
            )
        }
        return Table(rows)
    }

    /** How a column is set, as the row of dashes says it. */
    private fun alignmentOf(dashes: String): Alignment? = when {
        dashes.startsWith(":") && dashes.endsWith(":") -> Alignment.CENTER
        dashes.endsWith(":") -> Alignment.END
        dashes.startsWith(":") -> Alignment.START
        else -> null
    }

    /**
     * A row's cells: what stands between the pipes, with an escaped pipe
     * counting as a character of a cell rather than as the end of one.
     */
    private fun cellsOf(row: String): List<String> {
        val inner = row.removePrefix("|").let { if (it.endsWith("|")) it.dropLast(1) else it }
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < inner.length) {
            val character = inner[index]
            when {
                character == '\\' && index + 1 < inner.length && inner[index + 1] == '|' -> {
                    current.append('|')
                    index++
                }
                character == '|' -> {
                    cells += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(character)
            }
            index++
        }
        cells += current.toString().trim()
        return cells
    }

    private fun cell(text: String, alignment: Alignment?, notes: Map<String, List<Block>>): Paragraph {
        val paragraph = paragraph(text, ParagraphKind.BODY, listMarker = null, notes = notes)
        return if (alignment == null) paragraph
        else paragraph.copy(style = paragraph.style.copy(alignment = alignment))
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
        notes: Map<String, List<Block>> = emptyMap(),
    ): Paragraph {
        val direction = Bidi.firstStrongDirection(text)
        return Paragraph(
            runs = InlineMarkdown.parse(text, notes),
            style = ParagraphStyle(
                kind = kind,
                direction = direction,
                listMarker = listMarker,
                listLevel = if (listMarker == null) 0 else listLevel,
            ),
        )
    }
}
