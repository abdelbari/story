package app.morpho.engine.layout

/**
 * The addresses a document writes out in full.
 *
 * A page can carry a link two ways: as an annotation the producer attached
 * to a rectangle, which says exactly where it points, or as an address
 * typed into the text — an author's email under a paper's title, a web
 * address in a footnote. The second is what most documents have, because
 * most authors type an address rather than insert a link, and a reader
 * that keeps it as plain text hands the reader of the converted file
 * something they have to copy out by hand.
 *
 * So text that is unmistakably an address becomes a link on the run that
 * holds it. Unmistakable is the whole rule: an email address with a
 * domain that has a dot in it, or a web address that names its scheme or
 * starts with www. A word with an @ in it is not enough, and neither is a
 * file name with a dot; guessing wrongly would turn ordinary words blue
 * and send a reader somewhere the author never meant.
 */
object Links {

    /**
     * An email address: an ASCII local part, an @, and a domain of at
     * least two labels. Deliberately ASCII — an Arabic word next to an
     * address must not be dragged into it.
     */
    private val EMAIL = Regex("[A-Za-z0-9._%+!#$&'*/=?^`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")

    /** A web address: one that names its scheme, or the www a reader can complete. */
    private val URL = Regex("(?:https?://|ftp://|www\\.)[A-Za-z0-9@:%._+~#=/?&,;!$'*-]*[A-Za-z0-9@:%_+~#=/$-]")

    /** Punctuation a sentence leaves at the end of an address, which is the sentence's and not the address's. */
    private const val TRAILING = ".,;:!?)]}»”’'\""

    /** The schemes a converted document may carry outward. */
    private val OUTWARD = setOf("http", "https", "mailto", "ftp")

    /** A scheme as the standard writes one: a letter, then letters, digits, plus, dot or dash. */
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    /**
     * Whether [target] is an address this converter will write into a
     * document for somebody else to open.
     *
     * Every address [find] makes is one of these by construction — it
     * writes `mailto:` in front of an email and `https://` in front of a
     * `www.`. An address read out of a *file* is whatever that file said,
     * and a file may have been made to say anything: a link annotation on
     * a crafted PDF, or a relationship in a crafted .docx, is carried
     * straight through to the converted document. Pointed at a share on
     * somebody else's machine, that is a document which reaches a
     * stranger's host the moment it is opened — from an app whose whole
     * promise is that a document converted here stays here. Pointed at a
     * scheme the system hands to some other program, it is worse.
     *
     * So the schemes a reader chooses to follow are carried and the rest
     * are not. What is dropped is the address alone: the words keep their
     * place in the sentence, which is the trade this converter makes
     * everywhere it cannot vouch for something.
     *
     * A name inside the document — written `#somewhere` — goes nowhere
     * near a network and is carried as it is.
     */
    fun writable(target: String): Boolean {
        val said = target.trim()
        if (said.startsWith("#")) return said.length > 1
        val scheme = SCHEME.find(said)?.groupValues?.get(1)?.lowercase() ?: return false
        return scheme in OUTWARD
    }

    /** One address found in a text, and where it sits. */
    data class Match(val start: Int, val end: Int, val target: String)

    /** Every address in [text], in order, none of them overlapping. */
    fun find(text: String): List<Match> {
        val matches = mutableListOf<Match>()
        for (found in EMAIL.findAll(text)) {
            val trimmed = trimTrailing(found.range.first, found.value)
            if (trimmed != null) matches += Match(trimmed.first, trimmed.second, "mailto:" + text.substring(trimmed.first, trimmed.second))
        }
        for (found in URL.findAll(text)) {
            val trimmed = trimTrailing(found.range.first, found.value) ?: continue
            // An address inside an email that was already found is that email's.
            if (matches.any { trimmed.first < it.end && it.start < trimmed.second }) continue
            val written = text.substring(trimmed.first, trimmed.second)
            val target = if (written.startsWith("www.", ignoreCase = true)) "https://$written" else written
            matches += Match(trimmed.first, trimmed.second, target)
        }
        return matches.sortedBy { it.start }
    }

    /** [value] at [start] without the sentence punctuation it ends on, or null when nothing is left. */
    private fun trimTrailing(start: Int, value: String): Pair<Int, Int>? {
        var end = start + value.length
        while (end > start && value[end - start - 1] in TRAILING) end--
        // A bracket that closes one the address itself opened stays.
        return if (end - start >= MIN_LENGTH) start to end else null
    }

    private const val MIN_LENGTH = 5

    /** [document] with every address in its text carried as a link on the run that holds it. */
    fun refine(document: DocumentModel): DocumentModel = document.copy(
        blocks = document.blocks.map(::refineBlock),
        header = document.header.map(::refineBlock),
        footer = document.footer.map(::refineBlock),
    )

    private fun refineBlock(block: Block): Block = when (block) {
        is Paragraph -> block.copy(runs = refineRuns(block.runs))
        // Copied, never rebuilt: a row knows whether it is the head of
        // its table and a cell knows how many columns and rows it covers
        // and what colour it is filled with, and a fresh one knows none of
        // it — a merged cell would come out of here unmerged.
        is Table -> block.copy(rows = block.rows.map { row ->
            row.copy(cells = row.cells.map { cell -> cell.copy(blocks = cell.blocks.map(::refineBlock)) })
        })
        is ImageBlock -> block
    }

    /**
     * The runs again, split where an address begins and ends. A run that
     * already carries a link of its own is left alone: an annotation the
     * producer attached says more than the shape of the text does.
     */
    fun refineRuns(runs: List<TextRun>): List<TextRun> {
        val text = runs.joinToString(separator = "") { it.text }
        val matches = find(text)
        if (matches.isEmpty()) return runs
        val result = mutableListOf<TextRun>()
        var offset = 0
        for (run in runs) {
            val start = offset
            val end = start + run.text.length
            offset = end
            if (run.text.isEmpty() || run.link != null || run.field != null || run.image != null) {
                result += run
                continue
            }
            var cut = start
            for (match in matches) {
                if (match.end <= start || match.start >= end) continue
                val from = maxOf(match.start, start)
                val to = minOf(match.end, end)
                if (from > cut) result += run.copy(text = run.text.substring(cut - start, from - start))
                result += run.copy(text = run.text.substring(from - start, to - start), link = match.target)
                cut = to
            }
            if (cut < end) result += run.copy(text = run.text.substring(cut - start))
        }
        return result
    }
}
