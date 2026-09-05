package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentFormats
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.DocumentProperties
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A macro-enabled Word document, which is the format an institution's
 * forms and templates arrive in.
 *
 * A `.docm` is a `.docx` with a macro part beside the document and a
 * different content type on it — the same package, the same
 * `word/document.xml`, the same everything this reads. The reader has
 * always read one; the app turned it away on its name, so a reader with a
 * form to convert was told the file type was not supported by a converter
 * that could have converted it.
 *
 * That the macro part is never opened is the other half of it: what comes
 * out is the document without it, which is what somebody converting a
 * macro-enabled form to a PDF wants and what a reader of the result is
 * safer for.
 */
class MacroEnabledTest {

    private val document = DocumentModel(
        blocks = listOf(
            Paragraph(listOf(TextRun("Application", bold = true)), ParagraphStyle(ParagraphKind.HEADING_1)),
            Paragraph(listOf(TextRun("Fill this in and return it."))),
        ),
        properties = DocumentProperties.of("The Form", "An Institution", null, null),
    )

    /** [docx] as Word writes it with a macro in: the macro part, and the content type that says so. */
    private fun macroEnabled(docx: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ZipInputStream(docx.inputStream()).use { source ->
                while (true) {
                    val entry: ZipEntry = source.nextEntry ?: break
                    var bytes = source.readBytes()
                    if (entry.name == "[Content_Types].xml") {
                        bytes = String(bytes, Charsets.UTF_8).replace(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                            "application/vnd.ms-word.document.macroEnabled.main+xml",
                        ).toByteArray(Charsets.UTF_8)
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            // The macro itself — a compound-file header and nothing more,
            // since nothing here is ever going to look inside it.
            zip.putNextEntry(ZipEntry("word/vbaProject.bin"))
            zip.write(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `a macro-enabled document reads as the document it is`() {
        val plain = DocxReader.read(DocxWriter.toByteArray(document))
        val macro = DocxReader.read(macroEnabled(DocxWriter.toByteArray(document)))
        assertEquals(
            plain.blocks.filterIsInstance<Paragraph>().map { it.text },
            macro.blocks.filterIsInstance<Paragraph>().map { it.text },
        )
        assertEquals(plain.properties, macro.properties)
        assertEquals("The Form", macro.properties.title)
    }

    @Test
    fun `the macro itself reaches nothing`() {
        // The part is in the package and never opened: no block of the
        // model carries its bytes, and its name appears nowhere in what
        // the converter writes back out.
        val macro = DocxReader.read(macroEnabled(DocxWriter.toByteArray(document)))
        val written = String(DocxWriter.toByteArray(macro), Charsets.ISO_8859_1)
        assertFalse(written.contains("vbaProject"), "the macro part came back out")
        assertFalse(written.contains("macroEnabled"), "the macro content type came back out")
    }

    @Test
    fun `the app takes a macro-enabled file for a Word document`() {
        // The reading above is worth nothing to a reader whose file the
        // app refuses before opening it, which is what it did.
        assertTrue(DocumentFormats.isWord("Application.docm"))
        assertTrue(
            DocumentFormats.isWord("Application", "application/vnd.ms-word.document.macroEnabled.12")
        )
    }
}
