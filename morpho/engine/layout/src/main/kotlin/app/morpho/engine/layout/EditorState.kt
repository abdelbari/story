package app.morpho.engine.layout

/**
 * Where in a table a caret stands: the paragraph at [paragraph] among
 * the blocks of the cell at [column] of the row at [row] — the indices
 * the table stores its cells by, which are the indices a page gives its
 * `<td>`s, spans and all, so the two sides of the bridge count alike.
 */
data class Cell(val row: Int, val column: Int, val paragraph: Int = 0)

/**
 * A place a caret can stand: [offset] UTF-16 units into the text of the
 * paragraph at [block] — or, where that block is a table, into the
 * paragraph [cell] names inside it.
 *
 * UTF-16 because that is what a Kotlin string and a JavaScript string
 * both count in, so a position handed across the bridge from the screen
 * means the same thing on both sides without anybody converting it.
 */
data class Caret(val block: Int, val offset: Int, val cell: Cell? = null) : Comparable<Caret> {
    override fun compareTo(other: Caret): Int {
        if (block != other.block) return block.compareTo(other.block)
        val a = cell
        val b = other.cell
        if (a != null || b != null) {
            val rows = (a?.row ?: -1).compareTo(b?.row ?: -1)
            if (rows != 0) return rows
            val columns = (a?.column ?: -1).compareTo(b?.column ?: -1)
            if (columns != 0) return columns
            val paragraphs = (a?.paragraph ?: -1).compareTo(b?.paragraph ?: -1)
            if (paragraphs != 0) return paragraphs
        }
        return offset.compareTo(other.offset)
    }

    /** Whether this and [other] stand in the same run of paragraphs: the document's, or one cell's. */
    fun sharesContainerWith(other: Caret): Boolean {
        val a = cell
        val b = other.cell
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return block == other.block && a.row == b.row && a.column == b.column
    }

    /** Whether this and [other] stand in two cells of one table, which is what a selection across cells is. */
    fun sharesTableWith(other: Caret): Boolean =
        cell != null && other.cell != null && block == other.block
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
 * A caret stands in a paragraph of the document or in a paragraph of a
 * cell, and inside a cell every edit is the edit it is outside one — a
 * Return makes a second paragraph in the cell, Backspace at the head of
 * a cell's first paragraph does nothing, as in every word processor —
 * except that nothing crosses a cell's edge: a selection that reaches
 * out of a cell, or into one, stands where it began. A selection from
 * one cell to another is of whole cells — the rectangle between them,
 * grown to hold whole any merged cell it cuts through, as a word
 * processor selects cells — and what is done to it is done to every
 * cell in it: emptied, set, merged into one. Rows and columns are put
 * in and taken out of a table with merged cells in it as a word
 * processor does it: a cell that crosses the place grows or shrinks by
 * it, and one that lies wholly in a row or a column taken out goes
 * with it ([TableEdits]).
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
    fun paragraphAt(caret: Caret): Paragraph = document.paragraphAt(normalised(caret))

    /**
     * The way words typed at [caret] would be set: the look of the
     * character to its left, with anything chosen since laid over it.
     * What a toolbar reads to show which of its buttons are down.
     */
    fun lookAt(caret: Caret): TextRun {
        val at = normalised(caret)
        val look = ParagraphEdit.plain(lookIn(document.paragraphAt(at).runs, at.offset))
        return pending?.applyTo(look) ?: look
    }

    /**
     * The reader moved the caret or made a selection. No edit, so no
     * history; but anything chosen and not yet typed is let go, since it
     * was chosen for where the caret was.
     */
    fun select(selection: Selection): EditorState {
        val anchor = normalised(selection.anchor)
        val focus = normalised(selection.focus).let { if (it.sharesContainerWith(anchor) || it.sharesTableWith(anchor)) it else anchor }
        val normalised = Selection(anchor, focus)
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
        val doc = cleared?.document ?: document
        val paragraph = doc.paragraphAt(at)
        val look = ParagraphEdit.plain(lookIn(paragraph.runs, at.offset))
        val typed = (pending?.applyTo(look) ?: look).copy(text = text)
        val runs = ParagraphEdit.merged(
            ParagraphEdit.slice(paragraph.runs, 0, at.offset) + typed +
                ParagraphEdit.slice(paragraph.runs, at.offset, paragraph.text.length),
        )
        val after = at.copy(offset = at.offset + text.length)
        val change = Change(
            document = doc.replacingAt(at, paragraph.copy(runs = runs)),
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
        val text = document.paragraphAt(at).text
        if (at.offset > 0) {
            val from = at.offset - if (pairEndsAt(text, at.offset)) 2 else 1
            val range = Selection(at.copy(offset = from), at)
            val change = deletion(range) ?: return this
            return committed(
                change,
                next = Continuing(Continuing.Kind.ERASING, at.copy(offset = from)),
                joins = Continuing(Continuing.Kind.ERASING, at),
            )
        }
        val cell = at.cell
        if (cell != null) {
            // At the head of a cell's paragraph: joined to the one above it
            // in the cell, or nothing, since a cell's edge is not crossed.
            val above = cell.paragraph - 1
            val blocks = document.cellBlocksAt(at)
            if (above < 0 || blocks[above] !is Paragraph) return this
            return committed(mergingInCell(at, above))
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
        val text = document.paragraphAt(at).text
        if (at.offset < text.length) {
            val to = at.offset + if (pairStartsAt(text, at.offset)) 2 else 1
            val change = deletion(Selection(at, at.copy(offset = to))) ?: return this
            return committed(
                change,
                next = Continuing(Continuing.Kind.ERASING, at),
                joins = Continuing(Continuing.Kind.ERASING, at),
            )
        }
        val cell = at.cell
        if (cell != null) {
            val blocks = document.cellBlocksAt(at)
            val below = cell.paragraph + 1
            if (below >= blocks.size || blocks[below] !is Paragraph) return this
            return committed(mergingInCell(at.copy(cell = cell.copy(paragraph = below)), cell.paragraph).let {
                Change(it.document, it.origins, Selection(at))
            })
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
        val doc = cleared?.document ?: document
        val origins = cleared?.origins ?: origins
        val at = cleared?.selection?.start ?: normalised(selection.start)
        val paragraph = doc.paragraphAt(at)
        val length = paragraph.text.length
        if (length == 0 && paragraph.style.listMarker != null) {
            val ended = paragraph.copy(style = paragraph.style.copy(listMarker = null, listLevel = 0, listFormat = null))
            return committed(Change(doc.replacingAt(at, ended), origins, Selection(at)))
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
        val cell = at.cell
        if (cell != null) {
            // Inside a cell, the second half is a second paragraph of the
            // cell; the table stays the block it is.
            val cellBlocks = doc.cellBlocksAt(at)
            val split = cellBlocks.take(cell.paragraph) + first + second + cellBlocks.drop(cell.paragraph + 1)
            return committed(
                Change(doc.replacingCellBlocks(at, split), origins, Selection(at.copy(offset = 0, cell = cell.copy(paragraph = cell.paragraph + 1)))),
            )
        }
        val document = doc.copy(blocks = doc.blocks.take(at.block) + first + second + doc.blocks.drop(at.block + 1))
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
        selectedCellsOf(selection)?.let { selected ->
            // Every run of every cell selected, an empty cell's placeholder
            // included, so that typing into it afterwards takes the change.
            val table = eachParagraphOf(selected) { paragraph ->
                val runs = ParagraphEdit.merged(paragraph.runs.map(change::applyTo))
                paragraph.copy(runs = runs.ifEmpty { emptied(change.applyTo(lookIn(paragraph.runs, 0))) })
            }
            if (table == selected.table) return this
            return committed(Change(document.replacing(selected.block, table), origins, selection))
        }
        val start = normalised(selection.start)
        val end = normalised(selection.end)
        var doc = document
        for (at in doc.paragraphsBetween(start, end)) {
            val paragraph = doc.paragraphAt(at)
            val length = paragraph.text.length
            val from = if (at.sameParagraphAs(start)) start.offset else 0
            val to = if (at.sameParagraphAs(end)) end.offset else length
            if (from >= to) continue
            val runs = ParagraphEdit.merged(
                ParagraphEdit.slice(paragraph.runs, 0, from) +
                    ParagraphEdit.slice(paragraph.runs, from, to).map(change::applyTo) +
                    ParagraphEdit.slice(paragraph.runs, to, length),
            )
            doc = doc.replacingAt(at, paragraph.copy(runs = runs))
        }
        if (doc === document) return this
        return committed(Change(doc, origins, selection))
    }

    /** Every paragraph the selection touches set as [change] says. */
    fun restyle(change: ParagraphChange): EditorState {
        if (change.isEmpty) return this
        selectedCellsOf(selection)?.let { selected ->
            val table = eachParagraphOf(selected) { paragraph -> paragraph.copy(style = change.applyTo(paragraph.style)) }
            if (table == selected.table) return this
            return committed(Change(document.replacing(selected.block, table), origins, selection))
        }
        val start = normalised(selection.start)
        val end = normalised(selection.end)
        var doc = document
        for (at in doc.paragraphsBetween(start, end)) {
            val paragraph = doc.paragraphAt(at)
            val style = change.applyTo(paragraph.style)
            if (style == paragraph.style) continue
            doc = doc.replacingAt(at, paragraph.copy(style = style))
        }
        if (doc === document) return this
        return committed(Change(doc, origins, selection))
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
        // Not inside a cell: a table in a table is a thing the model can
        // hold and the page cannot yet put a caret in.
        if (normalised(selection.start).cell != null) return this
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
        val document = withParagraphsToStandIn((cleared?.document ?: document).copy(blocks = out))
        return committed(Change(document, outOrigins, Selection(Caret(landing, 0))))
    }

    // ---- finding and replacing ----

    /**
     * Every place [query] is written, in the order a reader meets them:
     * the document's paragraphs and the paragraphs of every cell, each as
     * a selection that [select] can be handed. Nothing where the query is
     * nothing. Matches do not overlap: `aa` is found once in `aaa`.
     */
    fun find(query: String, ignoreCase: Boolean = false): List<Selection> {
        if (query.isEmpty()) return emptyList()
        val out = mutableListOf<Selection>()
        fun scan(text: String, caret: (Int) -> Caret) {
            var from = 0
            while (from <= text.length) {
                val at = text.indexOf(query, from, ignoreCase)
                if (at < 0) break
                out += Selection(caret(at), caret(at + query.length))
                from = at + query.length
            }
        }
        for ((block, held) in document.blocks.withIndex()) {
            when (held) {
                is Paragraph -> scan(held.text) { Caret(block, it) }
                is Table -> for ((row, cells) in held.rows.withIndex()) {
                    for ((column, cell) in cells.cells.withIndex()) {
                        for ((paragraph, inner) in cell.blocks.withIndex()) {
                            if (inner is Paragraph) scan(inner.text) { Caret(block, it, Cell(row, column, paragraph)) }
                        }
                    }
                }
                is ImageBlock -> {}
            }
        }
        return out
    }

    /**
     * Every place [query] is written, written as [replacement] instead,
     * as one step to undo. Each replacement is set the way the first
     * character it replaces was set, which is what a word processor's
     * replace does and what keeps a word replaced inside a link inside
     * the link. Replacing with nothing takes the words out. The caret
     * stays where it was, put in range where what it stood in shrank.
     */
    fun replaceAll(query: String, replacement: String, ignoreCase: Boolean = false): EditorState {
        val matches = find(query, ignoreCase)
        if (matches.isEmpty()) return this
        var doc = document
        for ((address, hits) in matches.groupBy { it.start.copy(offset = 0) }) {
            var paragraph = doc.paragraphAt(address)
            // From the end of the paragraph backwards, so that a replacement
            // of another length moves nothing still to be replaced.
            for (hit in hits.sortedByDescending { it.start.offset }) {
                val from = hit.start.offset
                val to = hit.end.offset
                val look = ParagraphEdit.plain(lookIn(paragraph.runs, from + 1))
                val runs = ParagraphEdit.merged(
                    ParagraphEdit.slice(paragraph.runs, 0, from) + look.copy(text = replacement) +
                        ParagraphEdit.slice(paragraph.runs, to, paragraph.text.length),
                )
                paragraph = paragraph.copy(runs = runs.ifEmpty { emptied(look) })
            }
            doc = doc.replacingAt(address, paragraph)
        }
        return committed(Change(doc, origins, selection))
    }

    // ---- rows and columns ----

    /**
     * A row put into the table the caret is in, [below] the caret's cell
     * or above it — under the whole of a cell that covers several rows —
     * shaped like the row it is put beside: an empty cell as wide as
     * each of that row's, and a cell that crosses the place grown by a
     * row instead. The caret lands in the new row, under or over where
     * it was, or stays where it was if the new row has no cell there.
     */
    fun insertRow(below: Boolean): EditorState {
        val at = normalised(selection.start)
        val cell = at.cell ?: return this
        val table = document.blocks[at.block] as Table
        val rectangle = Places(table).rectangleOf(cell.row, cell.column) ?: return this
        val index = if (below) rectangle.bottom else rectangle.top
        val grown = TableEdits.insertRow(table, index, template = if (below) rectangle.bottom - 1 else rectangle.top) ?: return this
        val landing = TableEdits.cellAt(grown, index, rectangle.left)?.takeIf { it.row == index }
            ?: TableEdits.cellAt(grown, index, grown.rows[index].cells.indices.firstOrNull()?.let { 0 } ?: -1)?.takeIf { it.row == index && grown.rows[index].cells.isNotEmpty() }
            ?: Cell(if (below) cell.row else cell.row + 1, cell.column)
        return committed(Change(document.replacing(at.block, grown), origins, Selection(Caret(at.block, 0, landing.copy(paragraph = 0)))))
    }

    /**
     * The caret's row taken out of its table — every row its cell
     * covers, and every row where cells of several are selected — and
     * the table with it, where those were all its rows, since a table
     * with no rows is not a table anybody can see. A cell crossing into
     * the rows taken is shortened by them.
     */
    fun deleteRow(): EditorState {
        val at = normalised(selection.start)
        val cell = at.cell ?: return this
        val table = document.blocks[at.block] as Table
        val rectangle = selectedCellsOf(selection)?.rectangle ?: Places(table).rectangleOf(cell.row, cell.column) ?: return this
        val taken = rectangle.top until rectangle.bottom
        if (taken.count() >= table.rows.size) return committed(removal(at.block, null))
        val shrunk = TableEdits.deleteRows(table, taken) ?: return this
        if (shrunk.rows.none { it.cells.isNotEmpty() }) return committed(removal(at.block, null))
        val landing = landingIn(shrunk, taken.first, rectangle.left)
        return committed(Change(document.replacing(at.block, shrunk), origins, Selection(Caret(at.block, 0, landing))))
    }

    /**
     * A column put into the table the caret is in, [after] the caret's
     * cell or before it — beside the whole of a cell that covers several
     * columns: an empty cell in every row, as wide as the column it was
     * put beside where the table knows its widths, and a cell that
     * crosses the place grown by a column instead. The caret lands in
     * the new cell of its row, or stays where it was if its row has none.
     */
    fun insertColumn(after: Boolean): EditorState {
        val at = normalised(selection.start)
        val cell = at.cell ?: return this
        val table = document.blocks[at.block] as Table
        val rectangle = Places(table).rectangleOf(cell.row, cell.column) ?: return this
        val index = if (after) rectangle.right else rectangle.left
        val width = table.columnWidthsPt?.let { it.getOrNull(rectangle.left) ?: it.lastOrNull() }
        val grown = TableEdits.insertColumn(table, index, width) ?: return this
        val landing = TableEdits.cellAt(grown, rectangle.top, index)?.takeIf { it.row == rectangle.top }
            ?: TableEdits.cellAt(grown, rectangle.top, if (after) rectangle.left else rectangle.left + 1)
            ?: Cell(cell.row, cell.column)
        return committed(Change(document.replacing(at.block, grown), origins, Selection(Caret(at.block, 0, landing.copy(paragraph = 0)))))
    }

    /**
     * The caret's column taken out of its table — every column its cell
     * covers, and every column where cells of several are selected —
     * and the table with it, where those were all its columns. A cell
     * crossing into the columns taken is narrowed by them; a row left
     * with no cell and nothing covering it goes too.
     */
    fun deleteColumn(): EditorState {
        val at = normalised(selection.start)
        val cell = at.cell ?: return this
        val table = document.blocks[at.block] as Table
        val rectangle = selectedCellsOf(selection)?.rectangle ?: Places(table).rectangleOf(cell.row, cell.column) ?: return this
        val taken = rectangle.left until rectangle.right
        val shrunk = TableEdits.deleteColumns(table, taken) ?: return this
        if (shrunk.rows.none { it.cells.isNotEmpty() }) return committed(removal(at.block, null))
        val landing = landingIn(shrunk, rectangle.top, rectangle.left)
        return committed(Change(document.replacing(at.block, shrunk), origins, Selection(Caret(at.block, 0, landing))))
    }

    /**
     * Where the caret lands in [table] after rows or columns went: the
     * cell covering [row], [column], or the last cell of that row, or
     * the nearest cell there is, which normalising finds.
     */
    private fun landingIn(table: Table, row: Int, column: Int): Cell {
        val at = row.coerceIn(0, table.rows.size - 1)
        return TableEdits.cellAt(table, at, column) ?: Cell(at, (table.rows[at].cells.size - 1).coerceAtLeast(0))
    }

    /**
     * Tab: from a cell to the next, the whole of it selected, as in
     * every word processor, and from the last cell of a table to a new
     * row — which is how a table is grown by typing into it; or [back],
     * to the cell before. Outside a table, at the head of an item of a
     * list, the item is moved a level in or out. Anywhere else a tab is
     * typed, and a backward one is nothing.
     */
    fun tab(back: Boolean): EditorState {
        val at = normalised(selection.start)
        val cell = at.cell
        if (cell == null) {
            val paragraph = document.paragraphAt(at)
            if (paragraph.style.listMarker != null && selection.collapsed && at.offset == 0) {
                val level = paragraph.style.listLevel + (if (back) -1 else 1)
                return if (level in 0..MOST_LIST_LEVEL) restyle(ParagraphChange(listLevel = level)) else this
            }
            return if (back) this else type("\t")
        }
        val table = document.blocks[at.block] as Table
        val order = table.rows.flatMapIndexed { row, held -> held.cells.indices.map { Cell(row, it) } }
        val index = order.indexOf(Cell(cell.row, cell.column))
        val next = index + (if (back) -1 else 1)
        if (index < 0 || next < 0) return this
        if (next < order.size) return select(wholeOf(at.block, table, order[next]))
        val grown = insertRow(below = true)
        if (grown === this) return this
        val rows = grown.document.blocks[at.block] as Table
        return grown.select(wholeOf(at.block, rows, Cell(rows.rows.size - 1, 0)))
    }

    /**
     * The cells selected made one cell, covering the places they did
     * and holding their paragraphs one after another — an empty cell's
     * left out, since a merge that spreads empty paragraphs through the
     * cell is a merge that has to be tidied after. The first of them
     * keeps its place, its fill, and the caret.
     */
    fun mergeCells(): EditorState {
        val selected = selectedCellsOf(selection) ?: return this
        val table = selected.table
        val chosen = selected.cells.map { it.row to it.column }.toSet()
        val first = selected.cells.first()
        val held = table.rows[first.row].cells[first.column]
        val blocks = selected.cells.flatMap { table.rows[it.row].cells[it.column].blocks }.filterNot(::isBlank).ifEmpty { listOf(blank(held)) }
        val rectangle = selected.rectangle
        val merged = held.copy(blocks = blocks, columnSpan = rectangle.right - rectangle.left, rowSpan = rectangle.bottom - rectangle.top)
        val rows = table.rows.mapIndexed { row, held ->
            held.copy(
                cells = held.cells.mapIndexedNotNull { column, cell ->
                    when {
                        row == first.row && column == first.column -> merged
                        (row to column) in chosen -> null
                        else -> cell
                    }
                },
            )
        }
        return committed(Change(document.replacing(selected.block, table.copy(rows = rows)), origins, Selection(selected.first)))
    }

    /**
     * The merged cell the caret stands in made the cells it covered,
     * each at its own place: the first keeps what the cell held, the
     * others are empty and set as it was. The reverse of [mergeCells].
     */
    fun splitCell(): EditorState {
        val at = normalised(selection.start)
        val cell = at.cell ?: return this
        val table = document.blocks[at.block] as Table
        val places = Places(table)
        val rectangle = places.rectangleOf(cell.row, cell.column) ?: return this
        if (rectangle.bottom - rectangle.top <= 1 && rectangle.right - rectangle.left <= 1) return this
        val held = table.rows[cell.row].cells[cell.column]
        val rows = table.rows.toMutableList()
        for (row in rectangle.top until rectangle.bottom) {
            val kept = rows[row].cells.indices
                .filter { !(row == cell.row && it == cell.column) }
                .map { places.columnOf(row, it) to rows[row].cells[it] }
            val made = (rectangle.left until rectangle.right).map { column ->
                val piece = if (row == cell.row && column == rectangle.left) held else held.copy(blocks = listOf(emptyParagraph()))
                column to piece.copy(columnSpan = 1, rowSpan = 1)
            }
            rows[row] = rows[row].copy(cells = (kept + made).sortedBy { it.first }.map { it.second })
        }
        val landing = Caret(at.block, 0, Cell(cell.row, cell.column, 0))
        return committed(Change(document.replacing(at.block, table.copy(rows = rows)), origins, Selection(landing)))
    }

    /**
     * The cells a selection across cells covers — every cell of the
     * rectangle from one end's cell to the other's, grown to hold whole
     * any merged cell it cuts through, since half a cell cannot be
     * selected — in the order a reader meets them; or none, where the
     * selection stands in one cell or in no cell.
     */
    fun selectedCells(): List<Cell> = selectedCellsOf(selection)?.cells.orEmpty()

    /** Whether the selection is of cells [mergeCells] could make one. */
    val canMergeCells: Boolean get() = selectedCellsOf(selection) != null

    /** Whether the caret stands in a cell [splitCell] could make several. */
    val canSplitCell: Boolean
        get() {
            val at = normalised(selection.start)
            val cell = at.cell ?: return false
            val rectangle = Places(document.blocks[at.block] as Table).rectangleOf(cell.row, cell.column) ?: return false
            return rectangle.bottom - rectangle.top > 1 || rectangle.right - rectangle.left > 1
        }

    /**
     * The block at [index] taken out — the way a table or a picture is
     * deleted, since a caret cannot stand in one to select it.
     */
    fun removeBlock(index: Int): EditorState {
        if (index !in document.blocks.indices) return this
        return committed(removal(index, null))
    }

    /**
     * This session as text, to be kept somewhere the process's death does
     * not reach, and given back to [restored].
     *
     * The document as opened and as it now stands, where each block came
     * from, and where the caret is — everything a reader would miss, and
     * not the history, which is what they would least miss and most of
     * what there is. Written the way [DocumentJson] writes a document,
     * and read back as carefully.
     */
    fun saved(): String = Json.write(
        mapOf(
            "morpho" to DocumentJson.FORMAT,
            "opened" to DocumentJson.toMap(opened),
            "document" to DocumentJson.toMap(document),
            "origins" to origins,
            "selection" to listOf(EditorProtocol.caretJson(selection.anchor), EditorProtocol.caretJson(selection.focus)),
        ),
    )

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
        if (!start.sharesContainerWith(end)) {
            // Across cells, every cell selected is emptied, since the
            // selection is of whole cells; across anything else, nothing
            // crosses a cell's edge.
            val selected = selectedCellsOf(Selection(start, end)) ?: return null
            return Change(document.replacing(selected.block, cleared(selected)), origins, Selection(selected.first))
        }
        val first = document.paragraphAt(start)
        // Never inside a surrogate pair: a caret is put after one by
        // normalising, so a selection starting on the second half of a
        // character starts after the character.
        val from = start.offset
        val head = ParagraphEdit.slice(first.runs, 0, from)
        val look = ParagraphEdit.plain(lookIn(first.runs, from))
        if (start.sameParagraphAs(end)) {
            val runs = ParagraphEdit.merged(head + ParagraphEdit.slice(first.runs, end.offset, first.text.length))
            val kept = first.copy(runs = runs.ifEmpty { emptied(look) })
            return Change(document.replacingAt(start, kept), origins, Selection(start.copy(offset = from)))
        }
        val last = document.paragraphAt(end)
        val runs = ParagraphEdit.merged(head + ParagraphEdit.slice(last.runs, end.offset, last.text.length))
        val kept = first.copy(
            runs = runs.ifEmpty { emptied(look) },
            confidence = minOf(first.confidence, last.confidence),
            bookmarks = first.bookmarks + last.bookmarks,
        )
        val cell = start.cell
        if (cell != null) {
            val cellBlocks = document.cellBlocksAt(start)
            val left = cellBlocks.take(cell.paragraph) + kept + cellBlocks.drop(end.cell!!.paragraph + 1)
            return Change(document.replacingCellBlocks(start, left), origins, Selection(start.copy(offset = from)))
        }
        val blocks = document.blocks.take(start.block) + kept + document.blocks.drop(end.block + 1)
        val nextOrigins = origins.take(start.block) + origins[start.block] + origins.drop(end.block + 1)
        return Change(document.copy(blocks = blocks), nextOrigins, Selection(Caret(start.block, from)))
    }

    /** The paragraph at [caret] joined onto the paragraph at [above] in the same cell, with nothing between. */
    private fun mergingInCell(caret: Caret, above: Int): Change {
        val cell = caret.cell!!
        val blocks = document.cellBlocksAt(caret)
        val first = blocks[above] as Paragraph
        val second = blocks[cell.paragraph] as Paragraph
        val joined = first.copy(
            runs = ParagraphEdit.merged(first.runs + second.runs).ifEmpty { emptied(lookIn(first.runs, 0)) },
            confidence = minOf(first.confidence, second.confidence),
            bookmarks = first.bookmarks + second.bookmarks,
        )
        val left = blocks.take(above) + joined + blocks.drop(cell.paragraph + 1)
        val landing = Caret(caret.block, first.text.length, cell.copy(paragraph = above))
        return Change(document.replacingCellBlocks(caret, left), origins, Selection(landing))
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
     * or, given none, where the block was: at the head of the paragraph
     * after it, or at the end of the one before where nothing follows.
     * A document is never left without a paragraph to stand in.
     */
    private fun removal(index: Int, landing: Selection?): Change {
        var blocks = document.blocks.take(index) + document.blocks.drop(index + 1)
        var nextOrigins = origins.take(index) + origins.drop(index + 1)
        if (blocks.none { it is Paragraph }) {
            blocks = blocks + Paragraph(emptied(TextRun("")))
            nextOrigins = nextOrigins + null
        }
        val selection = landing ?: run {
            val after = (index until blocks.size).firstOrNull { blocks[it] is Paragraph }
            if (after != null) {
                Selection(Caret(after, 0))
            } else {
                val before = (index - 1 downTo 0).first { blocks[it] is Paragraph }
                Selection(Caret(before, (blocks[before] as Paragraph).text.length))
            }
        }
        return Change(document.copy(blocks = blocks), nextOrigins, selection)
    }

    // ---- cells selected together ----

    /** A selection across cells, as the cells it covers. */
    private class Selected(val block: Int, val table: Table, val rectangle: Rectangle, val cells: List<Cell>) {
        /** The caret at the head of the first cell, top left. */
        val first: Caret get() = Caret(block, 0, cells.first().copy(paragraph = 0))
    }

    /** What [selection] selects across cells, or null where it stands in one cell or in none. */
    private fun selectedCellsOf(selection: Selection): Selected? {
        val a = selection.anchor
        val f = selection.focus
        val ac = a.cell ?: return null
        val fc = f.cell ?: return null
        if (a.block != f.block || (ac.row == fc.row && ac.column == fc.column)) return null
        val table = document.blocks.getOrNull(a.block) as? Table ?: return null
        val places = Places(table)
        val ra = places.rectangleOf(ac.row, ac.column) ?: return null
        val rf = places.rectangleOf(fc.row, fc.column) ?: return null
        val rectangle = places.closed(ra.union(rf))
        return Selected(a.block, table, rectangle, places.cellsIn(rectangle))
    }

    /** [selected]'s table with every cell selected holding one empty paragraph, set as its first was. */
    private fun cleared(selected: Selected): Table {
        val chosen = selected.cells.map { it.row to it.column }.toSet()
        val rows = selected.table.rows.mapIndexed { row, held ->
            held.copy(cells = held.cells.mapIndexed { column, cell -> if ((row to column) in chosen) cell.copy(blocks = listOf(blank(cell))) else cell })
        }
        return selected.table.copy(rows = rows)
    }

    /** [selected]'s table with every paragraph of every cell selected replaced by what [change] makes of it. */
    private fun eachParagraphOf(selected: Selected, change: (Paragraph) -> Paragraph): Table {
        val chosen = selected.cells.map { it.row to it.column }.toSet()
        val rows = selected.table.rows.mapIndexed { row, held ->
            held.copy(
                cells = held.cells.mapIndexed { column, cell ->
                    if ((row to column) in chosen) cell.copy(blocks = cell.blocks.map { if (it is Paragraph) change(it) else it }) else cell
                },
            )
        }
        return selected.table.copy(rows = rows)
    }

    /** One empty paragraph in the place of [cell]'s blocks, set the way its first paragraph was. */
    private fun blank(cell: TableCell): Paragraph {
        val first = cell.blocks.firstOrNull { it is Paragraph } as? Paragraph ?: return emptyParagraph()
        return first.copy(runs = emptied(lookIn(first.runs, 0)))
    }

    /** Whether [block] is an empty paragraph: nothing written, no picture, no field. */
    private fun isBlank(block: Block): Boolean =
        block is Paragraph && block.runs.all { it.text.isEmpty() && it.image == null && it.field == null }

    /** The whole of the cell [cell] of [table], block [block], as a selection: its first paragraph's head to its last's end. */
    private fun wholeOf(block: Int, table: Table, cell: Cell): Selection {
        val held = table.rows[cell.row].cells[cell.column].blocks
        val paragraphs = held.indices.filter { held[it] is Paragraph }
        if (paragraphs.isEmpty()) return Selection(Caret(block, 0, cell.copy(paragraph = 0)))
        val last = paragraphs.last()
        return Selection(
            Caret(block, 0, cell.copy(paragraph = paragraphs.first())),
            Caret(block, (held[last] as Paragraph).text.length, cell.copy(paragraph = last)),
        )
    }

    /** A rectangle of a table's places: rows [top] until [bottom], columns [left] until [right]. */
    private data class Rectangle(val top: Int, val bottom: Int, val left: Int, val right: Int) {
        fun meets(other: Rectangle): Boolean =
            top < other.bottom && other.top < bottom && left < other.right && other.left < right

        fun union(other: Rectangle): Rectangle =
            Rectangle(minOf(top, other.top), maxOf(bottom, other.bottom), minOf(left, other.left), maxOf(right, other.right))
    }

    /**
     * A table's cells at the places of its grid, for the edits that need
     * to know which column a cell begins at and how far it reaches: the
     * rows store only the cells that begin, so the place of a cell is
     * not its index in its row wherever a cell before it spans.
     */
    private class Places(private val table: Table) {
        private val filled: List<List<TableGrid.Filled>> =
            TableGrid.of(table).rows.map { row -> row.filterIsInstance<TableGrid.Filled>() }

        /** Whether every cell the table stores has a place; one whose spans run off its grid has cells without. */
        private val sound: Boolean = filled.indices.all { filled[it].size == table.rows[it].cells.size }

        /** The rectangle of places the cell at [row], [column] covers, or null where the table is not sound. */
        fun rectangleOf(row: Int, column: Int): Rectangle? {
            if (!sound) return null
            val place = filled.getOrNull(row)?.getOrNull(column) ?: return null
            return Rectangle(row, minOf(row + place.rowSpan, table.rows.size), place.column, place.column + place.span)
        }

        /** The column the cell at [row], [column] begins at. */
        fun columnOf(row: Int, column: Int): Int = filled[row][column].column

        /** The cells whose places lie in or across [rectangle], in the order a reader meets them. */
        fun cellsIn(rectangle: Rectangle): List<Cell> = buildList {
            for (row in table.rows.indices) {
                for (column in table.rows[row].cells.indices) {
                    if (rectangleOf(row, column)?.meets(rectangle) == true) add(Cell(row, column))
                }
            }
        }

        /** [rectangle] grown until every cell it cuts through lies whole inside it. */
        fun closed(rectangle: Rectangle): Rectangle {
            var out = rectangle
            while (true) {
                val grown = cellsIn(out).fold(out) { acc, cell -> acc.union(rectangleOf(cell.row, cell.column)!!) }
                if (grown == out) return out
                out = grown
            }
        }
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

    private fun emptyParagraph(): Paragraph = Paragraph(listOf(TextRun("")))

    /** The look at [at] in [runs], or a plain one where the paragraph holds nothing to take it from. */
    private fun lookIn(runs: List<TextRun>, at: Int): TextRun =
        if (runs.isEmpty()) TextRun("") else ParagraphEdit.lookOf(runs, at)

    /** The one run an empty paragraph keeps, so that typing into it has a look to take. */
    private fun emptied(look: TextRun): List<TextRun> = listOf(ParagraphEdit.plain(look).copy(text = ""))

    companion object {
        /** The most steps kept to go back over; the oldest is let go past this. */
        const val MOST_STEPS = 500

        /** The deepest an item of a list is moved in, which is as deep as Word lets one go. */
        const val MOST_LIST_LEVEL = 8

        /**
         * A session [saved] earlier, as it was — with nothing to undo,
         * since the history was not kept, and refused with [Json.Malformed]
         * where the text is not a session.
         */
        fun restored(json: String): EditorState {
            val map = Json.parse(json) as? Map<*, *> ?: throw Json.Malformed("not a session")
            if (map["morpho"] != DocumentJson.FORMAT.toDouble()) throw Json.Malformed("a session in another shape")
            val opened = DocumentJson.fromMap(map["opened"] as? Map<*, *> ?: throw Json.Malformed("no opened document"))
            val document = DocumentJson.fromMap(map["document"] as? Map<*, *> ?: throw Json.Malformed("no document"))
            val origins = (map["origins"] as? List<*> ?: throw Json.Malformed("no origins")).map { origin ->
                when (origin) {
                    null -> null
                    is Double -> origin.toInt().also { if (it.toDouble() != origin || it !in opened.blocks.indices) throw Json.Malformed("an origin outside the document opened") }
                    else -> throw Json.Malformed("an origin that is not one")
                }
            }
            if (origins.size != document.blocks.size) throw Json.Malformed("origins for a different document")
            val carets = (map["selection"] as? List<*>)?.map { EditorProtocol.caret(it) ?: throw Json.Malformed("a caret that is not one") }
                ?: throw Json.Malformed("no selection")
            if (carets.size != 2) throw Json.Malformed("a selection that is not two carets")
            val blank = EditorState(
                document = document,
                selection = Selection(Caret(0, 0)),
                pending = null,
                opened = opened,
                origins = origins,
                undos = emptyList(),
                redos = emptyList(),
                continuing = null,
            )
            return blank.select(Selection(carets[0], carets[1]))
        }

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
            val opened = withParagraphsToStandIn(document)
            val blocks = opened.blocks
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
            val cell = caret.cell
            val table = blocks[block] as? Table
            if (table != null && cell != null && table.rows.any { it.cells.isNotEmpty() }) {
                // Into the nearest cell paragraph the table has, which it
                // always has, since every cell is given one to stand in —
                // the nearest row with a cell of its own, since a row a
                // merged cell covers whole has none.
                val nearest = cell.row.coerceIn(0, table.rows.size - 1)
                val row = table.rows.indices.filter { table.rows[it].cells.isNotEmpty() }.minBy { kotlin.math.abs(it - nearest) }
                val cells = table.rows[row].cells
                val column = cell.column.coerceIn(0, cells.size - 1)
                val held = cells[column].blocks
                val paragraphs = held.indices.filter { held[it] is Paragraph }
                val paragraph = paragraphs.minByOrNull { kotlin.math.abs(it - cell.paragraph) }!!
                val text = (held[paragraph] as Paragraph).text
                return Caret(block, snapped(text, caret.offset), Cell(row, column, paragraph))
            }
            if (blocks[block] !is Paragraph) {
                block = (block until blocks.size).firstOrNull { blocks[it] is Paragraph }
                    ?: (block downTo 0).first { blocks[it] is Paragraph }
            }
            return Caret(block, snapped((blocks[block] as Paragraph).text, caret.offset))
        }

        /** [offset] inside [text], and never between the two halves of a surrogate pair. */
        private fun snapped(text: String, offset: Int): Int {
            var at = offset.coerceIn(0, text.length)
            if (at in 1 until text.length && text[at].isLowSurrogate() && text[at - 1].isHighSurrogate()) at++
            return at
        }

        /**
         * [document] with a paragraph everywhere a caret may need one: at
         * the top, and in every cell of every table, since a cell with
         * nothing in it is a cell a reader will want to type into.
         */
        private fun withParagraphsToStandIn(document: DocumentModel): DocumentModel {
            var blocks = document.blocks.map { block ->
                if (block !is Table) return@map block
                val layout = TableGrid.of(block)
                val rows = block.rows.mapIndexed { index, row ->
                    if (row.cells.isEmpty()) {
                        // A row with no cells of its own is one a merged
                        // cell above reaches, whose row it now is, and is
                        // left so; or one nobody filled, which is given a
                        // cell.
                        val covered = layout.rows.getOrNull(index)?.any { it is TableGrid.Covered } == true
                        return@mapIndexed if (covered) row else row.copy(cells = listOf(TableCell(listOf(Paragraph(listOf(TextRun("")))))))
                    }
                    val cells = row.cells.map { cell ->
                        if (cell.blocks.any { it is Paragraph }) cell
                        else cell.copy(blocks = cell.blocks + Paragraph(listOf(TextRun(""))))
                    }
                    if (cells == row.cells) row else row.copy(cells = cells)
                }
                if (rows == block.rows) block else block.copy(rows = rows)
            }
            if (blocks.none { it is Paragraph }) blocks = blocks + Paragraph(listOf(TextRun("")))
            return if (blocks == document.blocks) document else document.copy(blocks = blocks)
        }

        /** The paragraph [caret] names, at the top or in a cell. */
        private fun DocumentModel.paragraphAt(caret: Caret): Paragraph {
            val cell = caret.cell ?: return blocks[caret.block] as Paragraph
            return cellBlocksAt(caret)[cell.paragraph] as Paragraph
        }

        /** The blocks of the cell [caret] stands in. */
        private fun DocumentModel.cellBlocksAt(caret: Caret): List<Block> {
            val cell = caret.cell!!
            return (blocks[caret.block] as Table).rows[cell.row].cells[cell.column].blocks
        }

        /** This document with the paragraph at [caret] replaced by [paragraph]. */
        private fun DocumentModel.replacingAt(caret: Caret, paragraph: Paragraph): DocumentModel {
            val cell = caret.cell ?: return replacing(caret.block, paragraph)
            return replacingCellBlocks(caret, cellBlocksAt(caret).toMutableList().also { it[cell.paragraph] = paragraph })
        }

        /** This document with the blocks of the cell [caret] stands in replaced by [held]. */
        private fun DocumentModel.replacingCellBlocks(caret: Caret, held: List<Block>): DocumentModel {
            val cell = caret.cell!!
            val table = blocks[caret.block] as Table
            val row = table.rows[cell.row]
            val cells = row.cells.toMutableList().also { it[cell.column] = it[cell.column].copy(blocks = held) }
            val rows = table.rows.toMutableList().also { it[cell.row] = row.copy(cells = cells) }
            return replacing(caret.block, table.copy(rows = rows))
        }

        /** Whether two carets name the same paragraph. */
        private fun Caret.sameParagraphAs(other: Caret): Boolean =
            block == other.block && cell == other.cell

        /**
         * The paragraphs from [start] to [end], in order — the document's
         * between two blocks, or one cell's between two of its paragraphs.
         * Two ends in different cells reach only the first.
         */
        private fun DocumentModel.paragraphsBetween(start: Caret, end: Caret): List<Caret> {
            val cell = start.cell
            if (cell == null) {
                if (end.cell != null) return listOf(start)
                return (start.block..end.block).mapNotNull { at -> if (blocks[at] is Paragraph) Caret(at, 0) else null }
            }
            if (!start.sharesContainerWith(end)) return listOf(start)
            val held = cellBlocksAt(start)
            return (cell.paragraph..end.cell!!.paragraph).mapNotNull { at ->
                if (held[at] is Paragraph) Caret(start.block, 0, cell.copy(paragraph = at)) else null
            }
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
