package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A conversion kept against the death of the process holding it, and read back as it was. */
class AutosaveTest {

    private val document = DocumentModel(
        listOf(
            Paragraph(listOf(TextRun("The form "), TextRun("arrives", bold = true, commentIds = listOf(1)))),
            Table(listOf(TableRow(listOf(TableCell(listOf(Paragraph(listOf(TextRun("cell")))), columnSpan = 2)))), ruled = false),
            ImageBlock(byteArrayOf(1, 2, 3), "image/png", 1, 1, description = "a seal"),
        ),
        comments = listOf(Comment(1, "check", "R")),
        pageSetup = PageSetup(595f, 842f, 72f, 72f, 60f, 60f),
    )

    @Test
    fun `what is kept comes back exactly, with its name and its kind`() {
        val text = Autosave.write("paper.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", document)!!
        val kept = Autosave.read(text)
        assertEquals("paper.docx", kept.name)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", kept.mimeType)
        assertEquals(document, kept.document)
        assertTrue(text.contains("\"kind\":\"conversion\""))
    }

    @Test
    fun `what is not a kept conversion is refused with the one exception`() {
        for (bad in listOf("", "null", "[]", "{}", """{"morpho":1}""", """{"morpho":2,"kind":"conversion","name":"a","mimeType":"b","document":{"morpho":1}}""",
            """{"morpho":1,"kind":"session","name":"a","mimeType":"b","document":{"morpho":1}}""", """{"morpho":1,"kind":"conversion","name":7,"mimeType":"b","document":{"morpho":1}}""",
            """{"morpho":1,"kind":"conversion","name":"a","mimeType":"b","document":{"morpho":1,"blocks":[{"kind":"song"}]}}""", "{" )) {
            assertThrows(Json.Malformed::class.java, { Autosave.read(bad) }, bad.take(60))
        }
        // The plainest conversion there is: a document with nothing in it.
        assertEquals(DocumentModel(emptyList()), Autosave.read("""{"morpho":1,"kind":"conversion","name":"a","mimeType":"b","document":{"morpho":1}}""").document)
    }

    @Test
    fun `too large to keep is not kept, and a name too long is cut`() {
        val huge = DocumentModel(listOf(ImageBlock(ByteArray(Autosave.MOST_LENGTH / 4 * 3 + 10), "image/png", 1, 1)))
        assertNull(Autosave.write("x", "y", huge))
        val long = Autosave.read(Autosave.write("n".repeat(1_000), "m", DocumentModel(emptyList()))!!)
        assertEquals(255, long.name.length)
    }
}
