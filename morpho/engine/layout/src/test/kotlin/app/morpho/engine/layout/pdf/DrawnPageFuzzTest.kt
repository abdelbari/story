package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Pages of words with ink drawn all over them.
 *
 * What a page draws is now read as well as what it writes: the grid of a
 * ruled table, the marker of a list, the sides of a column. Each reading
 * is a guess, each was added for a real document, and each can be wrong
 * on another — a chart, a map, a form, a page of dots. What is asked here
 * is not that the guesses be right, which no test can say, but that
 * whatever ink a page has on it the document survives being read: every
 * word still there, in one block or another, and the reading over in a
 * moment rather than in a minute.
 */
class DrawnPageFuzzTest {

    private fun words(seed: Int, at: Int) = "word${seed}x$at some more of it here"

    /** Lines of text with a scatter of drawn ink over them, from a fixed seed. */
    private fun page(seed: Int): Pair<List<PdfLine>, List<PdfDrawing>> {
        val random = Random(seed)
        val lines = (0 until 40).map { at ->
            PdfLine(
                text = words(seed, at),
                x = 60f + random.nextInt(80),
                baselineY = 80f + at * 18f,
                maxFontSize = 11f,
                page = 1 + at / 20,
                xEnd = 300f + random.nextInt(220),
            )
        }
        val drawings = (0 until 300).map {
            val left = random.nextFloat() * 560f
            val top = random.nextFloat() * 780f
            PdfDrawing(
                page = 1 + random.nextInt(2),
                left = left,
                top = top,
                right = left + random.nextFloat() * 200f,
                bottom = top + random.nextFloat() * 200f,
            )
        }
        return lines to drawings
    }

    @Test
    fun `whatever a page draws, none of its words are lost`() {
        for (seed in 1..40) {
            val (lines, drawings) = page(seed)
            val model = PdfLayout.reconstruct(lines, confidence = 0.6f, drawings = drawings)
            val out = StringBuilder()
            fun walk(blocks: List<app.morpho.engine.layout.Block>) {
                for (block in blocks) when (block) {
                    is Paragraph -> out.append(block.text).append(' ')
                    is Table -> block.rows.forEach { row -> row.cells.forEach { walk(it.blocks) } }
                    else -> {}
                }
            }
            walk(model.blocks)
            walk(model.header)
            walk(model.footer)
            val read = out.toString()
            for (at in 0 until 40) {
                assertTrue(read.contains("word${seed}x$at"), "seed $seed lost word${seed}x$at")
            }
        }
    }

    @Test
    fun `a page drawn all over is read in a moment, not a minute`() {
        // Every line of a page is measured against every mark on it, and
        // every cell of a grid against every line of it, so both readings
        // cost the square of the count unless they are bounded.
        val random = Random(7)
        val lines = (0 until 300).map { at ->
            PdfLine(
                text = "a line of the page, number $at",
                x = 60f,
                baselineY = 60f + (at % 50) * 14f,
                maxFontSize = 10f,
                page = 1 + at / 50,
                xEnd = 400f,
            )
        }
        val drawings = (0 until 20_000).map {
            val left = random.nextFloat() * 560f
            val top = random.nextFloat() * 780f
            PdfDrawing(1 + random.nextInt(6), left, top, left + random.nextFloat() * 8f, top + random.nextFloat() * 8f)
        }
        val started = System.nanoTime()
        val model = PdfLayout.reconstruct(lines, confidence = 0.6f, drawings = drawings)
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue(took < 10_000, "reading a page drawn all over took ${took}ms")
        assertEquals(1, model.blocks.count { it is Paragraph }.coerceAtMost(1))
    }

    @Test
    fun `a page that rules a hundred grids is read in a moment too`() {
        // The cell allowance is the page's, not any one grid's. Every grid
        // was bounded from the start; the page stopped being bounded when
        // it stopped being read as a single grid, and nothing keeps a file
        // from ruling ten thousand of them. A hundred grids of a hundred
        // and forty cells each way, each with words standing in it so none
        // bails out early, is two million cells on the one page.
        val n = 140
        val drawings = mutableListOf<PdfDrawing>()
        val lines = mutableListOf<PdfLine>()
        for (grid in 0 until 100) {
            val x = 10f + (grid % 10) * 3000f
            val y = 10f + (grid / 10) * 3000f
            for (r in 0..n) drawings += PdfDrawing(1, x, y + r * 4f - 0.4f, x + n * 4f, y + r * 4f + 0.4f)
            for (c in 0..n) drawings += PdfDrawing(1, x + c * 4f - 0.4f, y, x + c * 4f + 0.4f, y + n * 4f)
            for (at in 0 until 4) {
                lines += PdfLine("grid${grid}word$at and some more of it", x + 2f, y + 8f + at * 8f, 6f, 1, x + 500f)
            }
        }
        val started = System.nanoTime()
        val model = PdfLayout.reconstruct(lines, confidence = 0.6f, drawings = drawings)
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue(took < 10_000, "reading a page of a hundred grids took ${took}ms")
        // Bounded, and still nobody's words are lost: a grid the page
        // cannot pay for is left as the lines it holds.
        val out = StringBuilder()
        fun walk(blocks: List<app.morpho.engine.layout.Block>) {
            for (block in blocks) when (block) {
                is Paragraph -> out.append(block.text).append(' ')
                is Table -> block.rows.forEach { row -> row.cells.forEach { walk(it.blocks) } }
                else -> {}
            }
        }
        walk(model.blocks)
        walk(model.header)
        walk(model.footer)
        val read = out.toString()
        for (grid in 0 until 100) {
            for (at in 0 until 4) {
                assertTrue(read.contains("grid${grid}word$at"), "lost grid${grid}word$at")
            }
        }
    }
}
