package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * What crosses the bridge, both ways: an operation read as if a hostile
 * page wrote it, and a reply that paints exactly what changed.
 */
class EditorProtocolTest {

    private fun p(text: String, kind: ParagraphKind = ParagraphKind.BODY) =
        Paragraph(listOf(TextRun(text)), ParagraphStyle(kind = kind))

    private fun item(text: String) = Paragraph(listOf(TextRun(text)), ParagraphStyle(listMarker = ListMarker.BULLET))

    private fun open(vararg blocks: Block) = EditorState.open(DocumentModel(blocks.toList()))

    private fun reply(json: String): Map<*, *> = Json.parse(json) as Map<*, *>

    private fun texts(state: EditorState) = state.document.blocks.map { (it as? Paragraph)?.text ?: "<${it::class.simpleName}>" }

    private fun EditorState.at(block: Int, offset: Int) = select(Selection.at(block, offset))

    @Test
    fun `each operation over the bridge is the operation itself`() {
        val linked = Paragraph(listOf(TextRun("see "), TextRun("the site", link = "https://x")))
        val opened = open(p("one"), linked, p("three"), item("four")).at(1, 5)
        val cases: List<Pair<String, (EditorState) -> EditorState>> = listOf(
            """{"op":"select","anchor":[0,1],"focus":[2,2]}""" to { it.select(Selection(Caret(0, 1), Caret(2, 2))) },
            """{"op":"type","text":"whole "}""" to { it.type("whole ") },
            """{"op":"paste","text":"two\nlines"}""" to { it.paste("two\nlines") },
            """{"op":"paste","text":"rich","html":"<p><b>rich</b></p><h2>head</h2>"}""" to { it.pasteBlocks(HtmlReader.read("<p><b>rich</b></p><h2>head</h2>")) },
            """{"op":"paste","text":"plain","html":"<script>x</script>"}""" to { it.paste("plain") },
            """{"op":"erase"}""" to { it.erase() },
            """{"op":"eraseForward"}""" to { it.eraseForward() },
            """{"op":"split"}""" to { it.splitParagraph() },
            """{"op":"format","bold":true}""" to { it.format(RunChange(bold = true)) },
            """{"op":"restyle","kind":"HEADING_2","listMarker":"NUMBERED","listLevel":1}""" to {
                it.restyle(ParagraphChange(kind = ParagraphKind.HEADING_2, listMarker = Put(ListMarker.NUMBERED), listLevel = 1))
            },
            """{"op":"insertTable","rows":2,"columns":3}""" to {
                it.insertBlock(Table(List(2) { TableRow(List(3) { TableCell(listOf(Paragraph(listOf(TextRun(""))))) }) }))
            },
            """{"op":"removeBlock","block":3}""" to { it.removeBlock(3) },
            """{"op":"tab"}""" to { it.tab(back = false) },
            """{"op":"link","url":"https://y","text":"there"}""" to { it.link("https://y", "there") },
            """{"op":"link","url":null}""" to { it.link(null) },
            """{"op":"tab","back":true}""" to { it.tab(back = true) },
            """{"op":"undo"}""" to { it.undo() },
            """{"op":"redo"}""" to { it.redo() },
        )
        for ((json, direct) in cases) {
            val state = opened.type("x").undo() // something to undo and to redo
            val over = EditorProtocol.step(state, json).state
            val expected = direct(state)
            assertEquals(expected.document, over.document, json)
            assertEquals(expected.selection, over.selection, json)
        }
    }

    @Test
    fun `a caret in a cell crosses the bridge as five numbers, and rows and columns come and go over it`() {
        val table = Table(listOf(TableRow(listOf(TableCell(listOf(p("ab"))), TableCell(listOf(p("cd")))))))
        val state = open(p("before"), table)
        val into = EditorProtocol.step(state, """{"op":"select","anchor":[1,1,0,1,0],"focus":[1,1,0,1,0]}""")
        assertEquals(Caret(1, 1, Cell(0, 1, 0)), into.state.selection.anchor)
        assertEquals(mapOf("anchor" to listOf(1.0, 1.0, 0.0, 1.0, 0.0), "focus" to listOf(1.0, 1.0, 0.0, 1.0, 0.0)), reply(into.reply)["selection"])
        val typed = EditorProtocol.step(into.state, """{"op":"type","text":"!"}""")
        val cells = (typed.state.document.blocks[1] as Table).rows[0].cells.map { (it.blocks[0] as Paragraph).text }
        assertEquals(listOf("ab", "c!d"), cells)
        val splice = reply(typed.reply)["splice"] as Map<*, *>
        assertEquals(listOf(1.0, 2.0), listOf(splice["from"], splice["to"]), "the table is the block repainted")
        assertTrue(((splice["blocks"] as List<*>)[0] as String).startsWith("<table"))
        val rowed = EditorProtocol.step(typed.state, """{"op":"insertRow","below":true}""")
        assertEquals(2, (rowed.state.document.blocks[1] as Table).rows.size)
        assertEquals(rowed.state.document, typed.state.insertRow(true).document)
        val columned = EditorProtocol.step(rowed.state, """{"op":"insertColumn","after":false}""")
        assertEquals(3, (columned.state.document.blocks[1] as Table).rows[0].cells.size)
        assertEquals(2, (EditorProtocol.step(columned.state, """{"op":"deleteColumn"}""").state.document.blocks[1] as Table).rows[0].cells.size)
        assertEquals(1, (EditorProtocol.step(columned.state, """{"op":"deleteRow"}""").state.document.blocks[1] as Table).rows.size)
        assertNull(EditorProtocol.operation("""{"op":"select","anchor":[1,1,0],"focus":[1,1,0]}"""), "three numbers are not a caret")
    }

    @Test
    fun `cells selected together cross the bridge, and are merged and split over it`() {
        val table = Table(listOf(TableRow(listOf(TableCell(listOf(p("ab"))), TableCell(listOf(p("cd"))))), TableRow(listOf(TableCell(listOf(p("e"))), TableCell(listOf(p("f")))))))
        val state = open(p("before"), table)
        val across = EditorProtocol.step(state, """{"op":"select","anchor":[1,1,0,0,0],"focus":[1,0,1,1,0]}""")
        assertEquals(Selection(Caret(1, 1, Cell(0, 0, 0)), Caret(1, 0, Cell(1, 1, 0))), across.state.selection)
        val status = reply(across.reply)
        assertEquals(listOf(listOf(0.0, 0.0), listOf(0.0, 1.0), listOf(1.0, 0.0), listOf(1.0, 1.0)), status["cells"], "the cells selected, for the toolbar")
        assertEquals(true, status["canMerge"])
        assertEquals(false, status["canSplit"])
        val merged = EditorProtocol.step(across.state, """{"op":"mergeCells"}""")
        assertEquals(merged.state.document, across.state.mergeCells().document)
        assertEquals(listOf(1, 0), (merged.state.document.blocks[1] as Table).rows.map { it.cells.size })
        assertEquals(true, reply(merged.reply)["canSplit"])
        assertEquals(emptyList<Any>(), reply(merged.reply)["cells"])
        val html = ((reply(merged.reply)["splice"] as Map<*, *>)["blocks"] as List<*>)[0] as String
        assertTrue(html.contains("""<td colspan="2" rowspan="2">"""), html)
        assertTrue(html.contains("<tr></tr>"), "the covered row is still a row of the page: $html")
        val split = EditorProtocol.step(merged.state, """{"op":"splitCell"}""")
        assertEquals(listOf(2, 2), (split.state.document.blocks[1] as Table).rows.map { it.cells.size })
        val shaded = EditorProtocol.step(split.state, """{"op":"shadeCells","rgb":16777130}""")
        assertEquals(0xFFFFAA, (shaded.state.document.blocks[1] as Table).rows[0].cells[0].shadingRgb)
        assertEquals(mapOf("ruled" to true, "headRow" to false, "shadingRgb" to 16777130.0, "columnWidthPt" to null), reply(shaded.reply)["table"], "the table for the toolbar")
        val unruled = EditorProtocol.step(shaded.state, """{"op":"ruleTable","ruled":false}""")
        assertEquals(false, (reply(unruled.reply)["table"] as Map<*, *>)["ruled"])
        val headed = EditorProtocol.step(unruled.state, """{"op":"headRow","header":true}""")
        assertTrue((headed.state.document.blocks[1] as Table).rows[0].repeatsAsHeader)
        val widened = EditorProtocol.step(headed.state, """{"op":"setColumnWidth","widthPt":120}""")
        assertEquals(120.0, (reply(widened.reply)["table"] as Map<*, *>)["columnWidthPt"])
        assertNull(reply(EditorProtocol.reply(state, state))["table"], "nothing outside a table")
        assertNull(EditorProtocol.operation("""{"op":"headRow"}"""))
        assertNull(EditorProtocol.operation("""{"op":"setColumnWidth","widthPt":"wide"}"""))
        val tabbed = EditorProtocol.step(split.state, """{"op":"tab"}""")
        assertEquals(Selection(Caret(1, 0, Cell(0, 1, 0)), Caret(1, 0, Cell(0, 1, 0))), tabbed.state.selection, "Tab to the next cell, which is empty now")
        assertNull(EditorProtocol.operation("""{"op":"tab","back":"yes"}"""))
    }

    @Test
    fun `a search over the bridge is answered with the places, and a replacement with the document`() {
        val state = open(p("one form"), Table(listOf(TableRow(listOf(TableCell(listOf(p("Form two"))))))))
        val found = EditorProtocol.step(state, """{"op":"find","query":"form","ignoreCase":true}""")
        assertSame(state, found.state, "a search changes nothing")
        assertEquals(
            listOf(listOf(listOf(0.0, 4.0), listOf(0.0, 8.0)), listOf(listOf(1.0, 0.0, 0.0, 0.0, 0.0), listOf(1.0, 4.0, 0.0, 0.0, 0.0))),
            reply(found.reply)["matches"],
        )
        assertEquals(0, ((reply(found.reply)["splice"] as Map<*, *>)["blocks"] as List<*>).size, "nothing to paint")
        val replaced = EditorProtocol.step(state, """{"op":"replaceAll","query":"form","replacement":"x","ignoreCase":true}""")
        assertEquals(listOf("one x", "<Table>"), texts(replaced.state))
        assertEquals("x two", ((replaced.state.document.blocks[1] as Table).rows[0].cells[0].blocks[0] as Paragraph).text)
        assertNull(EditorProtocol.operation("""{"op":"find","query":7}"""))
        assertNull(EditorProtocol.operation("""{"op":"replaceAll","query":"a"}"""))
    }

    @Test
    fun `the blocks to doubt are asked for over the bridge, and the ones changed come with every reply`() {
        val state = open(p("sure"), p("read").copy(confidence = 0.6f))
        val asked = EditorProtocol.step(state, """{"op":"doubtful"}""")
        assertSame(state, asked.state)
        assertEquals(listOf(1.0), reply(asked.reply)["blocks"])
        assertEquals(emptyList<Any>(), reply(asked.reply)["changed"])
        val typed = EditorProtocol.step(state.at(1, 4), """{"op":"type","text":"!"}""")
        assertEquals(listOf(1.0), reply(typed.reply)["changed"])
        val html = ((reply(typed.reply)["splice"] as Map<*, *>)["blocks"] as List<*>)[0] as String
        assertTrue(html.startsWith("""<p data-block="1" data-band="medium">"""), "the band on the block's element: $html")
        assertTrue((reply(EditorProtocol.opening(state))["body"] as String).contains("""<p data-block="0">"""), "and none on a block read for certain")
        assertTrue(!HtmlWriter.write(state.document).contains("data-band"), "the preview says nothing of it")
    }

    @Test
    fun `a picture is put in over the bridge as the bytes the app hands over`() {
        val state = open(p("one"), p("two")).at(0, 3)
        val bytes = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val put = EditorProtocol.step(state, """{"op":"insertImage","bytes":"$bytes","mimeType":"image/JPEG","widthPx":40,"heightPx":30,"description":" a seal "}""")
        val image = put.state.document.blocks[1] as ImageBlock
        assertEquals(listOf("image/jpeg", 40, 30, "a seal"), listOf(image.mimeType, image.widthPx, image.heightPx, image.description))
        assertTrue(image.bytes.contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(listOf("one", "<ImageBlock>", "two"), texts(put.state))
        for (bad in listOf(
            """{"op":"insertImage","bytes":"!!","mimeType":"image/png","widthPx":1,"heightPx":1}""",
            """{"op":"insertImage","bytes":"$bytes","mimeType":"text/html","widthPx":1,"heightPx":1}""",
            """{"op":"insertImage","bytes":"$bytes","mimeType":"image/png","widthPx":0,"heightPx":1}""",
            """{"op":"insertImage","bytes":"","mimeType":"image/png","widthPx":1,"heightPx":1}""",
            """{"op":"insertImage","bytes":"${"A".repeat(EditorProtocol.MOST_IMAGE_BASE64 + 4)}","mimeType":"image/png","widthPx":1,"heightPx":1}""",
        )) assertNull(EditorProtocol.operation(bad), bad.take(80))
    }

    @Test
    fun `a picture is described and sized over the bridge, and the document counted`() {
        val state = open(p("one two"), ImageBlock(ByteArray(2), "image/png", 4, 2))
        val described = EditorProtocol.step(state, """{"op":"describeImage","block":1,"description":"a seal"}""")
        assertEquals("a seal", (described.state.document.blocks[1] as ImageBlock).description)
        assertTrue(((reply(described.reply)["splice"] as Map<*, *>)["blocks"] as List<*>).single().toString().contains("""alt="a seal""""))
        val sized = EditorProtocol.step(state, """{"op":"resizeImage","block":1,"widthPt":100}""")
        assertEquals(listOf(100f, 50f), (sized.state.document.blocks[1] as ImageBlock).let { listOf(it.widthPt, it.heightPt) })
        assertNull(EditorProtocol.operation("""{"op":"resizeImage","block":1,"widthPt":-1}"""))
        assertNull(EditorProtocol.operation("""{"op":"describeImage","block":"1"}"""))
        val counted = EditorProtocol.step(state, """{"op":"count"}""")
        assertSame(state, counted.state)
        assertEquals(mapOf("words" to 2.0, "characters" to 7.0, "charactersWithoutSpaces" to 6.0, "paragraphs" to 1.0), reply(counted.reply)["count"])
    }

    @Test
    fun `a note over the bridge marks the words with no text of its own, and renumbers the whole body`() {
        val state = open(p("see the site now"), p("later words")).select(Selection(Caret(1, 0), Caret(1, 5)))
        val later = EditorProtocol.step(state, """{"op":"comment","text":"second","author":"R"}""")
        assertEquals(listOf(Comment(1, "second", "R")), later.state.document.comments)
        assertEquals(true, reply(later.reply)["all"], "a note renumbers, so the whole body is painted")
        val body = reply(later.reply)["body"] as String
        assertTrue(body.contains("""<span class="comment-mark" data-comment="1" data-id="1"></span>"""), body)
        assertTrue(!body.contains("<sup"), "no text stands for the number: $body")
        assertTrue(body.contains("""<span class="commented" title="R: second">"""), body)
        val earlier = EditorProtocol.step(later.state.select(Selection(Caret(0, 4), Caret(0, 12))), """{"op":"comment","text":"first"}""")
        val renumbered = reply(earlier.reply)["body"] as String
        assertTrue(renumbered.contains("""data-comment="1" data-id="2"""") && renumbered.contains("""data-comment="2" data-id="1""""), "numbered as the text meets them: $renumbered")
        val at = reply(EditorProtocol.reply(earlier.state, earlier.state.at(0, 8)))
        assertEquals(listOf(mapOf("id" to 2.0, "text" to "first", "author" to null)), at["comments"])
        val stripped = EditorProtocol.step(earlier.state, """{"op":"uncomment","id":2}""")
        assertEquals(listOf(1), stripped.state.document.comments.map { it.id })
        assertTrue(!(reply(stripped.reply)["body"] as String).contains("""data-id="2""""))
        assertNull(EditorProtocol.operation("""{"op":"uncomment","id":0}"""))
        assertNull(EditorProtocol.operation("""{"op":"comment"}"""))
        val paged = EditorProtocol.step(state, """{"op":"setPage","widthPt":595,"heightPt":842,"marginTopPt":72,"marginBottomPt":72,"marginLeftPt":60,"marginRightPt":60}""")
        assertEquals(PageSetup(595f, 842f, 72f, 72f, 60f, 60f), paged.state.document.pageSetup)
        assertNull(EditorProtocol.operation("""{"op":"setPage","widthPt":595}"""))
        val described = EditorProtocol.step(state, """{"op":"describeDocument","title":"The paper","author":null}""")
        assertEquals("The paper", described.state.document.properties.title)
        assertNull(EditorProtocol.operation("""{"op":"describeDocument","title":7}"""))
    }

    @Test
    fun `a property set to nothing and a property left out are told apart`() {
        val state = open(Paragraph(listOf(TextRun("the site", link = "https://x", bold = false))))
            .select(Selection(Caret(0, 0), Caret(0, 8)))
        val bolded = EditorProtocol.step(state, """{"op":"format","bold":true}""").state
        assertEquals("https://x", (bolded.document.blocks[0] as Paragraph).runs.single().link, "left out means left alone")
        val unlinked = EditorProtocol.step(state, """{"op":"format","link":null}""").state
        assertNull((unlinked.document.blocks[0] as Paragraph).runs.single().link, "null means taken off")
    }

    @Test
    fun `what is not an operation is refused and changes nothing`() {
        val state = open(p("one"), p("two")).at(1, 1)
        for (bad in listOf(
            "", "not json", "[]", "{}", """{"op":"dance"}""", """{"op":"type","text":7}""", """{"op":"type"}""",
            """{"op":"insertTable","rows":1000,"columns":1000}""", """{"op":"insertTable","rows":0,"columns":1}""",
            """{"op":"format","bold":"yes"}""", """{"op":"format","fontSizePt":-4}""", """{"op":"format","colorRgb":16777216}""",
            """{"op":"restyle","kind":"HEADING_9"}""", """{"op":"restyle","listLevel":2.5}""",
            """{"op":"select","anchor":[0,1,2],"focus":[0,1]}""", """{"op":"select","anchor":[0,"a"],"focus":[0,1]}""",
            """{"op":"removeBlock","block":-1}""", """{"op":"type","text":"${"x".repeat(EditorProtocol.MOST_TYPED + 1)}"}""",
            """{"op":"paste","text":["a"]}""", """{"op":"paste","text":"${"y".repeat(EditorProtocol.MOST_TYPED + 1)}"}""",
            """{"op":"paste","text":"a","html":7}""", """{"op":"paste","text":"a","html":"${"<p>".repeat(EditorProtocol.MOST_HTML / 3 + 1)}"}""",
            """{"op":"tab","back":1}""", """{"op":"find","query":""}""".replace("\"query\":\"\"", "\"query\":null"),
            """{"op":"link","url":7}""", """{"op":"link","url":"https://x","text":false}""",
            """{"op":"describeImage","block":1.5,"description":"x"}""", """{"op":"describeImage","block":0,"description":["x"]}""",
            """{"op":"resizeImage","block":0,"widthPt":"wide"}""", """{"op":"resizeImage","block":0,"heightPt":1e9}""",
            """{"op":"shadeCells","rgb":-1}""", """{"op":"shadeCells","rgb":16777216}""", """{"op":"shadeCells","rgb":"red"}""",
            """{"op":"ruleTable"}""", """{"op":"ruleTable","ruled":"no"}""", """{"op":"headRow","header":0}""",
            """{"op":"setColumnWidth","widthPt":-5}""", """{"op":"setColumnWidth"}""",
        )) {
            assertNull(EditorProtocol.operation(bad), "read as an operation: ${bad.take(60)}")
            val step = EditorProtocol.step(state, bad)
            assertSame(state, step.state, "changed something: ${bad.take(60)}")
            assertEquals("refused", reply(step.reply)["error"], bad.take(60))
        }
    }

    @Test
    fun `an address the writers cannot vouch for is kept in the document and never written as a link`() {
        // The editor takes what it is given; the writers decide what goes out, as they do for a document read in.
        val state = open(p("see here"))
        val linked = EditorProtocol.step(state.select(Selection(Caret(0, 4), Caret(0, 8))), """{"op":"link","url":"javascript:alert(1)"}""")
        assertEquals("javascript:alert(1)", (linked.state.document.blocks[0] as Paragraph).runs.last().link)
        val html = ((reply(linked.reply)["splice"] as Map<*, *>)["blocks"] as List<*>)[0] as String
        assertTrue(!html.contains("<a "), "not a link in the page: $html")
        assertTrue(!html.contains("javascript"), html)
        val typed = EditorProtocol.step(state.at(0, 8), """{"op":"link","url":"file:///etc/passwd","text":" there"}""")
        val painted = ((reply(typed.reply)["splice"] as Map<*, *>)["blocks"] as List<*>)[0] as String
        assertTrue(painted.contains("there") && !painted.contains("href"), painted)
    }

    @Test
    fun `the reply says what to repaint and no more`() {
        val state = open(p("one"), p("two"), p("three")).at(1, 3)
        val typed = EditorProtocol.step(state, """{"op":"type","text":"!"}""")
        var splice = reply(typed.reply)["splice"] as Map<*, *>
        assertEquals(false, reply(typed.reply)["all"])
        assertEquals(1.0, splice["from"])
        assertEquals(2.0, splice["to"])
        val blocks = splice["blocks"] as List<*>
        assertEquals(1, blocks.size)
        assertTrue((blocks[0] as String).contains("data-block=\"1\"") && (blocks[0] as String).contains("two!"), blocks[0].toString())
        // A Return in the middle is two blocks in place of one; at the very
        // end the first half is as it was, so it is one block put in after.
        val split = EditorProtocol.step(typed.state.at(1, 2), """{"op":"split"}""")
        splice = reply(split.reply)["splice"] as Map<*, *>
        assertEquals(listOf(1.0, 2.0), listOf(splice["from"], splice["to"]))
        assertEquals(2, (splice["blocks"] as List<*>).size)
        val atEnd = EditorProtocol.step(typed.state, """{"op":"split"}""")
        splice = reply(atEnd.reply)["splice"] as Map<*, *>
        assertEquals(listOf(2.0, 2.0), listOf(splice["from"], splice["to"]))
        assertEquals(1, (splice["blocks"] as List<*>).size)
        // Moving the caret paints nothing.
        val moved = EditorProtocol.step(split.state, """{"op":"select","anchor":[0,0],"focus":[0,0]}""")
        splice = reply(moved.reply)["splice"] as Map<*, *>
        assertEquals(0, (splice["blocks"] as List<*>).size)
        assertEquals(splice["from"], splice["to"])
    }

    @Test
    fun `a keystroke in a document of five thousand blocks repaints one block, and the reply is small`() {
        // The cost of a keystroke must not grow with the document, or a
        // book is unusable: one block repainted, a reply of a few hundred
        // bytes, whatever the document's size.
        val big = EditorState.open(DocumentModel(List(5_000) { Paragraph(listOf(TextRun("paragraph $it of a long document"))) })).at(2_500, 5)
        var state = big
        for (letter in "typed") {
            val step = EditorProtocol.step(state, """{"op":"type","text":"$letter"}""")
            state = step.state
            val splice = reply(step.reply)["splice"] as Map<*, *>
            assertEquals(listOf(2500.0, 2501.0), listOf(splice["from"], splice["to"]), "one block repainted")
            assertTrue(step.reply.length < 1_000, "a reply of ${step.reply.length} characters for one keystroke")
        }
        assertEquals("parag" + "typed" + "raph 2500 of a long document", texts(state)[2_500])
        val split = EditorProtocol.step(state, """{"op":"split"}""")
        assertEquals(listOf(2500.0, 2501.0), (reply(split.reply)["splice"] as Map<*, *>).let { listOf(it["from"], it["to"]) }, "Return repaints the paragraph broken and puts one in")
        assertEquals(2, ((reply(split.reply)["splice"] as Map<*, *>)["blocks"] as List<*>).size)
    }

    @Test
    fun `an edit to an item of a list paints the whole body, since the list is not the item's`() {
        val state = open(p("head"), item("first"), item("second")).at(1, 5)
        val step = EditorProtocol.step(state, """{"op":"split"}""")
        val painted = reply(step.reply)
        assertEquals(true, painted["all"])
        val body = painted["body"] as String
        assertTrue(body.contains("<ul>") && body.contains("data-block=\"3\""), body)
        // And so does opening, which paints everything.
        val opening = reply(EditorProtocol.opening(state))
        assertEquals(true, opening["all"])
        assertTrue((opening["body"] as String).contains("data-block=\"2\""))
    }

    @Test
    fun `the reply carries the state at the caret for the toolbar`() {
        val state = open(p("Chapter", ParagraphKind.HEADING_1), Paragraph(listOf(TextRun("bold", bold = true), TextRun(" plain")))).at(1, 4)
        val status = reply(EditorProtocol.reply(state, state))
        assertEquals(mapOf("anchor" to listOf(1.0, 4.0), "focus" to listOf(1.0, 4.0)), status["selection"])
        assertEquals(true, (status["look"] as Map<*, *>)["bold"], "the look to the left of the caret is bold")
        assertEquals("BODY", (status["paragraph"] as Map<*, *>)["kind"])
        assertEquals(false, status["canUndo"])
        val heading = reply(EditorProtocol.reply(state, state.at(0, 2)))
        assertEquals("HEADING_1", (heading["paragraph"] as Map<*, *>)["kind"])
        val chosen = EditorProtocol.step(state, """{"op":"format","italic":true}""")
        assertEquals(true, (reply(chosen.reply)["look"] as Map<*, *>)["italic"], "a look chosen with nothing selected shows on the toolbar")
        val half = reply(EditorProtocol.reply(state, state.select(Selection(Caret(1, 2), Caret(1, 8)))))
        assertEquals(false, (half["look"] as Map<*, *>)["bold"], "a selection half bold is not bold, so the button is up and pressing it makes all of it bold")
        val whole = reply(EditorProtocol.reply(state, state.select(Selection(Caret(1, 0), Caret(1, 4)))))
        assertEquals(true, (whole["look"] as Map<*, *>)["bold"])
    }

    // ---- the fuzz ----

    private val words = listOf("form", "بحث", "the", "x", "\"q\"", "<b>")

    private fun document(random: Random): DocumentModel = DocumentModel(
        blocks = (1..random.nextInt(0, 6)).map {
            when (random.nextInt(6)) {
                0 -> Table((1..random.nextInt(1, 3)).map { TableRow((1..random.nextInt(1, 3)).map { TableCell(listOf(p("cell"))) }) })
                1 -> ImageBlock(ByteArray(2), "image/png", 1, 1)
                2 -> item(words[random.nextInt(words.size)])
                else -> Paragraph(
                    (1..random.nextInt(0, 3)).map {
                        TextRun(words[random.nextInt(words.size)], bold = random.nextBoolean(), link = if (random.nextInt(4) == 0) "https://x" else null)
                    },
                    ParagraphStyle(kind = ParagraphKind.entries[random.nextInt(ParagraphKind.entries.size)]),
                )
            }
        },
    )

    private fun operation(random: Random, state: EditorState): String {
        val n = state.document.blocks.size
        return when (random.nextInt(23)) {
            20 -> listOf(
                """{"op":"tab","back":${random.nextBoolean()}}""",
                """{"op":"link","url":"https://z","text":"here"}""",
                """{"op":"describeImage","block":${random.nextInt(n)},"description":"seen"}""",
                """{"op":"resizeImage","block":${random.nextInt(n)},"widthPt":50}""",
                """{"op":"count"}""",
                """{"op":"shadeCells","rgb":${if (random.nextBoolean()) "null" else "255"}}""",
                """{"op":"ruleTable","ruled":${random.nextBoolean()}}""",
                """{"op":"headRow","header":${random.nextBoolean()}}""",
                """{"op":"setColumnWidth","widthPt":${random.nextInt(1, 300)}}""",
                """{"op":"comment","text":"note ${random.nextInt(9)}"}""",
                """{"op":"uncomment","id":${random.nextInt(1, 4)}}""",
                """{"op":"setPage","widthPt":612,"heightPt":792,"marginTopPt":72,"marginBottomPt":72,"marginLeftPt":72,"marginRightPt":72}""",
                """{"op":"describeDocument","title":${if (random.nextBoolean()) "null" else "\"t\""}}""",
            )[random.nextInt(13)]
            21 -> """{"op":"mergeCells"}"""
            22 -> """{"op":"splitCell"}"""
            14 -> """{"op":"select","anchor":[${random.nextInt(n)},${random.nextInt(4)},${random.nextInt(-1, 3)},${random.nextInt(-1, 3)},${random.nextInt(-1, 2)}],"focus":[${random.nextInt(n)},${random.nextInt(4)},${random.nextInt(3)},${random.nextInt(3)},0]}"""
            15 -> """{"op":"insertRow","below":${random.nextBoolean()}}"""
            16 -> """{"op":"insertColumn","after":${random.nextBoolean()}}"""
            17 -> if (random.nextBoolean()) """{"op":"deleteRow"}""" else """{"op":"deleteColumn"}"""
            18 -> """{"op":"find","query":${Json.write(words[random.nextInt(words.size)])},"ignoreCase":${random.nextBoolean()}}"""
            19 -> """{"op":"replaceAll","query":${Json.write(words[random.nextInt(words.size)])},"replacement":${Json.write(words[random.nextInt(words.size)])}}"""
            0 -> """{"op":"select","anchor":[${random.nextInt(-1, n + 1)},${random.nextInt(-1, 9)}],"focus":[${random.nextInt(n)},${random.nextInt(9)}]}"""
            1 -> """{"op":"type","text":${Json.write(words[random.nextInt(words.size)])}}"""
            2 -> if (random.nextBoolean()) """{"op":"paste","text":${Json.write(words[random.nextInt(words.size)] + "\n" + words[random.nextInt(words.size)])}}"""
            else """{"op":"paste","text":"t","html":${Json.write(listOf("<p><b>x</b></p><p>y</p>", "<table><tr><td>c</td></tr></table>", "<ul><li>i</li></ul>", "<img src=\"data:image/png;base64,AQID\">", "<p>")[random.nextInt(5)])}}"""
            3 -> """{"op":"erase"}"""
            4 -> """{"op":"eraseForward"}"""
            5 -> """{"op":"split"}"""
            6 -> """{"op":"format","bold":${random.nextBoolean()}${if (random.nextBoolean()) ",\"link\":null" else ""}}"""
            7 -> """{"op":"restyle","kind":"${ParagraphKind.entries[random.nextInt(5)]}"${if (random.nextBoolean()) ",\"listMarker\":\"BULLET\"" else ",\"listMarker\":null"}}"""
            8 -> """{"op":"insertTable","rows":${random.nextInt(1, 3)},"columns":${random.nextInt(1, 3)}}"""
            9 -> """{"op":"removeBlock","block":${random.nextInt(n)}}"""
            10 -> """{"op":"undo"}"""
            11 -> """{"op":"redo"}"""
            12 -> listOf("garbage", "{\"op\":7}", "[", "{\"op\":\"type\",\"text\":null}")[random.nextInt(4)]
            else -> """{"op":"select","anchor":[0,0],"focus":[${n - 1},99]}"""
        }
    }

    private fun stripped(html: String) = html.replace(Regex(""" data-block="\d+""""), "")

    private fun rendered(state: EditorState) = state.document.blocks.indices.map { stripped(HtmlWriter.writeBlock(state.document, it)) }

    @Test
    fun `whatever comes over the bridge, the reply paints the document as it now is`() {
        for (seed in 1..1500) {
            val random = Random(seed)
            var state = EditorState.open(document(random))
            for (step in 1..random.nextInt(1, 15)) {
                val where = "seed $seed step $step"
                val json = operation(random, state)
                val before = rendered(state)
                val result = EditorProtocol.step(state, json)
                val operation = EditorProtocol.operation(json)
                if (operation == null) {
                    assertSame(state, result.state, "$where: refused and yet changed: $json")
                    continue
                }
                assertEquals(EditorProtocol.apply(state, operation).document, result.state.document, "$where: $json")
                val painted = reply(result.reply)
                val after = rendered(result.state)
                if (painted["all"] == true) {
                    val body = painted["body"] as String
                    for (at in result.state.document.blocks.indices) {
                        assertTrue(body.contains("data-block=\"$at\""), "$where: block $at missing from the body")
                    }
                } else {
                    val splice = painted["splice"] as Map<*, *>
                    val from = (splice["from"] as Double).toInt()
                    val to = (splice["to"] as Double).toInt()
                    val blocks = (splice["blocks"] as List<*>).map { stripped(it as String) }
                    assertEquals(after, before.take(from) + blocks + before.drop(to), "$where: the splice does not paint the document: $json")
                }
                state = result.state
            }
        }
    }
}
