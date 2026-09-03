package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a document says about itself, across the Markdown route and back.
 *
 * Both readers pick up a document's title, author, subject and keywords —
 * a PDF's from its information dictionary, a Word file's from
 * `docProps/core.xml` — and the Word writer and the preview both put them
 * back. The Markdown writer dropped all four without saying so, so a
 * paper converted for a notebook or a static site arrived with no title
 * and no author. This holds the two halves of the Markdown route to
 * carrying them.
 */
class FrontMatterTest {

    private val body = listOf(Paragraph(listOf(TextRun("The body."))))

    /** [properties] out through Markdown and back. */
    private fun tripped(properties: DocumentProperties): DocumentProperties =
        PlainTextImporter.import(
            MarkdownWriter.write(DocumentModel(blocks = body, properties = properties))
        ).properties

    @Test
    fun `a document's four fields survive the trip out and back`() {
        val said = DocumentProperties.of(
            "A Study of Something", "Abdelbari", "What it is about", "one; two",
        )
        assertEquals(said, tripped(said))
    }

    @Test
    fun `a document that named only itself keeps the one field it had`() {
        val said = DocumentProperties.of("Just a title", null, null, null)
        assertEquals(said, tripped(said))
    }

    @Test
    fun `an awkward value comes back the value it went out as`() {
        // Every one of these breaks a writer that puts the value in bare:
        // a colon starts another field, a quote ends the scalar, a
        // backslash escapes what follows it, a newline or a tab is not
        // valid inside a quoted YAML scalar at all, and a value that is
        // itself the fence would close the block early.
        for (value in listOf(
            "Morpho: a converter",
            "say \"hi\" \\ here",
            "الاستمارة في البحث العلمي",
            "a\tb",
            "one\ntwo",
            "a\u0001b",
            "---",
            "\"",
            "\\",
            "# not a heading",
        )) {
            val said = DocumentProperties.of(value, null, null, null)
            assertEquals(said, tripped(said), "the title \"$value\" did not survive")
        }
    }

    @Test
    fun `a document that says nothing about itself gets no fence`() {
        // The block is written only where there is something to put in it:
        // an empty fence at the top of every converted file would be
        // noise, and would change every file this ever wrote.
        val plain = MarkdownWriter.write(DocumentModel(blocks = body))
        assertEquals("The body.\n", plain)
        assertTrue(DocumentProperties().isEmpty)
    }

    @Test
    fun `the block goes above everything, with one blank line under it`() {
        val written = MarkdownWriter.write(
            DocumentModel(
                blocks = listOf(Paragraph(listOf(TextRun("Chapter One")), ParagraphStyle(ParagraphKind.HEADING_1))),
                properties = DocumentProperties.of("A Title", "An Author", null, null),
            )
        )
        assertEquals(
            "---\ntitle: \"A Title\"\nauthor: \"An Author\"\n---\n\n# Chapter One\n",
            written,
        )
    }

    @Test
    fun `a file that opens with a horizontal rule keeps its rule`() {
        // The one real ambiguity of front matter: `---` alone is also a
        // thematic break. A block is a fence, a closing fence, and
        // nothing between them that is not a field — anything else is the
        // text it looks like.
        for (text in listOf(
            "---\n\nA paragraph.\n",
            "---\nA paragraph.\n---\n",
            "---\ntitle: unclosed\n\nThe body.\n",
            "...\ntitle: not an opening fence\n...\n",
        )) {
            val model = PlainTextImporter.import(text)
            assertTrue(
                model.properties.isEmpty,
                "\"${text.replace("\n", "\\n")}\" was read as a block of fields",
            )
            assertTrue(
                model.blocks.filterIsInstance<Paragraph>().any { it.text.contains("---") ||
                    it.text.contains("...") },
                "\"${text.replace("\n", "\\n")}\" lost the rule it opened with",
            )
        }
    }

    @Test
    fun `a field this does not know is metadata, not the first paragraph`() {
        // Another tool's front matter carries `layout`, `date`, `tags`.
        // Those are the file's metadata whether or not this understands
        // them; letting them fall through would put "layout: post" in the
        // reader's opening line.
        val model = PlainTextImporter.import(
            "---\nlayout: post\ntitle: A Post\ndate: 2026-09-03\n---\n\nThe body.\n"
        )
        assertEquals("A Post", model.properties.title)
        assertEquals(
            listOf("The body."),
            model.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a hand-written block is read as written`() {
        // Nothing requires the quoting this writer uses: a person writing
        // front matter by hand writes the value bare, and Pandoc reads it.
        val model = PlainTextImporter.import(
            "---\ntitle: Bare Words\nAuthor: Someone\n---\n\nThe body.\n"
        )
        assertEquals("Bare Words", model.properties.title)
        // Keys are matched however the file cased them.
        assertEquals("Someone", model.properties.author)
    }

    @Test
    fun `an empty value is silence, not an empty title`() {
        val model = PlainTextImporter.import("---\ntitle:\nauthor: Someone\n---\n\nThe body.\n")
        assertNull(model.properties.title)
        assertEquals("Someone", model.properties.author)
    }

    @Test
    fun `a control character goes out as an escape a YAML reader accepts`() {
        // A raw control character inside a double-quoted scalar is invalid
        // YAML, so a file with one in its title would be a file Pandoc and
        // Jekyll refuse to parse — and a title comes from a document this
        // converter did not write.
        val written = FrontMatter.of(DocumentProperties.of("a\u0001b\u007Fc", null, null, null))
        assertEquals("---\ntitle: \"a\\x01b\\x7fc\"\n---", written)
        assertTrue(written.none { it.code < 0x20 && it != '\n' })
    }

    @Test
    fun `a block with no fields at all is still a block`() {
        // An empty fence is not something this writes, but a file may
        // carry one, and its two lines are not the document's first
        // paragraph.
        val model = PlainTextImporter.import("---\n---\n\nThe body.\n")
        assertTrue(model.properties.isEmpty)
        assertEquals(
            listOf("The body."),
            model.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }
}
