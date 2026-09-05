package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scanned book is read a page at a time, and a page's words come back
 * with nothing to say what they were. Handed on as one text with a blank
 * line between pages, the running head landed in the middle of a sentence
 * at every page turn and every paragraph that crossed a turn was cut in
 * two — hundreds of each in a book, every one for the reader to repair.
 */
class ScannedPagesTest {

    private fun textOf(vararg pages: String) = ScannedPages.of(pages.toList()).text

    private fun paragraphs(vararg pages: String) =
        PlainTextImporter.importPages(pages.toList()).blocks.filterIsInstance<Paragraph>().map { it.text }

    @Test
    fun `a paragraph does not end because a page did`() {
        val text = textOf(
            "Chapter Three\n\nThe committee met in the spring and considered",
            "Chapter Three\n\nthe question at length before",
            "Chapter Three\n\ndeciding what to do about it.",
        )
        assertTrue(text.contains("considered\nthe question"), text)
        assertFalse(text.contains("considered\n\nthe question"), text)
        assertTrue(text.contains("before\ndeciding"), text)
    }

    @Test
    fun `a document of two pages is left the way it was`() {
        // Too few pages to tell a running head from the first words of a
        // paragraph, and joining prose onto a head would be worse than
        // leaving the paragraph cut at the turn.
        val text = textOf(
            "Chapter Three\n\nThe committee met in the spring and considered",
            "Chapter Three\n\nthe question at length before deciding.",
        )
        assertTrue(text.contains("considered\n\nChapter Three"), text)
    }

    @Test
    fun `a page that ended its paragraph keeps the break`() {
        val text = textOf(
            "The committee met in the spring and decided.",
            "A separate matter arose the following year.",
            "A third matter was deferred entirely.",
        )
        assertTrue(text.contains("decided.\n\nA separate"), text)
        assertTrue(text.contains("year.\n\nA third"), text)
    }

    @Test
    fun `a running head repeated on every page is the page's, not the document's`() {
        val read = ScannedPages.of(
            listOf(
                "Chapter Three: Instruments\n\nThe first paragraph of the chapter.",
                "Chapter Three: Instruments\n\nThe second paragraph.",
                "Chapter Three: Instruments\n\nThe third paragraph.",
            )
        )
        assertFalse(read.text.contains("Chapter Three"), "the head stayed in the text: ${read.text}")
        assertEquals(1, read.header.size)
        assertEquals("Chapter Three: Instruments", (read.header.single() as Paragraph).text)
        assertTrue(read.text.contains("The first paragraph"), read.text)
    }

    @Test
    fun `a page number in the head keeps counting rather than being stamped`() {
        val read = ScannedPages.of(
            listOf(
                "The words of the first page.\n\nChapter Three  47",
                "The words of the second page.\n\nChapter Three  48",
                "The words of the third page.\n\nChapter Three  49",
            )
        )
        val foot = read.footer.single() as Paragraph
        assertEquals("Chapter Three  47", foot.text)
        val counted = foot.runs.single { it.field == RunField.PAGE_NUMBER }
        assertEquals("47", counted.text, "the number that counts the pages was not found")
        assertFalse(read.text.contains("Chapter Three"), read.text)
    }

    @Test
    fun `a head whose number does not count the pages is left as words`() {
        val read = ScannedPages.of(
            listOf(
                "Volume 4, Number 2\n\nThe first page.",
                "Volume 4, Number 2\n\nThe second page.",
                "Volume 4, Number 2\n\nThe third page.",
            )
        )
        val head = read.header.single() as Paragraph
        assertEquals("Volume 4, Number 2", head.text)
        assertTrue(head.runs.none { it.field == RunField.PAGE_NUMBER }, head.runs.toString())
    }

    @Test
    fun `two pages that happen to open alike are a coincidence, not a head`() {
        val read = ScannedPages.of(
            listOf("Introduction\n\nThe first page.", "Introduction\n\nThe second page.")
        )
        assertTrue(read.header.isEmpty(), read.header.toString())
        assertTrue(read.text.contains("Introduction"), read.text)
    }

    @Test
    fun `a page number counted in Arabic-Indic digits is found too`() {
        val read = ScannedPages.of(
            listOf(
                "الصفحة ٤٧\n\nنص الصفحة الأولى.",
                "الصفحة ٤٨\n\nنص الصفحة الثانية.",
                "الصفحة ٤٩\n\nنص الصفحة الثالثة.",
            )
        )
        val head = read.header.single() as Paragraph
        assertEquals("٤٧", head.runs.single { it.field == RunField.PAGE_NUMBER }.text)
    }

    @Test
    fun `a list item across a page turn stays its own item`() {
        val text = textOf(
            "The steps are as follows",
            "- the first step\n- the second step",
            "- the third step",
        )
        // The words before the turn stop mid-sentence, but what follows
        // plainly begins something of its own.
        assertTrue(text.contains("as follows\n\n- the first"), text)
    }

    @Test
    fun `a stray page number on its own line does not join the prose`() {
        val text = textOf(
            "The committee met in the spring and considered",
            "48",
            "the question at length.",
            "A further matter was raised.",
        )
        assertTrue(text.contains("considered\n\n48"), text)
    }

    @Test
    fun `the pages read as a document keep their head and their paragraphs`() {
        val blocks = paragraphs(
            "Chapter Three\n\nThe committee met in the spring and considered",
            "Chapter Three\n\nthe question at length before deciding.",
            "Chapter Three\n\nA separate matter arose the following year.",
        )
        assertEquals(2, blocks.size, blocks.toString())
        assertEquals(
            "The committee met in the spring and considered the question at length before deciding.",
            blocks[0],
        )
        assertEquals("A separate matter arose the following year.", blocks[1])
    }

    @Test
    fun `the number the pages start at is carried, not restarted`() {
        val read = ScannedPages.of(
            listOf(
                "Chapter Three  47\n\nThe first page.",
                "Chapter Three  48\n\nThe second page.",
                "Chapter Three  49\n\nThe third page.",
            )
        )
        assertEquals(47, read.firstPageNumber)
        // And on through the importer, onto the sheet the caller knows.
        val sheet = PageSetup(595f, 842f, 72f, 72f, 72f, 72f)
        val model = PlainTextImporter.importPages(
            listOf(
                "Chapter Three  47\n\nThe first page.",
                "Chapter Three  48\n\nThe second page.",
                "Chapter Three  49\n\nThe third page.",
            ),
            sheet,
        )
        assertEquals(47, model.pageSetup!!.firstPageNumber)
        assertEquals(595f, model.pageSetup!!.widthPt)
    }

    @Test
    fun `nothing invents a sheet for pages whose size is not known`() {
        val model = PlainTextImporter.importPages(
            listOf("The first page.", "The second page.", "The third page.")
        )
        assertEquals(null, model.pageSetup)
    }

    @Test
    fun `a head with no counting number says nothing about where numbering starts`() {
        val read = ScannedPages.of(
            listOf(
                "Volume 4, Number 2\n\nThe first page.",
                "Volume 4, Number 2\n\nThe second page.",
                "Volume 4, Number 2\n\nThe third page.",
            )
        )
        assertEquals(null, read.firstPageNumber)
    }

    @Test
    fun `a scanned book comes back as the paragraphs it was`() {
        // The whole of it at once, in the shape recognition hands back: a
        // running head with a number on every page, lines broken where the
        // page broke them, blank lines between paragraphs, and pages that
        // end sometimes mid-paragraph and sometimes exactly at the end of
        // one. What must come back is the prose, and nothing else.
        val prose = listOf(
            "The committee met in the spring of that year and considered the question of the " +
                "instruments at some length, without reaching a conclusion that satisfied anybody present.",
            "A second meeting was called for the autumn. Its minutes record that the same arguments " +
                "were made again, at greater length and to less effect.",
            "The report itself was published the following January. It runs to two hundred pages " +
                "and says, in substance, what the first meeting had said in an afternoon.",
            "Nothing further was heard of the matter until the instruments were replaced, four " +
                "years later, without a report of any kind.",
        )
        val flow = mutableListOf<String>()
        for ((index, paragraph) in prose.withIndex()) {
            if (index > 0) flow += ""
            flow += wrapped(paragraph, LINE_WIDTH)
        }
        val pages = mutableListOf<String>()
        var at = 0
        var number = 47
        while (at < flow.size) {
            val own = flow.subList(at, minOf(at + LINES_A_PAGE, flow.size)).joinToString("\n").trim('\n')
            pages += "Chapter Three: Instruments   $number\n\n$own"
            at += LINES_A_PAGE
            number++
        }
        val model = PlainTextImporter.importPages(pages)
        assertEquals(
            prose,
            model.blocks.filterIsInstance<Paragraph>().map { it.text },
            "the prose did not come back as the paragraphs it was",
        )
        assertEquals(1, model.header.size)
        assertEquals("Chapter Three: Instruments   47", (model.header.single() as Paragraph).text)
    }

    /** About [width] characters a line, broken between words as a page breaks them. */
    private fun wrapped(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (word in text.split(" ")) {
            if (sb.isNotEmpty() && sb.length + 1 + word.length > width) {
                lines += sb.toString()
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(word)
        }
        if (sb.isNotEmpty()) lines += sb.toString()
        return lines
    }

    private companion object {
        const val LINE_WIDTH = 52
        const val LINES_A_PAGE = 8
    }

    @Test
    fun `no pages at all is an empty reading`() {
        val read = ScannedPages.of(emptyList())
        assertEquals("", read.text)
        assertTrue(read.header.isEmpty() && read.footer.isEmpty())
    }

    @Test
    fun `a blank page is passed over rather than breaking the text twice`() {
        val text = textOf("The first page.", "   ", "The second page.", "The third page.")
        assertEquals("The first page.\n\nThe second page.\n\nThe third page.", text)
    }
}
