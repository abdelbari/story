package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * A filled-in form keeps its answers in its fields, not on its pages: the
 * government form, the application, the registration all look filled in
 * and read back blank, because what a reader sees is the field's drawing
 * and what the page holds is the empty form.
 */
class FilledFormTest {

    @Test
    fun `what somebody typed into a form is read with the form`() {
        val text = textOf(PdfReader().extract(filledForm()))
        assertTrue(text.contains("Full name"), "the form itself came back as: " + text)
        assertTrue(text.contains("Amina Haddad"), "the answer was lost: " + text)
    }

    @Test
    fun `the answer is read where it was written`() {
        // The label is on the page and the answer is in a field beside it;
        // drawn onto the page, the two read as one line.
        val model = PdfReader().extract(filledForm())
        val line = model.blocks.filterIsInstance<Paragraph>()
            .firstOrNull { it.text.contains("Amina") }
        assertTrue(line != null, "the answer came back on no line of its own")
        assertTrue(
            line!!.text.contains("Full name"),
            "the answer landed away from its label: " + line.text,
        )
    }

    private fun textOf(model: app.morpho.engine.layout.DocumentModel) =
        model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }

    /** A one-page form with a label drawn on the page and an answer typed into a field. */
    private fun filledForm(tagged: Boolean = false): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 700f)
                content.showText("Full name:")
                content.endText()
            }
            if (tagged) {
                val root = PDStructureTreeRoot()
                doc.documentCatalog.structureTreeRoot = root
                val document = PDStructureElement(StandardStructureTypes.DOCUMENT, root)
                document.page = page
                root.appendKid(document)
            }
            val form = PDAcroForm(doc)
            val resources = PDResources()
            val fontName = resources.add(PDType1Font.HELVETICA).name
            form.defaultResources = resources
            doc.documentCatalog.acroForm = form

            val field = PDTextField(form)
            field.partialName = "fullName"
            field.defaultAppearance = "/" + fontName + " 12 Tf 0 g"
            form.fields.add(field)
            val widget = field.widgets.first()
            widget.rectangle = PDRectangle(140f, 696f, 200f, 18f)
            widget.page = page
            widget.isPrinted = true
            page.annotations.add(widget)
            field.value = "Amina Haddad"

            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun `a tagged form is read from its pages, where the answers are`() {
        // The structure tree was written when the form was empty; what
        // somebody typed was drawn onto the page long afterwards, so the
        // tree cannot know about it and the page is read instead.
        val text = textOf(PdfReader().extract(filledForm(tagged = true)))
        assertTrue(text.contains("Amina Haddad"), "the answer was lost: " + text)
    }
}
