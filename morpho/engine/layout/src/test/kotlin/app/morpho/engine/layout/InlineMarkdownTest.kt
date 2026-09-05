package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The other half of what [MarkdownWriter] writes: a link, and the mark
 * that refers to a note. Until these were read the app could not read its
 * own output — a document converted to Markdown and then to Word arrived
 * with the syntax of its links showing in its sentences and the words of
 * its notes as stray lines after the last paragraph.
 */
class InlineMarkdownTest {

    private fun runs(md: String): List<TextRun> =
        (PlainTextImporter.import(md).blocks.first() as Paragraph).runs

    private fun paragraphs(md: String): List<Paragraph> =
        PlainTextImporter.import(md).blocks.filterIsInstance<Paragraph>()

    @Test
    fun `a link is read as a link, not as the characters that spell one`() {
        val runs = runs("Read [the page](https://example.org/a) now.")
        assertEquals(listOf("Read ", "the page", " now."), runs.map { it.text })
        assertNull(runs[0].link)
        assertEquals("https://example.org/a", runs[1].link)
        assertNull(runs[2].link)
    }

    @Test
    fun `emphasis inside a link is emphasis, and all of it is the link`() {
        val runs = runs("Read [the **big** page](https://example.org).")
        assertEquals("the big page", runs.filter { it.link != null }.joinToString("") { it.text })
        assertTrue(runs.single { it.bold }.let { it.text == "big" && it.link == "https://example.org" })
    }

    @Test
    fun `a link may carry a title, and parentheses inside its target`() {
        // A title is Markdown's tooltip and the model has nowhere to put
        // it; the target is what the link is.
        assertEquals(
            "https://example.org",
            runs("""Read [the page](https://example.org "Title") now.""")[1].link,
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Q_(disambiguation)",
            runs("See [it](https://en.wikipedia.org/wiki/Q_(disambiguation)).")[1].link,
        )
    }

    @Test
    fun `a bracket that opens nothing stays the text it is`() {
        // A citation, a cross-reference, an aside: brackets are ordinary
        // punctuation and a reader that took every one of them for a link
        // would eat a document's own words.
        for (line in listOf(
            "See [note 3] below and [Ibn Khaldun 1377] too.",
            "Nothing []() here.",
            "An unclosed [bracket and a (paren).",
        )) {
            assertEquals(line, runs(line).joinToString("") { it.text }, line)
            assertTrue(runs(line).all { it.link == null }, line)
        }
    }

    @Test
    fun `an escaped bracket is a bracket`() {
        val runs = runs("""See \[note 3\] below.""")
        assertEquals("See [note 3] below.", runs.joinToString("") { it.text })
        assertTrue(runs.all { it.link == null })
    }

    @Test
    fun `a picture is left as the words it is written with`() {
        // Nothing here can put the bytes back, and reading it as a link
        // would hand the document a data URI to click on.
        val line = "Before ![image](data:image/png;base64,AAAA) after."
        assertEquals(line, runs(line).joinToString("") { it.text })
        assertTrue(runs(line).all { it.link == null })
    }

    @Test
    fun `a mark is read as a note, with the words defined at the end`() {
        val runs = runs("A claim.[^1]\n\n[^1]: Board minutes, March 1999.")
        assertEquals(listOf("A claim.", "1"), runs.map { it.text })
        assertTrue(runs[1].superscript, "a note's mark is raised")
        assertEquals(
            "Board minutes, March 1999.",
            (runs[1].note!!.single() as Paragraph).text,
        )
        assertEquals(1, paragraphs("A claim.[^1]\n\n[^1]: Board minutes, March 1999.").size,
            "the definition is the note, not a paragraph of the document")
    }

    @Test
    fun `a note's words may wrap under its definition`() {
        val runs = runs("A claim.[^1]\n\n[^1]: First half\n    and the rest of it.")
        assertEquals("First half and the rest of it.", (runs[1].note!!.single() as Paragraph).text)
    }

    @Test
    fun `a note may itself hold a link`() {
        val runs = runs("A claim.[^1]\n\n[^1]: See [here](https://example.org).")
        val note = runs[1].note!!.single() as Paragraph
        assertEquals("https://example.org", note.runs.single { it.text == "here" }.link)
    }

    @Test
    fun `one note may be referred to twice`() {
        val runs = runs("One[^1] and two[^1].\n\n[^1]: Shared.")
        assertEquals(2, runs.count { it.note != null })
        assertTrue(runs.filter { it.note != null }.all { (it.note!!.single() as Paragraph).text == "Shared." })
    }

    @Test
    fun `a mark whose note nobody defined refers to nothing`() {
        val runs = runs("A claim.[^9]\n\nNext paragraph.")
        assertEquals("A claim.[^9]", runs.joinToString("") { it.text })
        assertTrue(runs.all { it.note == null })
    }

    @Test
    fun `a definition nobody refers to stays a line of the document`() {
        // It only looks like a note. Taking it out would lose the words
        // outright, and no mark anywhere would show them again.
        val paragraphs = paragraphs("A claim.\n\n[^9]: Loose words.")
        assertEquals(listOf("A claim.", "[^9]: Loose words."), paragraphs.map { it.text })
    }

    @Test
    fun `a mark stands in a table cell as it stands in a sentence`() {
        val table = PlainTextImporter.import(
            "| A | B |\n| --- | --- |\n| x[^1] | y |\n\n[^1]: The note."
        ).blocks.filterIsInstance<Table>().single()
        val cell = table.rows[1].cells.first().blocks.single() as Paragraph
        assertEquals("The note.", (cell.runs.last().note!!.single() as Paragraph).text)
    }

    @Test
    fun `a document that writes a link and a note reads back as it was`() {
        // The round trip the app itself makes when a PDF is converted to
        // Markdown and that Markdown is then converted to Word.
        val before = DocumentModel(
            listOf(
                Paragraph(
                    listOf(
                        TextRun("The rate fell, see "),
                        TextRun("the table", link = "https://example.org/t"),
                        TextRun(" and the note in [brackets]."),
                        TextRun("2", superscript = true,
                            note = listOf(Paragraph(listOf(TextRun("Bank of Algeria, 2019."))))),
                    )
                )
            )
        )
        val after = PlainTextImporter.import(MarkdownWriter.write(before))
        val runs = (after.blocks.single() as Paragraph).runs
        assertEquals(before.blocks.filterIsInstance<Paragraph>().single().text, runs.joinToString("") { it.text })
        assertEquals("https://example.org/t", runs.single { it.link != null }.link)
        assertEquals(
            "Bank of Algeria, 2019.",
            (runs.single { it.note != null }.note!!.single() as Paragraph).text,
        )
    }
}
