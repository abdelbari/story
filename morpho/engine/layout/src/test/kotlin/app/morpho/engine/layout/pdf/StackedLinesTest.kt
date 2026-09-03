package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StackedLinesTest {

    /** Four lines of twelve points each. */
    private val lines = listOf(12f, 24f, 36f, 48f)

    @Test
    fun `a column that fits is drawn whole`() {
        assertEquals(48f, StackedLines.cut(lines, from = 0f, room = 100f))
        assertFalse(StackedLines.more(lines, 48f))
    }

    @Test
    fun `the cut falls between lines, never through one`() {
        // Room for two and a half lines takes two.
        assertEquals(24f, StackedLines.cut(lines, from = 0f, room = 30f))
        assertTrue(StackedLines.more(lines, 24f))
    }

    @Test
    fun `what is left carries on from where the cut fell`() {
        val first = StackedLines.cut(lines, from = 0f, room = 30f)
        val second = StackedLines.cut(lines, from = first, room = 30f)
        assertEquals(48f, second)
        assertFalse(StackedLines.more(lines, second))
    }

    @Test
    fun `a line taller than the page is drawn rather than tried for ever`() {
        // Nothing fits; the alternative to drawing it clipped is a loop
        // that opens page after page and draws nothing on any of them.
        assertEquals(12f, StackedLines.cut(lines, from = 0f, room = 1f))
        assertEquals(24f, StackedLines.cut(lines, from = 12f, room = 1f))
    }

    @Test
    fun `a column with nothing left stays where it is`() {
        assertEquals(48f, StackedLines.cut(lines, from = 48f, room = 100f))
        assertEquals(0f, StackedLines.cut(emptyList(), from = 0f, room = 100f))
        assertFalse(StackedLines.more(emptyList(), 0f))
    }

    @Test
    fun `every cut moves the column on, whatever the room`() {
        // The property the drawing depends on: repeated cutting ends.
        for (room in listOf(0f, 1f, 11.9f, 12f, 13f, 47f, 48f, 1000f)) {
            var at = 0f
            var passes = 0
            while (StackedLines.more(lines, at)) {
                val next = StackedLines.cut(lines, at, room)
                assertTrue(next > at, "a cut with room $room did not move past $at")
                at = next
                assertTrue(++passes <= lines.size, "cutting with room $room did not end")
            }
            assertEquals(48f, at)
        }
    }
}
