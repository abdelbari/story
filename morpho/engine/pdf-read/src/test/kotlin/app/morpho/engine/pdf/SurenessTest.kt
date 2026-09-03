package app.morpho.engine.pdf

import app.morpho.engine.layout.FidelityReport
import app.morpho.engine.layout.Paragraph
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
 * Everything read out of an untagged PDF is a reconstruction, so saying
 * so about every block of it says nothing: a reader looking for what to
 * check is handed the whole document. The reader is surer of some of it
 * than of the rest, and now says which — the shakiest first — while never
 * claiming to have read anything from a structure the document does not
 * have.
 */
class SurenessTest {

    @Test
    fun `a heading the document itself names is surer than the prose around it`() {
        val model = PdfReader().extract(page())
        val byText = model.blocks.filterIsInstance<Paragraph>().associateBy { it.text }
        val named = byText.getValue("Getting started")
        val prose = byText.getValue("A line of ordinary prose, of the kind that fills a page.")
        assertTrue(
            named.confidence > prose.confidence,
            "the outline's own heading was no surer than prose: " +
                named.confidence + " against " + prose.confidence,
        )
    }

    @Test
    fun `everything read this way still says it was reconstructed`() {
        val report = FidelityReport.of(PdfReader().extract(page()))
        assertTrue(report.entries.isNotEmpty())
        assertEquals(
            setOf(FidelityReport.Source.RECONSTRUCTED),
            report.entries.map { it.source }.toSet(),
            "a block read off the page claimed to come from somewhere else",
        )
    }

    @Test
    fun `the report puts the least sure first`() {
        val report = FidelityReport.of(PdfReader().extract(page()))
        val doubtful = report.reviewables
        assertTrue(doubtful.size > 1, "nothing was flagged at all")
        assertTrue(
            doubtful.first().confidence <= doubtful.last().confidence,
            "the list is not ordered by how sure the reader is",
        )
    }

    /** One page: a heading the outline names, and prose under it. */
    private fun page(): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.setLeading(18f)
                content.newLineAtOffset(72f, 700f)
                content.showText("Getting started")
                content.newLine()
                content.showText("A line of ordinary prose, of the kind that fills a page.")
                content.endText()
            }
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val item = PDOutlineItem()
            item.title = "Getting started"
            val destination = PDPageFitDestination()
            destination.page = page
            item.destination = destination
            outline.addLast(item)
            document.save(out)
        }
        return out.toByteArray()
    }
}
