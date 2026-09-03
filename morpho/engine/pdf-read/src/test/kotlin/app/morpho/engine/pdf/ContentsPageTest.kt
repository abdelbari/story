package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A book, a manual, a thesis exported to PDF opens on a contents page
 * whose every line jumps to a page of the same file. Nothing outside a
 * PDF knows what "page 3" means, so a converter either drops those links
 * or writes them as addresses — and a converted manual's contents page
 * then reads like one and does nothing at all.
 */
class ContentsPageTest {

    /** Three pages: a contents page linking to the two chapters after it. */
    private fun manual(byAction: Boolean): ByteArray {
        PDDocument().use { doc ->
            val pages = (1..3).map { PDPage(PDRectangle.A4) }
            pages.forEach(doc::addPage)
            write(doc, pages[0], listOf("Contents", "1. Beginnings", "2. Endings"))
            write(doc, pages[1], listOf("Beginnings", "How it started."))
            write(doc, pages[2], listOf("Endings", "How it finished."))
            // Each contents line covers the words of one entry.
            for ((index, top) in listOf(676f, 652f).withIndex()) {
                val link = PDAnnotationLink()
                link.rectangle = PDRectangle(70f, top, 140f, 18f)
                val where = PDPageFitWidthDestination().apply { page = pages[index + 1] }
                if (byAction) {
                    link.action = PDActionGoTo().apply { destination = where }
                } else {
                    // A great many producers write the destination on the
                    // link itself and no action at all.
                    link.destination = where
                }
                pages[0].annotations.add(link)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun write(doc: PDDocument, page: PDPage, lines: List<String>) {
        PDPageContentStream(doc, page).use { content ->
            var y = 700f
            for (line in lines) {
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, y)
                content.showText(line)
                content.endText()
                y -= 24f
            }
        }
    }

    @Test
    fun `a contents line leads to the chapter it names`() {
        for (byAction in listOf(true, false)) {
            val model = PdfReader().extract(manual(byAction))
            val paragraphs = model.blocks.filterIsInstance<Paragraph>()
            val links = paragraphs.flatMap { it.runs }.mapNotNull { it.link }.distinct()
            assertEquals(listOf("#page2", "#page3"), links.sorted(), "destination written by action=$byAction")

            // And the chapters answer to those names.
            val named = paragraphs.filter { it.bookmarks.isNotEmpty() }
            assertEquals(listOf("page2", "page3"), named.flatMap { it.bookmarks }.sorted())
            assertTrue(named.any { it.text.contains("Beginnings") }, named.map { it.text }.toString())
            assertTrue(named.any { it.text.contains("Endings") }, named.map { it.text }.toString())
        }
    }

    @Test
    fun `no mark of the reader's own reaches the converted file`() {
        val model = PdfReader().extract(manual(byAction = true))
        val links = model.blocks.filterIsInstance<Paragraph>().flatMap { it.runs }.mapNotNull { it.link }
        assertTrue(links.none { it.startsWith("morpho:") }, links.toString())
    }

    @Test
    fun `a link that leads out of the document is left alone`() {
        // Only the pages of this file become names; the web stays the web.
        val model = PdfReader().extract(manual(byAction = true))
        assertNull(
            model.blocks.filterIsInstance<Paragraph>()
                .flatMap { it.runs }
                .firstOrNull { it.text.contains("How it started") }
                ?.link
        )
    }
}
