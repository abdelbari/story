package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A note is pinned to the foot of its page whatever the text above it
 * does. Counted as the page's own text, a page that stopped half way
 * looks full to the margin — so a paper whose title page carries the
 * corresponding-author note came back with that page and the one after it
 * run together, and the break a reader sees first was lost.
 *
 * Both readings measure how far a page ran, and both had to be taught the
 * same thing: the tagged one from the blocks, which already know a note
 * when they see one, and the untagged one from the short rule a page
 * draws above its notes.
 */
class NotedPageBreakTest {

    private val height = PDRectangle.A4.height
    private val font = PDType1Font.HELVETICA

    /**
     * Two pages. The first stops a third of the way down and carries a
     * note at its foot — a short rule with a small line under it, and the
     * mark raised in the text above, which is what makes it a note rather
     * than a stray paragraph. The second runs to the bottom.
     */
    private fun paper(tagged: Boolean, noted: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val root = if (tagged) PDStructureTreeRoot().also { document.documentCatalog.structureTreeRoot = it } else null
            val holder = root?.let { PDStructureElement(StandardStructureTypes.DOCUMENT, it).also(it::appendKid) }
            var mcid = 0

            /** One piece of a line: its words, where they start, and the size they are set in. */
            fun page(build: (PDPageContentStream, (List<Triple<String, FloatArray, Float>>) -> Unit) -> Unit) {
                val sheet = PDPage(PDRectangle.A4)
                document.addPage(sheet)
                holder?.page = sheet
                PDPageContentStream(document, sheet).use { content ->
                    // One paragraph of the document, drawn as one marked
                    // content so a mark raised beside its words belongs to
                    // the same line, which is what makes it a note's mark.
                    fun paragraph(pieces: List<Triple<String, FloatArray, Float>>) {
                        val element = holder?.let {
                            PDStructureElement(StandardStructureTypes.P, it).also { made ->
                                made.page = sheet
                                it.appendKid(made)
                            }
                        }
                        val properties = COSDictionary().apply { setInt(COSName.MCID, mcid) }
                        if (element != null) content.beginMarkedContent(COSName.P, PDPropertyList.create(properties))
                        for ((text, at, size) in pieces) {
                            content.beginText()
                            content.setFont(font, size)
                            content.newLineAtOffset(at[0], height - at[1])
                            content.showText(text)
                            content.endText()
                        }
                        if (element != null) {
                            content.endMarkedContent()
                            element.appendKid(PDMarkedContent(COSName.P, properties))
                        }
                        mcid++
                    }
                    build(content, ::paragraph)
                }
            }

            // Page one: six lines, then nothing until the note at the foot.
            page { content, paragraph ->
                for (index in 0 until 6) {
                    val text = "A line of the opening page, number $index."
                    val top = 100f + index * 24f
                    val end = 72f + font.getStringWidth(text) / 1000f * 12f
                    if (index < 5) {
                        paragraph(listOf(Triple(text, floatArrayOf(72f, top), 12f)))
                    } else {
                        // The mark raised beside the words of its own line.
                        paragraph(
                            listOf(
                                Triple(text, floatArrayOf(72f, top), 12f),
                                Triple("*", floatArrayOf(end + 1f, top - 4f), 7f),
                            )
                        )
                    }
                }
                if (noted) {
                    content.addRect(72f, height - 706f, 120f, 0.7f)
                    content.fill()
                    // The note's own mark is raised too, which is what
                    // tells a note from a paragraph that starts with a star.
                    paragraph(
                        listOf(
                            Triple("*", floatArrayOf(72f, 711f), 7f),
                            Triple("The note under the rule.", floatArrayOf(78f, 714f), 9f),
                        )
                    )
                }
            }
            // Page two: text all the way down.
            page { _, paragraph ->
                for (index in 0 until 25) {
                    paragraph(
                        listOf(
                            Triple(
                                "A line of the second page, number $index.",
                                floatArrayOf(72f, 100f + index * 24f),
                                12f,
                            )
                        )
                    )
                }
            }
            document.save(out)
        }
        return out.toByteArray()
    }

    private fun bodyOf(pdf: ByteArray): List<Paragraph> =
        PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>()

    private fun brokenAt(pdf: ByteArray): List<String> =
        bodyOf(pdf).filter { it.style.pageBreakBefore }.map { it.text.trim().take(30) }

    @Test
    fun `a page that stopped early is broken on purpose, note at its foot or not`() {
        for (tagged in listOf(false, true)) {
            val plain = brokenAt(paper(tagged, noted = false))
            assertTrue(
                plain.any { it.startsWith("A line of the second page") },
                "tagged=$tagged: the break was lost before the note was even there: $plain",
            )
            val noted = brokenAt(paper(tagged, noted = true))
            assertEquals(
                plain,
                noted,
                "tagged=$tagged: the note at the foot of the first page hid where it broke",
            )
        }
    }

    @Test
    fun `the note itself is not the page's text and does not break it`() {
        for (tagged in listOf(false, true)) {
            val broken = brokenAt(paper(tagged, noted = true))
            assertTrue(
                broken.none { it.contains("note under the rule") },
                "tagged=$tagged: the note was given a break of its own: $broken",
            )
        }
    }
}
