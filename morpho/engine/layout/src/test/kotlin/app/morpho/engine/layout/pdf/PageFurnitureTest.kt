package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.TextRun
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

    /**
     * A head no reader can read: a fake page whose top band holds a rule
     * and no text at all, which is what a running head set in a font the
     * file will not name leaves behind.
     */
    private fun ruledPaper(): List<PdfLine> = (1..4).flatMap { page ->
        listOf(
            line("The words of page $page.", page, 400f),
            line("${page + 47}", page, 770f, x = 290f, xEnd = 310f),
        )
    }

    private fun headRules() = (1..4).map { PdfRule(page = it, y = 46f, left = 60f, right = 540f) }

    /**
     * A crop seam that draws nothing but records what it was asked for.
     *
     * Its pages differ from one another, as pages do: a picture of page
     * two is not a picture of page three, and a seam that answered every
     * page alike would tell the furniture heuristics that every margin
     * repeats.
     */
    private class Asked : PageFurniture.Crop {
        val calls = mutableListOf<FloatArray>()
        var answer: (Int, FloatArray) -> PageFurniture.Cropped? = { page, box ->
            PageFurniture.Cropped(
                image = ImageBlock(
                    bytes = byteArrayOf(1, page.toByte()),
                    mimeType = "image/png",
                    widthPx = 10,
                    heightPx = 10,
                    widthPt = box[2] - box[0],
                    heightPt = box[3] - box[1],
                ),
                left = box[0],
                top = box[1],
                right = box[2],
                bottom = box[3],
            )
        }

        override fun of(
            page: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            masks: List<FloatArray>,
            trim: Boolean,
        ): PageFurniture.Cropped? {
            val box = floatArrayOf(left, top, right, bottom)
            calls += box
            return answer(page, box)
        }
    }

    /**
     * A crop seam that knows where the ink on its pages is: it answers
     * with a picture of whatever falls inside the box it was asked for
     * and was not painted out by a mask, and with nothing at all when
     * none does — which is what a real one does with a blank band.
     */
    private class Drawn(private vararg val ink: FloatArray) : PageFurniture.Crop {
        override fun of(
            page: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            masks: List<FloatArray>,
            trim: Boolean,
        ): PageFurniture.Cropped? {
            fun meets(box: FloatArray) =
                box[0] < right && box[2] > left && box[1] < bottom && box[3] > top
            fun hidden(box: FloatArray) = masks.any {
                it[0] <= box[0] && it[1] <= box[1] && it[2] >= box[2] && it[3] >= box[3]
            }
            val shown = ink.filter { meets(it) && !hidden(it) }
            if (shown.isEmpty()) return null
            val kept = if (trim) {
                floatArrayOf(
                    maxOf(left, shown.minOf { it[0] }), maxOf(top, shown.minOf { it[1] }),
                    minOf(right, shown.maxOf { it[2] }), minOf(bottom, shown.maxOf { it[3] }),
                )
            } else {
                floatArrayOf(left, top, right, bottom)
            }
            return PageFurniture.Cropped(
                image = ImageBlock(
                    bytes = byteArrayOf(2, page.toByte()),
                    mimeType = "image/png",
                    widthPx = 10,
                    heightPx = 10,
                    widthPt = kept[2] - kept[0],
                    heightPt = kept[3] - kept[1],
                ),
                left = kept[0], top = kept[1], right = kept[2], bottom = kept[3],
            )
        }
    }

    /** Where the head's own words are drawn, and the rule under them. */
    private val headInk = floatArrayOf(72f, 32f, 300f, 42f)
    private val headRuleInk = floatArrayOf(60f, 46f, 540f, 46.6f)

    @Test
    fun `a head the page's words account for is kept as words, with its rule`() {
        // Every mark in the band is one the reader read: the words of the
        // head and the line ruled under them. Photographing it would hand
        // back a header that cannot be edited, searched, or reflowed, in
        // place of a header that says the same thing and can.
        val split = PageFurniture.of(
            paper(), sheets(4), headRules(), Drawn(headInk, headRuleInk),
        )
        val head = split.header.single() as Paragraph
        assertEquals("The Journal of Something", head.text)
        assertTrue(head.style.ruleBelow, "the line the page ruled under the head was lost")
        assertTrue(split.header.none { it is ImageBlock }, "a picture of words that were read")
    }

    @Test
    fun `a head with more drawn in it than was read is photographed`() {
        // The same band, and a mark in it no reading of the words accounts
        // for: a logo beside the head, or the letters of one drawn as
        // outlines. Only a picture says what the band says.
        val logo = floatArrayOf(400f, 30f, 460f, 44f)
        val split = PageFurniture.of(
            paper(), sheets(4), headRules(), Drawn(headInk, headRuleInk, logo),
        )
        assertTrue(
            split.header.any { it is ImageBlock },
            "the head was given as words that leave the logo out: ${split.header}",
        )
    }

    @Test
    fun `a head with no text a reader can read is photographed`() {
        val crop = Asked()
        val split = PageFurniture.of(ruledPaper(), sheets(4), headRules(), crop)
        assertEquals(1, split.header.size, "the head is there even though not one word of it read")
        assertTrue(split.header.single() is ImageBlock, "and it is the page itself, not a guess at it")
        assertEquals(
            listOf("The words of page 1.", "The words of page 2.", "The words of page 3.", "The words of page 4."),
            split.body.map { it.text },
        )
    }

    @Test
    fun `the band asked for reaches the page's edge and stops at its text`() {
        val crop = Asked()
        PageFurniture.of(ruledPaper(), sheets(4), headRules(), crop)
        val head = crop.calls.first()
        assertEquals(0f, head[1], "nothing in the file says where an unreadable head begins")
        assertTrue(head[3] < 400f, "and it may not reach the page's own first line")
    }

    @Test
    fun `a rule inside the page's text is not a head`() {
        val crop = Asked()
        // The rule is where a page ruled all round would draw one, below
        // the first line of the text rather than above it.
        val inside = (1..4).map { PdfRule(page = it, y = 402f, left = 60f, right = 540f) }
        val split = PageFurniture.of(ruledPaper(), sheets(4), inside, crop)
        assertTrue(split.header.isEmpty(), "photographing past it would take the page's text with it")
        assertEquals(4, split.body.size)
    }

    @Test
    fun `a page that will not draw keeps whatever text was read`() {
        val crop = Asked()
        crop.answer = { _, _ -> null }
        val split = PageFurniture.of(paper(), sheets(4), headRules(), crop)
        assertEquals(
            listOf("The Journal of Something"),
            split.header.filterIsInstance<Paragraph>().map { it.text },
            "a head that could not be photographed still had words of its own",
        )
    }

    @Test
    fun `the number is cut out of the photograph and written as a field`() {
        val crop = Asked()
        val numbered = (1..4).flatMap { page ->
            listOf(
                line("The words of page $page.", page, 400f),
                PdfLine(
                    text = "${page + 47} Some Journal",
                    x = 60f,
                    baselineY = 770f,
                    maxFontSize = 10f,
                    page = page,
                    xEnd = 300f,
                    segments = listOf(
                        PdfSegment("${page + 47}", 60f, 80f),
                        PdfSegment("Some Journal", 90f, 300f),
                    ),
                ),
            )
        }
        val footRules = (1..4).map { PdfRule(page = it, y = 754f, left = 60f, right = 540f) }
        val split = PageFurniture.of(numbered, sheets(4), footRules, crop)
        val runs = split.footer.filterIsInstance<Paragraph>().single().runs
        assertEquals(
            listOf(RunField.PAGE_NUMBER),
            runs.mapNotNull { it.field },
            "every page must go on numbering itself",
        )
        assertTrue(runs.any { it.image != null }, "and the rest of the foot is the page as it was printed")
        assertEquals(48, split.firstPageNumber)
        // The photograph is cut beside where the digits sat, so the number
        // is not printed into it as well as written beside it.
        assertTrue(crop.calls.any { it[0] > 80f }, "the picture starts past the digits")
    }

    /**
     * A head the reader could read but the renderer could not draw. The
     * tagged reader hands over both, and what comes back must never be
     * nothing: a phone out of room to render a page is common, and a
     * header that disappears without a word is indistinguishable from one
     * the converter never looked for.
     */
    private fun refusing() = PageFurniture.Crop { _, _, _, _, _, _, _ -> null }

    private val head = listOf(TextRun("The Journal of Something"))

    @Test
    fun `a head that would not draw is written as the words it says`() {
        val blocks = PageFurniture.drawn(
            crop = refusing(),
            page = 1,
            box = floatArrayOf(72f, 30f, 500f, 50f),
            pageWidth = width,
            left = 72f,
            right = 500f,
            number = null,
            rtl = false,
            words = head,
        )
        assertEquals(
            listOf("The Journal of Something"),
            blocks.filterIsInstance<Paragraph>().map { it.text },
        )
    }

    @Test
    fun `a numbered head that would not draw keeps both its words and its number`() {
        val blocks = PageFurniture.drawn(
            crop = refusing(),
            page = 1,
            box = floatArrayOf(72f, 750f, 500f, 780f),
            pageWidth = width,
            left = 72f,
            right = 500f,
            number = PageFurniture.Numbered(
                field = TextRun("48", field = RunField.PAGE_NUMBER),
                box = floatArrayOf(80f, 750f, 100f, 780f),
            ),
            rtl = false,
            words = head,
        )
        val runs = blocks.filterIsInstance<Paragraph>().single().runs
        assertEquals(listOf(RunField.PAGE_NUMBER), runs.mapNotNull { it.field })
        assertTrue(runs.any { it.text == "The Journal of Something" }, "and the words beside it")
    }

    @Test
    fun `a head with neither a picture nor a word is nothing at all`() {
        val blocks = PageFurniture.drawn(
            crop = refusing(),
            page = 1,
            box = floatArrayOf(72f, 30f, 500f, 50f),
            pageWidth = width,
            left = 72f,
            right = 500f,
            number = null,
            rtl = false,
        )
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `a margin that draws the same thing on every page is a head, read or not`() {
        // No line in the margin and no rule: a head drawn as artwork, or
        // set in a font the file will not name. All that is left is that
        // the page draws the same thing there every time.
        val crop = Asked()
        crop.answer = { _, box ->
            PageFurniture.Cropped(
                image = ImageBlock(byteArrayOf(7, 7), "image/png", 10, 4, box[2] - box[0], 20f),
                left = 60f,
                top = 30f,
                right = 540f,
                bottom = 50f,
            )
        }
        val split = PageFurniture.of(ruledPaper(), sheets(4), emptyList(), crop)
        assertEquals(1, split.header.size, "the margin draws something, on page after page")
        assertTrue(split.header.single() is ImageBlock)
        assertEquals(30f, split.headerDistancePt)
        assertEquals(
            listOf("The words of page 1.", "The words of page 2.", "The words of page 3.", "The words of page 4."),
            split.body.map { it.text },
            "and none of the page's own text was taken with it",
        )
    }

    @Test
    fun `a margin that draws something different on each page is not a head`() {
        // A figure that happens to sit high on one page is not furniture,
        // and the test that tells them apart is that furniture repeats.
        val crop = Asked()
        val split = PageFurniture.of(ruledPaper(), sheets(4), emptyList(), crop)
        assertTrue(split.header.isEmpty(), "pages that differ have nothing in common to keep")
    }

    @Test
    fun `a margin the page draws nothing in is left empty`() {
        val crop = Asked()
        crop.answer = { _, _ -> null }
        val split = PageFurniture.of(ruledPaper(), sheets(4), emptyList(), crop)
        assertTrue(split.header.isEmpty(), "nothing is drawn at the head, so there is no head")
        // The foot is a different matter: its number was read as text, and
        // a page that will not draw takes nothing away from that.
        assertEquals(
            listOf(RunField.PAGE_NUMBER),
            split.footer.filterIsInstance<Paragraph>().single().runs.mapNotNull { it.field },
        )
        assertEquals(4, split.body.size)
    }

    @Test
    fun `the margin looked at is the margin, not half the page`() {
        val crop = Asked()
        PageFurniture.of(ruledPaper(), sheets(4), emptyList(), crop)
        val head = crop.calls.first { it[1] == 0f }
        assertTrue(head[3] <= 0.12f * height, "a wide top margin is not a head half a page tall: ${head[3]}")
    }
}
