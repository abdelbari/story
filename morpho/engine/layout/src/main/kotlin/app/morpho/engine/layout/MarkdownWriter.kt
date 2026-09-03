package app.morpho.engine.layout

/**
 * Writes a [DocumentModel] as Markdown — the inverse of [PlainTextImporter]
 * for the subset both sides speak, and the target of the app's Word→Markdown
 * conversion path.
 *
 * Mapping: TITLE and HEADING_1 become `#`, HEADING_2/3 become `##`/`###`;
 * bullet items become `- `, numbered items are renumbered sequentially per
 * contiguous list; bold/italic runs become `**`/`*` spans (bold-italic
 * `***`); tables become pipe tables with the first row as header. Literal
 * `*`, `\` and `|` characters are escaped so the output re-imports cleanly.
 *
 * A note goes where Markdown puts one: a reference where its mark stood
 * and the note itself at the end of the document, in the syntax every
 * Markdown that knows the idea uses. Dropping it instead — which is what
 * a writer that only walks the text does — loses the words of the note
 * outright, and a paper's notes are not decoration.
 *
 * Honest losses, stated rather than hidden: Markdown has no underline (the
 * flag is dropped), no text direction markup (RTL survives in the characters
 * themselves, not in syntax), and no per-run language tags. Images become
 * self-contained data-URI image syntax — large but faithful, and one-way:
 * [PlainTextImporter] reads such a line back as literal text, not an image.
 * A note is one-way too: read back, its reference is literal text and its
 * words are a line at the end.
 */
object MarkdownWriter {

    fun write(document: DocumentModel): String {
        val out = StringBuilder()
        val notes = Notes(document.blocks)
        var previousWasListItem = false
        // One count per level of nesting: an item of a list inside a list
        // counts on its own, and starts again each time its list does.
        val counts = ListCounts()

        for (block in document.blocks) {
            when (block) {
                is Paragraph -> {
                    val marker = block.style.listMarker
                    if (out.isNotEmpty()) {
                        // Items of one list stay adjacent; everything else gets a blank line.
                        out.append(if (marker != null && previousWasListItem) "\n" else "\n\n")
                    }
                    val level = block.style.listLevel.coerceIn(0, ListLabels.DEEPEST_LEVEL)
                    if (marker != null && !previousWasListItem) counts.clear()
                    val count = counts.next(block.style)
                    // Four spaces a level: past the marker of the item above,
                    // which is what makes Markdown read it as a list inside
                    // a list rather than the next item of the same one.
                    val indent = "    ".repeat(level)
                    when (marker) {
                        ListMarker.BULLET ->
                            out.append(indent).append("- ").append(runsToMarkdown(block.runs, notes))
                        ListMarker.NUMBERED ->
                            out.append(indent).append(count).append(". ")
                                .append(runsToMarkdown(block.runs, notes))
                        null ->
                            out.append(headingPrefix(block.style.kind)).append(runsToMarkdown(block.runs, notes))
                    }
                    previousWasListItem = marker != null
                }
                is Table -> {
                    if (out.isNotEmpty()) out.append("\n\n")
                    appendTable(out, block, notes)
                    previousWasListItem = false
                    counts.clear()
                }
                is ImageBlock -> {
                    if (out.isNotEmpty()) out.append("\n\n")
                    out.append("![image](data:")
                        .append(block.mimeType)
                        .append(";base64,")
                        .append(java.util.Base64.getEncoder().encodeToString(block.bytes))
                        .append(")")
                    previousWasListItem = false
                    counts.clear()
                }
            }
        }
        // The notes themselves, at the end, where Markdown keeps them.
        if (notes.any()) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(notes.definitions())
        }
        if (out.isNotEmpty()) out.append("\n")
        return out.toString()
    }

    /**
     * The document's notes, labelled and in the order their marks appear.
     *
     * A label is the mark the document itself used — a star, a dagger, a
     * number — where no two notes share one, since a reader who knows the
     * paper knows its marks. Where two do, they are numbered instead,
     * because a label names exactly one note.
     */
    private class Notes(blocks: List<Block>) {
        private val labelByRun = java.util.IdentityHashMap<TextRun, String>()
        private val bodies = mutableListOf<Pair<String, List<Block>>>()

        init {
            val marked = mutableListOf<TextRun>()
            fun walk(list: List<Block>) {
                for (block in list) when (block) {
                    is Paragraph -> for (run in block.runs) {
                        if (!run.note.isNullOrEmpty()) marked += run
                    }
                    is Table -> for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                    is ImageBlock -> {}
                }
            }
            walk(blocks)
            val counts = marked.groupingBy { label(it) }.eachCount()
            for ((index, run) in marked.withIndex()) {
                val own = label(run)
                val used = if (own != null && counts[own] == 1) own else (index + 1).toString()
                labelByRun[run] = used
                bodies += used to run.note.orEmpty()
            }
        }

        /** The mark a run carries, as a label may be written: one word, no brackets. */
        private fun label(run: TextRun): String? = run.text
            .trim()
            .takeIf { it.isNotEmpty() && it.length <= 4 }
            ?.filter { !it.isWhitespace() && it != '[' && it != ']' && it != '^' }
            ?.takeIf { it.isNotEmpty() }

        fun labelOf(run: TextRun): String? = labelByRun[run]

        fun any(): Boolean = bodies.isNotEmpty()

        /** Each note as its own definition, in the order the marks appeared. */
        fun definitions(): String = bodies.joinToString(separator = "\n") { (label, blocks) ->
            val words = blocks.filterIsInstance<Paragraph>()
                .joinToString(" ") { it.text.trim() }
                .replace("\n", " ")
                .trim()
            "[^" + label + "]: " + words
        }
    }

    private fun headingPrefix(kind: ParagraphKind): String = when (kind) {
        ParagraphKind.TITLE, ParagraphKind.HEADING_1 -> "# "
        ParagraphKind.HEADING_2 -> "## "
        ParagraphKind.HEADING_3 -> "### "
        ParagraphKind.BODY -> ""
    }

    private fun runsToMarkdown(runs: List<TextRun>, notes: Notes): String {
        val sb = StringBuilder()
        for (run in runs) {
            // A mark that carries a note becomes the reference to it: the
            // mark is the run's own text, so it is what the reference
            // replaces, and the note itself waits at the end.
            val label = notes.labelOf(run)
            if (label != null) {
                sb.append("[^").append(label).append("]")
                continue
            }
            // A link the source carried. An address written out in full is
            // left as it is: Markdown makes those live on their own, and
            // "[a@b.com](mailto:a@b.com)" only says the same thing twice.
            val link = run.link
            if (link != null && run.text.isNotBlank() && !linksToItself(run.text.trim(), link)) {
                sb.append(run.text.takeWhile { it.isWhitespace() })
                sb.append("[").append(escape(run.text.trim())).append("](").append(link).append(")")
                sb.append(run.text.takeLastWhile { it.isWhitespace() })
                continue
            }
            val emphasis = when {
                run.bold && run.italic -> "***"
                run.bold -> "**"
                run.italic -> "*"
                else -> ""
            }
            // Struck-through text is written the way every Markdown that
            // knows the idea writes it, outside the emphasis markers.
            val marker = if (run.strikethrough) "~~" + emphasis else emphasis
            val core = run.text.trim()
            if (marker.isEmpty() || core.isEmpty()) {
                // Unstyled text, or a whitespace-only styled run that no
                // marker could legally wrap, is written as-is.
                sb.append(escape(run.text))
                continue
            }
            // Word routinely splits runs so styled text carries boundary
            // whitespace; markers must hug non-whitespace or they will not
            // re-parse (here or in CommonMark), so whitespace is hoisted
            // outside the span.
            sb.append(run.text.takeWhile { it.isWhitespace() })
            sb.append(marker).append(escape(core)).append(marker.reversed())
            sb.append(run.text.takeLastWhile { it.isWhitespace() })
        }
        return sb.toString()
    }

    /** Whether the link says no more than the text it sits on already does. */
    private fun linksToItself(text: String, link: String): Boolean =
        link == text || link == "mailto:$text" || link == "https://$text" || link == "http://$text"

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("*", "\\*").replace("~", "\\~").replace("|", "\\|")

    private fun appendTable(out: StringBuilder, table: Table, notes: Notes) {
        if (table.rows.isEmpty()) return
        val columnCount = table.rows.maxOf { it.cells.size }.coerceAtLeast(1)

        fun cellText(cell: TableCell): String =
            cell.blocks.filterIsInstance<Paragraph>()
                .joinToString(" ") { runsToMarkdown(it.runs, notes) }
                .replace("\n", " ")

        fun appendRow(cells: List<TableCell>) {
            out.append("|")
            for (i in 0 until columnCount) {
                out.append(" ").append(cells.getOrNull(i)?.let(::cellText).orEmpty()).append(" |")
            }
            out.append("\n")
        }

        appendRow(table.rows.first().cells)
        out.append("|").append(" --- |".repeat(columnCount)).append("\n")
        for (row in table.rows.drop(1)) appendRow(row.cells)
        out.setLength(out.length - 1) // callers own the separators between blocks
    }
}
