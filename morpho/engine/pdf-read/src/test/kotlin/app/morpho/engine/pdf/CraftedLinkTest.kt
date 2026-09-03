package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A PDF says where its links point, and a PDF can be made to say anything.
 *
 * The reading carries a link annotation's address straight onto the run it
 * covers, and the writers carry it into the converted document — so a
 * crafted PDF is a way to put an address of somebody else's choosing into a
 * file the reader will open in Word. This is the reading end of that: what
 * the model comes back holding.
 */
class CraftedLinkTest {

    private fun linked(target: String) =
        PdfReader().extract(pageLinking(target))
            .blocks.filterIsInstance<Paragraph>().flatMap { it.runs }
            .filter { it.text.contains("linked") }

    @Test
    fun `an ordinary address is carried onto the words`() {
        val runs = linked("https://example.org/paper")
        assertTrue(runs.isNotEmpty(), "the words were not read at all")
        assertEquals("https://example.org/paper", runs.first { it.link != null }.link)
    }

    @Test
    fun `a crafted address is carried too, and refused where it would be written`() {
        // The reading is faithful: it says what the file says, which is
        // what a reading is for, and the model is not the thing anybody
        // opens. What must not happen is that address reaching a converted
        // document — which is [app.morpho.engine.layout.Links.writable]'s
        // job, checked where each writer writes. Kept here so the two
        // halves of that arrangement are written down together: if this
        // reading ever starts dropping the address itself, the tests over
        // there stop proving anything about a real file.
        val runs = linked("""\\attacker.example\share\x""")
        assertTrue(runs.isNotEmpty(), "the words were not read at all")
        assertEquals("""\\attacker.example\share\x""", runs.first { it.link != null }.link)
        assertTrue(
            !app.morpho.engine.layout.Links.writable(runs.first { it.link != null }.link!!),
            "the writers would carry a crafted address into the converted document",
        )
    }

    /** A page of a few words, with a link annotation over them pointing at [target]. */
    private fun pageLinking(target: String): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A5)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(60f, 500f)
                content.showText("these are the linked words of the page")
                content.endText()
            }
            val link = PDAnnotationLink()
            link.rectangle = PDRectangle(58f, 496f, 240f, 16f)
            link.action = PDActionURI().also { it.uri = target }
            page.annotations.add(link)
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }
}
