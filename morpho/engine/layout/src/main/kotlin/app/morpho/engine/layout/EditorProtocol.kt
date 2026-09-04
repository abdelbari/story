package app.morpho.engine.layout

/**
 * What crosses the bridge between the screen and [EditorState], in both
 * directions, as text.
 *
 * Going in, an operation: what the reader just did, as the screen's
 * script understood it — `{"op":"type","text":"x"}`, `{"op":"select",
 * "anchor":[3,12],"focus":[3,12]}`, `{"op":"format","bold":true}`. A flat
 * grammar of a dozen shapes, each read as if a hostile document wrote
 * it, because the page that sends it renders one: a shape this does not
 * know, a value of the wrong kind, a table of a million cells, are all
 * refused, and refusing is a reply that says so and a document that is
 * exactly as it was.
 *
 * Coming back, what the screen needs to repaint and no more: the blocks
 * that changed, as the elements the page writes for them, with where to
 * put them; where the caret now is; whether there is anything to undo;
 * and the look at the caret, for the toolbar's buttons. **Nothing in the
 * screen is the document** — the reply is a picture of the part that
 * changed, and if the screen lost it the next reply, or [opening],
 * paints it again.
 *
 * Which blocks changed is the splice between the document before and
 * after: the common head and the common tail are unchanged, and what
 * lies between is written out afresh. One edit changes one stretch, so
 * a keystroke repaints a paragraph, a Return two, and a deletion across
 * a page whatever the page held. The screen takes the elements from
 * `from` up to `to` out, puts the new ones in their place, and numbers
 * every block's mark again from the top, since the ones after a splice
 * have moved. The one thing a splice cannot say is
 * the list round an item, which belongs to its neighbours as much as to
 * it — so an edit that touches an item of a list, or a part set on a
 * sheet of its own, repaints the whole body instead, and says so.
 */
object EditorProtocol {

    /** The most rows or columns a table put in over the bridge may have. */
    const val MOST_TABLE_SIDE = 64

    /** The most characters one typing may carry; a paste is many of them. */
    const val MOST_TYPED = 200_000

    /** What the screen's script says the reader did. */
    sealed interface Operation {
        data class Select(val selection: Selection) : Operation
        data class Type(val text: String) : Operation
        data class Paste(val text: String) : Operation
        data object Erase : Operation
        data object EraseForward : Operation
        data object Split : Operation
        data class Format(val change: RunChange) : Operation
        data class Restyle(val change: ParagraphChange) : Operation
        data class InsertTable(val rows: Int, val columns: Int) : Operation
        data class InsertRow(val below: Boolean) : Operation
        data object DeleteRow : Operation
        data class InsertColumn(val after: Boolean) : Operation
        data object DeleteColumn : Operation
        data class RemoveBlock(val block: Int) : Operation
        data class Tab(val back: Boolean) : Operation
        data object MergeCells : Operation
        data object SplitCell : Operation
        data class Find(val query: String, val ignoreCase: Boolean) : Operation
        data class ReplaceAll(val query: String, val replacement: String, val ignoreCase: Boolean) : Operation
        data object Undo : Operation
        data object Redo : Operation
    }

    /** An operation read from [json], or null where it is not one this knows. */
    fun operation(json: String): Operation? {
        val map = try {
            Json.parse(json) as? Map<*, *>
        } catch (e: Json.Malformed) {
            null
        } ?: return null
        return try {
            when (map["op"]) {
                "select" -> Operation.Select(Selection(caret(map["anchor"]) ?: return null, caret(map["focus"]) ?: return null))
                "type" -> {
                    val text = map["text"] as? String ?: return null
                    if (text.length > MOST_TYPED) return null
                    Operation.Type(text)
                }
                "paste" -> Operation.Paste(typed(map, "text") ?: return null)
                "erase" -> Operation.Erase
                "eraseForward" -> Operation.EraseForward
                "split" -> Operation.Split
                "format" -> Operation.Format(
                    RunChange(
                        bold = flag(map, "bold"),
                        italic = flag(map, "italic"),
                        underline = flag(map, "underline"),
                        strikethrough = flag(map, "strikethrough"),
                        superscript = flag(map, "superscript"),
                        subscript = flag(map, "subscript"),
                        fontFamily = put(map, "fontFamily") { it as? String ?: throw Refused() },
                        fontSizePt = put(map, "fontSizePt") { size(it) },
                        colorRgb = put(map, "colorRgb") { rgb(it) },
                        highlightRgb = put(map, "highlightRgb") { rgb(it) },
                        link = put(map, "link") { it as? String ?: throw Refused() },
                        language = put(map, "language") { it as? String ?: throw Refused() },
                    )
                )
                "restyle" -> Operation.Restyle(
                    ParagraphChange(
                        kind = (map["kind"] as? String)?.let { name -> ParagraphKind.entries.firstOrNull { it.name == name } ?: throw Refused() },
                        alignment = put(map, "alignment") { v -> Alignment.entries.firstOrNull { it.name == v } ?: throw Refused() },
                        direction = put(map, "direction") { v -> TextDirection.entries.firstOrNull { it.name == v } ?: throw Refused() },
                        listMarker = put(map, "listMarker") { v -> ListMarker.entries.firstOrNull { it.name == v } ?: throw Refused() },
                        listLevel = (map["listLevel"] as? Double)?.let { whole(it, 0, 8) },
                        listFormat = put(map, "listFormat") { it as? String ?: throw Refused() },
                        firstLineIndentPt = put(map, "firstLineIndentPt") { size(it) },
                        startIndentPt = put(map, "startIndentPt") { size(it) },
                        hangingIndentPt = put(map, "hangingIndentPt") { size(it) },
                        spaceBeforePt = put(map, "spaceBeforePt") { size(it) },
                        spaceAfterPt = put(map, "spaceAfterPt") { size(it) },
                        linePitchPt = put(map, "linePitchPt") { size(it) },
                        pageBreakBefore = flag(map, "pageBreakBefore"),
                    )
                )
                "insertTable" -> Operation.InsertTable(
                    rows = whole(map["rows"] as? Double ?: return null, 1, MOST_TABLE_SIDE),
                    columns = whole(map["columns"] as? Double ?: return null, 1, MOST_TABLE_SIDE),
                )
                "insertRow" -> Operation.InsertRow(flag(map, "below") ?: true)
                "deleteRow" -> Operation.DeleteRow
                "insertColumn" -> Operation.InsertColumn(flag(map, "after") ?: true)
                "deleteColumn" -> Operation.DeleteColumn
                "removeBlock" -> Operation.RemoveBlock(whole(map["block"] as? Double ?: return null, 0, Int.MAX_VALUE))
                "tab" -> Operation.Tab(flag(map, "back") ?: false)
                "mergeCells" -> Operation.MergeCells
                "splitCell" -> Operation.SplitCell
                "find" -> Operation.Find(typed(map, "query") ?: return null, flag(map, "ignoreCase") ?: false)
                "replaceAll" -> Operation.ReplaceAll(
                    typed(map, "query") ?: return null,
                    typed(map, "replacement") ?: return null,
                    flag(map, "ignoreCase") ?: false,
                )
                "undo" -> Operation.Undo
                "redo" -> Operation.Redo
                else -> null
            }
        } catch (e: Refused) {
            null
        }
    }

    /** [state] with [operation] done to it. */
    fun apply(state: EditorState, operation: Operation): EditorState = when (operation) {
        is Operation.Select -> state.select(operation.selection)
        is Operation.Type -> state.type(operation.text)
        is Operation.Paste -> state.paste(operation.text)
        Operation.Erase -> state.erase()
        Operation.EraseForward -> state.eraseForward()
        Operation.Split -> state.splitParagraph()
        is Operation.Format -> state.format(operation.change)
        is Operation.Restyle -> state.restyle(operation.change)
        is Operation.InsertTable -> state.insertBlock(emptyTable(operation.rows, operation.columns))
        is Operation.InsertRow -> state.insertRow(operation.below)
        Operation.DeleteRow -> state.deleteRow()
        is Operation.InsertColumn -> state.insertColumn(operation.after)
        Operation.DeleteColumn -> state.deleteColumn()
        is Operation.RemoveBlock -> state.removeBlock(operation.block)
        is Operation.Tab -> state.tab(operation.back)
        Operation.MergeCells -> state.mergeCells()
        Operation.SplitCell -> state.splitCell()
        is Operation.Find -> state
        is Operation.ReplaceAll -> state.replaceAll(operation.query, operation.replacement, operation.ignoreCase)
        Operation.Undo -> state.undo()
        Operation.Redo -> state.redo()
    }

    /** The state after, and what to tell the screen. */
    class Step(val state: EditorState, val reply: String)

    /**
     * [json] read, done to [state], and answered — or refused, in which
     * case the state is [state] and the reply says why.
     */
    fun step(state: EditorState, json: String): Step {
        val operation = operation(json) ?: return Step(state, Json.write(mapOf("error" to "refused")))
        if (operation is Operation.Find) {
            // A question, not an edit: nothing to paint, and the places
            // asked for, each as a selection the screen can hand back.
            val matches = state.find(operation.query, operation.ignoreCase).take(MOST_MATCHES)
            val painting = mapOf("all" to false, "splice" to mapOf("from" to state.document.blocks.size, "to" to state.document.blocks.size, "blocks" to emptyList<String>()))
            return Step(state, Json.write(status(state) + painting + mapOf("matches" to matches.map { listOf(caretJson(it.start), caretJson(it.end)) })))
        }
        val after = apply(state, operation)
        return Step(after, reply(state, after))
    }

    /** The most places one search reports; a document that says a word more often says it enough. */
    const val MOST_MATCHES = 10_000

    /** A string of the length typing may be, or null where it is not one. */
    private fun typed(map: Map<*, *>, key: String): String? {
        val text = map[key] as? String ?: return null
        return if (text.length > MOST_TYPED) null else text
    }

    /** What the screen needs to paint [state] from nothing: the whole body. */
    fun opening(state: EditorState): String = Json.write(
        status(state) + mapOf("all" to true, "body" to HtmlWriter.writeBody(state.document, comments = false)),
    )

    /**
     * What the screen needs to go from [before] to [after]: the splice of
     * blocks that changed, rendered, or the whole body where a list or a
     * sheet is involved; and the state of things at the caret.
     */
    fun reply(before: EditorState, after: EditorState): String {
        val was = before.document.blocks
        val now = after.document.blocks
        var head = 0
        while (head < was.size && head < now.size && was[head] == now[head]) head++
        var tail = 0
        while (tail < was.size - head && tail < now.size - head && was[was.size - 1 - tail] == now[now.size - 1 - tail]) tail++
        val gone = was.subList(head, was.size - tail)
        val come = now.subList(head, now.size - tail)
        val wholeBody = (gone + come).any { block ->
            block is Paragraph && (block.style.listMarker != null || block.style.sectionSetup != null)
        }
        val painting: Map<String, Any?> = if (wholeBody) {
            mapOf("all" to true, "body" to HtmlWriter.writeBody(after.document, comments = false))
        } else {
            mapOf(
                "all" to false,
                "splice" to mapOf(
                    "from" to head,
                    "to" to was.size - tail,
                    "blocks" to come.indices.map { HtmlWriter.writeBlock(after.document, head + it, comments = false) },
                ),
            )
        }
        return Json.write(status(after) + painting)
    }

    /** Where the caret is and what is at it, which every reply carries. */
    private fun status(state: EditorState): Map<String, Any?> {
        val look = state.lookOf(state.selection)
        val style = state.paragraphAt(state.selection.start).style
        return mapOf(
            "selection" to mapOf(
                "anchor" to caretJson(state.selection.anchor),
                "focus" to caretJson(state.selection.focus),
            ),
            "canUndo" to state.canUndo,
            "canRedo" to state.canRedo,
            "modified" to state.modified.size,
            // The cells selected together, for a toolbar that offers to
            // merge them, and whether the caret's cell could be split.
            "cells" to state.selectedCells().map { listOf(it.row, it.column) },
            "canMerge" to state.canMergeCells,
            "canSplit" to state.canSplitCell,
            "look" to mapOf(
                "bold" to look.bold,
                "italic" to look.italic,
                "underline" to look.underline,
                "strikethrough" to look.strikethrough,
                "superscript" to look.superscript,
                "subscript" to look.subscript,
                "fontFamily" to look.fontFamily,
                "fontSizePt" to look.fontSizePt,
                "colorRgb" to look.colorRgb,
                "highlightRgb" to look.highlightRgb,
                "link" to look.link,
            ),
            "paragraph" to mapOf(
                "kind" to style.kind.name,
                "alignment" to style.alignment?.name,
                "direction" to style.direction?.name,
                "listMarker" to style.listMarker?.name,
                "listLevel" to style.listLevel,
            ),
        )
    }

    /** A table of [rows] by [columns] empty cells, ruled, for a reader to fill. */
    private fun emptyTable(rows: Int, columns: Int): Table = Table(
        rows = List(rows) { TableRow(List(columns) { TableCell(listOf(Paragraph(listOf(TextRun(""))))) }) },
    )

    // ---- reading the shapes, refusing what is not one ----

    private class Refused : Exception()

    /**
     * A caret as the page says it: `[block, offset]`, or inside a cell
     * `[block, offset, row, column, paragraph]`. Anything out of range is
     * put in range by the editor; anything that is not a whole number is
     * not a caret.
     */
    internal fun caret(value: Any?): Caret? {
        val parts = value as? List<*> ?: return null
        if (parts.size != 2 && parts.size != 5) return null
        val numbers = parts.map { (it as? Double ?: return null).let { d -> whole(d, -1, Int.MAX_VALUE) } }
        val cell = if (parts.size == 5) Cell(numbers[2], numbers[3], numbers[4]) else null
        return Caret(numbers[0], numbers[1], cell)
    }

    /** A caret as the page reads it; see [caret]. */
    internal fun caretJson(caret: Caret): List<Int> {
        val cell = caret.cell ?: return listOf(caret.block, caret.offset)
        return listOf(caret.block, caret.offset, cell.row, cell.column, cell.paragraph)
    }

    /** A yes, a no, or nothing said. */
    private fun flag(map: Map<*, *>, key: String): Boolean? = when (val v = map[key]) {
        null -> null
        is Boolean -> v
        else -> throw Refused()
    }

    /**
     * A property set, or set to nothing, or not mentioned: present and
     * null is [Put] of null, absent is no change. JSON has the three
     * states already, and this is what they are for.
     */
    private inline fun <T> put(map: Map<*, *>, key: String, read: (Any?) -> T): Put<T?>? {
        if (!map.containsKey(key)) return null
        val v = map[key] ?: return Put(null)
        return Put(read(v))
    }

    private fun size(value: Any?): Float {
        val d = value as? Double ?: throw Refused()
        if (d.isNaN() || d < 0.0 || d > 10_000.0) throw Refused()
        return d.toFloat()
    }

    private fun rgb(value: Any?): Int {
        val d = value as? Double ?: throw Refused()
        return whole(d, 0, 0xFFFFFF)
    }

    private fun whole(value: Double, least: Int, most: Int): Int {
        if (value.isNaN() || value != Math.rint(value)) throw Refused()
        if (value < least || value > most) throw Refused()
        return value.toInt()
    }
}
