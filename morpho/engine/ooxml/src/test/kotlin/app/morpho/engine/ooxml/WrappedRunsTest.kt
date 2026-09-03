package app.morpho.engine.ooxml

import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.TextDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A paragraph's runs are not always its children.
 *
 * Word wraps them in whatever it needs to say something about them: a
 * tracked insertion, a content control, custom XML a template put there,
 * a direction override. A wrapper the reader does not know is walked past
 * in silence and its words never reach the document — a paragraph comes
 * back empty from a file that plainly has words in it, and nothing says
 * why.
 *
 * Two wrappers hold what the document used to say and must stay out:
 * `w:del`, the text somebody deleted with changes tracked, and
 * `w:moveFrom`, the text moved away from where it stood. Reading those in
 * would put a deleted clause back into a document that no longer has it.
 */
class WrappedRunsTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun run(text: String) = """<w:r><w:t xml:space="preserve">$text</w:t></w:r>"""

    private fun docx(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                ("""<w:document xmlns:w="$w"><w:body><w:p>$body</w:p></w:body></w:document>""")
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun paragraph(body: String): Paragraph =
        DocxReader.read(docx(body)).blocks.filterIsInstance<Paragraph>().single()

    @Test
    fun `every wrapper a paragraph puts round its runs is read through`() {
        val wrapped = mapOf(
            "an insertion" to """<w:ins w:id="1" w:author="a">${run("inserted")}</w:ins>""",
            "a move to here" to """<w:moveTo w:id="2" w:author="a">${run("moved")}</w:moveTo>""",
            "a smart tag" to """<w:smartTag w:element="x">${run("tagged")}</w:smartTag>""",
            "a content control" to """<w:sdt><w:sdtContent>${run("controlled")}</w:sdtContent></w:sdt>""",
            "custom xml" to """<w:customXml w:element="x">${run("custom")}</w:customXml>""",
            "a direction override" to """<w:dir w:val="rtl">${run("turned")}</w:dir>""",
            "an override of the override" to """<w:bdo w:val="ltr">${run("forced")}</w:bdo>""",
        )
        for ((what, xml) in wrapped) {
            val read = paragraph(run("before ") + xml + run(" after"))
            assertTrue(
                read.text.contains(xml.substringAfter("<w:t xml:space=\"preserve\">").substringBefore("<")),
                "$what lost the words it wrapped: \"${read.text}\"",
            )
            assertEquals("before ", read.runs.first().text, "$what disturbed what was around it")
        }
    }

    @Test
    fun `what a document used to say is not what it says`() {
        val deleted = paragraph(
            run("what it says") +
                """<w:del w:id="3" w:author="a"><w:r><w:delText> and what it said</w:delText></w:r></w:del>"""
        )
        assertEquals("what it says", deleted.text)
        val moved = paragraph(
            run("what it says") +
                """<w:moveFrom w:id="4" w:author="a">${run(" and where it was")}</w:moveFrom>"""
        )
        assertEquals("what it says", moved.text)
    }

    @Test
    fun `a direction override turns the runs it holds and nothing else`() {
        val read = paragraph(
            run("plain ") +
                """<w:dir w:val="rtl">${run("مقلوب")}</w:dir>""" +
                run(" plain again")
        )
        val turned = read.runs.single { it.text == "مقلوب" }
        assertEquals(TextDirection.RTL, turned.direction)
        assertTrue(
            read.runs.filter { it.text != "مقلوب" }.all { it.direction != TextDirection.RTL },
            "the override reached past the runs it holds: ${read.runs.map { it.text to it.direction }}",
        )
    }

    @Test
    fun `custom xml round whole paragraphs keeps the paragraphs`() {
        // At the level of the body a wrapper holds paragraphs rather than
        // runs, and walking past one loses every paragraph it holds.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                (
                    """<w:document xmlns:w="$w"><w:body>""" +
                        """<w:p>${run("before")}</w:p>""" +
                        """<w:customXml w:element="held">""" +
                        """<w:p>${run("inside one")}</w:p>""" +
                        """<w:p>${run("inside two")}</w:p>""" +
                        """</w:customXml>""" +
                        """<w:p>${run("after")}</w:p>""" +
                        "</w:body></w:document>"
                    ).toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        val texts = DocxReader.read(out.toByteArray()).blocks.filterIsInstance<Paragraph>().map { it.text }
        assertEquals(listOf("before", "inside one", "inside two", "after"), texts)
    }

    @Test
    fun `a wrapper with nothing to say about direction says nothing`() {
        val read = paragraph("""<w:sdt><w:sdtContent>${run("held")}</w:sdtContent></w:sdt>""")
        assertEquals("held", read.text)
        assertEquals(null, read.runs.single().direction)
    }
}
