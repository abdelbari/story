package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One scale, and the preview set to it.
 *
 * The app makes a PDF two ways — it draws one, and it hands the preview to
 * the system print sheet — and the two were set to different scales: a
 * first-level heading at 21 points drawn and 20 printed, a third-level one
 * at 13 and 13.5, a title bold drawn and not printed, six points of air
 * under a body paragraph drawn and nine printed. A reader who previews a
 * document, saves it and prints it got three documents.
 */
class TypeScaleTest {

    private val html = HtmlWriter.write(
        DocumentModel(blocks = listOf(Paragraph(listOf(TextRun("A line"))))),
        "A document",
    )

    @Test
    fun `the preview is set to the scale, not to numbers of its own`() {
        with(TypeScale) {
            assertTrue(html.contains("h1{font-size:${pt(sizePt(ParagraphKind.HEADING_1))};}"), html)
            assertTrue(html.contains("h2{font-size:${pt(sizePt(ParagraphKind.HEADING_2))};}"), html)
            assertTrue(html.contains("h3{font-size:${pt(sizePt(ParagraphKind.HEADING_3))};}"), html)
            assertTrue(
                html.contains("h1.doc-title{font-size:${pt(sizePt(ParagraphKind.TITLE))};"),
                html,
            )
            assertTrue(html.contains("font-size:${pt(sizePt(ParagraphKind.BODY))};line-height"), html)
            assertTrue(html.contains("p{margin:0 0 ${pt(spaceAfterPt(ParagraphKind.BODY))};}"), html)
            assertTrue(
                html.contains(
                    "h1,h2,h3{line-height:1.25;margin:${pt(spaceBeforePt(ParagraphKind.HEADING_1))} " +
                        "0 ${pt(spaceAfterPt(ParagraphKind.HEADING_1))};}"
                ),
                html,
            )
        }
    }

    @Test
    fun `a title is not shown bold, and every heading is`() {
        // The preview says so in the one place it can; the drawn page has
        // to say the same, and it asks this.
        assertTrue(html.contains("h1.doc-title{font-size:26pt;font-weight:normal;}"), html)
        assertEquals(false, TypeScale.bold(ParagraphKind.TITLE))
        assertEquals(false, TypeScale.bold(ParagraphKind.BODY))
        for (kind in listOf(ParagraphKind.HEADING_1, ParagraphKind.HEADING_2, ParagraphKind.HEADING_3)) {
            assertEquals(true, TypeScale.bold(kind), "$kind is not set bold")
        }
    }

    @Test
    fun `the scale goes down as the level goes down`() {
        val order = listOf(
            ParagraphKind.TITLE,
            ParagraphKind.HEADING_1,
            ParagraphKind.HEADING_2,
            ParagraphKind.HEADING_3,
            ParagraphKind.BODY,
        )
        val sizes = order.map { TypeScale.sizePt(it) }
        assertEquals(
            sizes.sortedDescending(),
            sizes,
            "a heading is set smaller than the one it sits under: $sizes",
        )
        assertTrue(sizes.distinct().size == sizes.size, "two levels are set at the same size: $sizes")
    }

    @Test
    fun `every kind is on the scale`() {
        // A kind added to the model and not to the scale would be set at
        // whatever a `when` fell through to.
        for (kind in ParagraphKind.entries) {
            assertTrue(TypeScale.sizePt(kind) > 0f, "$kind has no size")
            assertTrue(TypeScale.spaceAfterPt(kind) >= 0f, "$kind has no space under it")
            assertTrue(TypeScale.spaceBeforePt(kind) >= 0f, "$kind has no space over it")
        }
    }

    @Test
    fun `a measurement is written as short as it is exact`() {
        // A stylesheet reading "20.0pt" is valid and reads as a mistake.
        assertEquals("20pt", TypeScale.pt(20f))
        assertEquals("13.5pt", TypeScale.pt(13.5f))
        assertEquals("0pt", TypeScale.pt(0f))
    }
}
