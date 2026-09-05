package app.morpho.engine.layout.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a page draws rather than places.
 *
 * A chart is paths, not a picture the file holds, and a reader that
 * gathers only pictures converts the text of a report and loses every
 * figure in it. But most of what a page paints is not a figure at all —
 * the rules of a table, the shading behind its head, a highlight over a
 * word, a border round the sheet — and every one of those was drawn to
 * sit behind or beside the document's own words. That is the test: a
 * figure holds no words, because it is not behind any.
 */
class PageFiguresTest {

    private val sheets = listOf(PdfPageSheet(1, 600f, 800f))

    private fun path(left: Float, top: Float, right: Float, bottom: Float, page: Int = 1) =
        PdfDrawing(page, left, top, right, bottom)

    private fun line(text: String, y: Float, x: Float = 72f, xEnd: Float = 400f, page: Int = 1) =
        PdfLine(text = text, x = x, baselineY = y, maxFontSize = 10f, page = page, xEnd = xEnd)

    @Test
    fun `a chart of many strokes is one figure`() {
        // Five bars and two axes, drawn as seven paths side by side.
        val bars = (0 until 5).map { path(120f + it * 60f, 300f - it * 20f, 160f + it * 60f, 400f) }
        val axes = listOf(path(110f, 200f, 111f, 400f), path(110f, 399f, 440f, 400f))
        val figures = PageFigures.of(bars + axes, listOf(line("The text above.", 100f)), sheets)
        assertEquals(1, figures.size, "a chart is one figure, not seven")
        val figure = figures.single()
        assertTrue(figure.left < 112f && figure.right > 438f, "it reaches both axes: $figure")
        assertTrue(figure.top < 202f && figure.bottom > 398f, "and its full height: $figure")
    }

    @Test
    fun `a rule under a heading is not a figure`() {
        val rule = path(72f, 120f, 500f, 120.6f)
        assertTrue(PageFigures.of(listOf(rule), listOf(line("A heading", 110f)), sheets).isEmpty())
    }

    @Test
    fun `shading behind a table's head is not a figure`() {
        // A filled box with the head of the table inside it. Photographed,
        // the words would be in the document twice — once as a picture and
        // once as the text they also are.
        val shading = path(60f, 200f, 540f, 240f)
        val head = line("Region    Cases    Share", 225f, x = 70f, xEnd = 520f)
        assertTrue(PageFigures.of(listOf(shading), listOf(head), sheets).isEmpty())
    }

    @Test
    fun `a border round the whole page is not a figure`() {
        val border = path(20f, 20f, 580f, 780f)
        assertTrue(PageFigures.of(listOf(border), listOf(line("The text.", 400f)), sheets).isEmpty())
    }

    @Test
    fun `an ornament too small to see is not a figure`() {
        val tick = path(100f, 100f, 110f, 110f)
        assertTrue(PageFigures.of(listOf(tick), emptyList(), sheets).isEmpty())
    }

    @Test
    fun `a figure beside a paragraph is still a figure`() {
        // The words are level with it but not inside it: a diagram in the
        // margin, or a chart with the text running past its side.
        val diagram = path(360f, 200f, 540f, 340f)
        val beside = line("Words that run past the side of it.", 260f, x = 60f, xEnd = 340f)
        assertEquals(1, PageFigures.of(listOf(diagram), listOf(beside), sheets).size)
    }

    @Test
    fun `each page keeps its own figures`() {
        val two = listOf(PdfPageSheet(1, 600f, 800f), PdfPageSheet(2, 600f, 800f))
        val figures = PageFigures.of(
            listOf(path(100f, 200f, 300f, 400f), path(100f, 200f, 300f, 400f, page = 2)),
            emptyList(),
            two,
        )
        assertEquals(listOf(1, 2), figures.map { it.page })
    }

    @Test
    fun `a page that draws nothing has no figures`() {
        assertTrue(PageFigures.of(emptyList(), listOf(line("Only words.", 100f)), sheets).isEmpty())
    }

    @Test
    fun `a chart keeps its own labels`() {
        // The years under the bars and the counts up the axis stand inside
        // the figure, and a chart that loses them for it is a chart lost.
        val bars = (0 until 5).map { path(120f + it * 60f, 300f - it * 20f, 160f + it * 60f, 400f) }
        val axis = listOf(path(110f, 200f, 111f, 400f), path(110f, 399f, 440f, 400f))
        val labels = listOf(
            line("2019", 415f, x = 125f, xEnd = 155f),
            line("2020", 415f, x = 185f, xEnd = 215f),
            line("40", 250f, x = 90f, xEnd = 108f),
        )
        assertEquals(1, PageFigures.of(bars + axis, labels, sheets).size)
    }

    @Test
    fun `a box drawn round a paragraph is not a figure`() {
        // Four strokes rather than one, so it is not caught for being a
        // single shape — but what stands in it reads across it, which is
        // prose and not a label.
        val callout = listOf(
            path(60f, 200f, 540f, 201f), path(60f, 340f, 540f, 341f),
            path(60f, 200f, 61f, 341f), path(539f, 200f, 540f, 341f),
        )
        val prose = listOf(
            line("A warning that runs the width of the box it is drawn in.", 240f, x = 70f, xEnd = 520f),
            line("And a second line of it, just as wide as the first one.", 268f, x = 70f, xEnd = 520f),
        )
        assertTrue(PageFigures.of(callout, prose, sheets).isEmpty())
    }

    @Test
    fun `a drawing holding a page of prose is not a figure`() {
        val many = (0 until 6).map { path(60f + it, 200f, 540f - it, 600f) }
        val prose = (0 until 12).map { line("Line $it of the page.", 220f + it * 28f, x = 70f, xEnd = 300f) }
        assertTrue(PageFigures.of(many, prose, sheets).isEmpty())
    }
}
