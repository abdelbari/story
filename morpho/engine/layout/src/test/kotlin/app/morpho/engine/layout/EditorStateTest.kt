package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * What an editor does to a document, held here where a test can reach
 * it: the caret, the selection, typing, and the formatting of the words
 * round an edit left exactly as it was.
 */
class EditorStateTest {

    private fun r(text: String, bold: Boolean = false, italic: Boolean = false, link: String? = null) =
        TextRun(text, bold = bold, italic = italic, link = link)

    private fun p(vararg runs: TextRun, style: ParagraphStyle = ParagraphStyle()) =
        Paragraph(runs.toList(), style)

    private fun p(text: String, kind: ParagraphKind = ParagraphKind.BODY) =
        Paragraph(listOf(TextRun(text)), ParagraphStyle(kind = kind))

    private fun doc(vararg blocks: Block) = DocumentModel(blocks.toList())

    private fun table() = Table(listOf(TableRow(listOf(TableCell(listOf(p("cell")))))))

    private fun picture() = ImageBlock(ByteArray(4), "image/png", 1, 1)

    private fun texts(state: EditorState) =
        state.document.blocks.map { (it as? Paragraph)?.text ?: "<${it::class.simpleName}>" }

    private fun runs(state: EditorState, block: Int) =
        (state.document.blocks[block] as Paragraph).runs.map { it.text to it.bold }

    private fun EditorState.at(block: Int, offset: Int) = select(Selection.at(block, offset))

    private fun EditorState.over(block: Int, offset: Int, toBlock: Int, toOffset: Int) =
        select(Selection(Caret(block, offset), Caret(toBlock, toOffset)))

    @Test
    fun `typing takes the look of the character to its left`() {
        val state = EditorState.open(doc(p(r("The "), r("form", bold = true), r(" arrives"))))
        val typed = state.at(0, 8).type("s")
        assertEquals(listOf("The " to false, "forms" to true, " arrives" to false), runs(typed, 0))
        assertEquals(Selection.at(0, 9), typed.selection)
        // At the very head there is nothing to the left, so the first run stands in.
        assertEquals(listOf("A The " to false, "form" to true, " arrives" to false), runs(state.at(0, 0).type("A "), 0))
    }

    @Test
    fun `typing inside a link stays inside the link`() {
        val state = EditorState.open(doc(p(r("see "), r("the site", link = "https://x"), r(" now"))))
        val typed = state.at(0, 7).type(" whole")
        val runs = (typed.document.blocks[0] as Paragraph).runs
        assertEquals(listOf("see ", "the whole site", " now"), runs.map { it.text })
        assertEquals(listOf(null, "https://x", null), runs.map { it.link })
    }

    @Test
    fun `a look chosen with nothing selected is taken by the next typing and let go by moving the caret`() {
        val state = EditorState.open(doc(p("The form")))
        val chosen = state.at(0, 4).format(RunChange(bold = true))
        assertEquals(RunChange(bold = true), chosen.pending)
        assertTrue(chosen.lookAt(Caret(0, 4)).bold, "the toolbar would show bold down")
        assertFalse(chosen.canUndo, "choosing a look is not an edit")
        val typed = chosen.type("whole ")
        assertEquals(listOf("The " to false, "whole " to true, "form" to false), runs(typed, 0))
        assertNull(typed.pending)
        // Chosen and then walked away from: forgotten.
        val moved = state.at(0, 4).format(RunChange(italic = true)).at(0, 0)
        assertNull(moved.pending)
        assertFalse((moved.type("x").document.blocks[0] as Paragraph).runs.first().italic)
    }

    @Test
    fun `typing over a selection replaces it in one step`() {
        val state = EditorState.open(doc(p("The form arrives")))
        val typed = state.over(0, 0, 0, 8).type("A letter")
        assertEquals(listOf("A letter arrives"), texts(typed))
        assertEquals(1, typed.undoDepth)
        assertEquals(listOf("The form arrives"), texts(typed.undo()))
    }

    @Test
    fun `backspace takes the character before the caret, and a word's worth of it is one step`() {
        val state = EditorState.open(doc(p("ab")))
        val typed = state.at(0, 2).type("c").type("d").type("e")
        assertEquals(listOf("abcde"), texts(typed))
        assertEquals(1, typed.undoDepth, "letters typed one after another are one step")
        val erased = typed.erase().erase()
        assertEquals(listOf("abc"), texts(erased))
        assertEquals(2, erased.undoDepth, "erasing is a step of its own, and one step for the run of it")
        assertEquals(listOf("abcde"), texts(erased.undo()))
        assertEquals(listOf("ab"), texts(erased.undo().undo()))
        assertEquals(listOf("abc"), texts(erased.undo().undo().redo().redo()))
    }

    @Test
    fun `backspace at the head of a paragraph joins it to the one above with nothing between`() {
        val state = EditorState.open(doc(p("First"), p(r("Second", italic = true))))
        val joined = state.at(1, 0).erase()
        assertEquals(listOf("FirstSecond"), texts(joined))
        assertEquals(Selection.at(0, 5), joined.selection)
        assertEquals(listOf(false, true), (joined.document.blocks[0] as Paragraph).runs.map { it.italic })
        assertEquals(setOf(0), joined.modified)
    }

    @Test
    fun `backspace at the head of a paragraph after a picture takes the picture out`() {
        val state = EditorState.open(doc(p("A"), picture(), p("B")))
        val erased = state.at(2, 0).erase()
        assertEquals(listOf("A", "B"), texts(erased))
        assertEquals(Selection.at(1, 0), erased.selection)
    }

    @Test
    fun `backspace at the very start of the document does nothing`() {
        val state = EditorState.open(doc(p("A")))
        assertSame(state.at(0, 0), state.at(0, 0).erase())
    }

    @Test
    fun `delete at the end of a paragraph joins the one below, and at the end of the document does nothing`() {
        val state = EditorState.open(doc(p("First"), p("Second")))
        val joined = state.at(0, 5).eraseForward()
        assertEquals(listOf("FirstSecond"), texts(joined))
        assertEquals(Selection.at(0, 5), joined.selection)
        val end = joined.at(0, 11)
        assertSame(end, end.eraseForward())
    }

    @Test
    fun `a selection across paragraphs and a table takes everything between`() {
        val state = EditorState.open(
            doc(
                p(r("Head "), r("of first", bold = true)),
                table(),
                p("middle"),
                p(r("tail of "), r("last", italic = true)),
            )
        )
        val cut = state.over(0, 5, 3, 8).erase()
        assertEquals(listOf("Head last"), texts(cut))
        val runs = (cut.document.blocks[0] as Paragraph).runs
        assertEquals(listOf("Head " to false, "last" to true), runs.map { it.text to it.italic })
        assertEquals(Selection.at(0, 5), cut.selection)
        assertEquals(listOf("Head of first", "<Table>", "middle", "tail of last"), texts(cut.undo()))
    }

    @Test
    fun `Return in the middle of a heading gives two headings, and at its end a body paragraph`() {
        val state = EditorState.open(doc(p("Chapter one", ParagraphKind.HEADING_1)))
        val middle = state.at(0, 7).splitParagraph()
        assertEquals(listOf("Chapter", " one"), texts(middle))
        assertEquals(
            listOf(ParagraphKind.HEADING_1, ParagraphKind.HEADING_1),
            middle.document.blocks.map { (it as Paragraph).style.kind },
        )
        assertEquals(Selection.at(1, 0), middle.selection)
        val end = state.at(0, 11).splitParagraph()
        assertEquals(listOf("Chapter one", ""), texts(end))
        assertEquals(ParagraphKind.BODY, (end.document.blocks[1] as Paragraph).style.kind)
    }

    @Test
    fun `Return at the end of a list item continues the list, and on an empty item ends it`() {
        val item = ParagraphStyle(listMarker = ListMarker.BULLET, listLevel = 1)
        val state = EditorState.open(doc(p(r("first point"), style = item)))
        val next = state.at(0, 11).splitParagraph()
        assertEquals(item, (next.document.blocks[1] as Paragraph).style)
        val ended = next.splitParagraph()
        assertEquals(2, ended.document.blocks.size, "ending a list makes no third paragraph")
        assertEquals(ParagraphStyle(), (ended.document.blocks[1] as Paragraph).style)
    }

    @Test
    fun `a page break stays with the first half`() {
        val broken = ParagraphStyle(pageBreakBefore = true, ruleBelow = true)
        val state = EditorState.open(doc(p(r("one two"), style = broken)))
        val split = state.at(0, 3).splitParagraph()
        val (first, second) = split.document.blocks.map { (it as Paragraph).style }
        assertTrue(first.pageBreakBefore)
        assertFalse(second.pageBreakBefore)
        assertFalse(first.ruleBelow, "the rule was under the end of the paragraph, which is now the second half")
        assertTrue(second.ruleBelow)
    }

    @Test
    fun `bold across three paragraphs sets exactly what lies between the ends`() {
        val state = EditorState.open(doc(p("alpha beta"), p("gamma"), p("delta epsilon")))
        val bold = state.over(0, 6, 2, 5).format(RunChange(bold = true))
        assertEquals(listOf("alpha " to false, "beta" to true), runs(bold, 0))
        assertEquals(listOf("gamma" to true), runs(bold, 1))
        assertEquals(listOf("delta" to true, " epsilon" to false), runs(bold, 2))
        assertEquals(state.over(0, 6, 2, 5).selection, bold.selection, "formatting leaves the selection where it was")
        assertEquals(setOf(0, 1, 2), bold.modified)
    }

    @Test
    fun `a link is put on and taken off, and neither touches the bold round it`() {
        val state = EditorState.open(doc(p(r("see ", bold = true), r("the site"))))
        val linked = state.over(0, 0, 0, 12).format(RunChange(link = Put("https://x")))
        val runs = (linked.document.blocks[0] as Paragraph).runs
        assertEquals(listOf("https://x", "https://x"), runs.map { it.link })
        assertEquals(listOf(true, false), runs.map { it.bold })
        val unlinked = linked.format(RunChange(link = Put(null)))
        assertEquals(listOf(null, null), (unlinked.document.blocks[0] as Paragraph).runs.map { it.link })
    }

    @Test
    fun `raised and lowered are one choice`() {
        val run = TextRun("2", subscript = true)
        assertTrue(RunChange(superscript = true).applyTo(run).superscript)
        assertFalse(RunChange(superscript = true).applyTo(run).subscript)
    }

    @Test
    fun `restyle sets every paragraph the selection touches and nothing else`() {
        val state = EditorState.open(doc(p("one"), table(), p("two"), p("three")))
        val styled = state.over(0, 1, 2, 1).restyle(ParagraphChange(kind = ParagraphKind.HEADING_2))
        assertEquals(
            listOf(ParagraphKind.HEADING_2, ParagraphKind.HEADING_2, ParagraphKind.BODY),
            styled.document.blocks.filterIsInstance<Paragraph>().map { it.style.kind },
        )
        assertEquals(state.document.blocks[1], styled.document.blocks[1])
    }

    @Test
    fun `a table put in at the caret breaks the paragraph round it`() {
        val state = EditorState.open(doc(p(r("before "), r("after", bold = true))))
        val inserted = state.at(0, 7).insertBlock(table())
        assertEquals(listOf("before ", "<Table>", "after"), texts(inserted))
        assertEquals(Selection.at(2, 0), inserted.selection)
        assertTrue((inserted.document.blocks[2] as Paragraph).runs.single().bold)
    }

    @Test
    fun `a table put in at the end of a document is followed by a paragraph to stand in`() {
        val state = EditorState.open(doc(p("only")))
        val inserted = state.at(0, 4).insertBlock(table())
        assertEquals(listOf("only", "<Table>", ""), texts(inserted))
        assertEquals(Selection.at(2, 0), inserted.selection)
        // And at the head of a paragraph, before it, with the caret still in it.
        val before = state.at(0, 0).insertBlock(picture())
        assertEquals(listOf("<ImageBlock>", "only"), texts(before))
        assertEquals(Selection.at(1, 0), before.selection)
    }

    @Test
    fun `a document is never left without a paragraph to stand in`() {
        val emptied = EditorState.open(doc(p("only"))).over(0, 0, 0, 4).erase()
        assertEquals(listOf(""), texts(emptied))
        val opened = EditorState.open(doc(table()))
        assertEquals(listOf("<Table>", ""), texts(opened))
        assertEquals(Selection.at(1, 0), opened.selection)
        assertEquals(emptySet<Int>(), opened.modified, "the paragraph given to stand in is part of what was opened")
        val removed = opened.removeBlock(1)
        assertEquals(listOf("<Table>", ""), texts(removed))
    }

    @Test
    fun `an emoji is typed round and erased whole`() {
        val state = EditorState.open(doc(p("a🙂b")))
        // A caret asked for between the halves stands after the character.
        assertEquals(Selection.at(0, 3), state.at(0, 2).selection)
        assertEquals(listOf("a🙂Xb"), texts(state.at(0, 2).type("X")))
        assertEquals(listOf("ab"), texts(state.at(0, 3).erase()))
        assertEquals(listOf("ab"), texts(state.at(0, 1).eraseForward()))
    }

    @Test
    fun `undo puts the caret back where it was, and a new edit forgets what could be redone`() {
        val state = EditorState.open(doc(p("one"), p("two")))
        val edited = state.at(1, 3).type("!")
        val undone = edited.undo()
        assertEquals(Selection.at(1, 3), undone.selection)
        assertTrue(undone.canRedo)
        assertFalse(undone.at(0, 0).type("x").canRedo)
        assertEquals(listOf("one", "two!"), texts(undone.redo()))
    }

    @Test
    fun `modified names the blocks that are not as they were opened, wherever they now stand`() {
        val state = EditorState.open(doc(p("one"), p("two")))
        val typed = state.at(1, 3).type("!")
        assertEquals(setOf(1), typed.modified)
        val split = typed.at(0, 1).splitParagraph()
        assertEquals(listOf("o", "ne", "two!"), texts(split))
        assertEquals(setOf(0, 1, 2), split.modified)
        assertEquals(emptySet<Int>(), split.undo().undo().modified)
    }

    // ---- cells ----

    private fun grid(vararg rows: List<String>, spans: Boolean = false) = Table(
        rows.map { row ->
            TableRow(row.mapIndexed { at, text -> TableCell(listOf(p(text)), columnSpan = if (spans && at == 0) 2 else 1) })
        },
    )

    private fun cells(state: EditorState, block: Int) =
        (state.document.blocks[block] as Table).rows.map { r -> r.cells.map { c -> c.blocks.map { (it as? Paragraph)?.text } } }

    private fun EditorState.inCell(block: Int, row: Int, column: Int, paragraph: Int, offset: Int) =
        select(Selection(Caret(block, offset, Cell(row, column, paragraph))))

    @Test
    fun `typing in a cell changes that cell and nothing else`() {
        val state = EditorState.open(doc(grid(listOf("a", "b"), listOf("c", "d")), p("after")))
        val typed = state.inCell(0, 0, 1, 0, 1).type("!")
        assertEquals(listOf(listOf(listOf("a"), listOf("b!")), listOf(listOf("c"), listOf("d"))), cells(typed, 0))
        assertEquals(Caret(0, 2, Cell(0, 1, 0)), typed.selection.anchor)
        assertEquals(setOf(0), typed.modified)
        assertTrue(typed.lookAt(typed.selection.anchor).text.isEmpty())
    }

    @Test
    fun `Return in a cell makes a second paragraph of the cell, and Backspace at its head does nothing`() {
        val state = EditorState.open(doc(grid(listOf("cd", "x"))))
        val split = state.inCell(0, 0, 0, 0, 1).splitParagraph()
        assertEquals(listOf(listOf(listOf("c", "d"), listOf("x"))), cells(split, 0))
        assertEquals(Caret(0, 0, Cell(0, 0, 1)), split.selection.anchor)
        assertEquals(state.document.blocks.size, split.document.blocks.size, "the table is still the one block it was")
        assertTrue(split.document.blocks[0] is Table)
        val joined = split.erase()
        assertEquals(listOf(listOf(listOf("cd"), listOf("x"))), cells(joined, 0), "Backspace at the head of the second paragraph joins it upward in the cell")
        assertEquals(Caret(0, 1, Cell(0, 0, 0)), joined.selection.anchor)
        val head = joined.inCell(0, 0, 0, 0, 0)
        assertSame(head, head.erase(), "a cell's edge is not crossed")
        val end = joined.inCell(0, 0, 0, 0, 2)
        assertSame(end, end.eraseForward())
    }

    @Test
    fun `a selection that reaches out of a cell stands where it began`() {
        val state = EditorState.open(doc(grid(listOf("ab", "cd")), p("after")))
        val reaching = state.select(Selection(Caret(0, 1, Cell(0, 0, 0)), Caret(1, 3)))
        assertTrue(reaching.selection.collapsed)
        assertEquals(Caret(0, 1, Cell(0, 0, 0)), reaching.selection.anchor)
        val across = state.select(Selection(Caret(0, 0, Cell(0, 0, 0)), Caret(0, 1, Cell(0, 1, 0))))
        assertTrue(across.selection.collapsed, "two cells are not one selection yet")
        assertEquals(listOf("after"), texts(across.type("X")).drop(1))
    }

    @Test
    fun `rows and columns come and go, and take the table with them when it is the last`() {
        val state = EditorState.open(doc(p("before"), grid(listOf("a", "b"), listOf("c", "d")).copy(columnWidthsPt = listOf(100f, 200f))))
        val rowed = state.inCell(1, 0, 1, 0, 0).insertRow(below = true)
        assertEquals(listOf(listOf(listOf("a"), listOf("b")), listOf(listOf(""), listOf("")), listOf(listOf("c"), listOf("d"))), cells(rowed, 1))
        assertEquals(Caret(1, 0, Cell(1, 1, 0)), rowed.selection.anchor)
        val columned = rowed.insertColumn(after = false)
        assertEquals(listOf(listOf("a"), listOf(""), listOf("b")), cells(columned, 1)[0])
        assertEquals(listOf(100f, 200f, 200f), (columned.document.blocks[1] as Table).columnWidthsPt)
        assertEquals(Caret(1, 0, Cell(1, 1, 0)), columned.selection.anchor)
        val unrowed = columned.deleteRow()
        assertEquals(2, (unrowed.document.blocks[1] as Table).rows.size)
        assertEquals(Caret(1, 0, Cell(1, 1, 0)), unrowed.selection.anchor)
        val gone = unrowed.deleteColumn().deleteColumn().deleteColumn()
        assertEquals(listOf("before"), texts(gone), "the last column taken out took the table")
        assertEquals(listOf("before", "<Table>"), texts(unrowed.deleteRow()), "one row left, still a table")
        assertEquals(listOf("before"), texts(unrowed.deleteRow().deleteRow()), "the last row taken out took the table")
        assertEquals(Selection.at(0, 6), unrowed.deleteRow().deleteRow().selection, "and the caret stands in the paragraph before it")
    }

    @Test
    fun `a table with a merged cell keeps its shape`() {
        val state = EditorState.open(doc(grid(listOf("a", "b"), listOf("c", "d"), spans = true)))
        val inCell = state.inCell(0, 1, 0, 0, 1)
        assertSame(inCell, inCell.insertRow(true))
        assertSame(inCell, inCell.deleteColumn())
        // Typing in it is still typing.
        assertEquals(listOf(listOf("c!"), listOf("d")), cells(inCell.type("!"), 0)[1])
    }

    @Test
    fun `a cell with nothing in it is given a paragraph to stand in`() {
        val bare = Table(listOf(TableRow(listOf(TableCell(emptyList()), TableCell(listOf(picture())))), TableRow(emptyList())))
        val state = EditorState.open(doc(bare))
        assertEquals(listOf(listOf(listOf(""), listOf(null, "")), listOf(listOf(""))), cells(state, 0))
        assertEquals(emptySet<Int>(), state.modified)
        val typed = state.inCell(0, 1, 0, 0, 0).type("x")
        assertEquals(listOf(listOf("x")), cells(typed, 0)[1])
    }

    @Test
    fun `whatever a reader does in a cell, the cell stands and every step comes back`() {
        for (seed in 1..800) {
            val random = Random(seed)
            val table = Table(
                (1..random.nextInt(1, 4)).map {
                    TableRow((1..random.nextInt(1, 4)).map { TableCell((0 until random.nextInt(0, 3)).map { p(words[random.nextInt(words.size)]) }) })
                },
            )
            val opened = EditorState.open(DocumentModel(listOf(p("before"), table, p("after"))))
            var state = opened
            for (step in 1..random.nextInt(1, 20)) {
                val where = "seed $seed step $step"
                val before = state
                when (random.nextInt(11)) {
                    0, 1 -> state = state.select(Selection(Caret(1, random.nextInt(-1, 6), Cell(random.nextInt(-1, 4), random.nextInt(-1, 4), random.nextInt(-1, 3)))))
                    2, 3 -> {
                        val at = state.selection.start
                        val word = words[random.nextInt(words.size)]
                        state = state.type(word)
                        if (at.cell != null && before.selection.collapsed) {
                            val text = state.paragraphAt(state.selection.anchor).text
                            assertEquals(word, text.substring(at.offset, at.offset + word.length), where)
                            assertEquals(before.paragraphAt(at).text.length + word.length, text.length, where)
                        }
                    }
                    4 -> state = state.erase()
                    5 -> state = state.eraseForward()
                    6 -> state = state.splitParagraph()
                    7 -> state = state.insertRow(random.nextBoolean())
                    8 -> state = state.insertColumn(random.nextBoolean())
                    9 -> state = if (random.nextBoolean()) state.deleteRow() else state.deleteColumn()
                    else -> state = if (random.nextBoolean()) state.undo() else state.redo()
                }
                assertSound(state, where)
                for (block in state.document.blocks) {
                    if (block !is Table) continue
                    for (row in block.rows) {
                        assertTrue(row.cells.isNotEmpty(), "$where: a row with no cells")
                        for (cell in row.cells) assertTrue(cell.blocks.any { it is Paragraph }, "$where: a cell with nothing to stand in")
                    }
                }
                val caret = state.selection.anchor
                if (caret.cell != null) {
                    val t = state.document.blocks[caret.block] as Table
                    val held = t.rows[caret.cell!!.row].cells[caret.cell!!.column].blocks
                    assertTrue(held[caret.cell!!.paragraph] is Paragraph, "$where: the caret is not in a paragraph of its cell")
                }
            }
            var back = state
            while (back.canUndo) back = back.undo()
            assertEquals(opened.document, back.document, "seed $seed: undoing everything did not give back the document opened")
        }
    }

    // ---- the fuzz ----

    private val words = listOf("form", "بحث", "the", "استمارة", "2022", "x", "لا")

    /** A document to edit, made of everything the model holds that an edit could disturb. */
    private inner class Documents(private val random: Random) {
        private fun word() = words[random.nextInt(words.size)]

        private fun run(): TextRun = when (random.nextInt(8)) {
            0 -> TextRun("", image = picture())
            1 -> TextRun("1", superscript = true, note = listOf(p("a note")))
            else -> TextRun(
                text = (1..random.nextInt(1, 3)).joinToString(" ") { word() },
                bold = random.nextBoolean(),
                italic = random.nextBoolean(),
                link = if (random.nextInt(4) == 0) "https://x/" + random.nextInt(3) else null,
                colorRgb = if (random.nextInt(4) == 0) 0xC00000 else null,
            )
        }

        private fun paragraph() = Paragraph(
            runs = if (random.nextInt(6) == 0) emptyList() else (1..random.nextInt(1, 4)).map { run() },
            style = ParagraphStyle(
                kind = ParagraphKind.entries[random.nextInt(ParagraphKind.entries.size)],
                listMarker = if (random.nextInt(3) == 0) ListMarker.entries[random.nextInt(2)] else null,
                pageBreakBefore = random.nextInt(5) == 0,
            ),
            confidence = random.nextFloat(),
            bookmarks = if (random.nextInt(5) == 0) listOf("bm" + random.nextInt(9)) else emptyList(),
        )

        fun document() = DocumentModel(
            blocks = (1..random.nextInt(0, 6)).map {
                when (random.nextInt(8)) {
                    0 -> table()
                    1 -> picture()
                    else -> paragraph()
                }
            },
        )
    }

    /**
     * The document as plain strings, edited by rules written out again
     * from scratch: one string per paragraph and a null for anything
     * else. It knows nothing of runs, so it checks what the words do and
     * not how they are set — and it is a second, independent statement
     * of what each edit is supposed to do to them.
     */
    private class Shadow(val items: MutableList<String?>) {
        fun cut(start: Caret, end: Caret) {
            if (start.block == end.block) {
                val s = items[start.block]!!
                items[start.block] = s.take(start.offset) + s.drop(end.offset)
                return
            }
            val first = items[start.block]!!.take(start.offset)
            val last = items[end.block]!!.drop(end.offset)
            items[start.block] = first + last
            for (at in end.block downTo start.block + 1) items.removeAt(at)
        }

        fun join(index: Int) {
            items[index - 1] = items[index - 1]!! + items[index]!!
            items.removeAt(index)
        }

        fun remove(index: Int) {
            items.removeAt(index)
            if (items.none { it != null }) items += ""
        }
    }

    private fun shadowOf(state: EditorState) =
        Shadow(state.document.blocks.mapTo(mutableListOf()) { (it as? Paragraph)?.text })

    private fun assertSound(state: EditorState, where: String) {
        val blocks = state.document.blocks
        assertTrue(blocks.any { it is Paragraph }, "$where: no paragraph to stand in")
        for (caret in listOf(state.selection.anchor, state.selection.focus)) {
            val cell = caret.cell
            val paragraph = if (cell == null) {
                blocks.getOrNull(caret.block) as? Paragraph
            } else {
                (blocks.getOrNull(caret.block) as? Table)?.rows?.getOrNull(cell.row)?.cells?.getOrNull(cell.column)
                    ?.blocks?.getOrNull(cell.paragraph) as? Paragraph
            }
            assertTrue(paragraph != null, "$where: the caret is not in a paragraph: $caret")
            assertTrue(caret.offset in 0..paragraph!!.text.length, "$where: the caret is outside its paragraph: $caret")
            val text = paragraph.text
            assertFalse(
                caret.offset in 1 until text.length && text[caret.offset].isLowSurrogate(),
                "$where: the caret is inside a surrogate pair",
            )
        }
        for ((at, block) in blocks.withIndex()) {
            val paragraph = block as? Paragraph ?: continue
            if (paragraph.runs.size == 1 && paragraph.runs[0].text.isEmpty()) continue
            for (run in paragraph.runs) {
                assertTrue(
                    run.text.isNotEmpty() || run.image != null || run.field != null,
                    "$where: block $at holds an empty run that is neither a picture nor a field",
                )
            }
        }
    }

    @Test
    fun `whatever a reader does, the words are what the edit says and every step comes back`() {
        for (seed in 1..2000) {
            val random = Random(seed)
            val opened = EditorState.open(Documents(random).document())
            var state = opened
            var shadow = shadowOf(state)
            val past = ArrayDeque<DocumentModel>()
            val future = ArrayDeque<DocumentModel>()
            for (step in 1..random.nextInt(1, 25)) {
                val where = "seed $seed step $step"
                val before = state
                val depth = state.undoDepth
                when (random.nextInt(12)) {
                    0, 1 -> {
                        // Anywhere at all, including places that do not exist.
                        val a = Caret(random.nextInt(-1, state.document.blocks.size + 1), random.nextInt(-1, 12))
                        val b = if (random.nextBoolean()) a else Caret(random.nextInt(state.document.blocks.size), random.nextInt(12))
                        state = state.select(Selection(a, b))
                    }
                    2, 3 -> {
                        val sel = state.selection
                        val word = words[random.nextInt(words.size)]
                        state = state.type(word)
                        if (!sel.collapsed) shadow.cut(sel.start, sel.end)
                        val at = if (sel.collapsed) sel.start else Caret(sel.start.block, sel.start.offset)
                        val s = shadow.items[at.block]!!
                        shadow.items[at.block] = s.take(at.offset) + word + s.drop(at.offset)
                    }
                    4 -> {
                        val sel = state.selection
                        val blocks = before.document.blocks
                        state = state.erase()
                        if (!sel.collapsed) {
                            shadow.cut(sel.start, sel.end)
                        } else {
                            val at = sel.start
                            when {
                                at.offset > 0 -> shadow.cut(Caret(at.block, at.offset - 1), at)
                                at.block == 0 -> {}
                                blocks[at.block - 1] is Paragraph -> shadow.join(at.block)
                                else -> shadow.remove(at.block - 1)
                            }
                        }
                    }
                    5 -> {
                        val sel = state.selection
                        val blocks = before.document.blocks
                        state = state.eraseForward()
                        if (!sel.collapsed) {
                            shadow.cut(sel.start, sel.end)
                        } else {
                            val at = sel.start
                            val length = shadow.items[at.block]!!.length
                            when {
                                at.offset < length -> shadow.cut(at, Caret(at.block, at.offset + 1))
                                at.block == blocks.size - 1 -> {}
                                blocks[at.block + 1] is Paragraph -> shadow.join(at.block + 1)
                                else -> shadow.remove(at.block + 1)
                            }
                        }
                    }
                    6 -> {
                        val sel = state.selection
                        val paragraph = before.document.blocks[sel.start.block] as Paragraph
                        state = state.splitParagraph()
                        if (!sel.collapsed) shadow.cut(sel.start, sel.end)
                        val at = sel.start
                        val s = shadow.items[at.block]!!
                        if (!(s.isEmpty() && paragraph.style.listMarker != null)) {
                            shadow.items[at.block] = s.take(at.offset)
                            shadow.items.add(at.block + 1, s.drop(at.offset))
                        }
                    }
                    7 -> state = state.format(
                        RunChange(
                            bold = if (random.nextBoolean()) random.nextBoolean() else null,
                            italic = if (random.nextBoolean()) random.nextBoolean() else null,
                            link = if (random.nextInt(3) == 0) Put(if (random.nextBoolean()) "https://y" else null) else null,
                        )
                    )
                    8 -> state = state.restyle(
                        ParagraphChange(
                            kind = ParagraphKind.entries[random.nextInt(ParagraphKind.entries.size)],
                            listMarker = if (random.nextBoolean()) Put(ListMarker.BULLET) else null,
                        )
                    )
                    9 -> {
                        val sel = state.selection
                        state = state.insertBlock(if (random.nextBoolean()) table() else picture())
                        if (!sel.collapsed) shadow.cut(sel.start, sel.end)
                        val at = sel.start
                        val s = shadow.items[at.block]!!
                        when {
                            s.isEmpty() -> {
                                shadow.items[at.block] = null
                                if (shadow.items.getOrNull(at.block + 1) == null) shadow.items.add(at.block + 1, "")
                            }
                            at.offset == 0 -> shadow.items.add(at.block, null)
                            at.offset == s.length -> {
                                shadow.items.add(at.block + 1, null)
                                if (shadow.items.getOrNull(at.block + 2) == null) shadow.items.add(at.block + 2, "")
                            }
                            else -> {
                                shadow.items[at.block] = s.take(at.offset)
                                shadow.items.add(at.block + 1, null)
                                shadow.items.add(at.block + 2, s.drop(at.offset))
                            }
                        }
                    }
                    10 -> {
                        val index = random.nextInt(state.document.blocks.size)
                        state = state.removeBlock(index)
                        shadow.remove(index)
                    }
                    else -> {
                        if (random.nextBoolean()) {
                            if (state.canUndo) {
                                val expected = past.removeLast()
                                future.addLast(state.document)
                                state = state.undo()
                                assertEquals(expected, state.document, "$where: undo gave back a different document")
                            }
                        } else if (state.canRedo) {
                            val expected = future.removeLast()
                            past.addLast(state.document)
                            state = state.redo()
                            assertEquals(expected, state.document, "$where: redo gave back a different document")
                        }
                        shadow = shadowOf(state)
                        assertSound(state, where)
                        continue
                    }
                }
                assertSound(state, where)
                assertEquals(shadow.items, shadowOf(state).items, "$where: the words are not what the edit says")
                if (state.undoDepth == depth + 1) {
                    past.addLast(before.document)
                    future.clear()
                } else if (state.document != before.document) {
                    assertEquals(depth, state.undoDepth, "$where: a step was kept and a step was lost")
                    future.clear()
                }
            }
            // Every step back, then exactly as many forward: the document
            // opened at one end and the document left at the other. (A
            // session that ends on an undo has steps beyond where it was
            // left, so it is the count that is redone, not everything.)
            val last = state.document
            var back = state
            var steps = 0
            while (back.canUndo) {
                back = back.undo()
                steps++
            }
            assertEquals(opened.document, back.document, "seed $seed: undoing everything did not give back the document opened")
            assertEquals(emptySet<Int>(), back.modified, "seed $seed: undone, and still marked as changed")
            var forward = back
            repeat(steps) { forward = forward.redo() }
            assertEquals(last, forward.document, "seed $seed: redoing every step did not give back the document left")
            assertEquals(state.selection, forward.selection, "seed $seed: redoing every step put the caret somewhere else")
            while (forward.canRedo) forward = forward.redo()
            assertSound(forward, "seed $seed at the end of everything that could be redone")
        }
    }
}
