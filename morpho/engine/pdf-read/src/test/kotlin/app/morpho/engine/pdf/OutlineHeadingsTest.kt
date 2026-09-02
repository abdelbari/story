package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A manual, a report, a book: set in one size throughout, with its
 * sections told apart by nothing a reader of the type can see — and an
 * outline in the sidebar that says outright what every one of them is.
 */
class OutlineHeadingsTest {


    @Test
    fun `a document that names its own chapters has them as headings`() {
        val model = PdfReader().extract(manual(withOutline = true))
        val kinds = model.blocks.filterIsInstance<Paragraph>().associate { it.text to it.style.kind }
        assertEquals(ParagraphKind.HEADING_1, kinds["Getting started"])
        assertEquals(ParagraphKind.HEADING_2, kinds["Turning it on"])
        assertEquals(ParagraphKind.HEADING_1, kinds["Maintenance"])
    }

    @Test
    fun `the text under a heading is left as text`() {
        val model = PdfReader().extract(manual(withOutline = true))
        val kinds = model.blocks.filterIsInstance<Paragraph>().associate { it.text to it.style.kind }
        assertEquals(ParagraphKind.BODY, kinds["Read this before you begin the work."])
    }

    @Test
    fun `the same document without an outline reads as it always did`() {
        val model = PdfReader().extract(manual(withOutline = false))
        val kinds = model.blocks.filterIsInstance<Paragraph>().map { it.style.kind }.toSet()
        assertEquals(setOf(ParagraphKind.BODY), kinds, "a heading was found where nothing says there is one")
    }

    /**
     * Two pages set in one size throughout — nothing about the type says
     * which lines are headings — with an outline that says which are.
     */
    private fun manual(withOutline: Boolean): ByteArray {
        PDDocument().use { doc ->
            val first = page(doc, listOf("Getting started", "Read this before you begin the work.", "Turning it on", "Press and hold the button."))
            val second = page(doc, listOf("Maintenance", "Wipe it with a dry cloth."))
            if (withOutline) {
                val outline = PDDocumentOutline()
                doc.documentCatalog.documentOutline = outline
                val started = item("Getting started", first)
                outline.addLast(started)
                started.addLast(item("Turning it on", first))
                outline.addLast(item("Maintenance", second))
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun item(title: String, page: PDPage): PDOutlineItem {
        val item = PDOutlineItem()
        item.title = title
        val destination = PDPageFitDestination()
        destination.page = page
        item.destination = destination
        return item
    }

    private fun page(doc: PDDocument, lines: List<String>): PDPage {
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { content ->
            content.beginText()
            content.setFont(PDType1Font.HELVETICA, 12f)
            content.setLeading(24f)
            content.newLineAtOffset(72f, 720f)
            for ((index, line) in lines.withIndex()) {
                if (index > 0) content.newLine()
                content.showText(line)
            }
            content.endText()
        }
        return page
    }
}
