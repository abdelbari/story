package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a tab lands, against what Word does with the same paragraph.
 *
 * A form is mostly tabs. The app had three answers to this and none of
 * them was Word's, so this is the one, with the cases that told the three
 * apart written down.
 */
class TabStopsTest {

    @Test
    fun `a tab goes to the next stop the paragraph named`() {
        val declared = listOf(72f, 144f, 216f)
        assertEquals(72f, TabStops.next(0f, declared))
        assertEquals(72f, TabStops.next(40f, declared))
        assertEquals(144f, TabStops.next(72f, declared), "a tab on a stop stays on it")
        assertEquals(216f, TabStops.next(200f, declared))
    }

    @Test
    fun `a stop the paragraph named is found whatever order it is in`() {
        assertEquals(72f, TabStops.next(0f, listOf(216f, 72f, 144f)))
    }

    @Test
    fun `past the last named stop a tab keeps to the default's own columns`() {
        // The rule the running head had wrong: it advanced by the default
        // from wherever the text reached, so the column moved whenever the
        // word before it was long.
        assertEquals(72f, TabStops.next(40f), "40pt should reach the second default column")
        assertEquals(36f, TabStops.next(0f))
        assertEquals(36f, TabStops.next(1f))
        assertEquals(72f, TabStops.next(36f), "a tab on a column goes to the next one")
        assertEquals(108f, TabStops.next(100f))
        // Two lines whose text reaches different widths still line up.
        assertEquals(TabStops.next(38f), TabStops.next(70f))
    }

    @Test
    fun `a tab past the paragraph's own stops falls to the default columns`() {
        // Word's rule, and the one the platform's text layout does not
        // follow on its own.
        val declared = listOf(50f)
        assertEquals(50f, TabStops.next(0f, declared))
        assertEquals(72f, TabStops.next(50f, declared))
        assertEquals(108f, TabStops.next(90f, declared))
    }

    @Test
    fun `nothing before the start of the line is a stop`() {
        assertEquals(36f, TabStops.next(-10f))
        assertEquals(72f, TabStops.next(40f, listOf(-5f, 0f)))
    }

    @Test
    fun `the stops of a line are its named ones and then the columns`() {
        assertEquals(
            listOf(50f, 72f, 108f, 144f, 180f),
            TabStops.through(200f, listOf(50f)),
        )
        assertEquals(listOf(36f, 72f, 108f), TabStops.through(120f))
        // A stop past the edge of the line is not a stop on it.
        assertEquals(listOf(36f, 72f), TabStops.through(100f, listOf(500f)))
    }

    @Test
    fun `handed to a layout, the stops answer the same as asking one at a time`() {
        // The two halves have to agree: the running head asks where the
        // next tab goes, and the body hands its stops to a text layout
        // that walks them itself. A form set one way and a running head
        // set the other would line up differently on the same page.
        for (declared in listOf(emptyList(), listOf(50f), listOf(72f, 144f), listOf(20f, 30f))) {
            val stops = TabStops.through(400f, declared)
            var at = 0f
            repeat(6) {
                val asked = TabStops.next(at, declared)
                if (asked <= 400f) {
                    assertTrue(asked in stops, "asking gave $asked, which the line's stops do not hold")
                }
                at = asked
            }
        }
    }

    @Test
    fun `a line with no width has no stops`() {
        assertEquals(emptyList<Float>(), TabStops.through(0f))
        assertEquals(emptyList<Float>(), TabStops.through(-1f, listOf(36f)))
    }

    @Test
    fun `a line of any width is given stops that make sense`() {
        // Widths and declared stops nobody would set, including negative
        // ones and stops past the end of the line: what comes back must
        // still be an ordered set of places on the line, and asking one at
        // a time must always move forward.
        val rng = kotlin.random.Random(20260903)
        repeat(3000) {
            val width = rng.nextFloat() * 2000f - 100f
            val declared = List(rng.nextInt(0, 5)) { rng.nextFloat() * 900f - 100f }
            val stops = TabStops.through(width, declared)
            assertEquals(stops.sorted(), stops, "out of order for $width, $declared")
            assertEquals(stops.distinct(), stops, "a stop twice for $width, $declared")
            assertTrue(stops.all { it <= width }, "a stop past the line for $width, $declared")
            var at = 0f
            repeat(4) {
                val next = TabStops.next(at, declared)
                assertTrue(next > at, "a tab did not move: $at to $next with $declared")
                at = next
            }
        }
    }

    @Test
    fun `a column narrower than a point is not a column`() {
        // A document is free to name a default of its own, and one naming
        // a thousandth of a point asked for a million columns across an
        // ordinary line: not a page of tab stops but a converter that
        // stops converting. A fuzz over this found it before any document
        // had the chance to.
        for (tiny in listOf(0.001f, 0.5f, 0f, -12f, Float.NaN, Float.MIN_VALUE)) {
            val stops = TabStops.through(1000f, emptyList(), defaultPt = tiny)
            assertTrue(
                stops.size <= (1000f / TabStops.DEFAULT_PT).toInt() + 1,
                "a default of $tiny asked for ${stops.size} stops",
            )
            assertEquals(TabStops.DEFAULT_PT, TabStops.next(0f, emptyList(), defaultPt = tiny))
        }
        // A column wider than the default is one a document may name.
        assertEquals(96f, TabStops.next(0f, emptyList(), defaultPt = 96f))
        assertEquals(listOf(96f, 192f), TabStops.through(200f, emptyList(), defaultPt = 96f))
    }

    @Test
    fun `a default of nothing is the default`() {
        // A document that names a default of zero would otherwise ask for
        // stops forever.
        assertEquals(36f, TabStops.next(0f, emptyList(), defaultPt = 0f))
        assertEquals(listOf(36f, 72f), TabStops.through(80f, emptyList(), defaultPt = 0f))
    }
}
