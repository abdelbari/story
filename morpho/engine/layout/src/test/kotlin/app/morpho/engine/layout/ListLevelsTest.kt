package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A document's lists are nested more often than not: a report's clauses
 * with their sub-clauses, a thesis's aims under its objectives. A writer
 * that keeps one level hands all of them back as one flat list, which is
 * a different document.
 */
class ListLevelsTest {

    private fun item(text: String, marker: ListMarker, level: Int) = Paragraph(
        runs = listOf(TextRun(text)),
        style = ParagraphStyle(listMarker = marker, listLevel = level),
    )

    private val outline = DocumentModel(
        blocks = listOf(
            item("Aims", ListMarker.NUMBERED, 0),
            item("To read a page", ListMarker.NUMBERED, 1),
            item("To read it in Arabic", ListMarker.NUMBERED, 1),
            item("Method", ListMarker.NUMBERED, 0),
            item("by hand", ListMarker.BULLET, 1),
        )
    )

    @Test
    fun `HTML nests a list inside the list it belongs to`() {
        val html = HtmlWriter.write(outline, "outline")
        val lists = Regex("</?[uo]l>").findAll(html).map { it.value }.toList()
        // The outer list is never closed and opened again: coming back out
        // of the nested one lands in the list that was still standing, so
        // Method is the second item of the first list, not the first of a
        // second.
        assertEquals(
            listOf("<ol>", "<ol>", "</ol>", "<ul>", "</ul>", "</ol>"),
            lists,
            "the lists opened and closed as: " + lists,
        )
    }

    @Test
    fun `HTML closes every list it opens`() {
        val html = HtmlWriter.write(outline, "outline")
        assertEquals(
            Regex("<[uo]l>").findAll(html).count(),
            Regex("</[uo]l>").findAll(html).count(),
        )
    }

    @Test
    fun `Markdown indents an item past the marker of the item above it`() {
        val markdown = MarkdownWriter.write(outline)
        val lines = markdown.lines().filter { it.isNotBlank() }
        assertEquals("1. Aims", lines[0])
        assertEquals("    1. To read a page", lines[1])
        assertEquals("    2. To read it in Arabic", lines[2])
        // Back out to the level above, where the count carries on.
        assertEquals("2. Method", lines[3])
        assertEquals("    - by hand", lines[4])
    }

    @Test
    fun `a list inside a list starts its own count each time`() {
        val document = DocumentModel(
            blocks = listOf(
                item("first", ListMarker.NUMBERED, 0),
                item("under the first", ListMarker.NUMBERED, 1),
                item("second", ListMarker.NUMBERED, 0),
                item("under the second", ListMarker.NUMBERED, 1),
            )
        )
        val lines = MarkdownWriter.write(document).lines().filter { it.isNotBlank() }
        assertEquals(listOf("1. first", "    1. under the first", "2. second", "    1. under the second"), lines)
    }

    @Test
    fun `a paragraph that is no list item ends the lists standing open`() {
        val document = DocumentModel(
            blocks = listOf(
                item("one", ListMarker.NUMBERED, 0),
                item("one and a half", ListMarker.NUMBERED, 1),
                Paragraph(runs = listOf(TextRun("Prose again."))),
            )
        )
        val html = HtmlWriter.write(document, "d")
        assertTrue(html.contains("</ol>\n</ol>"), "the nested list was left open: " + html)
    }

    @Test
    fun `Markdown read back in keeps the lists it nests`() {
        val markdown = """
            - Aims
              - to read a page
              - in Arabic
            - Method
        """.trimIndent()
        val document = PlainTextImporter.import(markdown)
        val shape = document.blocks.filterIsInstance<Paragraph>().map { it.text to it.style.listLevel }
        assertEquals(
            listOf("Aims" to 0, "to read a page" to 1, "in Arabic" to 1, "Method" to 0),
            shape,
        )
    }

    @Test
    fun `a nested list written out and read back is the same list`() {
        val markdown = MarkdownWriter.write(outline)
        val shape = PlainTextImporter.import(markdown).blocks.filterIsInstance<Paragraph>()
            .map { it.style.listMarker to it.style.listLevel }
        assertEquals(
            outline.blocks.filterIsInstance<Paragraph>().map { it.style.listMarker to it.style.listLevel },
            shape,
        )
    }
}
