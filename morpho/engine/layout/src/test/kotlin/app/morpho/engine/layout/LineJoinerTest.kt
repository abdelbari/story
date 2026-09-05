package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LineJoinerTest {

    @Test
    fun `ordinary lines join with single spaces`() {
        assertEquals(
            "The quick brown fox jumps over the lazy dog.",
            LineJoiner.join(listOf("The quick brown", "fox jumps over", "the lazy dog.")),
        )
    }

    @Test
    fun `a line ending in a hyphen abuts the next one`() {
        assertEquals(
            "an inter-national agreement",
            LineJoiner.join(listOf("an inter-", "national agreement")),
        )
        // U+2010 counts too; it is a hyphen, not a dash.
        assertEquals("co‐operate now", LineJoiner.join(listOf("co‐", "operate now")))
    }

    @Test
    fun `dashes are punctuation and keep their space`() {
        assertEquals(
            "a pause — then more",
            LineJoiner.join(listOf("a pause —", "then more")),
        )
        assertEquals("a range – of things", LineJoiner.join(listOf("a range –", "of things")))
    }

    @Test
    fun `blank and whitespace-only lines are skipped, edges trimmed`() {
        assertEquals("first second", LineJoiner.join(listOf("  first  ", "", "   ", " second ")))
        assertEquals("", LineJoiner.join(listOf("", "   ")))
        assertEquals("", LineJoiner.join(emptyList()))
    }

    @Test
    fun `a single line comes back as itself`() {
        assertEquals("just one line", LineJoiner.join(listOf("just one line")))
    }

    @Test
    fun `a word the document writes whole is put back together`() {
        // The document is the dictionary: it breaks "international" here
        // and writes it out on another page, which settles the hyphen.
        val known = LineJoiner.Vocabulary.of(
            listOf("The international agreement was signed.", "and other matters")
        )
        assertEquals(
            "an international agreement",
            LineJoiner.join(listOf("an inter-", "national agreement"), known),
        )
    }

    @Test
    fun `a word that carries its own hyphen keeps it`() {
        val known = LineJoiner.Vocabulary.of(listOf("a well-known result", "and other matters"))
        assertEquals(
            "a well-known result",
            LineJoiner.join(listOf("a well-", "known result"), known),
        )
    }

    @Test
    fun `a document that settles it both ways keeps the hyphen`() {
        // Both spellings attested is no answer, and keeping the hyphen is
        // the answer that destroys nothing.
        val known = LineJoiner.Vocabulary.of(listOf("co-operate and cooperate alike"))
        assertEquals("we co-operate now", LineJoiner.join(listOf("we co-", "operate now"), known))
    }

    @Test
    fun `a word the document never writes whole keeps its hyphen`() {
        val known = LineJoiner.Vocabulary.of(listOf("nothing here settles it"))
        assertEquals(
            "an inter-national agreement",
            LineJoiner.join(listOf("an inter-", "national agreement"), known),
        )
    }

    @Test
    fun `half a word is not taken for a whole one`() {
        // The line that breaks "international" ends on "inter-". Counted
        // as a word of the document, it would answer questions about
        // itself; and the line after it opening on "national" must not
        // make "national" the answer either.
        val known = LineJoiner.Vocabulary.of(listOf("an inter-", "national agreement"))
        assertEquals(
            "an inter-national agreement",
            LineJoiner.join(listOf("an inter-", "national agreement"), known),
        )
    }

    @Test
    fun `the word is found however it is punctuated or cased`() {
        val known = LineJoiner.Vocabulary.of(listOf("(International), and so on."))
        assertEquals(
            "an international agreement",
            LineJoiner.join(listOf("an inter-", "national agreement"), known),
        )
    }

    @Test
    fun `a next line opening on punctuation leaves the hyphen alone`() {
        // No word follows to ask about, so the question cannot be put and
        // the hyphen stays. The lines still abut, as a hyphen means they
        // should, whatever stands after it.
        val known = LineJoiner.Vocabulary.of(listOf("international matters"))
        assertEquals("an inter-— really", LineJoiner.join(listOf("an inter-", "— really"), known))
    }

    @Test
    fun `told nothing, the joiner behaves as it always did`() {
        assertEquals(
            "an inter-national agreement",
            LineJoiner.join(listOf("an inter-", "national agreement")),
        )
    }

    @Test
    fun `arabic lines join with a space like any other script`() {
        assertEquals(
            "النص العربي يُوصل بمسافة",
            LineJoiner.join(listOf("النص العربي", "يُوصل بمسافة")),
        )
    }

    @Test
    fun `a page's line that happens to end on a backslash means a backslash`() {
        // Markdown's hard break is a backslash at a line ending, and the
        // reading of a Markdown file asks for it. A page has no escaping
        // convention at all, so the same character there is a character,
        // and a wrapped line that ends on one must join like any other.
        // This is why the joining takes the question as a flag rather than
        // answering it the same way for everyone.
        val lines = listOf("the path is C:\\", "and then the rest of the sentence")
        assertEquals(
            "the path is C:\\ and then the rest of the sentence",
            LineJoiner.join(lines),
        )
    }

    @Test
    fun `a Markdown line asking to break does, and one escaping a backslash does not`() {
        // One backslash at the end is the break; two are a backslash the
        // document's own words hold, written escaped, and not a break.
        assertEquals(
            "University of Algiers\nFaculty of Letters",
            LineJoiner.join(listOf("University of Algiers\\", "Faculty of Letters"), hardBreaks = true),
        )
        assertEquals(
            "a literal \\\\ and more words",
            LineJoiner.join(listOf("a literal \\\\", "and more words"), hardBreaks = true),
        )
        // Three is an escaped backslash followed by a break.
        assertEquals(
            "ends on \\\\\nthe next line",
            LineJoiner.join(listOf("ends on \\\\\\", "the next line"), hardBreaks = true),
        )
    }

    @Test
    fun `a break asked for at the end of the last line is nothing to break`() {
        // There is no next line to join, so the backslash stays what it is
        // — which is CommonMark's own answer for a paragraph's last line.
        assertEquals("all of it\\", LineJoiner.join(listOf("all of it\\"), hardBreaks = true))
    }
}
