package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Block
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Pages nobody laid out, split into text and furniture.
 *
 * Telling a page's own marks from the document's text is guesswork, and
 * the guess has grown: a line that repeats in the margin, a rule that
 * does, a picture that does, two pages that simply draw the identical
 * thing, two sides of an opening headed differently, a first page that
 * carries none of it. Each was added for a real document and each can be
 * wrong on another, so what is asked here is not that the guess be right
 * — no test can say that — but that whatever it guesses, the document
 * survives it.
 *
 * Above all: a line is text or it is furniture, never neither. Losing a
 * paragraph to the margin is the one failure a reader cannot see and
 * cannot undo.
 */
class PageFurnitureFuzzTest {

    private val height = 800f
    private val width = 600f

    private inner class Pages(private val random: Random) {

        private fun <T> sometimes(one: Int, of: Int, make: () -> T): T? =
            if (random.nextInt(of) < one) make() else null

        private fun line(text: String, page: Int, y: Float, x: Float = 72f) =
            PdfLine(text = text, x = x, baselineY = y, maxFontSize = 10f, page = page, xEnd = x + 200f)

        fun lines(pages: Int): List<PdfLine> = (1..pages).flatMap { page ->
            val out = mutableListOf<PdfLine>()
            // A head: the same words, the same words on each side, or none.
            when (random.nextInt(4)) {
                0 -> {}
                1 -> out += line("The Journal of Something", page, 40f)
                2 -> out += line(
                    if (page % 2 == 1) "The Chapter" else "The Book",
                    page, 40f,
                    x = if (page % 2 == 1) 300f else 72f,
                )
                else -> if (page > 1) out += line("The Journal of Something", page, 40f)
            }
            // The page's own text, always at least one line.
            for (piece in 0..random.nextInt(1, 4)) {
                out += line("Words of page $page, piece $piece.", page, 200f + piece * 20f)
            }
            // A foot: a number, a number and a line, or none.
            when (random.nextInt(3)) {
                0 -> {}
                1 -> out += line("${page + 47}", page, 770f, x = if (page % 2 == 1) 500f else 72f)
                else -> {
                    out += line("${page + 47}", page, 770f)
                    out += line("Volume 12, Issue 1", page, 760f, x = 200f)
                }
            }
            out
        }

        fun rules(pages: Int): List<PdfRule> = (1..pages).flatMap { page ->
            listOfNotNull(
                sometimes(1, 3) { PdfRule(page, y = 46f, left = 60f, right = 540f) },
                sometimes(1, 4) { PdfRule(page, y = 754f, left = 60f, right = 540f) },
                // A page ruled all round draws one among the words too.
                sometimes(1, 5) { PdfRule(page, y = 300f, left = 60f, right = 540f) },
            )
        }

        fun images(pages: Int): List<PdfImage> = (1..pages).flatMap { page ->
            listOfNotNull(
                sometimes(1, 3) { PdfImage(page, topY = 30f, bytes = byteArrayOf(1, 2, 3), mimeType = "image/png", widthPx = 40, heightPx = 20) },
                sometimes(1, 6) { PdfImage(page, topY = 400f, bytes = byteArrayOf(9, 9), mimeType = "image/png", widthPx = 40, heightPx = 20) },
            )
        }
    }

    /** A crop seam whose pages differ from one another, as pages do. */
    private fun seam(sameEverywhere: Boolean) = PageFurniture.Crop { page, left, top, right, bottom, _, _ ->
        PageFurniture.Cropped(
            image = ImageBlock(
                bytes = byteArrayOf(1, if (sameEverywhere) 0 else page.toByte()),
                mimeType = "image/png",
                widthPx = 8,
                heightPx = 4,
                widthPt = right - left,
                heightPt = bottom - top,
            ),
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )
    }

    private fun textIn(blocks: List<Block>): List<String> =
        blocks.filterIsInstance<Paragraph>().map { it.text }

    @Test
    fun `a line is text or it is furniture, and never lost between them`() {
        for (seed in 1..400) {
            val random = Random(seed)
            val pages = Pages(random)
            val count = random.nextInt(1, 7)
            val lines = pages.lines(count)
            val split = PageFurniture.of(
                lines,
                (1..count).map { PdfPageSheet(it, width, height) },
                pages.rules(count),
                seam(sameEverywhere = random.nextBoolean()),
                pages.images(count),
            )
            // Every line of the body is a line that went in, in order, and
            // nothing was invented.
            assertTrue(
                split.body.all { line -> lines.any { it === line } },
                "seed $seed: the body holds a line the pages never had",
            )
            // What left the body left it to become furniture. A page that
            // had text still has it.
            val kept = split.body.map { it.page }.distinct()
            val had = lines.map { it.page }.distinct()
            assertEquals(had, kept, "seed $seed: a page lost every line it had")
        }
    }

    @Test
    fun `what a document says about itself is consistent`() {
        for (seed in 1..400) {
            val random = Random(seed)
            val pages = Pages(random)
            val count = random.nextInt(1, 7)
            val split = PageFurniture.of(
                pages.lines(count),
                (1..count).map { PdfPageSheet(it, width, height) },
                pages.rules(count),
                seam(sameEverywhere = random.nextBoolean()),
                pages.images(count),
            )
            // A left-hand page's own only where there is a right-hand
            // page's to be different from.
            if (split.evenHeader.isNotEmpty()) {
                assertTrue(split.header.isNotEmpty(), "seed $seed: a left head with no right one")
            }
            if (split.evenFooter.isNotEmpty()) {
                assertTrue(split.footer.isNotEmpty(), "seed $seed: a left foot with no right one")
            }
            // A first page of its own only where there is something for it
            // to be without.
            if (split.pageOneIsItsOwn()) {
                assertTrue(
                    split.header.isNotEmpty() || split.footer.isNotEmpty(),
                    "seed $seed: page one is its own, and there is no furniture for it to lack",
                )
            }
            // A head is never the page's own text said twice.
            val body = split.body.map { it.text }.toSet()
            for (line in textIn(split.header) + textIn(split.footer)) {
                assertTrue(
                    line !in body,
                    "seed $seed: \"$line\" is both the document's text and the page's furniture",
                )
            }
        }
    }

    @Test
    fun `the pages nobody laid out reach every answer the reader can give`() {
        // A gate that never reaches the code it guards is a green light
        // over an empty road. These counts say the documents generated
        // here really do produce each kind of answer.
        var heads = 0
        var feet = 0
        var mirrored = 0
        var titlePages = 0
        var pictures = 0
        var bare = 0
        for (seed in 1..400) {
            val random = Random(seed)
            val pages = Pages(random)
            val count = random.nextInt(1, 7)
            val split = PageFurniture.of(
                pages.lines(count),
                (1..count).map { PdfPageSheet(it, width, height) },
                pages.rules(count),
                seam(sameEverywhere = random.nextBoolean()),
                pages.images(count),
            )
            if (split.header.isNotEmpty()) heads++
            if (split.footer.isNotEmpty()) feet++
            if (split.evenHeader.isNotEmpty() || split.evenFooter.isNotEmpty()) mirrored++
            if (split.differentFirstPage) titlePages++
            if ((split.header + split.footer).any { it is ImageBlock }) pictures++
            if (split.header.isEmpty() && split.footer.isEmpty()) bare++
        }
        val reached = "head=$heads foot=$feet mirrored=$mirrored " +
            "titlePage=$titlePages photographed=$pictures bare=$bare of 400"
        assertTrue(heads > 20, reached)
        assertTrue(feet > 20, reached)
        assertTrue(mirrored > 20, reached)
        assertTrue(titlePages > 0, reached)
        assertTrue(pictures > 20, reached)
        assertTrue(bare > 20, reached)
    }

    private fun PageFurniture.Split.pageOneIsItsOwn(): Boolean = differentFirstPage
}
