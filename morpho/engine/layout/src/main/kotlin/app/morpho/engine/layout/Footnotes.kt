package app.morpho.engine.layout

/**
 * The notes a page sets at its foot.
 *
 * A PDF has no notes: it has a rule drawn across the bottom of a page and
 * some small text under it, and, somewhere above, a raised mark. Nothing
 * joins the two — a reader that keeps the words in the order they were
 * painted drops the note into the middle of the text, between the abstract
 * it interrupts and the section that follows.
 *
 * The mark is what joins them. A paragraph under a page's separator rule
 * that opens with a raised mark is a note, and the run that carries that
 * same mark, raised, earlier in the document is what it belongs to. The
 * note moves there, and a writer with footnotes of its own sets it at the
 * foot of whatever page the mark lands on.
 *
 * A mark with nothing to point at is left where it is: better an odd
 * paragraph than a note that disappears.
 */
object Footnotes {

    /** A mark is this short — "*", "1", "†", "12". */
    private const val LONGEST_MARK = 3

    /** [document] with each note moved onto the mark that refers to it. */
    fun refine(document: DocumentModel): DocumentModel {
        val notes = notesIn(document.blocks)
        if (notes.isEmpty()) return document
        val blocks = document.blocks.toMutableList()
        val placed = mutableSetOf<Int>()
        val spokenFor = mutableSetOf<Pair<Int, Int>>()
        for ((index, mark) in notes) {
            val note = blocks[index] as? Paragraph ?: continue
            // The note without its own mark, which the writer sets again,
            // and without the page's separator rule: a writer that knows
            // notes draws that rule itself, and two would be one too many.
            val body = note.copy(
                runs = note.runs.drop(1).trimLeadingSpace(),
                style = note.style.copy(ruleAbove = false),
            )
            if (body.text.isBlank()) continue
            val reference = referenceTo(blocks, mark, before = index, spokenFor = spokenFor) ?: continue
            spokenFor += reference
            val paragraph = blocks[reference.first] as Paragraph
            val runs = paragraph.runs.toMutableList()
            runs[reference.second] = runs[reference.second].copy(note = listOf(body))
            blocks[reference.first] = paragraph.copy(runs = runs)
            placed += index
        }
        if (placed.isEmpty()) return document
        return document.copy(blocks = blocks.filterIndexed { index, _ -> index !in placed })
    }

    /** The runs without the space a note leaves between its mark and its first word. */
    private fun List<TextRun>.trimLeadingSpace(): List<TextRun> {
        val first = firstOrNull() ?: return this
        val trimmed = first.text.trimStart()
        if (trimmed == first.text) return this
        return listOf(first.copy(text = trimmed)) + drop(1)
    }

    /**
     * The paragraphs that are notes, each with the mark it opens with: one
     * under a page's separator rule, and any that follow it in the same
     * small type, which is how a page with two notes sets its second.
     */
    private fun notesIn(blocks: List<Block>): List<Pair<Int, String>> {
        val notes = mutableListOf<Pair<Int, String>>()
        var following = false
        for ((index, block) in blocks.withIndex()) {
            val paragraph = block as? Paragraph
            val mark = paragraph?.let(::markOf)
            if (mark == null) {
                following = false
                continue
            }
            if (paragraph.style.ruleAbove || following) {
                notes += index to mark
                following = true
            }
        }
        return notes
    }

    /** The raised mark a note opens with, or null when the paragraph does not open with one. */
    private fun markOf(paragraph: Paragraph): String? {
        val first = paragraph.runs.firstOrNull() ?: return null
        if (!first.superscript) return null
        val mark = first.text.trim()
        if (mark.isEmpty() || mark.length > LONGEST_MARK) return null
        if (paragraph.runs.size < 2) return null
        return mark
    }

    /**
     * Where the same mark is raised in the text before the note: the block
     * and the run within it. Marks already spoken for are skipped, so two
     * notes marked alike find two different references.
     */
    private fun referenceTo(
        blocks: List<Block>,
        mark: String,
        before: Int,
        spokenFor: Set<Pair<Int, Int>>,
    ): Pair<Int, Int>? {
        for (index in 0 until before) {
            val paragraph = blocks[index] as? Paragraph ?: continue
            for ((run, text) in paragraph.runs.withIndex()) {
                if (index to run in spokenFor) continue
                if (text.superscript && text.text.trim() == mark && text.note == null) return index to run
            }
        }
        return null
    }
}
