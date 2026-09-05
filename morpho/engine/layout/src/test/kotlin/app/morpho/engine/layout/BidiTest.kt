package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BidiTest {

    @Test
    fun `latin text is LTR`() {
        assertEquals(TextDirection.LTR, Bidi.firstStrongDirection("Hello Morpho"))
    }

    @Test
    fun `arabic text is RTL`() {
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("مرحبا بالعالم"))
    }

    @Test
    fun `hebrew text is RTL`() {
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("שלום"))
    }

    @Test
    fun `leading digits and punctuation are skipped`() {
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("42 — مرحبا"))
        assertEquals(TextDirection.LTR, Bidi.firstStrongDirection("42 apples"))
    }

    @Test
    fun `text with no strong direction returns null`() {
        assertNull(Bidi.firstStrongDirection("123 + 456 = ..."))
        assertNull(Bidi.firstStrongDirection(""))
    }

    @Test
    fun `mixed text follows first strong character`() {
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("مرحبا Morpho"))
        assertEquals(TextDirection.LTR, Bidi.firstStrongDirection("Morpho مرحبا"))
    }

    // ---- UAX #9 run analysis ----

    private fun segments(text: String, base: TextDirection) =
        Bidi.directionalRuns(text, base).map { text.substring(it.start, it.end) to it.direction }

    @Test
    fun `uniform text is a single run`() {
        assertEquals(
            listOf("plain text" to TextDirection.LTR),
            segments("plain text", TextDirection.LTR),
        )
        assertEquals(
            listOf("نص عربي" to TextDirection.RTL),
            segments("نص عربي", TextDirection.RTL),
        )
        assertEquals(emptyList<Pair<String, TextDirection>>(), segments("", TextDirection.LTR))
    }

    @Test
    fun `neutrals between same-direction text stay with the base run`() {
        assertEquals(
            listOf(
                "hello " to TextDirection.LTR,
                "عربي" to TextDirection.RTL,
                " world" to TextDirection.LTR,
            ),
            segments("hello عربي world", TextDirection.LTR),
        )
        assertEquals(
            listOf(
                "مرحبا " to TextDirection.RTL,
                "hello" to TextDirection.LTR,
                " بك" to TextDirection.RTL,
            ),
            segments("مرحبا hello بك", TextDirection.RTL),
        )
    }

    @Test
    fun `digits inside RTL text resolve to an even level, as UAX 9 says`() {
        assertEquals(
            listOf(
                "عدد " to TextDirection.RTL,
                "123" to TextDirection.LTR,
                " بعد" to TextDirection.RTL,
            ),
            segments("عدد 123 بعد", TextDirection.RTL),
        )
    }

    @Test
    fun `refineRuns splits a mixed styled run and keeps its styling`() {
        val refined = Bidi.refineRuns(
            listOf(TextRun("hello عربي", bold = true)),
            paragraphDirection = TextDirection.LTR,
        )
        assertEquals(
            listOf(
                TextRun("hello ", bold = true),
                TextRun("عربي", bold = true, direction = TextDirection.RTL),
            ),
            refined,
        )
    }

    @Test
    fun `refineRuns splits around a styled span that straddles a boundary`() {
        // "hello " + bold("عربي and") + " more" — the bold span itself is mixed.
        val refined = Bidi.refineRuns(
            listOf(
                TextRun("hello "),
                TextRun("عربي and", bold = true),
                TextRun(" more"),
            ),
            paragraphDirection = TextDirection.LTR,
        )
        assertEquals(
            listOf(
                TextRun("hello "),
                TextRun("عربي", bold = true, direction = TextDirection.RTL),
                TextRun(" and", bold = true),
                TextRun(" more"),
            ),
            refined,
        )
    }

    @Test
    fun `refineRuns preserves the concatenated text exactly`() {
        val runs = listOf(
            TextRun("42 — "),
            TextRun("مرحبا ", italic = true),
            TextRun("Morpho v2", bold = true),
            TextRun(" النهاية"),
        )
        val refined = Bidi.refineRuns(runs, TextDirection.RTL)
        assertEquals(
            runs.joinToString("") { it.text },
            refined.joinToString("") { it.text },
        )
    }

    @Test
    fun `refineRuns normalizes a stale explicit direction that matches the paragraph`() {
        val refined = Bidi.refineRuns(
            listOf(TextRun("نص واحد", direction = TextDirection.RTL)),
            paragraphDirection = TextDirection.RTL,
        )
        assertEquals(listOf(TextRun("نص واحد")), refined)
    }

    @Test
    fun `refine walks table cells`() {
        val model = DocumentModel(
            blocks = listOf(
                Table(
                    rows = listOf(
                        TableRow(
                            listOf(
                                TableCell(
                                    listOf(
                                        Paragraph(
                                            runs = listOf(TextRun("cell مع عربي")),
                                            style = ParagraphStyle(direction = TextDirection.LTR),
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            ),
        )
        val cell = (Bidi.refine(model).blocks.single() as Table)
            .rows.single().cells.single()
        val runs = (cell.blocks.single() as Paragraph).runs
        assertEquals(
            listOf(
                TextRun("cell "),
                TextRun("مع عربي", direction = TextDirection.RTL),
            ),
            runs,
        )
    }

    @Test
    fun `a font's own glyph says nothing about direction`() {
        // Word paints the bullet before an Arabic list item as a Symbol
        // glyph, which reaches a reader as a private-use code point.
        // Unicode files those as left-to-right for want of anywhere else,
        // and taking that at face value turns the item around: its marker
        // ends up on the left of the page instead of the right.
        assertEquals(TextDirection.RTL, Bidi.firstStrongDirection("\uF0B7 الاستمارة البريدية"))
        assertEquals(TextDirection.LTR, Bidi.firstStrongDirection("\uF0B7 the postal form"))
        assertNull(Bidi.firstStrongDirection("\uF0B7 \uF02D"))
        assertEquals(TextDirection.RTL, Bidi.dominantDirection("\uF0B7\uF0B7\uF0B7 مرحبا"))
    }

}
