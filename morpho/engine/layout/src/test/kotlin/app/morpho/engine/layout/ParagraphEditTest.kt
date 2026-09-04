package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Correcting a word without losing the page.
 *
 * The first edit anybody makes to a converted document is to a word
 * recognition got wrong, and the naive way to allow it flattens the
 * paragraph: a text box holds a string, and a paragraph that goes into
 * one and comes back is one run of plain text with its bold, its links
 * and its notes gone. So the words are laid over the runs instead.
 */
class ParagraphEditTest {

    private fun paragraph(vararg runs: TextRun) = Paragraph(runs.toList())

    @Test
    fun `a word corrected in the middle leaves everything round it alone`() {
        val was = paragraph(
            TextRun("The form is "),
            TextRun("recieved", bold = true),
            TextRun(" by the office."),
        )
        val now = ParagraphEdit.retext(was, "The form is received by the office.")
        assertEquals("The form is received by the office.", now.text)
        assertEquals(3, now.runs.size, now.runs.map { it.text }.toString())
        assertEquals("received", now.runs[1].text)
        assertTrue(now.runs[1].bold, "the corrected word kept the weight it was set in")
        assertEquals(listOf(false, true, false), now.runs.map { it.bold })
    }

    @Test
    fun `a word corrected inside a link stays inside the link`() {
        val was = paragraph(
            TextRun("Write to "),
            TextRun("nebbarrebih@gmial.com", link = "mailto:nebbarrebih@gmial.com"),
            TextRun(" about it."),
        )
        val now = ParagraphEdit.retext(was, "Write to nebbarrebih@gmail.com about it.")
        assertEquals("nebbarrebih@gmail.com", now.runs[1].text)
        assertEquals("mailto:nebbarrebih@gmial.com", now.runs[1].link, "the run is still the link's")
        assertEquals(3, now.runs.size)
    }

    @Test
    fun `text typed at the end continues what it was typed after`() {
        val was = paragraph(TextRun("The finding is "), TextRun("clear", italic = true))
        val now = ParagraphEdit.retext(was, "The finding is clearer still")
        assertEquals("The finding is clearer still", now.text)
        assertTrue(
            now.runs.last().italic,
            "typing after italic type is italic, as it is in every word processor",
        )
        assertEquals(2, now.runs.size, now.runs.map { "${it.text}/${it.italic}" }.toString())
    }

    @Test
    fun `text typed at the very start takes the look of what it is typed before`() {
        val was = paragraph(TextRun("form", bold = true), TextRun(" in research"))
        val now = ParagraphEdit.retext(was, "The form in research")
        assertEquals("The form in research", now.text)
        assertTrue(now.runs.first().bold, "there is nothing to the left, so the first run stands in")
    }

    @Test
    fun `deleting from the middle keeps both sides`() {
        val was = paragraph(
            TextRun("Keep this "),
            TextRun("delete this ", strikethrough = true),
            TextRun("and this."),
        )
        val now = ParagraphEdit.retext(was, "Keep this and this.")
        assertEquals("Keep this and this.", now.text)
        assertTrue(now.runs.none { it.strikethrough }, "the struck run went with its words")
        assertEquals(1, now.runs.size, "what is left is set alike and is one run")
    }

    @Test
    fun `a paragraph left as it was is the paragraph it was`() {
        val was = paragraph(TextRun("Unchanged.", bold = true), TextRun(" Also unchanged."))
        assertEquals(was, ParagraphEdit.retext(was, "Unchanged. Also unchanged."))
    }

    @Test
    fun `the paragraph's own style is not the paragraph's words`() {
        val was = Paragraph(
            runs = listOf(TextRun("2-تعريف الاستمارة")),
            style = ParagraphStyle(
                kind = ParagraphKind.HEADING_2,
                direction = TextDirection.RTL,
                alignment = Alignment.CENTER,
            ),
            bookmarks = listOf("section-2"),
        )
        val now = ParagraphEdit.retext(was, "2-تعريف الاستمارة والاستبيان")
        assertEquals(was.style, now.style, "changing the words is not changing the paragraph")
        assertEquals(was.bookmarks, now.bookmarks, "a link elsewhere still points here")
    }

    @Test
    fun `a page number is not copied over the words typed after it`() {
        // A field is filled in by the writer rather than written down, so
        // text typed after one is text — not a second page number.
        val was = paragraph(
            TextRun("48", field = RunField.PAGE_NUMBER),
            TextRun(" of the report"),
        )
        val now = ParagraphEdit.retext(was, "48 of the whole report")
        assertEquals("48 of the whole report", now.text)
        assertEquals(RunField.PAGE_NUMBER, now.runs.first().field)
        assertEquals(1, now.runs.count { it.field != null }, "one page number, not two")
    }

    @Test
    fun `a note's mark keeps its note and what follows does not take it`() {
        val was = paragraph(
            TextRun("The form"),
            TextRun("1", superscript = true, note = listOf(Paragraph(listOf(TextRun("See the appendix."))))),
            TextRun(" is enclosed."),
        )
        val now = ParagraphEdit.retext(was, "The form1 is now enclosed.")
        assertEquals("The form1 is now enclosed.", now.text)
        assertEquals(1, now.runs.count { it.note != null }, "one note, still on its own mark")
        assertNull(
            now.runs.last().note,
            "the words after the mark are not a second copy of the note",
        )
    }

    @Test
    fun `a picture set in the line is kept or dropped whole`() {
        val logo = ImageBlock(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", widthPx = 24, heightPx = 24)
        val was = paragraph(TextRun("Before "), TextRun("", image = logo), TextRun(" after"))
        val kept = ParagraphEdit.retext(was, "Before  after and more")
        assertEquals(1, kept.runs.count { it.image != null }, "the picture is still in the line")
    }

    @Test
    fun `emptying a paragraph leaves a paragraph`() {
        val was = Paragraph(
            listOf(TextRun("Something", bold = true)),
            style = ParagraphStyle(kind = ParagraphKind.HEADING_1),
        )
        val now = ParagraphEdit.retext(was, "")
        assertEquals("", now.text)
        assertEquals(ParagraphKind.HEADING_1, now.style.kind, "an empty heading is still a heading")
    }

    @Test
    fun `a paragraph with no runs at all takes the words it is given`() {
        val now = ParagraphEdit.retext(Paragraph(emptyList()), "Now it says something.")
        assertEquals("Now it says something.", now.text)
    }

    @Test
    fun `a character written as two is never cut in half`() {
        // An emoji, and much of the mathematical and historical type a
        // thesis uses, is a surrogate pair. Split across two runs it is
        // not a character at all.
        val was = paragraph(TextRun("a😀b", bold = true), TextRun("c"))
        val now = ParagraphEdit.retext(was, "a😀zc")
        assertEquals("a😀zc", now.text)
        for (run in now.runs) {
            assertTrue(
                run.text.isEmpty() || !run.text.first().isLowSurrogate(),
                "a run begins with the back half of a character: ${run.text.map { it.code }}",
            )
            assertTrue(
                run.text.isEmpty() || !run.text.last().isHighSurrogate(),
                "a run ends with the front half of a character: ${run.text.map { it.code }}",
            )
        }
    }

    @Test
    fun `whatever is typed, the paragraph says it`() {
        // The one property that has to hold for every edit there is: what
        // the reader typed is what the paragraph says afterwards.
        val pieces = listOf("", "a", "الاستمارة", "the ", "😀", "1", " ", "\n", "form")
        val rng = kotlin.random.Random(20260904)
        val looks = listOf(
            TextRun(""), TextRun("", bold = true), TextRun("", italic = true),
            TextRun("", link = "https://example.org"), TextRun("", fontSizePt = 14f),
        )
        repeat(4000) {
            val runs = List(rng.nextInt(1, 5)) { at ->
                looks.random(rng).copy(text = (1..rng.nextInt(0, 4)).joinToString("") { pieces.random(rng) })
            }
            val was = Paragraph(runs)
            val text = (1..rng.nextInt(0, 6)).joinToString("") { pieces.random(rng) }
            val now = ParagraphEdit.retext(was, text)
            assertEquals(text, now.text, "typed \"$text\" over ${runs.map { it.text }}")
            assertTrue(
                now.runs.isNotEmpty(),
                "a paragraph must always hold at least one run for a writer to write",
            )
        }
    }
}
