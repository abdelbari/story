package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the app decides about a file before it reads a byte of it.
 *
 * This decision used to live in the view model, where nothing compiles it
 * but CI and nothing tests it at all, and it was wrong: it named `.docx`
 * and the single MIME type the Word writer writes, so a `.docm` — an
 * institution's form or template, saved macro-enabled because the template
 * it came from was — was turned away as an unsupported type by a converter
 * whose reader reads one perfectly.
 */
class DocumentFormatsTest {

    @Test
    fun `every wordprocessing package Word writes is a Word document`() {
        // All four are the same OOXML package with the same document part
        // inside; the macro-enabled ones carry a part this never opens.
        for (name in listOf("Form.docx", "Form.docm", "Template.dotx", "Template.dotm")) {
            assertTrue(DocumentFormats.isWord(name), "$name was not taken for a Word document")
        }
        for (type in listOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-word.document.macroEnabled.12",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "application/vnd.ms-word.template.macroEnabled.12",
        )) {
            assertTrue(DocumentFormats.isWord("no-extension", type), "$type was not taken for one")
        }
    }

    @Test
    fun `a name in any case is the name it is`() {
        assertTrue(DocumentFormats.isWord("REPORT.DOCX"))
        assertTrue(DocumentFormats.isPdf("SCAN.PDF"))
        assertTrue(DocumentFormats.isPlainText("NOTES.MD"))
    }

    @Test
    fun `a format this cannot read is not a Word document`() {
        // A binary .doc, an OpenDocument text, rich text and a Pages
        // document are all word processing and none of them is a package
        // this reads. Saying so is what puts "this file type isn't
        // supported" in front of the reader instead of "couldn't read
        // that file", which would send them back to pick it again.
        for (name in listOf("old.doc", "open.odt", "rich.rtf", "apple.pages", "sheet.xlsx")) {
            assertFalse(DocumentFormats.isWord(name), "$name was taken for a Word document")
        }
        assertFalse(DocumentFormats.isWord("old.doc", "application/msword"))
        // A .docx a provider mislabelled as a .doc is caught by its name.
        assertTrue(DocumentFormats.isWord("new.docx", "application/msword"))
    }

    @Test
    fun `a PDF is a PDF by either its name or its type`() {
        assertTrue(DocumentFormats.isPdf("paper.pdf"))
        assertTrue(DocumentFormats.isPdf("no-extension", "application/pdf"))
        // Older tools still write this one.
        assertTrue(DocumentFormats.isPdf("no-extension", "application/x-pdf"))
        assertFalse(DocumentFormats.isPdf("paper.docx"))
        assertFalse(DocumentFormats.isPdf("paper"))
    }

    @Test
    fun `an unknown binary is not text just because nothing typed it`() {
        // The trap this exists to avoid: a null type taken for text means
        // an unknown binary imported as a document of replacement
        // characters, which looks like a conversion that worked.
        assertFalse(DocumentFormats.isPlainText("mystery", null))
        assertFalse(DocumentFormats.isPlainText("archive.zip", null))
        assertFalse(DocumentFormats.isPlainText("photo.png", "image/png"))
        assertTrue(DocumentFormats.isPlainText("notes.txt"))
        assertTrue(DocumentFormats.isPlainText("notes.md"))
        assertTrue(DocumentFormats.isPlainText("notes.markdown"))
        assertTrue(DocumentFormats.isPlainText("mystery", "text/plain"))
        assertTrue(DocumentFormats.isPlainText("mystery", "text/markdown"))
    }

    @Test
    fun `the three kinds do not overlap`() {
        // The app asks these in an order, and an order only matters where
        // two of them can be true at once. For every name and type a
        // reader is likely to pick, exactly one is.
        for ((name, type) in listOf(
            "paper.pdf" to "application/pdf",
            "form.docm" to "application/vnd.ms-word.document.macroEnabled.12",
            "notes.md" to "text/markdown",
            "notes.txt" to "text/plain",
        )) {
            val said = listOf(
                DocumentFormats.isPdf(name, type),
                DocumentFormats.isWord(name, type),
                DocumentFormats.isPlainText(name, type),
            )
            assertEquals(1, said.count { it }, "$name / $type is more than one kind, or none")
        }
    }

    @Test
    fun `the picker offers every type the converter can read`() {
        // The drift this exists to stop: the view model was widened to
        // accept a kind of file and the picker's own list was not, so the
        // reader was never shown one to pick. A type the converter reads
        // and the picker does not offer is a file the reader cannot
        // convert, whatever the converter can do with it.
        for (type in DocumentFormats.PICKABLE_MIME_TYPES.filter { !it.endsWith("/*") }) {
            assertTrue(
                DocumentFormats.isWord("", type) ||
                    DocumentFormats.isPdf("", type) ||
                    DocumentFormats.isPlainText("", type),
                "the picker offers $type and the converter does not read it",
            )
        }
        for (type in listOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-word.document.macroEnabled.12",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "application/vnd.ms-word.template.macroEnabled.12",
            "application/pdf",
            "text/markdown",
            "text/plain",
        )) {
            assertTrue(
                type in DocumentFormats.PICKABLE_MIME_TYPES,
                "the converter reads $type and the picker does not offer it",
            )
        }
    }

    @Test
    fun `a converted file keeps the name it came in with`() {
        assertEquals("report.docx", DocumentFormats.outputName("report.pdf", "docx"))
        assertEquals("report.md", DocumentFormats.outputName("report.docm", "md"))
        assertEquals("notes.pdf", DocumentFormats.outputName("notes.txt", "pdf"))
        // A name with dots of its own keeps all but the last.
        assertEquals("my.report.v2.docx", DocumentFormats.outputName("my.report.v2.pdf", "docx"))
        // A name with no extension at all is the whole name.
        assertEquals("report.docx", DocumentFormats.outputName("report", "docx"))
        // Arabic names are names.
        assertEquals("الاستمارة.docx", DocumentFormats.outputName("الاستمارة.pdf", "docx"))
    }

    @Test
    fun `a name from anywhere still gets an answer and a file name`() {
        // A display name comes from a provider this app does not control,
        // and some of them are careless. Whatever it is, the three
        // questions must answer and the result must have a name and the
        // extension asked for.
        val pieces = listOf(".", "..", "/", "\\", " ", "\t", "\n", ".pdf", ".DOCX", ".docm",
            "الاستمارة", "x", "\u0000", "\uFFFD", "😀", "")
        val rng = kotlin.random.Random(20260903)
        repeat(3000) {
            val name = (0 until rng.nextInt(0, 6)).joinToString("") { pieces.random(rng) }
            val mime = if (rng.nextBoolean()) null else pieces.random(rng)
            DocumentFormats.isWord(name, mime)
            DocumentFormats.isPdf(name, mime)
            DocumentFormats.isPlainText(name, mime)
            val out = DocumentFormats.outputName(name, "docx")
            assertTrue(out.endsWith(".docx"), "\"$name\" became \"$out\"")
            assertTrue(out.length > ".docx".length, "\"$name\" became a file with no name")
        }
    }

    @Test
    fun `a picture of a page is known by its name or by its type`() {
        for (name in listOf(
            "page.jpg", "PAGE.JPEG", "scan.png", "shot.webp", "IMG_0421.HEIC",
            "photo.heif", "new.avif", "old.bmp", "animated.gif",
        )) {
            assertTrue(DocumentFormats.isImage(name), "\"$name\" is a picture")
        }
        // A provider that names the type is believed whatever the file is
        // called: a camera roll hands over a content URI with no name at
        // all worth reading.
        assertTrue(DocumentFormats.isImage("00001", "image/jpeg"))
        assertTrue(DocumentFormats.isImage("", "image/heic"))
        assertTrue(DocumentFormats.isImage("x", "image/some-format-from-2031"))
    }

    @Test
    fun `a document is not a picture, whatever it is called`() {
        for ((name, mime) in listOf(
            "report.pdf" to DocumentFormats.PDF_MIME,
            "notes.docx" to DocumentFormats.WORD_MIME,
            "readme.md" to "text/markdown",
            "plain.txt" to "text/plain",
            // The trap: a name that ends in a picture's letters without
            // being one, and a picture's name on a Word document.
            "not-a.jpg.docx" to DocumentFormats.WORD_MIME,
        )) {
            assertFalse(DocumentFormats.isImage(name, mime), "\"$name\" is not a picture")
        }
        // And the other way: a picture is none of the other three, or the
        // conversion would go down a path with nothing to read.
        for (picture in listOf("page.jpg" to "image/jpeg", "scan.png" to "image/png")) {
            val (name, mime) = picture
            assertFalse(DocumentFormats.isWord(name, mime))
            assertFalse(DocumentFormats.isPdf(name, mime))
            assertFalse(DocumentFormats.isPlainText(name, mime))
        }
    }

    @Test
    fun `a picture is a type the picker offers`() {
        // The other half of the same decision, and the half that is quietly
        // wrong on its own: a picker that does not offer pictures never
        // shows the reader one, so a converter that reads them changes
        // nothing anybody can see.
        assertTrue(
            DocumentFormats.IMAGE_WILDCARD in DocumentFormats.PICKABLE_MIME_TYPES,
            "the picker offers ${DocumentFormats.PICKABLE_MIME_TYPES}",
        )
        assertTrue(DocumentFormats.isImage("anything.jpg", DocumentFormats.IMAGE_WILDCARD.dropLast(1) + "jpeg"))
    }

    @Test
    fun `a file that is all extension still gets called something`() {
        // A hidden file picked from a folder of them would otherwise be
        // saved as a file named ".docx", which is another hidden file.
        assertEquals("converted.docx", DocumentFormats.outputName(".pdf", "docx"))
        assertEquals("converted.docx", DocumentFormats.outputName("", "docx"))
        assertEquals("converted.docx", DocumentFormats.outputName("   .pdf", "docx"))
        // The print sheet names the job rather than a file, and says so.
        assertEquals("document", DocumentFormats.baseName(".pdf", whenEmpty = "document"))
    }
}
