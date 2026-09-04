package app.morpho.engine.layout

/**
 * A paragraph whose words a reader has changed, with its formatting still
 * on the words that did not.
 *
 * The first edit anybody wants to make to a converted document is to a
 * word recognition got wrong, and the naive way to allow it loses the
 * paragraph: a text box holds a string, and a paragraph that goes into
 * one and comes back is one run of plain text. Its bold is gone, its
 * links are gone, the mark on its footnote is gone, and the reader who
 * corrected one letter has flattened a page.
 *
 * So the text is laid over the runs rather than replacing them. What is
 * the same at the front of the paragraph and at the back of it keeps
 * exactly the runs it had; what changed in between takes the look of the
 * character the change was typed after, which is what typing does in
 * every word processor there is. Correcting "recieve" to "receive"
 * leaves a paragraph identical in every other respect, and correcting a
 * word inside a link leaves it inside the link.
 */
object ParagraphEdit {

    /**
     * [first] and [second] as one paragraph, which is what they were
     * before something broke them apart.
     *
     * Recognition splits a paragraph wherever a page or a column ends,
     * because it never sees the two halves together, and the reader is
     * left with a sentence stopping mid-clause and starting again as a
     * paragraph of its own. Rejoining is not retyping: the second half
     * keeps its own runs, so its bold, its links and its notes come over
     * with it, which is the whole of the difference between this and
     * typing the second half into the first.
     *
     * The joined paragraph is the first one — its kind, its ranging, its
     * list — because that is where the sentence began. What sits between
     * the halves is a space, unless one side already ends or begins with
     * one, or with a break: a paragraph broken at a hyphenated word is
     * joined by the reader typing over the join afterwards, and a space
     * put in unasked would have to be taken out again.
     *
     * Confidence is the lower of the two, and this is the one edit where
     * it moves. A correction says something about the characters and
     * nothing about how they were read; a join makes one block whose
     * words really did come from both, and a block is only as certain as
     * the least certain thing in it.
     */
    fun join(first: Paragraph, second: Paragraph): Paragraph {
        if (second.runs.isEmpty()) return first
        if (first.runs.isEmpty()) {
            return first.copy(runs = second.runs, confidence = leastOf(first, second))
        }
        val gap = if (abuts(first.text, second.text)) emptyList() else listOf(spacer(first.runs))
        return first.copy(
            runs = merged(first.runs + gap + second.runs),
            confidence = leastOf(first, second),
        )
    }

    /** Whether nothing need go between [before] and [after] to keep them apart. */
    private fun abuts(before: String, after: String): Boolean =
        before.isEmpty() || after.isEmpty() ||
            before.last().isWhitespace() || after.first().isWhitespace()

    /**
     * The space between two joined halves, set the way the first half
     * ended — but never inside its link, which belonged to the words and
     * not to the gap after them.
     */
    private fun spacer(runs: List<TextRun>): TextRun =
        plain(runs.last()).copy(text = " ", link = null)

    private fun leastOf(first: Paragraph, second: Paragraph): Float =
        minOf(first.confidence, second.confidence)

    /**
     * [paragraph] saying [text] instead of what it said.
     *
     * The paragraph's own style — what kind it is, how it is ranged, what
     * list it belongs to — is untouched: this changes the words and
     * nothing about the paragraph.
     */
    fun retext(paragraph: Paragraph, text: String): Paragraph {
        val was = paragraph.text
        if (text == was) return paragraph
        if (paragraph.runs.isEmpty()) return paragraph.copy(runs = listOf(TextRun(text)))
        if (text.isEmpty()) {
            // Emptied rather than deleted: the paragraph is still there,
            // still whatever kind of paragraph it was, and a reader who
            // meant to delete it deletes it.
            return paragraph.copy(runs = listOf(lookOf(paragraph.runs, 0).copy(text = "")))
        }
        val head = sharedHead(was, text)
        val tail = sharedTail(was, text, head)
        val kept = lookOf(paragraph.runs, head)
        val typed = text.substring(head, text.length - tail)
        val runs = slice(paragraph.runs, 0, head) +
            listOfNotNull(typed.ifEmpty { null }?.let { plain(kept).copy(text = it) }) +
            slice(paragraph.runs, was.length - tail, was.length)
        return paragraph.copy(runs = merged(runs).ifEmpty { listOf(TextRun(text)) })
    }

    /**
     * How much of the front of the two is the same, never stopping inside
     * a character written as a surrogate pair.
     *
     * Splitting one would leave half of it in one run and half in another,
     * which is not a character at all — and the pairs are not exotic here:
     * an emoji is one, and so is much of the mathematical and historical
     * type a thesis uses.
     */
    private fun sharedHead(was: String, text: String): Int {
        val most = minOf(was.length, text.length)
        var at = 0
        while (at < most && was[at] == text[at]) at++
        return if (at > 0 && was[at - 1].isHighSurrogate()) at - 1 else at
    }

    /** The same at the back, never overlapping [head] and never splitting a pair. */
    private fun sharedTail(was: String, text: String, head: Int): Int {
        val most = minOf(was.length, text.length) - head
        var at = 0
        while (at < most && was[was.length - 1 - at] == text[text.length - 1 - at]) at++
        return if (at > 0 && was[was.length - at].isLowSurrogate()) at - 1 else at
    }

    /**
     * The look typing at [at] takes: the run the character to its left
     * belongs to.
     *
     * That is what every word processor does, and it is what makes an
     * edit inside a link stay inside the link. At the very start there is
     * nothing to the left, so the paragraph's first run stands in.
     */
    private fun lookOf(runs: List<TextRun>, at: Int): TextRun {
        if (at <= 0) return runs.first()
        var seen = 0
        for (run in runs) {
            seen += run.text.length
            if (at <= seen) return run
        }
        return runs.last()
    }

    /**
     * [run] as a look to type into: everything about how it is set, and
     * nothing that belongs to the particular characters it held.
     *
     * A picture is one of those, and so is a field the writer fills in:
     * text typed after a page number is text, not a second page number,
     * and text typed after a picture is not another copy of the picture.
     * A note's mark is the same — it belongs to the mark and not to what
     * follows it.
     */
    private fun plain(run: TextRun): TextRun =
        run.copy(text = "", field = null, image = null, note = null)

    /** The part of [runs] covering the characters from [from] until [to]. */
    private fun slice(runs: List<TextRun>, from: Int, to: Int): List<TextRun> {
        if (from >= to) return emptyList()
        val out = mutableListOf<TextRun>()
        var at = 0
        for (run in runs) {
            val start = at
            val end = at + run.text.length
            at = end
            // A picture sits in the line as a character with no text of
            // its own, so it belongs to the position it was at and is kept
            // whole or not at all.
            if (run.text.isEmpty()) {
                if (start in from until maxOf(to, from + 1)) out += run
                continue
            }
            val opens = maxOf(from, start)
            val shuts = minOf(to, end)
            if (opens < shuts) out += run.copy(text = run.text.substring(opens - start, shuts - start))
        }
        return out
    }

    /**
     * [runs] with neighbours that are set alike joined, and empty ones
     * dropped.
     *
     * Two runs are set alike when everything but their text is equal,
     * which is asked by comparing them with their text taken off — so a
     * property added to a run later is compared without anybody having to
     * remember this. A run carrying something of its own, rather than a
     * way of being set, is never joined to its neighbour.
     */
    private fun merged(runs: List<TextRun>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        for (run in runs) {
            if (run.text.isEmpty() && run.image == null && run.field == null) continue
            val last = out.lastOrNull()
            val joinable = last != null &&
                last.field == null && run.field == null &&
                last.image == null && run.image == null &&
                last.note == null && run.note == null &&
                last.copy(text = "") == run.copy(text = "")
            if (joinable) out[out.size - 1] = last!!.copy(text = last.text + run.text) else out += run
        }
        return out
    }
}
