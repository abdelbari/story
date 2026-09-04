package app.morpho.engine.layout

/**
 * The Fidelity Report (plan §4): a structured account of how certain the
 * conversion is and where the doubts sit, computed purely from the per-block
 * confidence every reader records — native formats 1.0, tagged PDFs 0.9,
 * untagged heuristics 0.6, OCR 0.5. This is the data layer Review Mode and
 * the post-save notice render; it has no opinions about UI.
 *
 * Bands: [Band.HIGH] is ≥ 0.85 (native, tagged), [Band.MEDIUM] is ≥ 0.55
 * (position heuristics), [Band.LOW] is everything below (OCR). The overall
 * score is a text-length-weighted mean, so one doubtful footnote does not
 * drown a page of certain text — and a doubtful page is not hidden by a
 * certain footnote.
 */
object FidelityReport {

    enum class Band { HIGH, MEDIUM, LOW }

    /**
     * How a block's content was obtained, derived from the same confidence
     * the readers record. A band says how much to worry; this says why —
     * which is what a person actually needs in order to check the right
     * thing. Keeping the mapping here means no caller has to interpret
     * bare floats.
     */
    enum class Source {
        /** Read exactly from a format that states its own structure (DOCX, Markdown). */
        EXACT,

        /** Read from a PDF's own structure tags. */
        TAGGED,

        /** Reconstructed from glyph positions in an untagged PDF. */
        RECONSTRUCTED,

        /** Recognized from an image by OCR. */
        RECOGNIZED,
    }

    data class Entry(
        /** Index of the block in [DocumentModel.blocks]. */
        val index: Int,
        val kind: Kind,
        /** Up to [EXCERPT_LENGTH] code points of the block's text. */
        val excerpt: String,
        val confidence: Float,
        val band: Band,
        val source: Source,
    )

    enum class Kind { HEADING, PARAGRAPH, TABLE, IMAGE }

    data class Report(
        /** One entry per block, in document order. */
        val entries: List<Entry>,
        /** Text-length-weighted mean confidence over all blocks. */
        val overall: Float,
        /** Every band is present as a key, possibly with count 0. */
        val counts: Map<Band, Int>,
        /** MEDIUM and LOW entries, most doubtful first. */
        val reviewables: List<Entry>,
    )

    fun of(model: DocumentModel): Report {
        val entries = model.blocks.mapIndexed { index, block ->
            Entry(
                index = index,
                kind = kindOf(block),
                excerpt = excerptOf(block),
                confidence = block.confidence,
                band = bandOf(block.confidence),
                source = sourceOf(block.confidence),
            )
        }
        var weightSum = 0L
        var weighted = 0.0
        for ((entry, block) in entries.zip(model.blocks)) {
            val weight = weightOf(block)
            weightSum += weight
            weighted += entry.confidence.toDouble() * weight
        }
        return Report(
            entries = entries,
            overall = if (weightSum == 0L) 1f else (weighted / weightSum).toFloat(),
            counts = Band.entries.associateWith { band -> entries.count { it.band == band } },
            reviewables = entries.filter { it.band != Band.HIGH }.sortedBy { it.confidence },
        )
    }

    private fun bandOf(confidence: Float): Band = when {
        confidence >= 0.85f -> Band.HIGH
        confidence >= 0.55f -> Band.MEDIUM
        else -> Band.LOW
    }

    /** The reader conventions, read backwards: 1.0 native, 0.9 tagged, 0.6 heuristics, 0.5 OCR. */
    private fun sourceOf(confidence: Float): Source = when {
        confidence >= 0.99f -> Source.EXACT
        confidence >= 0.85f -> Source.TAGGED
        confidence >= 0.55f -> Source.RECONSTRUCTED
        else -> Source.RECOGNIZED
    }

    private fun kindOf(block: Block): Kind = when (block) {
        is Paragraph ->
            if (block.style.kind == ParagraphKind.BODY) Kind.PARAGRAPH else Kind.HEADING
        is Table -> Kind.TABLE
        is ImageBlock -> Kind.IMAGE
    }

    private fun excerptOf(block: Block): String {
        val text = when (block) {
            is Paragraph -> opening(block.runs, EXCERPT_LENGTH)
            is Table -> block.rows.firstOrNull()?.cells.orEmpty()
                .flatMap { it.blocks }.filterIsInstance<Paragraph>()
                .joinToString(separator = " ") { it.text }
            is ImageBlock -> return ""
        }
        if (text.codePointCount(0, text.length) <= EXCERPT_LENGTH) return text
        val end = text.offsetByCodePoints(0, EXCERPT_LENGTH)
        return text.substring(0, end) + "…"
    }

    /**
     * Enough of what [runs] say to cut an excerpt of [most] code points
     * from, and no more of it than that.
     *
     * A paragraph's text is built afresh from its runs every time it is
     * read, and an excerpt is eighty code points of a paragraph that may
     * be thousands — so building the whole of one to throw nearly all of
     * it away was the greater part of what a report cost, and a report is
     * made again after every correction a reader makes. Measured on a
     * three-thousand-block scan: 23ms a report before this, on a desktop.
     *
     * The stop is counted in characters rather than code points because
     * counting code points as they arrive is quadratic over many short
     * runs. A code point is one character or two, so twice [most]
     * characters is always at least [most] code points and the excerpt
     * can never come out short.
     */
    private fun opening(runs: List<TextRun>, most: Int): String {
        val out = StringBuilder()
        for (run in runs) {
            out.append(run.text)
            if (out.length > most * 2) break
        }
        return out.toString()
    }

    /**
     * Text length, at least 1, so empty blocks and images still count.
     *
     * Counted off the runs rather than off the text, for the same reason
     * [opening] exists: this needs a number, and building the whole of
     * every block to measure it doubled what a report cost.
     */
    private fun weightOf(block: Block): Long = when (block) {
        is Paragraph -> lengthOf(block).coerceAtLeast(1L)
        is Table -> block.rows.sumOf { row ->
            row.cells.sumOf { cell ->
                cell.blocks.filterIsInstance<Paragraph>().sumOf(::lengthOf)
            }
        }.coerceAtLeast(1L)
        is ImageBlock -> 1L
    }

    private fun lengthOf(paragraph: Paragraph): Long =
        paragraph.runs.sumOf { it.text.length.toLong() }

    private const val EXCERPT_LENGTH = 80
}
