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

    /**
     * Rebuilds logical order from text captured in visual order.
     *
     * A PDF content stream paints glyphs in the order they land on the page,
     * left to right, so a right-to-left line arrives backwards: characters
     * reversed and words in the opposite order. PDFBox's text stripper undoes
     * this before handing text to callers, but the marked-content path a
     * tagged PDF is read through never goes past the raw glyphs, so the
     * reconstruction has to happen here instead.
     *
     * This is UAX #9 run reordering run backwards: split the visual text into
     * runs, put those runs back in logical order, and reverse the characters
     * inside each right-to-left run.
     *
     * Paired punctuation is deliberately left alone. Rendering mirrors the
     * glyph, not the character, and a PDF's ToUnicode maps that glyph back to
     * the character the author typed — so "(" reaches us as "(" however it was
     * drawn, and reversing the run is enough to put it back at the opening
     * end. Mirroring here as well would turn a correct "(text)" into ")text(".
     * PDFBox does mirror, which is why its own output disagrees on this.
     *
     * Text with no right-to-left character is returned unchanged, so
     * left-to-right documents pass through untouched.
     */
    fun visualToLogical(text: String, base: TextDirection? = null): String {
        if (text.isEmpty() || !containsRtl(text)) return text
        // The base direction is the paragraph's, and a line cannot tell its
        // own: an Arabic line whose leftmost word is an email address starts,
        // visually, with a Latin letter. Callers that know the document pass
        // it; the first strong character is only the fallback.
        val flags = when (base) {
            TextDirection.RTL -> java.text.Bidi.DIRECTION_RIGHT_TO_LEFT
            TextDirection.LTR -> java.text.Bidi.DIRECTION_LEFT_TO_RIGHT
            null -> java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
        }
        // A number with separators — a date, a time, a page range — reads
        // left to right as one unit wherever it sits. Run through the bidi
        // rules in visual order it does not: digits that happen to follow
        // Arabic letters count as Arabic numbers, the hyphens between them
        // become right-to-left separators, and 2022-04-21 comes back as
        // 21-04-2022 — while the same date at the start of a line, with no
        // letter before it, comes back whole. Fencing each such number with
        // left-to-right marks gives its digits a left-to-right neighbour on
        // both sides, so the rules see one European number; the marks are
        // removed again once the line is in order.
        val fenced = NUMBER_WITH_SEPARATORS.replace(text) { "\u200E${it.value}\u200E" }
        val bidi = java.text.Bidi(fenced, flags)
        if (!bidi.isMixed && bidi.baseIsLeftToRight()) return text
        val runCount = bidi.runCount
        val levels = ByteArray(runCount)
        // reorderVisually permutes this array and reads levels by the original
        // index, so the levels array itself must stay in run order.
        val logicalOrder = arrayOfNulls<Any>(runCount)
        for (run in 0 until runCount) {
            levels[run] = bidi.getRunLevel(run).toByte()
            logicalOrder[run] = run
        }
        java.text.Bidi.reorderVisually(levels, 0, logicalOrder, 0, runCount)
        val out = StringBuilder(fenced.length)
        for (position in 0 until runCount) {
            val run = logicalOrder[position] as Int
            val start = bidi.getRunStart(run)
            var end = bidi.getRunLimit(run)
            if (levels[run].toInt() and 1 == 1) {
                while (end > start) out.append(fenced[--end])
            } else {
                out.append(fenced, start, end)
            }
        }
        return out.toString().replace("\u200E", "")
    }

    /** Digits — European or Arabic-Indic — joined by the separators dates, times and ranges use. */
    private val NUMBER_WITH_SEPARATORS =
        Regex("[0-9\u0660-\u0669]+(?:[-\u2013/.:,][0-9\u0660-\u0669]+)+")

    /**
     * The direction most of [text] is written in — right-to-left if its
     * strongly right-to-left characters outnumber the left-to-right ones —
     * or null when it has neither. Over a whole document this is the base
     * direction its lines should be reconstructed against when the file
     * does not say.
     */
    fun dominantDirection(text: CharSequence): TextDirection? {
        var ltr = 0
        var rtl = 0
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return when {
            rtl == 0 && ltr == 0 -> null
            rtl > ltr -> TextDirection.RTL
            else -> TextDirection.LTR
        }
    }

    /**
     * The writing direction of a BCP 47 language tag such as a PDF's /Lang
     * ("ar-DZ", "he", "en-US"), or null when the tag is absent or unknown.
     */
    fun directionOfLanguage(tag: String?): TextDirection? {
        val primary = tag?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (primary in RTL_LANGUAGES) TextDirection.RTL else TextDirection.LTR
    }

    private val RTL_LANGUAGES = setOf("ar", "he", "fa", "ur", "ps", "sd", "ug", "yi", "dv")

    /** True when [text] carries any strongly right-to-left character. */
    private fun containsRtl(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                else -> {}
            }
            i += Character.charCount(cp)
        }
        return false
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
