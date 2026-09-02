package app.morpho.engine.pdf

import app.morpho.engine.layout.Paragraph
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

/**
 * A bank statement, a payslip, an official record: the PDFs people most
 * need converted are the ones that ask for a password. Telling the reader
 * to go and remove the password in some other app is the answer a
 * converter gives when it cannot be bothered; this one asks.
 */
class EncryptedPdfTest {

    @Test
    fun `a document that needs a password says so rather than failing`() {
        val locked = lockedPdf(userPassword = "open sesame")
        val failure = assertThrows<PdfReader.EncryptedDocument> { PdfReader().extract(locked) }
        assertFalse(failure.passwordWasTried, "no password was tried, so none was wrong")
    }

    @Test
    fun `the right password reads the document like any other`() {
        val locked = lockedPdf(userPassword = "open sesame")
        val model = PdfReader().extract(locked, "open sesame")
        val text = model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        assertTrue(text.contains("Statement of account"), "the page came back as: " + text)
    }

    @Test
    fun `a password that does not open it is reported as the wrong one`() {
        val locked = lockedPdf(userPassword = "open sesame")
        val failure = assertThrows<PdfReader.EncryptedDocument> { PdfReader().extract(locked, "guess") }
        assertTrue(failure.passwordWasTried, "a password was tried and did not work")
    }

    @Test
    fun `a document locked against copying alone opens on its own`() {
        // An owner password restricts what may be done with a document; it
        // does not stop it being opened, and the reader must not ask for
        // one it does not need.
        val locked = lockedPdf(userPassword = "", ownerPassword = "the owner")
        val model = PdfReader().extract(locked)
        val text = model.blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
        assertTrue(text.contains("Statement of account"), "the page came back as: " + text)
    }

    @Test
    fun `inspecting a locked document takes the password too`() {
        val locked = lockedPdf(userPassword = "open sesame")
        assertEquals(1, PdfReader().inspect(locked, "open sesame").pageCount)
        assertThrows<PdfReader.EncryptedDocument> { PdfReader().inspect(locked) }
    }

    private fun lockedPdf(userPassword: String, ownerPassword: String = "the owner"): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText("Statement of account")
                content.endText()
            }
            val permissions = AccessPermission()
            permissions.setCanExtractContent(true)
            val policy = StandardProtectionPolicy(ownerPassword, userPassword, permissions)
            policy.encryptionKeyLength = 128
            policy.isPreferAES = true
            doc.protect(policy)
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }
}
