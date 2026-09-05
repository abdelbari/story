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
 * A page may also set the mark on a line of its own, small, with the
 * words of the note under it — read off a page with no tags, that is two
 * paragraphs rather than one, and the mark is not raised because there is
 * nothing on its line for it to be raised above. Those two are a note as
 * much as the one is.
 *
 * What keeps either shape honest is the far end: a mark is only a note's
 * mark when the same mark is raised somewhere earlier in the document. A
 * mark with nothing to point at is left where it is — better an odd
 * paragraph than a note that disappears.
 */
object Footnotes {

    /** A mark is this short — "*", "1", "†", "12". */
    private const val LONGEST_MARK = 3

    /** A note as the page set it: its mark, where the mark is, and where its words are. */
    private class Found(val mark: String, val markIndex: Int, val bodyIndex: Int)

    /**
     * Which blocks are a page's notes rather than its text.
     *
     * A note is pinned to the foot of the page whatever the text above it
     * does, so a page whose text stopped half way still has ink near its
     * bottom edge. Anything measuring how far a page's text ran has to
     * leave the notes out of it, and this says which they are — by the
     * same reading [refine] uses to move them, before it moves them.
     */
    fun noteBlocks(blocks: List<Block>): Set<Int> {
        val out = mutableSetOf<Int>()
        for (found in notesIn(blocks)) {
            out += found.markIndex
            out += found.bodyIndex
        }
        return out
    }

    /** [document] with each note moved onto the mark that refers to it. */
    fun refine(document: DocumentModel): DocumentModel {
        val notes = notesIn(document.blocks)
        if (notes.isEmpty()) return document
        val blocks = document.blocks.toMutableList()
        val placed = mutableSetOf<Int>()
        val spokenFor = mutableSetOf<Pair<Int, Int>>()
        for (found in notes) {
            val note = blocks[found.bodyIndex] as? Paragraph ?: continue
            // The note without its own mark, which the writer sets again,
            // and without the page's separator rule: a writer that knows
            // notes draws that rule itself, and two would be one too many.
            val body = if (found.bodyIndex == found.markIndex) {
                note.copy(
                    runs = note.runs.drop(1).trimLeadingSpace(),
                    style = note.style.copy(ruleAbove = false),
                )
            } else {
                note.copy(style = note.style.copy(ruleAbove = false))
            }
            if (body.text.isBlank()) continue
            val reference =
                referenceTo(blocks, found.mark, before = found.markIndex, spokenFor = spokenFor) ?: continue
            spokenFor += reference
            val paragraph = blocks[reference.first] as Paragraph
            val runs = paragraph.runs.toMutableList()
            runs[reference.second] = runs[reference.second].copy(note = listOf(body))
            blocks[reference.first] = paragraph.copy(runs = runs)
            placed += found.markIndex
            placed += found.bodyIndex
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
    private fun notesIn(blocks: List<Block>): List<Found> {
        val notes = mutableListOf<Found>()
        var following = false
        var index = 0
        while (index < blocks.size) {
            val paragraph = blocks[index] as? Paragraph
            val opening = paragraph?.let(::markOf)
            val alone = paragraph?.let(::loneMark)
            val under = paragraph != null && (paragraph.style.ruleAbove || following)
            val words = (blocks.getOrNull(index + 1) as? Paragraph)?.takeIf { loneMark(it) == null }
            when {
                under && opening != null -> {
                    notes += Found(opening, index, index)
                    following = true
                    index++
                }
                // The mark on a line of its own, the note under it.
                under && alone != null && words != null -> {
                    notes += Found(alone, index, index + 1)
                    following = true
                    index += 2
                }
                else -> {
                    following = false
                    index++
                }
            }
        }
        return notes
    }

    /**
     * The mark a paragraph consists of and nothing else, or null when it
     * says more than a mark says. A word is not a mark, however short,
     * but a single letter may be one: a page that letters its notes أ ب
     * ت sets them the same way a page that stars them does.
     */
    private fun loneMark(paragraph: Paragraph): String? {
        val mark = paragraph.text.trim()
        if (mark.isEmpty() || mark.length > LONGEST_MARK) return null
        if (mark.length > 1 && mark.any { it.isLetter() }) return null
        return mark
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
