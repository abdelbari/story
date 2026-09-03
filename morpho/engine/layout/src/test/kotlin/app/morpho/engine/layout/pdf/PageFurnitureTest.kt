package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.RunField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A page with no tags says nothing about which of its lines are the
 * page's own furniture, so they are known by repetition. Taken out of the
 * text they no longer interrupt the reading; put back where a document
 * keeps them, the converted file carries the running head and goes on
 * numbering its pages as the paper did.
 */
class PageFurnitureTest {

    private val height = 800f
    private val width = 600f

    private fun sheets(pages: Int) = (1..pages).map { PdfPageSheet(it, width, height) }

    private fun line(text: String, page: Int, y: Float, x: Float = 72f, xEnd: Float = 300f) =
        PdfLine(text = text, x = x, baselineY = y, maxFontSize = 10f, page = page, xEnd = xEnd)

    /** Four pages, each with a running head, a body line, and a numbered foot. */
    private fun paper(): List<PdfLine> = (1..4).flatMap { page ->
        listOf(
            line("The Journal of Something", page, 40f),
            line("The words of page $page.", page, 400f),
            line("${page + 47}", page, 770f, x = 290f, xEnd = 310f),
        )
    }

    @Test
    fun `what repeats in the margin is the page's own, not the document's`() {
        val split = PageFurniture.of(paper(), sheets(4))
        assertEquals(
            listOf("The words of page 1.", "The words of page 2.", "The words of page 3.", "The words of page 4."),
            split.body.map { it.text },
        )
        assertEquals("The Journal of Something", (split.header.single() as Paragraph).text)
        assertEquals(1, split.footer.size)
    }

    @Test
    fun `the number that keeps step with the pages is written as a field`() {
        val split = PageFurniture.of(paper(), sheets(4))
        val foot = split.footer.single() as Paragraph
        assertEquals(listOf(RunField.PAGE_NUMBER), foot.runs.mapNotNull { it.field })
        // The paper opens on page 48, so the document must start there.
        assertEquals(48, split.firstPageNumber)
        assertEquals("48", foot.runs.first { it.field != null }.text)
    }

    @Test
    fun `a number that does not count the pages is left as it is`() {
        // A year, a volume, an ISSN: it repeats, but it does not advance.
        val lines = (1..4).flatMap { page ->
            listOf(
                line("Volume 2022", page, 770f),
                line("The words of page $page.", page, 400f),
            )
        }
        val split = PageFurniture.of(lines, sheets(4))
        val foot = split.footer.single() as Paragraph
        assertTrue(foot.runs.none { it.field != null }, foot.runs.toString())
        assertNull(split.firstPageNumber)
    }

    @Test
    fun `a document too short to compare keeps everything`() {
        val lines = (1..2).flatMap { page ->
            listOf(line("A heading", page, 40f), line("Words $page.", page, 400f))
        }
        val split = PageFurniture.of(lines, sheets(2))
        assertEquals(lines.size, split.body.size)
        assertTrue(split.header.isEmpty() && split.footer.isEmpty())
    }

    @Test
    fun `a line in the middle of the page is text however often it repeats`() {
        val lines = (1..4).flatMap { page ->
            listOf(line("Said on every page", page, 400f), line("Words $page.", page, 420f))
        }
        val split = PageFurniture.of(lines, sheets(4))
        assertEquals(lines.size, split.body.size)
    }

    @Test
    fun `the head and the foot are measured from the edges they sit against`() {
        val split = PageFurniture.of(paper(), sheets(4))
        // The head's baseline is 40pt down and its type 10pt: its ink
        // starts 8 points above the baseline.
        assertEquals(32f, split.headerDistancePt!!, 0.01f)
        // The foot's baseline is 30pt up from the bottom, its descender
        // reaching 2.5pt below it.
        assertEquals(27.5f, split.footerDistancePt!!, 0.01f)
    }

    @Test
    fun `a document with nothing in its margins is left whole`() {
        val lines = (1..4).map { line("Words $it.", it, 400f) }
        val split = PageFurniture.of(lines, sheets(4))
        assertEquals(lines, split.body)
        assertNull(split.headerDistancePt)
    }
}
