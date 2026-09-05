package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.ParagraphKind
import kotlin.math.roundToInt

/**
 * When a PDF says nothing about which paragraphs are headings, their type
 * size is the only evidence left. This is the one definition of how that
 * evidence is read, shared by the two readers that need it: the position
 * heuristics used for untagged files, and the tagged reader when a
 * structure tree turns out to carry no heading elements at all.
 *
 * A paragraph is a candidate when it is short and set meaningfully larger
 * than the body text. The distinct candidate sizes then rank descending
 * onto HEADING_1/2/3 — sizes below the third are left as body, because a
 * document with four heading sizes is likelier to be one with decorative
 * text than one with four real levels.
 */
object HeadingSizes {

    /** How much larger than body text a heading has to be set. */
    const val SIZE_FACTOR = 1.2f

    /** Headings are short; a long line set large is a pull quote or a cover. */
    const val MAX_CHARS = 80

    /** Half-point buckets, so float noise cannot multiply heading levels. */
    fun sizeKey(size: Float): Int = (size * 2).roundToInt()

    /** True when text of [length] set at [size] could be a heading. */
    fun isCandidate(size: Float, length: Int, bodySize: Float): Boolean =
        bodySize > 0f && length <= MAX_CHARS && size >= SIZE_FACTOR * bodySize

    /**
     * Ranks [candidateSizes] largest-first onto the three heading levels,
     * keyed by [sizeKey]. Sizes past the third rank are absent from the map
     * and stay body text.
     */
    fun rank(candidateSizes: List<Float>): Map<Int, ParagraphKind> =
        candidateSizes
            .map(::sizeKey)
            .distinct()
            .sortedDescending()
            .zip(LEVELS)
            .toMap()

    /**
     * The level a bold paragraph set at body size should take, given the
     * levels [sizeRanked] already claimed by larger type.
     *
     * Setting a heading in bold at the same size as the body is ordinary
     * practice — it is how most of a hand-formatted paper's section
     * headings are made — and it leaves type size with nothing to say. Such
     * a heading sits one level below whatever larger sizes found, so a paper
     * with a 15pt title and bold 12pt sections comes out with the title
     * above its sections rather than level with them.
     */
    fun boldLevel(sizeRanked: Map<Int, ParagraphKind>): ParagraphKind {
        val deepest = sizeRanked.values.maxByOrNull(LEVELS::indexOf)
        val next = LEVELS.indexOf(deepest ?: return ParagraphKind.HEADING_1) + 1
        return LEVELS.getOrElse(next) { ParagraphKind.HEADING_3 }
    }

    /**
     * Bold stops being evidence when most of the document is bold: that is a
     * typographic choice about the whole text, not a mark on its headings.
     */
    fun boldIsMeaningful(boldParagraphs: Int, totalParagraphs: Int): Boolean =
        totalParagraphs > 0 && boldParagraphs * 2 < totalParagraphs

    private val LEVELS =
        listOf(ParagraphKind.HEADING_1, ParagraphKind.HEADING_2, ParagraphKind.HEADING_3)

    /** Middle value of [values], averaging the pair when even; 0 when empty. */
    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2f
    }
}
