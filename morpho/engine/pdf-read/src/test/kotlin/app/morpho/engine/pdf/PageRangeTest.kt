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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A reader who wants one chapter of a book, or one part of a document too
 * big for the phone to hold whole, should be able to convert what they
 * need rather than all of it.
 */
class PageRangeTest {

    @Test
    fun `only the pages asked for are read`() {
        assertEquals(listOf("Page two", "Page three"), textOf(book(), 2..3))
    }

    @Test
    fun `asking for one page reads one page`() {
        assertEquals(listOf("Page four"), textOf(book(), 4..4))
    }

    @Test
    fun `asking for no range reads the whole document`() {
        assertEquals(
            listOf("Page one", "Page two", "Page three", "Page four", "Page five"),
            textOf(book(), null),
        )
    }

    @Test
    fun `a range that runs past the end reads what is there`() {
        assertEquals(listOf("Page four", "Page five"), textOf(book(), 4..40))
    }

    @Test
    fun `a range of pages the document does not have reads its first`() {
        // Asking for nothing is not an answer; the first page beats an
        // empty document handed back as though that were the file.
        assertEquals(listOf("Page one"), textOf(book(), 40..50))
    }

    @Test
    fun `the part is read as a document of its own`() {
        // The pages of the part are numbered from one, so anything measured
        // per page — the running head, the page a line sits on — counts
        // from the start of what was asked for.
        val model = PdfReader().extract(book(), "", 3..5)
        assertTrue(model.pageSetup != null, "the part was not measured at all")
        assertEquals(3, model.blocks.filterIsInstance<Paragraph>().size)
    }

    @Test
    fun `a part keeps what the whole document said it was`() {
        // The part is lifted out as a document of its own, and a document
        // made here has an empty information dictionary and no language on
        // it. So a chapter of an Arabic paper converted on its own came
        // out nameless, by nobody, and with nothing to say what language
        // to proof it in — every word of it underlined in red by Word.
        val whole = named()
        val part = PdfReader().extract(whole, "", 2..3)
        assertEquals("ar-DZ", part.defaultLanguage)
        assertEquals("الاستمارة في البحث العلمي", part.properties.title)
        assertEquals("ربيحة نبار", part.properties.author)
        assertEquals(
            PdfReader().extract(whole).properties,
            part.properties,
            "the part says something different about itself from the whole",
        )
    }

    /** Three pages of a document that names itself and says what it is written in. */
    private fun named(): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            for (name in listOf("one", "two", "three")) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 12f)
                    content.newLineAtOffset(72f, 700f)
                    content.showText("Page $name")
                    content.endText()
                }
            }
            document.documentCatalog.language = "ar-DZ"
            document.documentInformation.title = "الاستمارة في البحث العلمي"
            document.documentInformation.author = "ربيحة نبار"
            document.save(out)
        }
        return out.toByteArray()
    }

    private fun textOf(pdf: ByteArray, pages: IntRange?): List<String> =
        PdfReader().extract(pdf, "", pages).blocks
            .filterIsInstance<Paragraph>()
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }

    /** Five pages, each saying which it is. */
    private fun book(): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            for (name in listOf("one", "two", "three", "four", "five")) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 12f)
                    content.newLineAtOffset(72f, 700f)
                    content.showText("Page " + name)
                    content.endText()
                }
            }
            document.save(out)
        }
        return out.toByteArray()
    }

    @Test
    fun `the chapters a document names are still named in a part of it`() {
        // A manual set in one size throughout: only its outline says which
        // lines are headings, and a reader converting one chapter needs
        // that as much as one converting the book.
        val pdf = manual()
        val kinds = PdfReader().extract(pdf, "", 2..3).blocks
            .filterIsInstance<Paragraph>()
            .associate { it.text to it.style.kind }
        assertEquals(ParagraphKind.HEADING_1, kinds["Chapter two"])
        assertEquals(ParagraphKind.HEADING_1, kinds["Chapter three"])
        assertEquals(ParagraphKind.BODY, kinds["Words under it."])
    }

    /** Three pages, each a chapter the outline names, all set the same size. */
    private fun manual(): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            for (name in listOf("one", "two", "three")) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 12f)
                    content.setLeading(24f)
                    content.newLineAtOffset(72f, 700f)
                    content.showText("Chapter " + name)
                    content.newLine()
                    content.showText("Words under it.")
                    content.endText()
                }
                val item = PDOutlineItem()
                item.title = "Chapter " + name
                val destination = PDPageFitDestination()
                destination.page = page
                item.destination = destination
                outline.addLast(item)
            }
            document.save(out)
        }
        return out.toByteArray()
    }
}
