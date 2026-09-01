package app.morpho.engine.layout

/**
 * Direction analysis for the document model. [firstStrongDirection] is the
 * paragraph-level heuristic the importers use to tag paragraph direction;
 * [refineRuns] is the full UAX #9 run analysis (via [java.text.Bidi]) that
 * splits a paragraph's styled runs at direction boundaries, so writers can
 * mark direction per run (w:rtl in OOXML, dir spans in HTML) instead of
 * guessing from a run's first strong character — which mislabels the rest
 * of a mixed run.
 */
object Bidi {

    /**
     * Direction of the first strongly-directional code point, or null when the
     * text has none (digits, punctuation, whitespace only).
     */
    fun firstStrongDirection(text: String): TextDirection? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT ->
                    return TextDirection.LTR
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ->
                    return TextDirection.RTL
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return null
    }

    /** One directional segment of a paragraph's text, in logical order. */
    data class DirectionalRun(val start: Int, val end: Int, val direction: TextDirection)

    /**
     * Splits [text] into directional runs per UAX #9, resolved against the
     * paragraph's [base] direction. Neutrals between same-direction text take
     * the base direction, and European/Arabic-Indic digits inside RTL text
     * resolve to an even embedding level, exactly as the algorithm says.
     * Adjacent runs whose levels share parity are merged, so each returned
     * run flips direction. Runs are contiguous, in logical order, and cover
     * the whole text.
     */
    fun directionalRuns(text: String, base: TextDirection): List<DirectionalRun> {
        if (text.isEmpty()) return emptyList()
        val bidi = java.text.Bidi(
            text,
            if (base == TextDirection.RTL) java.text.Bidi.DIRECTION_RIGHT_TO_LEFT
            else java.text.Bidi.DIRECTION_LEFT_TO_RIGHT,
        )
        val out = mutableListOf<DirectionalRun>()
        for (i in 0 until bidi.runCount) {
            val direction =
                if (bidi.getRunLevel(i) % 2 == 1) TextDirection.RTL else TextDirection.LTR
            val last = out.lastOrNull()
            if (last != null && last.direction == direction) {
                out[out.size - 1] = last.copy(end = bidi.getRunLimit(i))
            } else {
                out += DirectionalRun(bidi.getRunStart(i), bidi.getRunLimit(i), direction)
            }
        }
        return out
    }

    /**
     * Re-splits a paragraph's styled runs so every run is uniformly
     * directional. Styling and language carry over to each piece; a piece's
     * direction is explicit only where it differs from [paragraphDirection],
     * keeping the model's "null = inherit" contract (a stale explicit
     * direction equal to the paragraph's is normalized away). The
     * concatenated text is unchanged — the analysis runs over the whole
     * paragraph, so a boundary can fall inside or between styled runs.
     */
    fun refineRuns(runs: List<TextRun>, paragraphDirection: TextDirection): List<TextRun> {
        val text = runs.joinToString(separator = "") { it.text }
        val segments = directionalRuns(text, paragraphDirection)
        if (segments.size <= 1) {
            val direction = segments.firstOrNull()?.direction ?: paragraphDirection
            val explicit = direction.takeIf { it != paragraphDirection }
            return runs.map { if (it.direction == explicit) it else it.copy(direction = explicit) }
        }
        val result = mutableListOf<TextRun>()
        var offset = 0
        var index = 0
        for (run in runs) {
            if (run.text.isEmpty()) {
                result += run
                continue
            }
            val runEnd = offset + run.text.length
            var cursor = offset
            while (cursor < runEnd) {
                while (segments[index].end <= cursor) index++
                val segment = segments[index]
                val end = minOf(runEnd, segment.end)
                result += run.copy(
                    text = run.text.substring(cursor - offset, end - offset),
                    direction = segment.direction.takeIf { it != paragraphDirection },
                )
                cursor = end
            }
            offset = runEnd
        }
        return result
    }

    /**
     * [refineRuns] applied to every paragraph in the model, table cells
     * included. Readers call this once on their finished model; DOCX input
     * is deliberately not refined, since its runs carry the author's own
     * explicit direction marks.
     */
    fun refine(model: DocumentModel): DocumentModel =
        model.copy(blocks = model.blocks.map { refineBlock(it, model.defaultDirection) })

    private fun refineBlock(block: Block, defaultDirection: TextDirection): Block = when (block) {
        is Paragraph -> block.copy(
            runs = refineRuns(block.runs, block.style.direction ?: defaultDirection)
        )
        is Table -> block.copy(rows = block.rows.map { row ->
            TableRow(row.cells.map { cell ->
                TableCell(cell.blocks.map { refineBlock(it, defaultDirection) })
            })
        })
        is ImageBlock -> block
    }
}
