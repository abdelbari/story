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
 * `*`, `\`, `|`, `[` and `]` characters are escaped so the output re-imports cleanly.
 *
 * A note goes where Markdown puts one: a reference where its mark stood
 * and the note itself at the end of the document, in the syntax every
 * Markdown that knows the idea uses. Dropping it instead — which is what
 * a writer that only walks the text does — loses the words of the note
 * outright, and a paper's notes are not decoration.
 *
 * Honest losses, stated rather than hidden: Markdown has no underline (the
 * flag is dropped), no text direction markup (RTL survives in the characters
 * themselves, not in syntax), no per-run language tags, and no way to name
 * a typeface, a size, a colour or a highlight — the words keep their order
 * and their emphasis, and everything about how they looked is gone. Images become
 * self-contained data-URI image syntax — large but faithful, and one-way:
 * [PlainTextImporter] reads such a line back as literal text, not an image.
 * A note is one-way too: read back, its reference is literal text and its
 * words are a line at the end. A page's running head and foot are written
 * once each, at the top and the bottom, because a flat file has no margins
 * to repeat them in; read back they are text of the document like any
 * other, which is the honest half of a loss whose other half — dropping
 * them — loses the words as well as the place.
 */
object MarkdownWriter {

    fun write(document: DocumentModel): String {
        val out = StringBuilder()
        val notes = Notes(document.blocks)
        // A page's own head and foot are not text of the document, but a
        // Markdown file has no margins to keep them in, and dropping them
        // loses the journal, the author and the section they name. They go
        // where a person transcribing the page would put them: the head
        // once at the top, the foot once at the bottom, rather than once
        // for every page or not at all.
        appendBlocks(out, document.header, notes)
        appendBlocks(out, document.evenHeader, notes)
        appendBlocks(out, document.blocks, notes)
        // The notes themselves, at the end, where Markdown keeps them.
        if (notes.any()) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(notes.definitions())
        }
        appendBlocks(out, document.footer, notes)
        appendBlocks(out, document.evenFooter, notes)
        if (out.isNotEmpty()) out.append("\n")
        return out.toString()
    }

    private fun appendBlocks(out: StringBuilder, blocks: List<Block>, notes: Notes) {
        var previousWasListItem = false
        // One count per level of nesting: an item of a list inside a list
        // counts on its own, and starts again each time its list does.
        val counts = ListCounts()

        for (block in blocks) {
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
                        null -> {
                            val prefix = headingPrefix(block.style.kind)
                            val line = runsToMarkdown(block.runs, notes)
                            // A heading already says what it is; a paragraph
                            // that only begins like one must say it does not.
                            out.append(prefix)
                                .append(if (prefix.isEmpty()) escapeLineStart(line) else line)
                        }
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
                    out.append(pictureOf(block))
                    previousWasListItem = false
                    counts.clear()
                }
            }
        }
    }

    /** A picture as the self-contained image Markdown writes. */
    private fun pictureOf(image: ImageBlock): String =
        "![image](data:" + image.mimeType + ";base64," +
            java.util.Base64.getEncoder().encodeToString(image.bytes) + ")"

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
            // A label names one note. A mark that can be its own label is
            // kept, so a page's printed mark survives; a mark that cannot
            // is given a number — and that number must not be one another
            // note already answers to, or two marks lead to the same words
            // and the second note is lost outright.
            val taken = marked.mapNotNullTo(HashSet()) { label(it)?.takeIf { own -> counts[own] == 1 } }
            var counter = 0
            for (run in marked) {
                val own = label(run)
                val used = if (own != null && counts[own] == 1) {
                    own
                } else {
                    do counter++ while (!taken.add(counter.toString()))
                    counter.toString()
                }
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

        /**
         * Each note as its own definition, in the order the marks appeared.
         *
         * A note's words are written the way the document's are — escaped,
         * and with the emphasis and links they carry — because they are the
         * document's words. Written raw, a note holding a bracket or an
         * asterisk came back as something else, and a note's bold came back
         * plain.
         */
        fun definitions(): String = bodies.joinToString(separator = "\n") { (label, blocks) ->
            val words = blocks.filterIsInstance<Paragraph>()
                .joinToString(" ") { runsToMarkdown(it.runs, this).trim() }
                .replace("\n", " ")
                .trim()
            "[^" + label + "]: " + words
        }
    }

    /**
     * [line] with a leading marker escaped, where a paragraph happens to
     * begin with one.
     *
     * "1. Introduction" left over from a list a page drew and the reader
     * did not recognise, "- see the appendix", "#3 in the series": read
     * back as they stand, the marker is taken for what it looks like and
     * eaten, and the paragraph comes back as the list item or the heading
     * it was only shaped like, a word short.
     *
     * A bullet Word draws with `•` is the one marker with no escape of
     * its own: a paragraph that opens with one and is not a list item
     * comes back as a list item without it.
     */
    private fun escapeLineStart(line: String): String {
        val space = line.takeWhile { it.isWhitespace() }
        val rest = line.substring(space.length)
        if (OPENS_A_BLOCK.containsMatchIn(rest)) return space + "\\" + rest
        val counted = OPENS_A_COUNT.find(rest) ?: return line
        val at = counted.groups[1]!!.range.first
        return space + rest.substring(0, at) + "\\" + rest.substring(at)
    }

    /** What the importer reads at the head of a line as a heading or a bullet. */
    private val OPENS_A_BLOCK = Regex("""^(#{1,3}|-) """)

    /** A number that opens a list item, with the mark that makes it one. */
    private val OPENS_A_COUNT = Regex("""^[0-9\u0660-\u0669\u06F0-\u06F9]{1,2}([.)])\s""")

    private fun headingPrefix(kind: ParagraphKind): String = when (kind) {
        ParagraphKind.TITLE, ParagraphKind.HEADING_1 -> "# "
        ParagraphKind.HEADING_2 -> "## "
        ParagraphKind.HEADING_3 -> "### "
        ParagraphKind.BODY -> ""
    }

    private fun runsToMarkdown(runs: List<TextRun>, notes: Notes): String {
        val sb = StringBuilder()
        var index = 0
        while (index < runs.size) {
            val run = runs[index]
            // A picture set among words — the head of a foot, beside the
            // page number that follows it. Markdown writes one inline, and
            // a run that carries a picture carries no text to write
            // instead, so passing over it loses the picture outright.
            val picture = run.image
            if (picture != null) {
                sb.append(pictureOf(picture))
                index++
                continue
            }
            // A mark that carries a note becomes the reference to it: the
            // mark is the run's own text, so it is what the reference
            // replaces, and the note itself waits at the end.
            val label = notes.labelOf(run)
            if (label != null) {
                sb.append("[^").append(label).append("]")
                index++
                continue
            }
            // A link is written around as many runs as carry it, so the
            // emphasis inside one stays inside it rather than closing the
            // link and opening it again.
            var end = index + 1
            while (end < runs.size &&
                notes.labelOf(runs[end]) == null &&
                runs[end].image == null &&
                runs[end].link == run.link
            ) end++
            val held = runs.subList(index, end)
            val link = run.link
            val words = held.joinToString("") { it.text }
            if (link != null && words.isNotBlank() && !linksToItself(words.trim(), link)) {
                // A link the source carried. An address written out in
                // full is left as it is: Markdown makes those live on
                // their own, and "[a@b.com](mailto:a@b.com)" only says the
                // same thing twice.
                sb.append(words.takeWhile { it.isWhitespace() })
                sb.append("[")
                appendStruck(sb, trimmedRuns(held))
                sb.append("](").append(link).append(")")
                sb.append(words.takeLastWhile { it.isWhitespace() })
            } else {
                appendStruck(sb, held)
            }
            index = end
        }
        return sb.toString()
    }

    /**
     * [runs] with one pair of tildes around each stretch that is struck
     * through, whatever changes inside it.
     *
     * Word splits a sentence into runs wherever it likes — at a
     * spell-check boundary, at a language change, at nothing at all — and
     * closing a marker only to open the same one again writes `~~a~~~~b~~`,
     * whose four tildes are four tildes on the page: a run of more than two
     * is not a marker here, and is not one in CommonMark either.
     */
    private fun appendStruck(sb: StringBuilder, runs: List<TextRun>) {
        var index = 0
        while (index < runs.size) {
            val struck = runs[index].strikethrough
            var end = index + 1
            while (end < runs.size && runs[end].strikethrough == struck) end++
            val held = runs.subList(index, end)
            val words = held.joinToString("") { it.text }
            if (!struck || words.isBlank()) {
                appendEmphasis(sb, held)
            } else {
                // A marker must hug non-whitespace or it will not re-parse,
                // so the space at either end is hoisted outside the pair.
                sb.append(words.takeWhile { it.isWhitespace() })
                sb.append("~~")
                appendEmphasis(sb, trimmedRuns(held))
                sb.append("~~")
                sb.append(words.takeLastWhile { it.isWhitespace() })
            }
            index = end
        }
    }

    /** [runs] with each stretch of one weight and slope written once. */
    private fun appendEmphasis(sb: StringBuilder, runs: List<TextRun>) {
        var index = 0
        while (index < runs.size) {
            val look = runs[index]
            var end = index + 1
            while (end < runs.size &&
                runs[end].bold == look.bold &&
                runs[end].italic == look.italic
            ) end++
            val words = runs.subList(index, end).joinToString("") { it.text }
            val marker = when {
                look.bold && look.italic -> "***"
                look.bold -> "**"
                look.italic -> "*"
                else -> ""
            }
            val core = words.trim()
            if (marker.isEmpty() || core.isEmpty()) {
                // Unstyled text, or a stretch of nothing but whitespace
                // that no marker could legally wrap, is written as it is.
                sb.append(escape(words))
            } else {
                sb.append(words.takeWhile { it.isWhitespace() })
                sb.append(marker).append(escape(core)).append(marker.reversed())
                sb.append(words.takeLastWhile { it.isWhitespace() })
            }
            index = end
        }
    }

    /** [runs] with the space at either end of the whole stretch taken off. */
    private fun trimmedRuns(runs: List<TextRun>): List<TextRun> {
        if (runs.isEmpty()) return runs
        val out = runs.toMutableList()
        out[0] = out[0].copy(text = out[0].text.trimStart())
        val last = out.size - 1
        out[last] = out[last].copy(text = out[last].text.trimEnd())
        return out
    }

    /** Whether the link says no more than the text it sits on already does. */
    private fun linksToItself(text: String, link: String): Boolean =
        link == text || link == "mailto:$text" || link == "https://$text" || link == "http://$text"

    // Brackets among them: a document's own words hold "see [note 3]" and
    // "[Ibn Khaldun 1377]", and a reader that has learnt what a link and a
    // note's mark look like would read those as one.
    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("*", "\\*").replace("~", "\\~").replace("|", "\\|")
            .replace("[", "\\[").replace("]", "\\]")
            // Markdown has no tab stops, and a line that begins with a tab
            // is a block of code — which is what a foot set as the page set
            // it, the head then a tab then the number, would have become.
            .replace("\t", " ")

    private fun appendTable(out: StringBuilder, table: Table, notes: Notes) {
        if (table.rows.isEmpty()) return
        val columnCount = table.rows.maxOf { it.cells.size }.coerceAtLeast(1)

        // Everything a cell holds, not only its paragraphs. Markdown has no
        // table inside a table and no way to invent one, so the words of an
        // inner table are given in the order they are read — but they are
        // given: dropped, a form or an invoice laid out as a table inside a
        // table loses the half of itself that carries the figures, and
        // nothing in the file says anything is missing.
        fun wordsOf(blocks: List<Block>): String =
            blocks.joinToString(" ") { block ->
                when (block) {
                    is Paragraph -> runsToMarkdown(block.runs, notes)
                    is Table -> block.rows.joinToString(" ") { row ->
                        row.cells.joinToString(" ") { wordsOf(it.blocks) }
                    }
                    is ImageBlock -> pictureOf(block)
                }
            }

        fun cellText(cell: TableCell): String = wordsOf(cell.blocks).replace("\n", " ")

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
