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

    data class Entry(
        /** Index of the block in [DocumentModel.blocks]. */
        val index: Int,
        val kind: Kind,
        /** Up to [EXCERPT_LENGTH] code points of the block's text. */
        val excerpt: String,
        val confidence: Float,
        val band: Band,
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

    private fun kindOf(block: Block): Kind = when (block) {
        is Paragraph ->
            if (block.style.kind == ParagraphKind.BODY) Kind.PARAGRAPH else Kind.HEADING
        is Table -> Kind.TABLE
        is ImageBlock -> Kind.IMAGE
    }

    private fun excerptOf(block: Block): String {
        val text = when (block) {
            is Paragraph -> block.text
            is Table -> block.rows.firstOrNull()?.cells.orEmpty()
                .flatMap { it.blocks }.filterIsInstance<Paragraph>()
                .joinToString(separator = " ") { it.text }
            is ImageBlock -> return ""
        }
        if (text.codePointCount(0, text.length) <= EXCERPT_LENGTH) return text
        val end = text.offsetByCodePoints(0, EXCERPT_LENGTH)
        return text.substring(0, end) + "…"
    }

    /** Text length, at least 1, so empty blocks and images still count. */
    private fun weightOf(block: Block): Long = when (block) {
        is Paragraph -> block.text.length.toLong().coerceAtLeast(1L)
        is Table -> block.rows.sumOf { row ->
            row.cells.sumOf { cell ->
                cell.blocks.filterIsInstance<Paragraph>().sumOf { it.text.length.toLong() }
            }
        }.coerceAtLeast(1L)
        is ImageBlock -> 1L
    }

    private const val EXCERPT_LENGTH = 80
}
