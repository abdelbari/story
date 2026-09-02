package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Addresses a document writes out in full. Most authors type an address
 * rather than insert a link, and a conversion that leaves it as plain
 * text hands its reader something to copy out by hand.
 */
class LinksTest {

    @Test
    fun `an email address under a paper's title becomes a link`() {
        val runs = Links.refineRuns(listOf(TextRun("جامعة الوادي(الجزائر)، nebbarrebih@gmail.com")))
        val address = runs.single { it.link != null }
        assertEquals("nebbarrebih@gmail.com", address.text)
        assertEquals("mailto:nebbarrebih@gmail.com", address.link)
        // The Arabic around it is left alone and keeps its place.
        assertEquals("جامعة الوادي(الجزائر)، nebbarrebih@gmail.com", runs.joinToString("") { it.text })
    }

    @Test
    fun `a web address is a link, and the sentence's full stop is not part of it`() {
        val runs = Links.refineRuns(listOf(TextRun("See https://example.org/a-page?q=1, then stop.")))
        val link = runs.single { it.link != null }
        assertEquals("https://example.org/a-page?q=1", link.text)
        assertEquals("https://example.org/a-page?q=1", link.link)
    }

    @Test
    fun `an address with no scheme still points somewhere a browser can follow`() {
        val runs = Links.refineRuns(listOf(TextRun("at www.example.org today")))
        val link = runs.single { it.link != null }
        assertEquals("www.example.org", link.text)
        assertEquals("https://www.example.org", link.link)
    }

    @Test
    fun `text that merely resembles an address is left alone`() {
        val plain = listOf(
            "a file named report.docx",
            "the ratio 3.5:1",
            "an @mention of someone",
            "an email@ with nowhere to go",
            "الاستمارة في البحث العلمي",
        )
        for (text in plain) {
            assertTrue(Links.find(text).isEmpty(), "$text was taken for an address: ${Links.find(text)}")
        }
    }

    @Test
    fun `an address split across runs by a change of face is one link`() {
        // Word splits a run wherever anything about it changes, so an
        // address can arrive in pieces.
        val runs = Links.refineRuns(
            listOf(
                TextRun("write to ", fontSizePt = 11f),
                TextRun("nebbarrebih", bold = true),
                TextRun("@gmail.com"),
            )
        )
        val linked = runs.filter { it.link != null }
        assertEquals("nebbarrebih@gmail.com", linked.joinToString("") { it.text })
        assertTrue(linked.all { it.link == "mailto:nebbarrebih@gmail.com" })
        // Each piece keeps the look it had.
        assertTrue(linked.first().bold, "the bold half stays bold")
        assertNull(runs.first().link)
    }

    @Test
    fun `a link the source itself carried is never second-guessed`() {
        val runs = Links.refineRuns(
            listOf(TextRun("our site", link = "https://example.org/other"), TextRun(" www.example.com"))
        )
        assertEquals("https://example.org/other", runs.first().link)
    }

    @Test
    fun `the whole document is looked over, its header and footer too`() {
        val model = Links.refine(
            DocumentModel(
                blocks = listOf(
                    Table(
                        listOf(TableRow(listOf(TableCell(listOf(Paragraph(listOf(TextRun("mail a@b.co")))))))),
                    )
                ),
                footer = listOf(Paragraph(listOf(TextRun("www.journal.dz")))),
            )
        )
        val cell = (model.blocks.single() as Table).rows.single().cells.single()
        val inCell = (cell.blocks.single() as Paragraph).runs.single { it.link != null }
        assertEquals("mailto:a@b.co", inCell.link)
        val inFooter = (model.footer.single() as Paragraph).runs.single { it.link != null }
        assertEquals("https://www.journal.dz", inFooter.link)
    }
}
