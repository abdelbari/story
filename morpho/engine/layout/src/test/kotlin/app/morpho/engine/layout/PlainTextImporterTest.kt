package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlainTextImporterTest {

    private fun paragraphs(model: DocumentModel): List<Paragraph> =
        model.blocks.filterIsInstance<Paragraph>()

    @Test
    fun `blank lines split paragraphs and soft wraps are unwrapped`() {
        val model = PlainTextImporter.import("line one\nline two\n\nsecond paragraph\n")
        val paras = paragraphs(model)
        assertEquals(2, paras.size)
        assertEquals("line one line two", paras[0].text)
        assertEquals("second paragraph", paras[1].text)
    }

    @Test
    fun `windows and old mac line endings are normalized`() {
        val model = PlainTextImporter.import("a\r\nb\r\rc")
        val paras = paragraphs(model)
        assertEquals(listOf("a b", "c"), paras.map { it.text })
    }

    @Test
    fun `markdown headings map to heading kinds`() {
        val model = PlainTextImporter.import("# One\n## Two\n### Three\nbody\n")
        val paras = paragraphs(model)
        assertEquals(ParagraphKind.HEADING_1, paras[0].style.kind)
        assertEquals(ParagraphKind.HEADING_2, paras[1].style.kind)
        assertEquals(ParagraphKind.HEADING_3, paras[2].style.kind)
        assertEquals(ParagraphKind.BODY, paras[3].style.kind)
        assertEquals("One", paras[0].text)
    }

    @Test
    fun `bullet and numbered items become list paragraphs`() {
        val model = PlainTextImporter.import("- first\n* second\n1. third\n2) fourth\n")
        val paras = paragraphs(model)
        assertEquals(ListMarker.BULLET, paras[0].style.listMarker)
        assertEquals(ListMarker.BULLET, paras[1].style.listMarker)
        assertEquals(ListMarker.NUMBERED, paras[2].style.listMarker)
        assertEquals(ListMarker.NUMBERED, paras[3].style.listMarker)
        assertEquals("third", paras[2].text)
    }

    @Test
    fun `a year at the start of a sentence is not a numbered item`() {
        val model = PlainTextImporter.import("2024. That was the year it began.")
        val paras = paragraphs(model)
        assertNull(paras[0].style.listMarker)
    }

    @Test
    fun `arabic paragraphs are tagged RTL and latin ones LTR`() {
        val model = PlainTextImporter.import("Hello world\n\nمرحبا بالعالم\n")
        val paras = paragraphs(model)
        assertEquals(TextDirection.LTR, paras[0].style.direction)
        assertEquals(TextDirection.RTL, paras[1].style.direction)
    }

    @Test
    fun `mostly arabic document gets an RTL default direction`() {
        val model = PlainTextImporter.import("مرحبا\n\nالعالم\n\nHello\n")
        assertEquals(TextDirection.RTL, model.defaultDirection)
    }

    @Test
    fun `mostly latin document keeps an LTR default direction`() {
        val model = PlainTextImporter.import("Hello\n\nWorld\n\nمرحبا\n")
        assertEquals(TextDirection.LTR, model.defaultDirection)
    }

    @Test
    fun `bold span splits a paragraph into styled runs`() {
        val runs = paragraphs(PlainTextImporter.import("before **bold** after"))[0].runs
        assertEquals(listOf("before ", "bold", " after"), runs.map { it.text })
        assertFalse(runs[0].bold)
        assertTrue(runs[1].bold)
        assertFalse(runs[1].italic)
        assertFalse(runs[2].bold)
        // Same direction as the paragraph: inherited, not marked explicitly.
        assertNull(runs[1].direction)
    }

    @Test
    fun `italic span sets only the italic flag`() {
        val runs = paragraphs(PlainTextImporter.import("an *italic* word"))[0].runs
        assertEquals(listOf("an ", "italic", " word"), runs.map { it.text })
        assertTrue(runs[1].italic)
        assertFalse(runs[1].bold)
    }

    @Test
    fun `triple asterisks produce a bold italic run`() {
        val runs = paragraphs(PlainTextImporter.import("go ***fast*** now"))[0].runs
        assertEquals(listOf("go ", "fast", " now"), runs.map { it.text })
        assertTrue(runs[1].bold)
        assertTrue(runs[1].italic)
    }

    @Test
    fun `unmatched and empty markers stay literal`() {
        val paras = paragraphs(PlainTextImporter.import("a * b\n\nx ** y\n\n**oops"))
        assertEquals(listOf("a * b", "x ** y", "**oops"), paras.map { it.text })
        paras.forEach { para ->
            assertEquals(1, para.runs.size)
            assertFalse(para.runs[0].bold)
            assertFalse(para.runs[0].italic)
        }
    }

    @Test
    fun `escaped asterisks are literal and never open emphasis`() {
        val para = paragraphs(PlainTextImporter.import("""\*not italic\*"""))[0]
        assertEquals(1, para.runs.size)
        assertEquals("*not italic*", para.text)
        assertFalse(para.runs[0].italic)
    }

    @Test
    fun `underscore emphasis is left verbatim`() {
        val para = paragraphs(PlainTextImporter.import("keep _this_ verbatim"))[0]
        assertEquals(listOf("keep _this_ verbatim"), para.runs.map { it.text })
        assertFalse(para.runs[0].italic)
    }

    @Test
    fun `emphasis never spans a paragraph break`() {
        val paras = paragraphs(PlainTextImporter.import("**start\n\nend**"))
        assertEquals(listOf("**start", "end**"), paras.map { it.text })
        assertTrue(paras.flatMap { it.runs }.none { it.bold })
    }

    @Test
    fun `bold arabic word keeps the paragraph RTL`() {
        val para = paragraphs(PlainTextImporter.import("قبل **عربي** بعد"))[0]
        assertEquals(TextDirection.RTL, para.style.direction)
        assertEquals(listOf("قبل ", "عربي", " بعد"), para.runs.map { it.text })
        assertTrue(para.runs[1].bold)
        // Same direction as the paragraph: inherited, not marked explicitly.
        assertNull(para.runs[1].direction)
    }

    @Test
    fun `bold latin word in an arabic paragraph gets an LTR run direction`() {
        val para = paragraphs(PlainTextImporter.import("النص **bold** عربي"))[0]
        assertEquals(TextDirection.RTL, para.style.direction)
        assertTrue(para.runs[1].bold)
        assertEquals(TextDirection.LTR, para.runs[1].direction)
        // The Arabic runs match the paragraph direction, so they inherit it.
        assertNull(para.runs[0].direction)
        assertNull(para.runs[2].direction)
    }

    @Test
    fun `a run with no strong character has null direction`() {
        val runs = paragraphs(PlainTextImporter.import("**bold** 123"))[0].runs
        assertTrue(runs[0].bold)
        assertNull(runs[1].direction)
        assertEquals(" 123", runs[1].text)
    }

    @Test
    fun `emphasis works inside headings and bullet items`() {
        val paras = paragraphs(PlainTextImporter.import("# A **big** title\n- item with *flair*\n"))
        assertEquals(ParagraphKind.HEADING_1, paras[0].style.kind)
        assertEquals("A big title", paras[0].text)
        assertTrue(paras[0].runs[1].bold)
        assertEquals(ListMarker.BULLET, paras[1].style.listMarker)
        assertEquals("item with flair", paras[1].text)
        assertTrue(paras[1].runs[1].italic)
    }

    @Test
    fun `emphasis spans soft-wrapped lines within one paragraph`() {
        val para = paragraphs(PlainTextImporter.import("**two\nwords** here"))[0]
        assertEquals(listOf("two words", " here"), para.runs.map { it.text })
        assertTrue(para.runs[0].bold)
    }

    private fun table(model: DocumentModel): Table = model.blocks.filterIsInstance<Table>().single()

    private fun cells(row: TableRow): List<String> =
        row.cells.map { (it.blocks.single() as Paragraph).text }

    @Test
    fun `a pipe table is read as a table`() {
        // What MarkdownWriter writes, read back: without this the app
        // cannot read its own output, and a document's tables come back
        // from Markdown as paragraphs full of pipe characters.
        val model = PlainTextImporter.import(
            """
            |Before the table.
            |
            || Year | Entries |
            || --- | --- |
            || 2019 | 412 |
            || 2020 | 503 |
            |
            |After the table.
            """.trimMargin()
        )
        val table = table(model)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("Year", "Entries"), cells(table.rows[0]))
        assertEquals(listOf("2019", "412"), cells(table.rows[1]))
        assertEquals(listOf("2020", "503"), cells(table.rows[2]))
        assertEquals(
            listOf("Before the table.", "After the table."),
            paragraphs(model).map { it.text },
        )
    }

    @Test
    fun `the first row of a Markdown table is its head`() {
        val model = PlainTextImporter.import("| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(listOf(true, false), table(model).rows.map { it.repeatsAsHeader })
    }

    @Test
    fun `the row of dashes says how each column is set`() {
        val model = PlainTextImporter.import("| L | R | C |\n| :--- | ---: | :---: |\n| 1 | 2 | 3 |")
        assertEquals(
            listOf(Alignment.START, Alignment.END, Alignment.CENTER),
            table(model).rows[0].cells.map { (it.blocks.single() as Paragraph).style.alignment },
        )
    }

    @Test
    fun `lines of pipes with no row of dashes are the text they are`() {
        val model = PlainTextImporter.import("| not | a table |\n| still | not one |")
        assertTrue(model.blocks.filterIsInstance<Table>().isEmpty())
        val text = paragraphs(model).single().text
        assertTrue(text.contains("not | a table"), text)
    }

    @Test
    fun `a pipe written into a cell stays a character of it`() {
        val model = PlainTextImporter.import("| Sign | Meaning |\n| --- | --- |\n| \\| | a pipe |")
        assertEquals(listOf("|", "a pipe"), cells(table(model).rows[1]))
    }

    @Test
    fun `a table is styled inside its cells like anything else`() {
        val model = PlainTextImporter.import("| Word | Note |\n| --- | --- |\n| **bold** | plain |")
        val cell = table(model).rows[1].cells.first().blocks.single() as Paragraph
        assertEquals("bold", cell.runs.single().text)
        assertTrue(cell.runs.single().bold)
    }
}
