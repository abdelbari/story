package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
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

    // ---- finding and replacing ----

    @Test
    fun `the blocks to doubt are the report's, as the document now stands, and an edit does not settle one`() {
        val state = EditorState.open(doc(p("sure"), p("read").copy(confidence = 0.6f), p("guessed").copy(confidence = 0.5f), p("also sure")))
        assertEquals(listOf(1, 2), state.doubtful())
        val split = state.at(0, 2).splitParagraph()
        assertEquals(listOf(2, 3), split.doubtful(), "by index as the document now stands")
        val fixed = state.at(1, 4).type("!")
        assertEquals(listOf(1, 2), fixed.doubtful(), "still to doubt, the reading's doubt being the reading's")
        assertEquals(setOf(1), fixed.modified, "but known to have been touched")
    }

    @Test
    fun `a picture is described and sized, and a block that is not one is left alone`() {
        val wide = ImageBlock(ByteArray(4), "image/png", 200, 100)
        val state = EditorState.open(doc(p("text"), wide))
        val described = state.describeImage(1, "  the seal of the faculty ")
        assertEquals("the seal of the faculty", (described.document.blocks[1] as ImageBlock).description, "trimmed")
        assertNull((described.describeImage(1, "   ").document.blocks[1] as ImageBlock).description, "blank is none")
        assertSame(state, state.describeImage(0, "x"), "a paragraph is not described")
        assertSame(state, state.describeImage(1, null))
        val sized = state.resizeImage(1, 100f, null)
        assertEquals(listOf(100f, 50f), (sized.document.blocks[1] as ImageBlock).let { listOf(it.widthPt, it.heightPt) }, "one side given keeps the shape")
        assertEquals(listOf(300f, 150f), (state.resizeImage(1, null, 150f).document.blocks[1] as ImageBlock).let { listOf(it.widthPt, it.heightPt) })
        assertEquals(listOf(30f, 40f), (state.resizeImage(1, 30f, 40f).document.blocks[1] as ImageBlock).let { listOf(it.widthPt, it.heightPt) }, "both given are both taken")
        assertEquals(listOf(null, null), (sized.resizeImage(1, null, null).document.blocks[1] as ImageBlock).let { listOf(it.widthPt, it.heightPt) }, "neither is the writer's choice again")
        assertEquals(state.document, sized.undo().document)
        assertEquals(setOf(1), sized.modified)
    }

    @Test
    fun `a link is put on the selection, or typed with its words, as one step`() {
        val state = EditorState.open(doc(p("see the site now"))).over(0, 4, 0, 12)
        val linked = state.link("https://x")
        assertEquals(listOf("see " to null, "the site" to "https://x", " now" to null), (linked.document.blocks[0] as Paragraph).runs.map { it.text to it.link })
        assertEquals(listOf("see the site now" to null), (linked.link(null).document.blocks[0] as Paragraph).runs.map { it.text to it.link }, "and taken off")
        val typed = EditorState.open(doc(p("see  now"))).at(0, 4).link("https://x", "the site")
        assertEquals(listOf("see " to null, "the site" to "https://x", " now" to null), (typed.document.blocks[0] as Paragraph).runs.map { it.text to it.link })
        assertEquals(Selection.at(0, 12), typed.selection, "the caret after the words typed")
        assertEquals(1, typed.undoDepth)
        val bare = EditorState.open(doc(p(""))).link("https://x")
        assertEquals(listOf("https://x" to "https://x"), (bare.document.blocks[0] as Paragraph).runs.map { it.text to it.link }, "no words: the address is the words")
        val nothing = EditorState.open(doc(p("a"))).at(0, 1)
        assertSame(nothing, nothing.link(null), "nothing selected, nothing to link, nothing to take off")
    }

    @Test
    fun `how much a document says is counted the way Word counts`() {
        val state = EditorState.open(doc(p("The form  arrives"), grid(listOf("a b", "")), p(r("x"), TextRun("1", superscript = true, note = listOf(p("a note of five words"))))))
        val count = state.count()
        assertEquals(6, count.words, "three, two in the cells, one — the note not counted")
        assertEquals(17 + 3 + 0 + 2, count.characters)
        assertEquals(14 + 2 + 0 + 2, count.charactersWithoutSpaces)
        assertEquals(4, count.paragraphs, "the cells' too")
        assertEquals(1, EditorState.open(doc(p("عَرَبِيّ"))).count().words, "an Arabic word with its vowels is one word")
    }

    @Test
    fun `how a selection is set is what every run of it shares`() {
        val state = EditorState.open(doc(p(r("plain "), r("bold", bold = true), r("", link = "https://x").copy(image = picture())), p(r("also bold", bold = true, link = "https://x"))))
        assertTrue(state.lookOf(Selection(Caret(0, 6), Caret(0, 10))).bold, "bold alone")
        assertFalse(state.lookOf(Selection(Caret(0, 2), Caret(0, 10))).bold, "half bold is not bold, so Bold makes all of it bold")
        val across = state.lookOf(Selection(Caret(0, 6), Caret(1, 4)))
        assertTrue(across.bold, "bold across two paragraphs, the picture between saying nothing")
        assertNull(across.link, "one of them linked is not a link")
        assertEquals("https://x", state.lookOf(Selection(Caret(1, 0), Caret(1, 9))).link)
        assertEquals(state.lookAt(Caret(0, 3)), state.lookOf(Selection(Caret(0, 3))), "nothing selected is the look at the caret")
        val table = EditorState.open(doc(grid(listOf("a", "b"))))
        val bolded = table.select(Selection(Caret(0, 0, Cell(0, 0, 0)), Caret(0, 0, Cell(0, 1, 0)))).format(RunChange(bold = true))
        assertTrue(bolded.lookOf(bolded.selection).bold, "cells selected together, every run of them")
    }

    @Test
    fun `a paste is a paragraph for each line, as one step`() {
        val state = EditorState.open(doc(p("one two"), p("after"))).at(0, 4)
        val pasted = state.paste("A\nB\r\n\nC")
        assertEquals(listOf("one A", "B", "", "Ctwo", "after"), texts(pasted))
        assertEquals(Selection.at(3, 1), pasted.selection)
        assertEquals(1, pasted.undoDepth, "however many lines, one step")
        assertEquals(state.document, pasted.undo().document)
        assertEquals(listOf("one Xtwo", "after"), texts(state.paste("X")), "one line is typing")
        val over = state.over(0, 0, 1, 2).paste("p\nq")
        assertEquals(listOf("p", "qter"), texts(over), "in place of what was selected")
        val styled = EditorState.open(doc(p(r("ab", bold = true)))).at(0, 1).paste("x\ny")
        assertTrue(runs(styled, 1).all { it.second }, "each line set as the character it was typed after")
        val heading = EditorState.open(doc(p("Title", ParagraphKind.HEADING_1))).at(0, 5).paste("\nsecond\nthird")
        assertTrue((heading.document.blocks[2] as Paragraph).style.kind == ParagraphKind.HEADING_1, "each line is a paragraph like the one pasted into")
        val inCell = EditorState.open(doc(grid(listOf("ab")))).inCell(0, 0, 0, 0, 1).paste("1\n2")
        assertEquals(listOf(listOf(listOf("a1", "2b"))), cells(inCell, 0))
        assertEquals(Caret(0, 1, Cell(0, 0, 1)), inCell.selection.anchor)
        val book = EditorState.open(doc(p(""))).paste((1..20_000).joinToString("\n") { "line $it" })
        assertEquals(20_000, book.document.blocks.size, "a book pasted in, in a pass")
    }

    @Test
    fun `blocks pasted join the paragraph at both ends and stand as they came between`() {
        val state = EditorState.open(doc(p("one two"), p("after"))).at(0, 4)
        val pasted = state.pasteBlocks(listOf(p(r("A", bold = true)), p("Head", ParagraphKind.HEADING_2), table(), p("Z")))
        assertEquals(listOf("one A", "Head", "<Table>", "Ztwo", "after"), texts(pasted))
        assertEquals(ParagraphKind.HEADING_2, (pasted.document.blocks[1] as Paragraph).style.kind, "a heading between stands as it came")
        assertEquals(ParagraphKind.BODY, (pasted.document.blocks[3] as Paragraph).style.kind, "the last takes the paragraph pasted into")
        assertTrue(runs(pasted, 0)[1].second, "the first's words keep their own look")
        assertEquals(Selection.at(3, 1), pasted.selection)
        assertEquals(1, pasted.undoDepth)
        assertEquals(state.document, pasted.undo().document)
        assertEquals(setOf(0, 1, 2, 3), pasted.modified)
        val one = state.pasteBlocks(listOf(p(r("X", italic = true))))
        assertEquals(listOf("one Xtwo", "after"), texts(one))
        assertEquals(Selection.at(0, 5), one.selection)
        val ending = state.pasteBlocks(listOf(p("A"), picture()))
        assertEquals(listOf("one A", "<ImageBlock>", "two", "after"), texts(ending), "a paste ending in a picture has the rest of the paragraph after it")
        assertEquals(Selection.at(2, 0), ending.selection)
        val starting = EditorState.open(doc(p("one two"))).at(0, 0).pasteBlocks(listOf(table(), p("B")))
        assertEquals(listOf("<Table>", "Bone two"), texts(starting), "at the head of a paragraph a table goes before it, and no empty paragraph is left")
        val atEnd = EditorState.open(doc(p("one"))).at(0, 3).pasteBlocks(listOf(table()))
        assertEquals(listOf("one", "<Table>", ""), texts(atEnd), "a table at the end has a paragraph to stand in after it")
        assertEquals(Selection.at(2, 0), atEnd.selection)
        val inCell = EditorState.open(doc(grid(listOf("ab")))).inCell(0, 0, 0, 0, 1).pasteBlocks(listOf(p("1"), p("2")))
        assertEquals(listOf(listOf(listOf("a1", "2b"))), cells(inCell, 0))
        assertEquals(Caret(0, 1, Cell(0, 0, 1)), inCell.selection.anchor)
        assertSame(state, state.pasteBlocks(emptyList()))
    }

    @Test
    fun `a note is left about the words selected, shown at the caret, and taken off again`() {
        val state = EditorState.open(doc(p("see the site now"), grid(listOf("a", "b")))).over(0, 4, 0, 12)
        val noted = state.comment("  check this  ", "R")
        val runs = (noted.document.blocks[0] as Paragraph).runs
        assertEquals(listOf("see " to emptyList(), "the site" to listOf(1), " now" to emptyList<Int>()), runs.map { it.text to it.commentIds })
        assertEquals(listOf(Comment(1, "check this", "R")), noted.document.comments)
        assertEquals(listOf(Comment(1, "check this", "R")), noted.commentsAt(Caret(0, 6)), "at a character the note is about")
        assertEquals(emptyList<Comment>(), noted.commentsAt(Caret(0, 2)))
        assertEquals(noted.selection, state.selection, "the words stay selected")
        val second = noted.over(0, 0, 0, 6).comment("again")
        assertEquals(2, second.document.comments.size)
        assertEquals(listOf(1, 2), second.commentsAt(Caret(0, 6)).map { it.id }, "two notes about one place")
        val stripped = second.uncomment(1)
        assertEquals(listOf(Comment(2, "again", null)), stripped.document.comments)
        assertTrue((stripped.document.blocks[0] as Paragraph).runs.none { 1 in it.commentIds })
        assertEquals(listOf("see th" to listOf(2), "e site now" to emptyList<Int>()), (stripped.document.blocks[0] as Paragraph).runs.map { it.text to it.commentIds }, "and the runs set alike again are one")
        assertSame(stripped, stripped.uncomment(1), "gone is gone")
        val collapsed = state.at(0, 1)
        assertSame(collapsed, collapsed.comment("nothing selected"))
        assertSame(state, state.comment("   "))
        val cells = state.select(Selection(Caret(1, 0, Cell(0, 0, 0)), Caret(1, 0, Cell(0, 1, 0)))).comment("both")
        assertTrue((cells.document.blocks[1] as Table).rows[0].cells.all { c -> (c.blocks[0] as Paragraph).runs.all { 1 in it.commentIds } })
        assertEquals(state.document, noted.undo().document)
    }

    @Test
    fun `the page is set and the document described, within bounds`() {
        val state = EditorState.open(doc(p("x")))
        val set = state.setPage(595f, 842f, 72f, 72f, 60f, 60f)
        assertEquals(PageSetup(595f, 842f, 72f, 72f, 60f, 60f), set.document.pageSetup)
        assertSame(set, set.setPage(595f, 842f, 72f, 72f, 60f, 60f))
        val wild = set.setPage(1f, 1_000_000f, -5f, 9_000f, 10f, 10f).document.pageSetup!!
        assertEquals(listOf(EditorState.LEAST_PAGE_PT, EditorState.MOST_PAGE_PT, 0f, EditorState.MOST_PAGE_PT / 2), listOf(wild.widthPt, wild.heightPt, wild.marginTopPt, wild.marginBottomPt))
        val kept = EditorState.open(doc(p("x")).copy(pageSetup = PageSetup(612f, 792f, 72f, 72f, 72f, 72f, headerDistancePt = 36f, firstPageNumber = 3))).setPage(612f, 792f, 50f, 72f, 72f, 72f).document.pageSetup!!
        assertEquals(36f to 3, kept.headerDistancePt to kept.firstPageNumber, "what the page had of a head's distance and a first number is kept")
        val described = state.describeDocument(title = Put(" The paper "), author = Put("A. Writer"))
        assertEquals("The paper" to "A. Writer", described.document.properties.title to described.document.properties.author)
        val cleared = described.describeDocument(title = Put(null), subject = Put("  "))
        assertEquals(null to "A. Writer", cleared.document.properties.title to cleared.document.properties.author, "cleared, and left alone")
        assertSame(described, described.describeDocument(), "nothing said changes nothing")
        assertEquals(state.document, described.undo().document)
    }

    @Test
    fun `a table's cells are filled, its rules drawn or not, its head set, and a column made a width`() {
        val state = EditorState.open(doc(grid(listOf("a", "b"), listOf("c", "d")), p("after")))
        val shaded = state.select(Selection(Caret(0, 0, Cell(0, 0, 0)), Caret(0, 0, Cell(0, 1, 0)))).shadeCells(0xFFEE88)
        assertEquals(listOf(listOf(0xFFEE88, 0xFFEE88), listOf(null, null)), (shaded.document.blocks[0] as Table).rows.map { r -> r.cells.map { it.shadingRgb } })
        assertEquals(listOf(listOf(0xFFEE88, null), listOf(null, null)), (shaded.inCell(0, 0, 1, 0, 0).shadeCells(null).document.blocks[0] as Table).rows.map { r -> r.cells.map { it.shadingRgb } }, "the caret's alone, emptied")
        assertSame(state, state.at(1, 0).shadeCells(1), "not in a table")
        val unruled = state.inCell(0, 0, 0, 0, 0).ruleTable(false)
        assertFalse((unruled.document.blocks[0] as Table).ruled)
        assertSame(unruled, unruled.ruleTable(false))
        val headed = state.inCell(0, 1, 0, 0, 0).headRow(true)
        assertEquals(listOf(true, true), (headed.document.blocks[0] as Table).rows.map { it.repeatsAsHeader }, "the row and every row above it")
        assertEquals(listOf(true, false), (headed.inCell(0, 1, 0, 0, 0).headRow(false).document.blocks[0] as Table).rows.map { it.repeatsAsHeader })
        assertEquals(listOf(false, false), (headed.inCell(0, 0, 0, 0, 0).headRow(false).document.blocks[0] as Table).rows.map { it.repeatsAsHeader }, "and every row below")
        val widened = state.inCell(0, 0, 1, 0, 0).setColumnWidth(300f)
        assertEquals(listOf(234f, 300f), (widened.document.blocks[0] as Table).columnWidthsPt, "the rest shared out first")
        assertEquals(listOf(50f, 300f), (widened.inCell(0, 0, 0, 0, 0).setColumnWidth(50f).document.blocks[0] as Table).columnWidthsPt)
        val look = widened.inCell(0, 1, 1, 0, 0).tableAt(widened.selection.start)
        assertEquals(EditorState.TableLook(ruled = true, headRow = false, shadingRgb = null, columnWidthPt = 300f), widened.tableAt(Caret(0, 0, Cell(1, 1, 0))))
        assertNull(widened.tableAt(Caret(1, 0)))
        assertTrue(look != null)
    }

    @Test
    fun `every place a word is written is found, in the order a reader meets them`() {
        val state = EditorState.open(doc(
            p("the form and the Form"),
            grid(listOf("form", "no"), listOf("x", "FORM form")),
            p("aaa"),
        ))
        assertEquals(
            listOf(Selection(Caret(0, 4), Caret(0, 8)), Selection(Caret(1, 0, Cell(0, 0, 0)), Caret(1, 4, Cell(0, 0, 0))), Selection(Caret(1, 5, Cell(1, 1, 0)), Caret(1, 9, Cell(1, 1, 0)))),
            state.find("form"),
        )
        assertEquals(5, state.find("form", ignoreCase = true).size)
        assertEquals(listOf(Selection(Caret(2, 0), Caret(2, 2))), state.find("aa"), "matches do not overlap")
        assertEquals(emptyList<Selection>(), state.find(""))
    }

    @Test
    fun `replacing everywhere is one step, and each replacement is set as what it replaced was`() {
        val state = EditorState.open(doc(
            p(r("see "), r("the form", link = "https://x"), r(" and the form", bold = true)),
            grid(listOf("form here")),
        ))
        val replaced = state.replaceAll("form", "questionnaire")
        val runs = (replaced.document.blocks[0] as Paragraph).runs
        assertEquals(listOf("see ", "the questionnaire", " and the questionnaire"), runs.map { it.text })
        assertEquals(listOf(null, "https://x", null), runs.map { it.link }, "a word replaced inside a link is inside the link")
        assertEquals(listOf(false, false, true), runs.map { it.bold })
        assertEquals(listOf(listOf(listOf("questionnaire here"))), cells(replaced, 1))
        assertEquals(1, replaced.undoDepth)
        assertEquals(state.document, replaced.undo().document)
        // Replacing with nothing takes the words out; a paragraph of nothing else stands.
        val emptied = EditorState.open(doc(p("form"))).replaceAll("form", "")
        assertEquals(listOf(""), texts(emptied))
        assertSame(state, state.replaceAll("absent", "x"))
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
        val outward = state.select(Selection(Caret(1, 2), Caret(0, 1, Cell(0, 0, 0))))
        assertEquals(Selection.at(1, 2), outward.selection, "nor into one")
    }

    @Test
    fun `a selection from one cell to another is of whole cells, and what is done to it is done to every one`() {
        val state = EditorState.open(doc(grid(listOf("ab", "cd", "ef"), listOf("gh", "ij", "kl"), listOf("mn", "op", "qr")), p("after")))
        val across = state.select(Selection(Caret(0, 1, Cell(0, 1, 0)), Caret(0, 2, Cell(1, 0, 0))))
        assertEquals(Selection(Caret(0, 1, Cell(0, 1, 0)), Caret(0, 2, Cell(1, 0, 0))), across.selection, "the selection stands as it was made")
        assertEquals(listOf(Cell(0, 0), Cell(0, 1), Cell(1, 0), Cell(1, 1)), across.selectedCells(), "the rectangle between the two, whichever way it was dragged")
        assertTrue(across.canMergeCells)
        assertFalse(across.canSplitCell)
        val emptied = across.erase()
        assertEquals(listOf(listOf(""), listOf(""), listOf("ef")), cells(emptied, 0)[0])
        assertEquals(listOf(listOf(""), listOf(""), listOf("kl")), cells(emptied, 0)[1])
        assertEquals(listOf(listOf("mn"), listOf("op"), listOf("qr")), cells(emptied, 0)[2], "a cell outside the rectangle is as it was")
        assertEquals(Selection(Caret(0, 0, Cell(0, 0, 0))), emptied.selection, "the caret lands in the first of them")
        assertEquals(state.document, emptied.undo().document)
        val typed = across.type("X")
        assertEquals(listOf(listOf("X"), listOf(""), listOf("ef")), cells(typed, 0)[0], "typing empties them and writes in the first")
        assertEquals(listOf(listOf(""), listOf(""), listOf("kl")), cells(typed, 0)[1])
        val bold = across.format(RunChange(bold = true))
        val table = bold.document.blocks[0] as Table
        for (row in 0..1) for (column in 0..1) assertTrue((table.rows[row].cells[column].blocks[0] as Paragraph).runs.all { it.bold }, "$row,$column is bold")
        assertFalse((table.rows[0].cells[2].blocks[0] as Paragraph).runs.any { it.bold })
        assertEquals(across.selection, bold.selection, "and the cells stay selected")
        val headed = across.restyle(ParagraphChange(kind = ParagraphKind.HEADING_2))
        assertEquals(ParagraphKind.HEADING_2, ((headed.document.blocks[0] as Table).rows[1].cells[1].blocks[0] as Paragraph).style.kind)
        assertEquals(ParagraphKind.BODY, ((headed.document.blocks[0] as Table).rows[2].cells[1].blocks[0] as Paragraph).style.kind)
        assertEquals(listOf(listOf(listOf("mn"), listOf("op"), listOf("qr"))), cells(across.deleteRow(), 0), "the rows selected go together")
        assertEquals(Caret(0, 0, Cell(0, 0, 0)), across.deleteRow().selection.anchor)
        assertEquals(listOf(listOf("ef")), cells(across.deleteColumn(), 0)[0], "and so do the columns")
        val whole = state.select(Selection(Caret(0, 0, Cell(0, 0, 0)), Caret(0, 0, Cell(2, 2, 0))))
        assertEquals(listOf("after"), texts(whole.deleteRow()), "every row selected takes the table")
        assertEquals(listOf("after"), texts(whole.deleteColumn()))
        assertSame(state, state.mergeCells(), "one cell is not a selection of cells")
        assertSame(state, state.splitCell(), "and a cell that covers one place is not split")
    }

    @Test
    fun `a selection across cells grows to hold a merged cell whole`() {
        // a covers two columns over c and d, so a selection that takes half of it takes all of it.
        val table = Table(
            listOf(
                TableRow(listOf(TableCell(listOf(p("a")), columnSpan = 2), TableCell(listOf(p("b"))))),
                TableRow(listOf(TableCell(listOf(p("c"))), TableCell(listOf(p("d"))), TableCell(listOf(p("e"))))),
            ),
        )
        val state = EditorState.open(doc(table))
        fun selected(from: Cell, to: Cell) = state.select(Selection(Caret(0, 0, from), Caret(0, 0, to))).selectedCells()
        assertEquals(listOf(Cell(1, 0), Cell(1, 1)), selected(Cell(1, 0), Cell(1, 1)), "c to d reaches nothing above")
        assertEquals(listOf(Cell(0, 0), Cell(1, 0), Cell(1, 1)), selected(Cell(1, 1), Cell(0, 0)), "d to a reaches c, under a, and not b or e")
        assertEquals(listOf(Cell(0, 0), Cell(0, 1), Cell(1, 0), Cell(1, 1), Cell(1, 2)), selected(Cell(0, 1), Cell(1, 0)), "b to c reaches everything")
        assertEquals(listOf(Cell(0, 1), Cell(1, 2)), selected(Cell(1, 2), Cell(0, 1)), "e to b is the last column")
        assertTrue(state.inCell(0, 0, 0, 0, 0).canSplitCell)
        assertFalse(state.inCell(0, 0, 1, 0, 0).canSplitCell)
    }

    @Test
    fun `Tab moves from cell to cell with the whole cell selected, and grows the table from the last one`() {
        val two = TableCell(listOf(p("one"), p("two")))
        val table = Table(listOf(TableRow(listOf(TableCell(listOf(p("a"))), two)), TableRow(listOf(TableCell(listOf(p("c"))), TableCell(listOf(p("d")))))))
        val state = EditorState.open(doc(table, p("after"))).inCell(0, 0, 0, 0, 1)
        val second = state.tab(back = false)
        assertEquals(Selection(Caret(0, 0, Cell(0, 1, 0)), Caret(0, 3, Cell(0, 1, 1))), second.selection, "the whole of the next cell, both its paragraphs")
        assertEquals(listOf(listOf("a"), listOf("X")), cells(second.type("X"), 0)[0], "and typing writes over it")
        val third = second.tab(back = false)
        assertEquals(Selection(Caret(0, 0, Cell(1, 0, 0)), Caret(0, 1, Cell(1, 0, 0))), third.selection, "on to the next row")
        val last = third.tab(back = false)
        val grown = last.tab(back = false)
        assertEquals(3, (grown.document.blocks[0] as Table).rows.size, "Tab from the last cell adds a row")
        assertEquals(Selection(Caret(0, 0, Cell(2, 0, 0))), grown.selection)
        assertEquals(Selection(Caret(0, 0, Cell(1, 1, 0)), Caret(0, 1, Cell(1, 1, 0))), grown.tab(back = true).selection, "and Shift+Tab goes back")
        assertEquals(2, (grown.undo().document.blocks[0] as Table).rows.size)
        val first = state.inCell(0, 0, 0, 0, 0)
        assertSame(first, first.tab(back = true), "there is nothing before the first cell")
        assertEquals(state.document, last.document, "moving changes nothing")
    }

    @Test
    fun `Tab outside a table types a tab, and at the head of an item of a list moves it a level`() {
        val state = EditorState.open(doc(p("ab"), p(r("item"), style = ParagraphStyle(listMarker = ListMarker.BULLET)))).at(0, 1)
        assertEquals("a\tb", texts(state.tab(back = false))[0])
        assertSame(state, state.tab(back = true))
        val item = state.at(1, 0)
        val deeper = item.tab(back = false)
        assertEquals(1, (deeper.document.blocks[1] as Paragraph).style.listLevel)
        assertEquals("item", texts(deeper)[1])
        assertEquals(0, (deeper.tab(back = true).document.blocks[1] as Paragraph).style.listLevel)
        assertSame(item, item.tab(back = true), "an item at the first level stays there")
        assertEquals("i\ttem", texts(item.at(1, 1).tab(back = false))[1], "inside the item's words a tab is a tab")
        var deepest = item
        repeat(12) { deepest = deepest.tab(back = false) }
        assertEquals(EditorState.MOST_LIST_LEVEL, (deepest.document.blocks[1] as Paragraph).style.listLevel)
    }

    @Test
    fun `cells merged are one cell holding their paragraphs, and split are cells again`() {
        val state = EditorState.open(doc(grid(listOf("a", "b"), listOf("c", ""))))
        val row = state.select(Selection(Caret(0, 0, Cell(0, 0, 0)), Caret(0, 0, Cell(0, 1, 0)))).mergeCells()
        val merged = (row.document.blocks[0] as Table).rows[0]
        assertEquals(1, merged.cells.size)
        assertEquals(2, merged.cells[0].columnSpan)
        assertEquals(1, merged.cells[0].rowSpan)
        assertEquals(listOf(listOf("a", "b")), cells(row, 0)[0], "their paragraphs one after another")
        assertEquals(listOf(listOf("c"), listOf("")), cells(row, 0)[1])
        assertEquals(Selection(Caret(0, 0, Cell(0, 0, 0))), row.selection)
        assertTrue(row.canSplitCell)
        assertFalse(row.canMergeCells)
        assertEquals(listOf(2, 1, 2), (row.insertRow(true).document.blocks[0] as Table).let { t -> listOf(t.rows[0].cells[0].columnSpan, t.rows[1].cells.size, t.rows[2].cells.size) }, "a row under the merged one is one cell as wide")
        val split = row.splitCell()
        assertEquals(listOf(listOf("a", "b"), listOf("")), cells(split, 0)[0], "split, the first keeps what the cell held")
        assertEquals(listOf(1, 1), (split.document.blocks[0] as Table).rows[0].cells.map { it.columnSpan })
        assertEquals(Selection(Caret(0, 0, Cell(0, 0, 0))), split.selection)
        assertEquals(state.document, row.undo().document)
        // All four: the second row is left with no cell of its own, covered whole.
        val all = state.select(Selection(Caret(0, 0, Cell(1, 1, 0)), Caret(0, 0, Cell(0, 0, 0)))).mergeCells()
        val table = all.document.blocks[0] as Table
        assertEquals(listOf(1, 0), table.rows.map { it.cells.size })
        assertEquals(2, table.rows[0].cells[0].rowSpan)
        assertEquals(2, table.rows[0].cells[0].columnSpan)
        assertEquals(listOf(listOf("a", "b", "c")), cells(all, 0)[0], "and the empty cell's paragraph is left out")
        assertEquals(Caret(0, 0, Cell(0, 0, 0)), all.inCell(0, 1, 1, 0, 0).selection.anchor, "a caret sent to the covered row stands in the cell that covers it")
        val back = all.splitCell()
        assertEquals(listOf(listOf(listOf("a", "b", "c"), listOf("")), listOf(listOf(""), listOf(""))), cells(back, 0))
        assertEquals(listOf(2, 2), (back.document.blocks[0] as Table).rows.map { it.cells.size })
        val blank = EditorState.open(doc(grid(listOf("", "")))).select(Selection(Caret(0, 0, Cell(0, 0, 0)), Caret(0, 0, Cell(0, 1, 0)))).mergeCells()
        assertEquals(listOf(listOf(listOf(""))), cells(blank, 0), "two empty cells merged hold one empty paragraph")
        // Merged, typed into, merged again with a neighbour: the spans add up.
        val wide = EditorState.open(doc(grid(listOf("a", "b", "c"), listOf("d", "e", "f"))))
            .select(Selection(Caret(0, 0, Cell(0, 0, 0)), Caret(0, 0, Cell(0, 1, 0)))).mergeCells()
            .select(Selection(Caret(0, 0, Cell(0, 0, 0)), Caret(0, 0, Cell(1, 0, 0)))).mergeCells()
        val corner = (wide.document.blocks[0] as Table).rows[0].cells[0]
        assertEquals(listOf(2, 2), listOf(corner.columnSpan, corner.rowSpan))
        assertEquals(listOf(listOf(listOf("a", "b", "d", "e"), listOf("c")), listOf(listOf("f"))), cells(wide, 0))
    }

    @Test
    fun `a row a merged cell reaches is left without a cell, and one nobody filled is given one`() {
        val covered = Table(listOf(TableRow(listOf(TableCell(listOf(p("tall")), rowSpan = 2))), TableRow(emptyList())))
        val state = EditorState.open(doc(covered))
        assertEquals(listOf(1, 0), (state.document.blocks[0] as Table).rows.map { it.cells.size })
        assertEquals(emptySet<Int>(), state.modified)
        // Reached in part: the row is the merged cell's, ragged or not, as
        // a merge in a ragged table leaves it.
        val partly = Table(listOf(TableRow(listOf(TableCell(listOf(p("tall")), rowSpan = 2), TableCell(listOf(p("short"))))), TableRow(emptyList())))
        assertEquals(listOf(2, 0), (EditorState.open(doc(partly)).document.blocks[0] as Table).rows.map { it.cells.size })
        val unfilled = Table(listOf(TableRow(listOf(TableCell(listOf(p("a"))))), TableRow(emptyList())))
        assertEquals(listOf(1, 1), (EditorState.open(doc(unfilled)).document.blocks[0] as Table).rows.map { it.cells.size })
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
    fun `rows and columns go into a table with merged cells and come out of it, the cells growing and shrinking`() {
        // a covers two columns, over c and d; e is beside it, over f.
        val table = Table(
            listOf(
                TableRow(listOf(TableCell(listOf(p("a")), columnSpan = 2), TableCell(listOf(p("e")), rowSpan = 2))),
                TableRow(listOf(TableCell(listOf(p("c"))), TableCell(listOf(p("d"))))),
            ),
        )
        fun spans(state: EditorState) = (state.document.blocks[0] as Table).rows.map { r -> r.cells.map { c -> "${(c.blocks[0] as Paragraph).text}${c.columnSpan}x${c.rowSpan}" } }
        val state = EditorState.open(doc(table))
        val inC = state.inCell(0, 1, 0, 0, 1)
        val below = inC.insertRow(below = true)
        assertEquals(listOf(listOf("a2x1", "e1x2"), listOf("c1x1", "d1x1"), listOf("1x1", "1x1", "1x1")), spans(below), "a row below the last row, shaped like it, e ending above")
        assertEquals(Caret(0, 0, Cell(2, 0, 0)), below.selection.anchor)
        val above = inC.insertRow(below = false)
        assertEquals(listOf(listOf("a2x1", "e1x3"), listOf("1x1", "1x1"), listOf("c1x1", "d1x1")), spans(above), "a row between: e crosses it and grows, a ends above it")
        assertEquals(Caret(0, 0, Cell(1, 0, 0)), above.selection.anchor)
        val inA = state.inCell(0, 0, 0, 0, 0)
        assertEquals(listOf(listOf("2x1", "1x1"), listOf("a2x1", "e1x2"), listOf("c1x1", "d1x1")), spans(inA.insertRow(below = false)), "above a: as wide as a, and one for e")
        assertEquals(listOf(listOf("a2x1", "e1x3"), listOf("2x1"), listOf("c1x1", "d1x1")), spans(inA.insertRow(below = true)), "below a: as wide as a, and e crosses the new row and grows")
        val inE = state.inCell(0, 0, 1, 0, 0)
        assertEquals(3, (inE.insertRow(below = true).document.blocks[0] as Table).rows.size)
        assertEquals(listOf(listOf("a2x1", "e1x2"), listOf("c1x1", "d1x1"), listOf("1x1", "1x1", "1x1")), spans(inE.insertRow(below = true)), "below the whole of e")
        assertEquals(Caret(0, 0, Cell(2, 2, 0)), inE.insertRow(below = true).selection.anchor, "under e")
        val narrowed = inC.deleteColumn()
        assertEquals(listOf(listOf("a1x1", "e1x2"), listOf("d1x1")), spans(narrowed), "c's column out: a narrows, c goes")
        assertEquals(Caret(0, 0, Cell(1, 0, 0)), narrowed.selection.anchor)
        assertEquals(listOf(listOf("e1x2"), listOf()), spans(inA.deleteColumn()), "a's columns out: c and d go with them; their row stays, e covering it")
        val shortened = inC.deleteRow()
        assertEquals(listOf(listOf("a2x1", "e1x1")), spans(shortened), "c's row out: e shortens")
        assertEquals(listOf(""), texts(inE.deleteRow()), "e's rows are all the rows: the table goes")
        val widened = inC.insertColumn(after = true)
        assertEquals(listOf(listOf("a3x1", "e1x2"), listOf("c1x1", "1x1", "d1x1")), spans(widened), "a column after c: a grows across it")
        assertEquals(Caret(0, 0, Cell(1, 1, 0)), widened.selection.anchor)
        val before = inC.insertColumn(after = false)
        assertEquals(listOf(listOf("1x1", "a2x1", "e1x2"), listOf("1x1", "c1x1", "d1x1")), spans(before), "a column before c, in every row")
        assertEquals(Caret(0, 0, Cell(1, 0, 0)), before.selection.anchor)
        assertEquals(listOf(listOf("a2x1", "1x1", "e1x2"), listOf("c1x1", "d1x1", "1x1")), spans(inA.insertColumn(after = true)), "a column after the whole of a")
        assertEquals(state.document, below.undo().document)
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
                val selected = state.selectedCells()
                val was = state.document.blocks.getOrNull(1) as? Table
                val op = random.nextInt(16)
                when (op) {
                    0, 1 -> state = state.select(Selection(Caret(1, random.nextInt(-1, 6), Cell(random.nextInt(-1, 4), random.nextInt(-1, 4), random.nextInt(-1, 3)))))
                    11 -> state = state.select(
                        Selection(
                            Caret(1, random.nextInt(-1, 6), Cell(random.nextInt(-1, 4), random.nextInt(-1, 4), random.nextInt(-1, 3))),
                            Caret(1, random.nextInt(-1, 6), Cell(random.nextInt(-1, 4), random.nextInt(-1, 4), random.nextInt(-1, 3))),
                        ),
                    )
                    12 -> state = state.tab(random.nextBoolean())
                    13 -> {
                        state = state.mergeCells()
                        if (selected.isNotEmpty() && was != null) {
                            assertNotSame(before, state, "$where: cells selected and not merged")
                            val now = state.document.blocks[1] as Table
                            assertEquals(was.rows.sumOf { it.cells.size } - selected.size + 1, now.rows.sumOf { it.cells.size }, "$where: merged into one")
                        }
                    }
                    14 -> state = state.splitCell()
                    15 -> state = state.format(RunChange(bold = random.nextBoolean(), italic = if (random.nextBoolean()) true else null))
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
                    4, 5 -> {
                        state = if (op == 4) state.erase() else state.eraseForward()
                        if (selected.isNotEmpty() && was != null) {
                            // Every cell selected is one empty paragraph; every other is as it was.
                            val now = state.document.blocks[1] as Table
                            for ((row, held) in was.rows.withIndex()) for ((column, cell) in held.cells.withIndex()) {
                                val after = now.rows[row].cells[column]
                                if (Cell(row, column) in selected) {
                                    assertEquals(1, after.blocks.size, "$where: a cell emptied holds more than one paragraph")
                                    assertEquals("", (after.blocks[0] as Paragraph).text, "$where: a cell selected was not emptied")
                                } else {
                                    assertEquals(cell, after, "$where: a cell not selected changed")
                                }
                            }
                            assertEquals(Selection(Caret(1, 0, selected.first().copy(paragraph = 0))), state.selection, "$where: the caret is not in the first cell emptied")
                        }
                    }
                    6 -> state = state.splitParagraph()
                    7 -> state = state.insertRow(random.nextBoolean())
                    8 -> state = state.insertColumn(random.nextBoolean())
                    9 -> state = if (random.nextBoolean()) state.deleteRow() else state.deleteColumn()
                    else -> state = if (random.nextBoolean()) state.undo() else state.redo()
                }
                assertSound(state, where)
                for (block in state.document.blocks) {
                    if (block !is Table) continue
                    val layout = TableGrid.of(block)
                    for ((index, row) in block.rows.withIndex()) {
                        assertEquals(row.cells.size, layout.rows[index].count { it is TableGrid.Filled }, "$where: a cell without a place on the grid")
                        assertTrue(row.cells.isNotEmpty() || layout.rows[index].any { it is TableGrid.Covered }, "$where: a row with no cells that nothing covers")
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
