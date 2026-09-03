package app.morpho.engine.ooxml

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.RunField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A field written the long way round.
 *
 * Word writes a page number, and often a link, as a begin, an
 * instruction, a separator, the result it last worked out, and an end —
 * five runs where the short form is one element. Read as the plain text
 * of that result, a footer that numbers its pages says the same number on
 * every page it is stamped on, and a link written this way leads nowhere.
 */
class ComplexFieldTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun run(text: String) = """<w:r><w:t xml:space="preserve">$text</w:t></w:r>"""

    private fun field(instruction: String, result: String) =
        """<w:r><w:fldChar w:fldCharType="begin"/></w:r>""" +
            """<w:r><w:instrText xml:space="preserve">$instruction</w:instrText></w:r>""" +
            """<w:r><w:fldChar w:fldCharType="separate"/></w:r>""" +
            run(result) +
            """<w:r><w:fldChar w:fldCharType="end"/></w:r>"""

    private fun paragraph(body: String): Paragraph {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                ("""<w:document xmlns:w="$w"><w:body><w:p>$body</w:p></w:body></w:document>""")
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return DocxReader.read(out.toByteArray()).blocks.filterIsInstance<Paragraph>().single()
    }

    @Test
    fun `a page number written the long way round is a page number`() {
        val read = paragraph(run("Page ") + field(" PAGE  \\* MERGEFORMAT ", "48") + run(" of it"))
        assertEquals("Page 48 of it", read.text)
        val field = read.runs.single { it.field == RunField.PAGE_NUMBER }
        assertEquals("48", field.text, "the number the document last showed is what stands in for it")
        assertTrue(
            read.runs.filter { it.text.contains("Page") || it.text.contains("of it") }
                .all { it.field == null },
            "the words around the field were made a field too",
        )
    }

    @Test
    fun `the instruction is not the document's words`() {
        val read = paragraph(field(" PAGE  \\* MERGEFORMAT ", "48"))
        assertEquals("48", read.text, "the instruction was read as text")
    }

    @Test
    fun `a link written as a field points where it says`() {
        val read = paragraph(field(""" HYPERLINK "https://example.com/x" """, "the paper"))
        assertEquals("the paper", read.text)
        assertEquals("https://example.com/x", read.runs.single().link)
    }

    @Test
    fun `a link into the document itself points at the place it names`() {
        val read = paragraph(field(""" HYPERLINK \l "chapter3" """, "see chapter three"))
        assertEquals("#chapter3", read.runs.single().link)
    }

    @Test
    fun `a field the reader has no use for is left as the words it worked out to`() {
        val read = paragraph(run("Last saved ") + field(" DATE \\@ \"d MMMM yyyy\" ", "3 September 2026"))
        assertEquals("Last saved 3 September 2026", read.text)
        assertTrue(read.runs.all { it.field == null && it.link == null })
    }

    @Test
    fun `a field with no result at all leaves nothing behind`() {
        val read = paragraph(
            run("before ") +
                """<w:r><w:fldChar w:fldCharType="begin"/></w:r>""" +
                """<w:r><w:instrText> PAGE </w:instrText></w:r>""" +
                """<w:r><w:fldChar w:fldCharType="end"/></w:r>""" +
                run("after")
        )
        assertEquals("before after", read.text)
        assertNull(read.runs.firstOrNull { it.field != null })
    }

    @Test
    fun `a field inside a field's result does not swallow what follows`() {
        val read = paragraph(
            field(" PAGE ", "48") +
                run(" and ") +
                field(""" HYPERLINK "https://example.com/y" """, "a link")
        )
        assertEquals("48 and a link", read.text)
        assertEquals(RunField.PAGE_NUMBER, read.runs.first { it.text == "48" }.field)
        assertEquals("https://example.com/y", read.runs.first { it.text == "a link" }.link)
        assertNull(read.runs.first { it.text == " and " }.field)
    }
}
