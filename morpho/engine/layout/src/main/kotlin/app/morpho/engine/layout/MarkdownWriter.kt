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
 * Honest losses, stated rather than hidden: Markdown has no underline (the
 * flag is dropped), no text direction markup (RTL survives in the characters
 * themselves, not in syntax), and no per-run language tags.
 */
object MarkdownWriter {

    fun write(document: DocumentModel): String {
        val out = StringBuilder()
        var previousWasListItem = false
        var numberedCount = 0

        for (block in document.blocks) {
            when (block) {
                is Paragraph -> {
                    val marker = block.style.listMarker
                    if (out.isNotEmpty()) {
                        // Items of one list stay adjacent; everything else gets a blank line.
                        out.append(if (marker != null && previousWasListItem) "\n" else "\n\n")
                    }
                    when (marker) {
                        ListMarker.BULLET -> {
                            numberedCount = 0
                            out.append("- ").append(runsToMarkdown(block.runs))
                        }
                        ListMarker.NUMBERED -> {
                            numberedCount = if (previousWasListItem) numberedCount + 1 else 1
                            out.append(numberedCount).append(". ").append(runsToMarkdown(block.runs))
                        }
                        null -> {
                            numberedCount = 0
                            out.append(headingPrefix(block.style.kind)).append(runsToMarkdown(block.runs))
                        }
                    }
                    previousWasListItem = marker != null
                }
                is Table -> {
                    if (out.isNotEmpty()) out.append("\n\n")
                    appendTable(out, block)
                    previousWasListItem = false
                    numberedCount = 0
                }
                is ImageBlock -> throw UnsupportedOperationException(
                    "ImageBlock is not supported yet: image extraction lands with milestone M1. " +
                        "Refusing to write a document that would silently lose content."
                )
            }
        }
        if (out.isNotEmpty()) out.append("\n")
        return out.toString()
    }

    private fun headingPrefix(kind: ParagraphKind): String = when (kind) {
        ParagraphKind.TITLE, ParagraphKind.HEADING_1 -> "# "
        ParagraphKind.HEADING_2 -> "## "
        ParagraphKind.HEADING_3 -> "### "
        ParagraphKind.BODY -> ""
    }

    private fun runsToMarkdown(runs: List<TextRun>): String {
        val sb = StringBuilder()
        for (run in runs) {
            val marker = when {
                run.bold && run.italic -> "***"
                run.bold -> "**"
                run.italic -> "*"
                else -> ""
            }
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
            sb.append(marker).append(escape(core)).append(marker)
            sb.append(run.text.takeLastWhile { it.isWhitespace() })
        }
        return sb.toString()
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("*", "\\*").replace("|", "\\|")

    private fun appendTable(out: StringBuilder, table: Table) {
        if (table.rows.isEmpty()) return
        val columnCount = table.rows.maxOf { it.cells.size }.coerceAtLeast(1)

        fun cellText(cell: TableCell): String =
            cell.blocks.filterIsInstance<Paragraph>()
                .joinToString(" ") { runsToMarkdown(it.runs) }
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
