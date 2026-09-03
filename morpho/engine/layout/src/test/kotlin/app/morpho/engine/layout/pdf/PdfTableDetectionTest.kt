package app.morpho.engine.layout.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TextDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Synthetic-geometry tests: table detection needs no real PDF. */
class PdfTableDetectionTest {

    private var y = 700f

    private fun line(vararg cells: Pair<String, Float>, page: Int = 1, size: Float = 12f): PdfLine {
        val segments = cells.map { (text, x) ->
            PdfSegment(text = text, xStart = x, xEnd = x + text.length * size * 0.5f)
        }
        val text = cells.joinToString(" ") { it.first }
        return PdfLine(
            text = text,
            x = segments.minOf { it.xStart },
            baselineY = y.also { y += 15f },
            maxFontSize = size,
            page = page,
            segments = segments,
        )
    }

    /** Prose: many word chunks with ordinary word gaps. */
    private fun proseLine(text: String, size: Float = 12f, page: Int = 1): PdfLine {
        var x = 72f
        val segments = text.split(" ").map { word ->
            val width = word.length * size * 0.5f
            PdfSegment(word, x, x + width).also { x += width + size * 0.3f }
        }
        return PdfLine(text, 72f, y.also { y += 15f }, size, page, segments = segments)
    }

    @Test
    fun `aligned cell columns across consecutive lines become a table`() {
        val model = PdfLayout.reconstruct(
            listOf(
                line("City" to 72f, "Population" to 220f, "Country" to 380f),
                line("Rabat" to 72f, "580000" to 220f, "Morocco" to 380f),
                line("Paris" to 72f, "2100000" to 220f, "France" to 380f),
            ),
            confidence = 0.6f,
        )
        val table = model.blocks.filterIsInstance<Table>().single()
        assertEquals(3, table.rows.size)
        assertEquals(
            listOf("City", "Population", "Country"),
            table.rows[0].cells.map { it.blocks.filterIsInstance<Paragraph>().single().text },
        )
        // A table found by the alignment of its columns is the biggest
        // guess the untagged reader makes, and says so: it sits a little
        // below the plain reconstruction everything else starts from.
        assertEquals(0.56f, table.confidence, 0.001f)
        assertTrue(model.blocks.filterIsInstance<Paragraph>().isEmpty())
    }

    @Test
    fun `prose lines merge into one cell and never form a table`() {
        val model = PdfLayout.reconstruct(
            listOf(
                proseLine("ordinary words with ordinary gaps flowing"),
                proseLine("across the page as body text always does"),
            ),
            confidence = 0.6f,
        )
        assertTrue(model.blocks.filterIsInstance<Table>().isEmpty())
        assertEquals(1, model.blocks.filterIsInstance<Paragraph>().size)
    }

    @Test
    fun `a head centred over its columns is part of the table`() {
        // A table's head is centred over columns whose figures are ranged
        // right, so its cells begin nowhere near theirs and need not
        // overlap them by a point. Read on start positions alone it is not
        // part of the table at all, and a converted report keeps its
        // numbers and loses the words that say what they count.
        val model = PdfLayout.reconstruct(
            listOf(
                line("Item" to 100f, "Respondents" to 250f, "Share" to 430f),
                line("Clear" to 72f, "48" to 300f, "80%" to 470f),
                line("Vague" to 72f, "12" to 300f, "20%" to 470f),
            ),
            confidence = 0.6f,
        )
        val table = model.blocks.filterIsInstance<Table>().single()
        assertEquals(3, table.rows.size, "the head was left out: ${table.rows.map { r -> r.cells.map { it.blocks } }}")
        assertEquals(
            listOf("Item", "Respondents", "Share"),
            table.rows.first().cells.map { cell ->
                cell.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
            },
        )
    }

    @Test
    fun `a table the page ruled keeps its rules, and the paragraphs beside it gain none`() {
        // Left in the pile every paragraph is measured against, a table's
        // own border is read as a rule above the paragraph under it and
        // below the one over it: the table came back with no border, and
        // two sentences that never had one gained a line.
        val before = proseLine("a sentence of ordinary prose before the table")
        val head = line("Name" to 72f, "Value" to 250f)
        val first = line("Speed" to 72f, "42" to 250f)
        val second = line("Mass" to 72f, "7" to 250f)
        val after = proseLine("a sentence of ordinary prose after the table")
        val borders = listOf(head.baselineY - 12f, first.baselineY - 6f, second.baselineY + 6f)
            .map { PdfRule(page = 1, y = it, left = 60f, right = 520f) }
        val model = PdfLayout.reconstruct(
            listOf(before, head, first, second, after),
            confidence = 0.6f,
            rules = borders,
        )
        val table = model.blocks.filterIsInstance<Table>().single()
        assertTrue(table.ruled, "the page drew lines round it and the table came back with none")
        assertTrue(
            model.blocks.filterIsInstance<Paragraph>().none { it.style.ruleAbove || it.style.ruleBelow },
            "the table's own border was drawn on the prose beside it",
        )
    }

    @Test
    fun `the columns of a ruled table are measured where the page ruled them`() {
        // Measured from the ink instead, a column of one-word headings
        // under a column of sentences comes back a third of the width the
        // page gave it, and the converted table is a different shape from
        // the one it was read from. A page that drew the sides of its
        // columns said exactly where they are.
        val head = line("Name" to 80f, "Value" to 260f)
        val first = line("Speed" to 80f, "42" to 260f)
        val second = line("Mass" to 80f, "7" to 260f)
        val borders = listOf(head.baselineY - 12f, second.baselineY + 6f)
            .map { PdfRule(page = 1, y = it, left = 60f, right = 520f) }
        // The sides of the cells, drawn a row at a time as a page draws them.
        val sides = listOf(head, first, second).flatMap { row ->
            listOf(60f, 240f, 520f).map { at ->
                PdfDrawing(
                    page = 1, left = at - 0.5f, top = row.baselineY - 12f,
                    right = at + 0.5f, bottom = row.baselineY + 6f,
                )
            }
        }
        val model = PdfLayout.reconstruct(
            listOf(head, first, second),
            confidence = 0.6f,
            rules = borders,
            drawings = sides,
        )
        val table = model.blocks.filterIsInstance<Table>().single()
        assertTrue(table.ruled)
        assertEquals(listOf(180f, 280f), table.columnWidthsPt, "the columns were measured off the ink")
    }

    @Test
    fun `a table the page drew no sides for is measured from its ink`() {
        val head = line("Name" to 80f, "Value" to 260f)
        val first = line("Speed" to 80f, "42" to 260f)
        val model = PdfLayout.reconstruct(listOf(head, first), confidence = 0.6f)
        val widths = model.blocks.filterIsInstance<Table>().single().columnWidthsPt
        assertTrue(widths != null && widths.size == 2, "the columns were not measured at all: $widths")
    }

    @Test
    fun `a table nothing was drawn around is not ruled`() {
        val model = PdfLayout.reconstruct(
            listOf(
                line("Name" to 72f, "Value" to 250f),
                line("Speed" to 72f, "42" to 250f),
            ),
            confidence = 0.6f,
        )
        assertTrue(!model.blocks.filterIsInstance<Table>().single().ruled)
    }

    @Test
    fun `a table is never founded on columns that only nearly line up`() {
        // The guard on the reading above. Two lines with a wide gap in
        // each of them share the clear space in the middle, and that is
        // not enough to call them a table: the rows that prove one line
        // up exactly, and only then may the rows around them join it on
        // the looser reading.
        val model = PdfLayout.reconstruct(
            listOf(
                line("alpha" to 72f, "beta" to 220f),
                line("gamma" to 72f, "delta" to 300f),
                line("epsilon" to 72f, "zeta" to 380f),
            ),
            confidence = 0.6f,
        )
        assertTrue(model.blocks.filterIsInstance<Table>().isEmpty())
    }

    @Test
    fun `misaligned columns stay paragraphs`() {
        val model = PdfLayout.reconstruct(
            listOf(
                line("alpha" to 72f, "beta" to 220f),
                line("gamma" to 72f, "delta" to 300f),
            ),
            confidence = 0.6f,
        )
        assertTrue(model.blocks.filterIsInstance<Table>().isEmpty())
    }

    @Test
    fun `a table between body paragraphs keeps document order and heading detection`() {
        val model = PdfLayout.reconstruct(
            listOf(
                line("Report Title" to 72f, size = 18f),
                proseLine("An introduction paragraph before the table."),
                line("Name" to 72f, "Value" to 250f),
                line("Speed" to 72f, "42" to 250f),
                proseLine("A closing paragraph after the table body."),
                proseLine("It wraps onto a second captured line."),
            ),
            confidence = 0.6f,
        )
        assertEquals(ParagraphKind.HEADING_1, (model.blocks[0] as Paragraph).style.kind)
        assertTrue(model.blocks[1] is Paragraph)
        assertTrue(model.blocks[2] is Table)
        assertTrue(model.blocks[3] is Paragraph)
        assertEquals(4, model.blocks.size)
    }

    @Test
    fun `a page break splits would-be table rows`() {
        val model = PdfLayout.reconstruct(
            listOf(
                line("a" to 72f, "b" to 220f, page = 1),
                line("c" to 72f, "d" to 220f, page = 2),
            ),
            confidence = 0.6f,
        )
        assertTrue(model.blocks.filterIsInstance<Table>().isEmpty())
    }

    @Test
    fun `arabic cells carry RTL direction`() {
        val model = PdfLayout.reconstruct(
            listOf(
                line("اللغة" to 72f, "العينة" to 220f),
                line("العربية" to 72f, "مرحبا" to 220f),
            ),
            confidence = 0.6f,
        )
        val table = model.blocks.filterIsInstance<Table>().single()
        val cell = table.rows[1].cells[0].blocks.filterIsInstance<Paragraph>().single()
        assertEquals(TextDirection.RTL, cell.style.direction)
    }

    @Test
    fun `lines without captured segments fall back to single-cell behavior`() {
        val bare = PdfLine("just a line", 72f, 700f, 12f, 1)
        assertEquals(1, PdfTableDetector.cellsOf(bare).size)
    }
}

/** Image interleaving by position — synthetic geometry, no real PDF. */
class PdfImageInterleaveTest {

    private fun textLine(text: String, y: Float, page: Int = 1): PdfLine =
        PdfLine(text, 72f, y, 12f, page)

    private fun image(y: Float, page: Int = 1) =
        PdfImage(page, y, byteArrayOf(9, 9), "image/png", 40, 20)

    @org.junit.jupiter.api.Test
    fun `an image lands between the paragraphs around it`() {
        val model = PdfLayout.reconstruct(
            lines = listOf(
                textLine("above line one", 100f),
                textLine("above line two", 115f),
                textLine("below line one", 400f),
                textLine("below line two", 415f),
            ),
            confidence = 0.6f,
            images = listOf(image(y = 200f)),
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("Paragraph", "ImageBlock", "Paragraph"),
            model.blocks.map { it.javaClass.simpleName },
        )
        val img = model.blocks[1] as app.morpho.engine.layout.ImageBlock
        org.junit.jupiter.api.Assertions.assertEquals(0.6f, img.confidence)
    }

    @org.junit.jupiter.api.Test
    fun `page order beats y order`() {
        val model = PdfLayout.reconstruct(
            lines = listOf(textLine("page two text", 100f, page = 2)),
            confidence = 0.6f,
            images = listOf(image(y = 700f, page = 1)),
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("ImageBlock", "Paragraph"),
            model.blocks.map { it.javaClass.simpleName },
        )
    }

    @org.junit.jupiter.api.Test
    fun `images alone still produce a model`() {
        val model = PdfLayout.reconstruct(emptyList(), 0.6f, listOf(image(y = 100f)))
        org.junit.jupiter.api.Assertions.assertEquals(1, model.blocks.size)
    }
}
