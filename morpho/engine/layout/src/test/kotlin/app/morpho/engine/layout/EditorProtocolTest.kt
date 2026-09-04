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
        )) {
            assertNull(EditorProtocol.operation(bad), "read as an operation: ${bad.take(60)}")
            val step = EditorProtocol.step(state, bad)
            assertSame(state, step.state, "changed something: ${bad.take(60)}")
            assertEquals("refused", reply(step.reply)["error"], bad.take(60))
        }
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
            20 -> """{"op":"tab","back":${random.nextBoolean()}}"""
            21 -> """{"op":"mergeCells"}"""
            22 -> """{"op":"splitCell"}"""
            14 -> """{"op":"select","anchor":[${random.nextInt(n)},${random.nextInt(4)},${random.nextInt(-1, 3)},${random.nextInt(-1, 3)},${random.nextInt(-1, 2)}],"focus":[${random.nextInt(n)},${random.nextInt(4)},${random.nextInt(3)},${random.nextInt(3)},0]}"""
            15 -> """{"op":"insertRow","below":${random.nextBoolean()}}"""
            16 -> """{"op":"insertColumn","after":${random.nextBoolean()}}"""
            17 -> if (random.nextBoolean()) """{"op":"deleteRow"}""" else """{"op":"deleteColumn"}"""
            18 -> """{"op":"find","query":${Json.write(words[random.nextInt(words.size)])},"ignoreCase":${random.nextBoolean()}}"""
            19 -> """{"op":"replaceAll","query":${Json.write(words[random.nextInt(words.size)])},"replacement":${Json.write(words[random.nextInt(words.size)])}}"""
            0 -> """{"op":"select","anchor":[${random.nextInt(-1, n + 1)},${random.nextInt(-1, 9)}],"focus":[${random.nextInt(n)},${random.nextInt(9)}]}"""
            1, 2 -> """{"op":"type","text":${Json.write(words[random.nextInt(words.size)])}}"""
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
