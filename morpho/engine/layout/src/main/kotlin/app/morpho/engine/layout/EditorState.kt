package app.morpho.engine.layout

/**
 * A place a caret can stand: [offset] UTF-16 units into the text of the
 * paragraph at [block].
 *
 * UTF-16 because that is what a Kotlin string and a JavaScript string
 * both count in, so a position handed across the bridge from the screen
 * means the same thing on both sides without anybody converting it. A
 * caret is only ever in a paragraph at the top of the document; a cell of
 * a table is a later stage of the editor and will widen this rather than
 * change it.
 */
data class Caret(val block: Int, val offset: Int) : Comparable<Caret> {
    override fun compareTo(other: Caret): Int =
        if (block != other.block) block.compareTo(other.block) else offset.compareTo(other.offset)
}

/**
 * What a reader has selected, from where they put the caret down to
 * where they dragged it — either way round, since a selection made
 * backwards is still the same selection.
 */
data class Selection(val anchor: Caret, val focus: Caret = anchor) {
    val start: Caret get() = minOf(anchor, focus)
    val end: Caret get() = maxOf(anchor, focus)
    val collapsed: Boolean get() = anchor == focus

    companion object {
        fun at(block: Int, offset: Int): Selection = Selection(Caret(block, offset))
    }
}

/**
 * A property to set, wrapped so that setting it to nothing can be told
 * apart from leaving it alone: `Put(null)` takes a link off a run, where
 * a plain `null` in the change says the link is not this change's
 * business.
 */
data class Put<out T>(val value: T)

/**
 * A change to how the runs of a selection are set. Every property is
 * optional and an unset one leaves the run's own value alone, so making
 * a selection bold does not also take its links away.
 */
data class RunChange(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val superscript: Boolean? = null,
    val subscript: Boolean? = null,
    val fontFamily: Put<String?>? = null,
    val fontSizePt: Put<Float?>? = null,
    val colorRgb: Put<Int?>? = null,
    val highlightRgb: Put<Int?>? = null,
    val link: Put<String?>? = null,
    val language: Put<String?>? = null,
) {
    val isEmpty: Boolean
        get() = this == RunChange()

    /**
     * [run] set this way. Raised and lowered are one choice, not two: a
     * run made superscript stops being subscript, as a footnote mark
     * typed over a chemical formula would be.
     */
    fun applyTo(run: TextRun): TextRun = run.copy(
        bold = bold ?: run.bold,
        italic = italic ?: run.italic,
        underline = underline ?: run.underline,
        strikethrough = strikethrough ?: run.strikethrough,
        superscript = when {
            superscript != null -> superscript
            subscript == true -> false
            else -> run.superscript
        },
        subscript = when {
            subscript != null -> subscript
            superscript == true -> false
            else -> run.subscript
        },
        fontFamily = if (fontFamily != null) fontFamily.value else run.fontFamily,
        fontSizePt = if (fontSizePt != null) fontSizePt.value else run.fontSizePt,
        colorRgb = if (colorRgb != null) colorRgb.value else run.colorRgb,
        highlightRgb = if (highlightRgb != null) highlightRgb.value else run.highlightRgb,
        link = if (link != null) link.value else run.link,
        language = if (language != null) language.value else run.language,
    )

    /** This change with [later] laid over it, so the later word wins. */
    fun over(later: RunChange): RunChange = RunChange(
        bold = later.bold ?: bold,
        italic = later.italic ?: italic,
        underline = later.underline ?: underline,
        strikethrough = later.strikethrough ?: strikethrough,
        superscript = later.superscript ?: superscript,
        subscript = later.subscript ?: subscript,
        fontFamily = later.fontFamily ?: fontFamily,
        fontSizePt = later.fontSizePt ?: fontSizePt,
        colorRgb = later.colorRgb ?: colorRgb,
        highlightRgb = later.highlightRgb ?: highlightRgb,
        link = later.link ?: link,
        language = later.language ?: language,
    )
}

/** A change to how the paragraphs of a selection are set; unset leaves alone. */
data class ParagraphChange(
    val kind: ParagraphKind? = null,
    val alignment: Put<Alignment?>? = null,
    val direction: Put<TextDirection?>? = null,
    val listMarker: Put<ListMarker?>? = null,
    val listLevel: Int? = null,
    val listFormat: Put<String?>? = null,
    val firstLineIndentPt: Put<Float?>? = null,
    val startIndentPt: Put<Float?>? = null,
    val hangingIndentPt: Put<Float?>? = null,
    val spaceBeforePt: Put<Float?>? = null,
    val spaceAfterPt: Put<Float?>? = null,
    val linePitchPt: Put<Float?>? = null,
    val pageBreakBefore: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = this == ParagraphChange()

    fun applyTo(style: ParagraphStyle): ParagraphStyle = style.copy(
        kind = kind ?: style.kind,
        alignment = if (alignment != null) alignment.value else style.alignment,
        direction = if (direction != null) direction.value else style.direction,
        listMarker = if (listMarker != null) listMarker.value else style.listMarker,
        listLevel = listLevel ?: style.listLevel,
        listFormat = if (listFormat != null) listFormat.value else style.listFormat,
        firstLineIndentPt = if (firstLineIndentPt != null) firstLineIndentPt.value else style.firstLineIndentPt,
        startIndentPt = if (startIndentPt != null) startIndentPt.value else style.startIndentPt,
        hangingIndentPt = if (hangingIndentPt != null) hangingIndentPt.value else style.hangingIndentPt,
        spaceBeforePt = if (spaceBeforePt != null) spaceBeforePt.value else style.spaceBeforePt,
        spaceAfterPt = if (spaceAfterPt != null) spaceAfterPt.value else style.spaceAfterPt,
        linePitchPt = if (linePitchPt != null) linePitchPt.value else style.linePitchPt,
        pageBreakBefore = pageBreakBefore ?: style.pageBreakBefore,
    )
}

/**
 * A document being edited the way a word processor edits one: a caret,
 * a selection, typing that lands where the caret is, and the formatting
 * of the words round it left exactly as it was.
 *
 * This is the whole of what an editor *is*, apart from the drawing of
 * it. It lives here rather than on the screen for the reason everything
 * about editing does: a screen compiles on a phone and nowhere else, and
 * what it does to a document is not something to find out on a phone.
 * Every operation is a function from one of these to the next; the
 * screen sends what the reader did, is handed the document as it now
 * stands and where the caret now is, and draws that. **The document is
 * the truth and the screen is a picture of it**, never the other way
 * round, which is the one rule that keeps every writer, every test and
 * the Fidelity Report upstream of a browser.
 *
 * The invariants, held by a fuzz over two thousand random sessions:
 * the selection always stands in a paragraph at the top of the
 * document; there is always at least one such paragraph to stand in; a
 * paragraph's runs never hold an empty run that is not a picture, a
 * field or the one placeholder of an empty paragraph; a character
 * written as a surrogate pair is never split; undoing every step gives
 * back exactly the document that was opened, and redoing them all gives
 * back exactly the document that was left.
 *
 * Blocks move here — a paragraph split is two paragraphs, a table deleted
 * is gone — which is what [DocumentEdit] exists to forbid, and the two are
 * for different screens: that one lists a reading's blocks against marks
 * kept by position, and this one is opened on what it produces. What the
 * review still needs from an editor, which blocks a reader has changed,
 * is [modified], kept by each block's origin rather than its index.
 */
class EditorState private constructor(
    /** The document as it now stands, with every edit made. */
    val document: DocumentModel,
    /** Where the reader's caret is, or what they have selected. */
    val selection: Selection,
    /** A way of setting words chosen with nothing selected, waiting for the next typing to take it. */
    val pending: RunChange?,
    private val opened: DocumentModel,
    /** For each block, its index in [opened], or null for a block made since. */
    private val origins: List<Int?>,
    private val undos: List<Snapshot>,
    private val redos: List<Snapshot>,
    /** The typing or erasing the last edit was, so the next of the same can join its undo step. */
    private val continuing: Continuing?,
) {

    private class Snapshot(val document: DocumentModel, val origins: List<Int?>, val selection: Selection)

    private data class Continuing(val kind: Kind, val at: Caret) {
        enum class Kind { TYPING, ERASING }
    }

    /** The state after a change to the document, before it has a history. */
    private class Change(val document: DocumentModel, val origins: List<Int?>, val selection: Selection)

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** How many steps back a reader could take, for a test that counts them. */
    val undoDepth: Int get() = undos.size
    val redoDepth: Int get() = redos.size

    /**
     * The blocks that are not as they were opened: changed, or made since.
     *
     * By origin rather than by index, because blocks move here. A block
     * that was split is the first half, changed, and a second half that
     * is new; a block that was deleted is simply not among them, its
     * absence being the change.
     */
    val modified: Set<Int>
        get() = document.blocks.indices.filterTo(mutableSetOf()) { at ->
            val origin = origins[at]
            origin == null || document.blocks[at] != opened.blocks[origin]
        }

    /** The paragraph the caret stands in. */
    fun paragraphAt(caret: Caret): Paragraph = paragraph(normalised(caret).block)

    /**
     * The way words typed at [caret] would be set: the look of the
     * character to its left, with anything chosen since laid over it.
     * What a toolbar reads to show which of its buttons are down.
     */
    fun lookAt(caret: Caret): TextRun {
        val at = normalised(caret)
        val look = ParagraphEdit.plain(lookIn(paragraph(at.block).runs, at.offset))
        return pending?.applyTo(look) ?: look
    }

    /**
     * The reader moved the caret or made a selection. No edit, so no
     * history; but anything chosen and not yet typed is let go, since it
     * was chosen for where the caret was.
     */
    fun select(selection: Selection): EditorState {
        val normalised = Selection(normalised(selection.anchor), normalised(selection.focus))
        if (normalised == this.selection && pending == null && continuing == null) return this
        return with(selection = normalised, pending = null, continuing = null)
    }

    /**
     * [text] typed at the caret, in place of whatever was selected.
     *
     * It takes the look of the character to its left, which is what
     * typing does in every word processor there is and what keeps a word
     * corrected inside a link inside the link — or the look chosen since
     * with nothing selected, which is what pressing Bold and then typing
     * means. Typing that carries straight on from the last typing joins
     * its undo step, so undoing a word does not undo it a letter at a
     * time; typing over a selection is a step of its own.
     */
    fun type(text: String): EditorState {
        if (text.isEmpty()) return this
        val cleared = deletion(selection)
        val at = if (cleared == null) normalised(selection.start) else cleared.selection.start
        val blocks = cleared?.document?.blocks ?: document.blocks
        val paragraph = blocks[at.block] as Paragraph
        val look = ParagraphEdit.plain(lookIn(paragraph.runs, at.offset))
        val typed = (pending?.applyTo(look) ?: look).copy(text = text)
        val runs = ParagraphEdit.merged(
            ParagraphEdit.slice(paragraph.runs, 0, at.offset) + typed +
                ParagraphEdit.slice(paragraph.runs, at.offset, paragraph.text.length),
        )
        val after = Caret(at.block, at.offset + text.length)
        val change = Change(
            document = (cleared?.document ?: document).replacing(at.block, paragraph.copy(runs = runs)),
            origins = cleared?.origins ?: origins,
            selection = Selection(after),
        )
        val joins = if (cleared == null && pending == null) Continuing(Continuing.Kind.TYPING, at) else null
        return committed(change, next = Continuing(Continuing.Kind.TYPING, after), joins = joins)
    }

    /**
     * Backspace: the selection taken out, or the character before the
     * caret, or — at the head of a paragraph — the paragraph joined to
     * the one above, or the picture or table above it taken out.
     *
     * Joining is the plainest kind: no space is put between the halves,
     * because the reader has the caret between them and will type one
     * if they want one. Erasing that carries straight on joins its undo
     * step, as typing does.
     */
    fun erase(): EditorState {
        if (!selection.collapsed) return committed(deletion(selection) ?: return this)
        val at = normalised(selection.start)
        val text = paragraph(at.block).text
        if (at.offset > 0) {
            val from = at.offset - if (pairEndsAt(text, at.offset)) 2 else 1
            val range = Selection(Caret(at.block, from), at)
            val change = deletion(range) ?: return this
            return committed(
                change,
                next = Continuing(Continuing.Kind.ERASING, Caret(at.block, from)),
                joins = Continuing(Continuing.Kind.ERASING, at),
            )
        }
        if (at.block == 0) return this
        return if (document.blocks[at.block - 1] is Paragraph) {
            committed(merging(at.block))
        } else {
            committed(removal(at.block - 1, Selection(Caret(at.block - 1, 0))))
        }
    }

    /**
     * Delete: the selection taken out, or the character after the caret,
     * or — at the end of a paragraph — the paragraph below joined to this
     * one, or the picture or table below taken out.
     */
    fun eraseForward(): EditorState {
        if (!selection.collapsed) return committed(deletion(selection) ?: return this)
        val at = normalised(selection.start)
        val text = paragraph(at.block).text
        if (at.offset < text.length) {
            val to = at.offset + if (pairStartsAt(text, at.offset)) 2 else 1
            val change = deletion(Selection(at, Caret(at.block, to))) ?: return this
            return committed(
                change,
                next = Continuing(Continuing.Kind.ERASING, at),
                joins = Continuing(Continuing.Kind.ERASING, at),
            )
        }
        if (at.block == document.blocks.size - 1) return this
        return if (document.blocks[at.block + 1] is Paragraph) {
            committed(merging(at.block + 1).let { Change(it.document, it.origins, Selection(at)) })
        } else {
            committed(removal(at.block + 1, Selection(at)))
        }
    }

    /**
     * Return: the paragraph broken in two at the caret, in place of
     * whatever was selected.
     *
     * Which kind of paragraph the second half is follows what a reader
     * expects from a word processor. Broken in the middle, both halves
     * are what the paragraph was. Broken at the end, a list item is
     * followed by another item of the list and a heading by a body
     * paragraph, since nobody writes two headings by pressing Return.
     * Return on an empty list item ends the list rather than adding an
     * item to it — that is how a list is ended in every editor, and it
     * makes one paragraph of the item rather than two.
     *
     * A page break belongs to the first half, since that is the paragraph
     * the page began on; a rule under the paragraph goes with whichever
     * half ends where it did.
     */
    fun splitParagraph(): EditorState {
        val cleared = deletion(selection)
        val blocks = cleared?.document?.blocks ?: document.blocks
        val origins = cleared?.origins ?: origins
        val at = cleared?.selection?.start ?: normalised(selection.start)
        val paragraph = blocks[at.block] as Paragraph
        val length = paragraph.text.length
        if (length == 0 && paragraph.style.listMarker != null) {
            val ended = paragraph.copy(style = paragraph.style.copy(listMarker = null, listLevel = 0, listFormat = null))
            val document = (cleared?.document ?: document).replacing(at.block, ended)
            return committed(Change(document, origins, Selection(at)))
        }
        val look = ParagraphEdit.plain(lookIn(paragraph.runs, at.offset))
        val head = ParagraphEdit.merged(ParagraphEdit.slice(paragraph.runs, 0, at.offset))
        val tail = ParagraphEdit.merged(ParagraphEdit.slice(paragraph.runs, at.offset, length))
        val inTheMiddle = at.offset < length
        val first = paragraph.copy(
            runs = head.ifEmpty { emptied(look) },
            style = paragraph.style.copy(ruleBelow = paragraph.style.ruleBelow && !inTheMiddle),
        )
        val kind = when {
            inTheMiddle -> paragraph.style
            paragraph.style.listMarker != null -> paragraph.style
            paragraph.style.kind != ParagraphKind.BODY -> paragraph.style.copy(kind = ParagraphKind.BODY)
            else -> paragraph.style
        }
        val second = Paragraph(
            runs = tail.ifEmpty { emptied(look) },
            style = kind.copy(
                pageBreakBefore = false,
                sectionSetup = null,
                ruleAbove = false,
                ruleBelow = paragraph.style.ruleBelow && inTheMiddle,
            ),
            confidence = paragraph.confidence,
        )
        val document = (cleared?.document ?: document).let { doc ->
            doc.copy(blocks = doc.blocks.take(at.block) + first + second + doc.blocks.drop(at.block + 1))
        }
        val nextOrigins = origins.take(at.block) + origins[at.block] + null + origins.drop(at.block + 1)
        return committed(Change(document, nextOrigins, Selection(Caret(at.block + 1, 0))))
    }

    /**
     * The selection set as [change] says — or, with nothing selected, the
     * change held for the next typing, which is what pressing Bold with
     * the caret between two words means.
     *
     * Only the runs the selection covers change, split where it cuts
     * through one, so making half a link bold leaves the whole of it a
     * link. A picture in the selection is set the same way as the words
     * round it, which is how a picture comes to be a link.
     */
    fun format(change: RunChange): EditorState {
        if (change.isEmpty) return this
        if (selection.collapsed) return with(pending = (pending ?: RunChange()).over(change))
        val start = normalised(selection.start)
        val end = normalised(selection.end)
        var blocks = document.blocks
        for (block in start.block..end.block) {
            val paragraph = blocks[block] as? Paragraph ?: continue
            val length = paragraph.text.length
            val from = if (block == start.block) start.offset else 0
            val to = if (block == end.block) end.offset else length
            if (from >= to) continue
            val runs = ParagraphEdit.merged(
                ParagraphEdit.slice(paragraph.runs, 0, from) +
                    ParagraphEdit.slice(paragraph.runs, from, to).map(change::applyTo) +
                    ParagraphEdit.slice(paragraph.runs, to, length),
            )
            blocks = blocks.toMutableList().also { it[block] = paragraph.copy(runs = runs) }
        }
        if (blocks === document.blocks) return this
        return committed(Change(document.copy(blocks = blocks), origins, selection))
    }

    /** Every paragraph the selection touches set as [change] says. */
    fun restyle(change: ParagraphChange): EditorState {
        if (change.isEmpty) return this
        val start = normalised(selection.start)
        val end = normalised(selection.end)
        var blocks = document.blocks
        for (block in start.block..end.block) {
            val paragraph = blocks[block] as? Paragraph ?: continue
            val style = change.applyTo(paragraph.style)
            if (style == paragraph.style) continue
            blocks = blocks.toMutableList().also { it[block] = paragraph.copy(style = style) }
        }
        if (blocks === document.blocks) return this
        return committed(Change(document.copy(blocks = blocks), origins, selection))
    }

    /**
     * [block] — a table, a picture — put in at the caret, in place of
     * whatever was selected.
     *
     * A paragraph the caret is inside is broken round it; one it is at
     * the end of has the block after it. Either way the caret lands at
     * the head of the paragraph after the block, and there is always one,
     * because a caret has to have somewhere to stand: a table put in at
     * the very end of a document is followed by an empty paragraph, the
     * way a Word document always ends with a paragraph mark.
     */
    fun insertBlock(block: Block): EditorState {
        val cleared = deletion(selection)
        val blocks = cleared?.document?.blocks ?: document.blocks
        val origins = cleared?.origins ?: origins
        val at = cleared?.selection?.start ?: normalised(selection.start)
        val paragraph = blocks[at.block] as Paragraph
        val length = paragraph.text.length
        val look = ParagraphEdit.plain(lookIn(paragraph.runs, at.offset))
        val out = mutableListOf<Block>()
        val outOrigins = mutableListOf<Int?>()
        out += blocks.take(at.block)
        outOrigins += origins.take(at.block)
        // Where the caret lands: a paragraph already placed, or the one
        // after the block, which is put there if nothing follows.
        var landing: Int? = null
        when {
            length == 0 -> {
                out += block
                outOrigins += null
            }
            at.offset == 0 -> {
                out += block
                outOrigins += null
                landing = out.size
                out += paragraph
                outOrigins += origins[at.block]
            }
            at.offset == length -> {
                out += paragraph
                outOrigins += origins[at.block]
                out += block
                outOrigins += null
            }
            else -> {
                out += paragraph.copy(runs = ParagraphEdit.merged(ParagraphEdit.slice(paragraph.runs, 0, at.offset)))
                outOrigins += origins[at.block]
                out += block
                outOrigins += null
                landing = out.size
                out += Paragraph(
                    runs = ParagraphEdit.merged(ParagraphEdit.slice(paragraph.runs, at.offset, length)),
                    style = paragraph.style.copy(pageBreakBefore = false, sectionSetup = null, ruleAbove = false),
                    confidence = paragraph.confidence,
                )
                outOrigins += null
            }
        }
        val rest = blocks.drop(at.block + 1)
        if (landing == null) {
            landing = out.size
            if (rest.firstOrNull() !is Paragraph) {
                out += Paragraph(emptied(look), confidence = paragraph.confidence)
                outOrigins += null
            }
        }
        out += rest
        outOrigins += origins.drop(at.block + 1)
        val document = (cleared?.document ?: document).copy(blocks = out)
        return committed(Change(document, outOrigins, Selection(Caret(landing, 0))))
    }

    /**
     * The block at [index] taken out — the way a table or a picture is
     * deleted, since a caret cannot stand in one to select it.
     */
    fun removeBlock(index: Int): EditorState {
        if (index !in document.blocks.indices) return this
        return committed(removal(index, null))
    }

    /** The last step taken back, and the caret put where it was before it. */
    fun undo(): EditorState {
        val last = undos.lastOrNull() ?: return this
        return EditorState(
            document = last.document,
            selection = last.selection,
            pending = null,
            opened = opened,
            origins = last.origins,
            undos = undos.dropLast(1),
            redos = redos + snapshot(),
            continuing = null,
        )
    }

    /** The last step taken back, taken again. */
    fun redo(): EditorState {
        val next = redos.lastOrNull() ?: return this
        return EditorState(
            document = next.document,
            selection = next.selection,
            pending = null,
            opened = opened,
            origins = next.origins,
            undos = undos + snapshot(),
            redos = redos.dropLast(1),
            continuing = null,
        )
    }

    // ---- the edits themselves, before they have a history ----

    /**
     * [selection] taken out, or null where there is nothing in it.
     *
     * Inside one paragraph, the characters between. Across paragraphs,
     * the head of the first and the tail of the last become one paragraph
     * — the first's, in kind and in ranging, since that is where the
     * reader's sentence now begins — and everything that stood between
     * goes with the selection, a table or a picture included. A character
     * written as a surrogate pair is taken whole or not at all: a
     * selection that starts on the second half of one starts on the
     * first instead.
     */
    private fun deletion(selection: Selection): Change? {
        if (selection.collapsed) return null
        var start = normalised(selection.start)
        var end = normalised(selection.end)
        if (end < start) start = end.also { end = start }
        if (start == end) return null
        val first = paragraph(start.block)
        // Never inside a surrogate pair: a caret is put after one by
        // normalising, so a selection starting on the second half of a
        // character starts after the character.
        val from = start.offset
        val head = ParagraphEdit.slice(first.runs, 0, from)
        val look = ParagraphEdit.plain(lookIn(first.runs, from))
        if (start.block == end.block) {
            val runs = ParagraphEdit.merged(head + ParagraphEdit.slice(first.runs, end.offset, first.text.length))
            val kept = first.copy(runs = runs.ifEmpty { emptied(look) })
            return Change(document.replacing(start.block, kept), origins, Selection(Caret(start.block, from)))
        }
        val last = paragraph(end.block)
        val runs = ParagraphEdit.merged(head + ParagraphEdit.slice(last.runs, end.offset, last.text.length))
        val kept = first.copy(
            runs = runs.ifEmpty { emptied(look) },
            confidence = minOf(first.confidence, last.confidence),
            bookmarks = first.bookmarks + last.bookmarks,
        )
        val blocks = document.blocks.take(start.block) + kept + document.blocks.drop(end.block + 1)
        val nextOrigins = origins.take(start.block) + origins[start.block] + origins.drop(end.block + 1)
        return Change(document.copy(blocks = blocks), nextOrigins, Selection(Caret(start.block, from)))
    }

    /** The paragraph at [index] joined onto the paragraph above it, with nothing between. */
    private fun merging(index: Int): Change {
        val above = paragraph(index - 1)
        val below = paragraph(index)
        val joined = above.copy(
            runs = ParagraphEdit.merged(above.runs + below.runs).ifEmpty { emptied(lookIn(above.runs, 0)) },
            confidence = minOf(above.confidence, below.confidence),
            bookmarks = above.bookmarks + below.bookmarks,
        )
        val blocks = document.blocks.take(index - 1) + joined + document.blocks.drop(index + 1)
        val nextOrigins = origins.take(index - 1) + origins[index - 1] + origins.drop(index + 1)
        return Change(document.copy(blocks = blocks), nextOrigins, Selection(Caret(index - 1, above.text.length)))
    }

    /**
     * The block at [index] taken out, and the caret put at [landing] —
     * or, given none, at the nearest paragraph. A document is never left
     * without a paragraph to stand in.
     */
    private fun removal(index: Int, landing: Selection?): Change {
        var blocks = document.blocks.take(index) + document.blocks.drop(index + 1)
        var nextOrigins = origins.take(index) + origins.drop(index + 1)
        if (blocks.none { it is Paragraph }) {
            blocks = blocks + Paragraph(emptied(TextRun("")))
            nextOrigins = nextOrigins + null
        }
        val selection = landing ?: run {
            val block = (index until blocks.size).firstOrNull { blocks[it] is Paragraph }
                ?: (index - 1 downTo 0).first { blocks[it] is Paragraph }
            Selection(Caret(block, 0))
        }
        return Change(document.copy(blocks = blocks), nextOrigins, selection)
    }

    // ---- history ----

    /**
     * [change] made, and this state kept to go back to — unless the
     * change carries straight on from the last one and [joins] it, in
     * which case the step already kept covers both.
     */
    private fun committed(change: Change, next: Continuing? = null, joins: Continuing? = null): EditorState {
        val coalesced = joins != null && continuing == joins
        val kept = if (coalesced) undos else (undos + snapshot()).takeLast(MOST_STEPS)
        return EditorState(
            document = change.document,
            selection = Selection(
                normalisedIn(change.document, change.selection.anchor),
                normalisedIn(change.document, change.selection.focus),
            ),
            pending = null,
            opened = opened,
            origins = change.origins,
            undos = kept,
            redos = emptyList(),
            continuing = next,
        )
    }

    private fun snapshot() = Snapshot(document, origins, selection)

    private fun with(
        selection: Selection = this.selection,
        pending: RunChange? = this.pending,
        continuing: Continuing? = this.continuing,
    ) = EditorState(document, selection, pending, opened, origins, undos, redos, continuing)

    // ---- positions ----

    private fun paragraph(index: Int): Paragraph = document.blocks[index] as Paragraph

    private fun normalised(caret: Caret): Caret = normalisedIn(document, caret)

    /** The look at [at] in [runs], or a plain one where the paragraph holds nothing to take it from. */
    private fun lookIn(runs: List<TextRun>, at: Int): TextRun =
        if (runs.isEmpty()) TextRun("") else ParagraphEdit.lookOf(runs, at)

    /** The one run an empty paragraph keeps, so that typing into it has a look to take. */
    private fun emptied(look: TextRun): List<TextRun> = listOf(ParagraphEdit.plain(look).copy(text = ""))

    companion object {
        /** The most steps kept to go back over; the oldest is let go past this. */
        const val MOST_STEPS = 500

        /**
         * [document] opened for editing, with the caret at the head of its
         * first paragraph.
         *
         * A document with no paragraph at all — a table and nothing else —
         * is given an empty one at the end to stand in, which is what a
         * word processor does with the same document. That paragraph is
         * part of what was opened, not something the reader did: a
         * session in which nothing was touched reports nothing
         * [modified].
         */
        fun open(document: DocumentModel): EditorState {
            var blocks = document.blocks
            if (blocks.none { it is Paragraph }) blocks = blocks + Paragraph(listOf(TextRun("")))
            val opened = document.copy(blocks = blocks)
            val first = blocks.indexOfFirst { it is Paragraph }
            return EditorState(
                document = opened,
                selection = Selection(Caret(first, 0)),
                pending = null,
                opened = opened,
                origins = blocks.indices.toList(),
                undos = emptyList(),
                redos = emptyList(),
                continuing = null,
            )
        }

        /**
         * [caret] as a place that exists in [document]: in a paragraph,
         * inside its text, and never between the two halves of a
         * character written as a surrogate pair.
         *
         * A caret that names a table or a picture stands in the paragraph
         * after it, or before it where none follows; one past the end of
         * the document stands in its last paragraph.
         */
        private fun normalisedIn(document: DocumentModel, caret: Caret): Caret {
            val blocks = document.blocks
            var block = caret.block.coerceIn(0, blocks.size - 1)
            if (blocks[block] !is Paragraph) {
                block = (block until blocks.size).firstOrNull { blocks[it] is Paragraph }
                    ?: (block downTo 0).first { blocks[it] is Paragraph }
            }
            val text = (blocks[block] as Paragraph).text
            var offset = caret.offset.coerceIn(0, text.length)
            if (offset in 1 until text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) {
                offset++
            }
            return Caret(block, offset)
        }

        /** Whether the character ending at [offset] in [text] is a surrogate pair. */
        private fun pairEndsAt(text: String, offset: Int): Boolean =
            offset >= 2 && text[offset - 1].isLowSurrogate() && text[offset - 2].isHighSurrogate()

        /** Whether the character starting at [offset] in [text] is a surrogate pair. */
        private fun pairStartsAt(text: String, offset: Int): Boolean =
            offset + 1 < text.length && text[offset].isHighSurrogate() && text[offset + 1].isLowSurrogate()

        private fun DocumentModel.replacing(index: Int, block: Block): DocumentModel =
            copy(blocks = blocks.toMutableList().also { it[index] = block })
    }
}
