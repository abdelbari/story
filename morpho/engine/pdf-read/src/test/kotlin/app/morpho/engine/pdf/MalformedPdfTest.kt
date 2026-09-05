package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.io.ByteArrayOutputStream
import java.time.Duration

/**
 * The PDFs a phone's picker can reach were written by everything from a
 * press to a hand-rolled script, and some of them are wrong. None of it
 * may hang the app or take it down: a document that is broken in one
 * place still has to come back with everything that was right about it.
 *
 * These cover the parts read since the reader learned outlines, markings
 * and filled-in forms.
 */
class MalformedPdfTest {

    @Test
    fun `a damaged file is refused or read, and nothing else`() {
        // A file people convert is a file that may be half a download, a
        // truncated attachment, a byte flipped in transit. Reading one may
        // fail — but it must fail as an exception the app can catch and
        // report, not as an error nothing catches, and not by going round
        // for ever. The damage is made from a fixed seed so a failure can
        // be repeated exactly.
        val whole = paperPdf()
        val random = java.util.Random(20260903L)
        assertTimeoutPreemptively(Duration.ofSeconds(90)) {
            for (round in 0 until 45) {
                val broken = whole.copyOf()
                val damaged = when (round % 3) {
                    // Cut short, as a download that stopped.
                    0 -> broken.copyOf(1 + random.nextInt(broken.size - 1))
                    // Struck here and there, as bytes lost in transit.
                    1 -> broken.also { file ->
                        repeat(1 + random.nextInt(20)) {
                            file[random.nextInt(file.size)] = random.nextInt(256).toByte()
                        }
                    }
                    // A stretch of it gone, as a bad sector reads.
                    else -> broken.also { file ->
                        val at = random.nextInt(file.size)
                        val length = minOf(file.size - at, 1 + random.nextInt(500))
                        java.util.Arrays.fill(file, at, at + length, 0)
                    }
                }
                try {
                    PdfReader().extract(damaged)
                } catch (expected: Exception) {
                    // A file that cannot be read is refused, which is the app's cue to say so.
                } catch (fatal: Throwable) {
                    throw AssertionError("round $round: reading a damaged file threw $fatal", fatal)
                }
            }
        }
    }

    /** Two pages of ordinary text, as small as a real document gets. */
    private fun paperPdf(): ByteArray {
        PDDocument().use { doc ->
            repeat(2) { page ->
                val sheet = PDPage(PDRectangle.A4)
                doc.addPage(sheet)
                PDPageContentStream(doc, sheet).use { content ->
                    var y = 740f
                    for (line in 1..12) {
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 12f)
                        content.newLineAtOffset(72f, y)
                        content.showText("Page ${page + 1}, line $line of the document.")
                        content.endText()
                        y -= 18f
                    }
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `an outline that leads round in a circle does not go round for ever`() {
        val pdf = pdf { document, page ->
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val item = PDOutlineItem()
            item.title = "Round"
            outline.addLast(item)
            // A file that says the entry after this one is this one.
            item.cosObject.setItem(COSName.getPDFName("Next"), item.cosObject)
        }
        val model = assertTimeoutPreemptively(Duration.ofSeconds(20)) { PdfReader().extract(pdf) }
        assertTrue(model.blocks.filterIsInstance<Paragraph>().any { it.text.contains("Page one") })
    }

    @Test
    fun `an outline entry that leads nowhere names nothing`() {
        val pdf = pdf { document, page ->
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val item = PDOutlineItem()
            item.title = "Nowhere in particular"
            outline.addLast(item)
        }
        assertDoesNotThrow { PdfReader().extract(pdf) }
    }

    @Test
    fun `a marking with nothing to mark marks nothing`() {
        val pdf = pdf { _, page ->
            val highlight = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
            page.annotations.add(highlight)
        }
        val runs = PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
        assertTrue(runs.none { it.highlightRgb != null }, "a marking of nowhere marked something")
        assertTrue(runs.any { it.text.contains("Page one") }, "the page was lost with the marking")
    }

    @Test
    fun `a form with no answers in it is left alone`() {
        val pdf = pdf { document, _ ->
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            val field = PDTextField(form)
            field.partialName = "empty"
            form.fields.add(field)
        }
        val text = PdfReader().extract(pdf).blocks.filterIsInstance<Paragraph>()
            .joinToString(" ") { it.text }
        assertEquals("Page one", text.trim())
    }

    @Test
    fun `a document with no pages at all is read as an empty document`() {
        val out = ByteArrayOutputStream()
        PDDocument().use { it.save(out) }
        val model = assertDoesNotThrow { PdfReader().extract(out.toByteArray()) }
        assertTrue(model.blocks.isEmpty())
    }

    /** One page saying "Page one", with [arrange] free to make a mess of the rest. */
    private fun pdf(arrange: (PDDocument, PDPage) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("Page one")
                content.endText()
            }
            arrange(document, page)
            document.save(out)
        }
        return out.toByteArray()
    }
}
