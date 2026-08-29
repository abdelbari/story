package app.morpho.engine.layout

import java.text.Normalizer

/**
 * Pure similarity metrics comparing an expected [DocumentModel] (or its text)
 * against what a conversion actually produced. These scores power the
 * multilingual corpus CI gate today and feed the app's aggregate fidelity
 * number later, so they are deterministic, dependency-free, and deliberately
 * blunt: edit distance, not linguistics. They measure how much survived a
 * conversion, not whether the result reads well.
 */
object FidelityScorer {

    /**
     * Text similarity in 0..1: one minus the Levenshtein distance between the
     * two strings, normalized by the longer length. Both inputs are first
     * canonicalized — NFC-normalized via [java.text.Normalizer] with every run
     * of whitespace collapsed to a single space and outer whitespace dropped —
     * so re-wrapped lines and composed-versus-decomposed accents never count
     * as differences. Distance is computed over code points in O(n*m) time and
     * O(min(n, m)) space. Identical inputs score exactly 1.0, as do two inputs
     * that are both empty after canonicalization.
     */
    fun textSimilarity(a: String, b: String): Double =
        similarity(codePoints(canonicalText(a)), codePoints(canonicalText(b)))

    /**
     * Structural similarity in 0..1 between two documents: every block is
     * reduced to a signature — paragraph kind, list marker, and effective
     * direction (the paragraph's own, else the document default); tables as
     * "table RxC" — and the two signature sequences are compared by normalized
     * edit distance. Text content is ignored entirely; a heading demoted to
     * body or a dropped list marker costs one edit, so a single structural
     * regression lowers the score below 1.0 without collapsing it to 0. Two
     * empty documents score 1.0.
     */
    fun structureSimilarity(expected: DocumentModel, actual: DocumentModel): Double =
        similarity(signatures(expected), signatures(actual))

    private val whitespaceRun = Regex("""\s+""")

    private fun canonicalText(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(whitespaceRun, " ")
            .trim()

    private fun codePoints(text: String): List<Int> = text.codePoints().toArray().toList()

    private fun signatures(model: DocumentModel): List<String> =
        model.blocks.map { block ->
            when (block) {
                is Paragraph -> {
                    val direction = block.style.direction ?: model.defaultDirection
                    "paragraph ${block.style.kind} ${block.style.listMarker ?: "-"} $direction"
                }
                is Table -> {
                    val columns = block.rows.maxOfOrNull { it.cells.size } ?: 0
                    "table ${block.rows.size}x$columns"
                }
                is ImageBlock -> "image"
            }
        }

    private fun <T> similarity(a: List<T>, b: List<T>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / maxOf(a.size, b.size)
    }

    /** Two-row Levenshtein: O(n*m) time, O(min(n, m)) space. */
    private fun <T> editDistance(a: List<T>, b: List<T>): Int {
        val (longer, shorter) = if (a.size >= b.size) a to b else b to a
        if (shorter.isEmpty()) return longer.size
        var previous = IntArray(shorter.size + 1) { it }
        var current = IntArray(shorter.size + 1)
        for (i in 1..longer.size) {
            current[0] = i
            for (j in 1..shorter.size) {
                val substitution =
                    previous[j - 1] + if (longer[i - 1] == shorter[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[shorter.size]
    }
}
