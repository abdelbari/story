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
}
