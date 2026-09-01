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
    fun `arabic lines join with a space like any other script`() {
        assertEquals(
            "النص العربي يُوصل بمسافة",
            LineJoiner.join(listOf("النص العربي", "يُوصل بمسافة")),
        )
    }
}
