package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The preview shows the look a reader measured, the way the file will hold it. */
class HtmlLookTest {

    @Test
    fun `raised marks, indents, spacing and the page reach the preview`() {
        val html = HtmlWriter.write(
            DocumentModel(
                blocks = listOf(
                    Paragraph(
                        runs = listOf(TextRun("ربيحة نبار "), TextRun("1", superscript = true), TextRun("2", subscript = true)),
                        style = ParagraphStyle(firstLineIndentPt = 36f, spaceAfterPt = 6f, linePitchPt = 21.5f),
                    ),
                    Paragraph(
                        runs = listOf(TextRun("إبراهيم، مروان. أسس البحث")),
                        style = ParagraphStyle(startIndentPt = 60f, hangingIndentPt = 30f, ruleAbove = true),
                    ),
                ),
                defaultDirection = TextDirection.RTL,
                pageSetup = PageSetup(595.3f, 841.9f, 61.1f, 91.7f, 56.6f, 84.8f),
            )
        )
        assertTrue(html.contains("<sup>1</sup>"), html)
        assertTrue(html.contains("<sub>2</sub>"), html)
        assertTrue(html.contains("""text-indent:36.0pt;margin-bottom:6.0pt;line-height:21.5pt"""), html)
        assertTrue(html.contains("""padding-inline-start:60.0pt;text-indent:-30.0pt;border-top:0.75pt solid;padding-top:1pt"""), html)
        assertTrue(html.contains("@page{size:595.3pt 841.9pt;margin:61.1pt 84.8pt 91.7pt 56.6pt;}"), html)
        // On screen only: printing takes its margins from the sheet, and
        // setting them on the body as well would apply them twice.
        assertTrue(html.contains("@media screen{body{margin:61.1pt 84.8pt 91.7pt 56.6pt;}}"), html)
    }

    @Test
    fun `text after a tab is placed at its stop`() {
        val html = HtmlWriter.write(
            DocumentModel(
                blocks = listOf(
                    Paragraph(
                        runs = listOf(TextRun("تاريخ الاستلام:2022-04-21\tتاريخ القبول: 2022-05-19\tتاريخ النشر: 2022-06-03")),
                        style = ParagraphStyle(direction = TextDirection.RTL, tabStopsPt = listOf(182.5f, 346.5f)),
                    )
                ),
                defaultDirection = TextDirection.RTL,
            )
        )
        assertTrue(html.contains("""<p style="position:relative">"""), html)
        assertTrue(html.contains("""<span style="position:absolute;inset-inline-start:182.5pt">"""), html)
        assertTrue(html.contains("""<span style="position:absolute;inset-inline-start:346.5pt">"""), html)
        assertTrue(!html.contains("\t"), "the tab character itself has no place in the markup: $html")
    }

    @Test
    fun `a tab without stops keeps its white space`() {
        val html = HtmlWriter.write(DocumentModel(listOf(Paragraph(listOf(TextRun("a\tb"))))))
        assertTrue(html.contains("""<p style="white-space:pre-wrap">a	b</p>"""), html)
    }
    @Test
    fun `a run keeps its colour on the page`() {
        val html = HtmlWriter.write(
            DocumentModel(
                listOf(
                    Paragraph(
                        listOf(
                            TextRun("Heading", colorRgb = 0xC00000),
                            TextRun(" and plain"),
                        )
                    )
                )
            )
        )
        assertTrue(html.contains("color:#c00000"), html)
        assertTrue(!html.contains("color:#000000"), "black is left to the page's own")
    }
    @Test
    fun `a link is a link on the page`() {
        val html = HtmlWriter.write(
            DocumentModel(
                listOf(Paragraph(listOf(TextRun("a@b.co", link = "mailto:a@b.co"))))
            )
        )
        assertTrue(html.contains("<a href=\"mailto:a@b.co\">"), html)
    }
}
