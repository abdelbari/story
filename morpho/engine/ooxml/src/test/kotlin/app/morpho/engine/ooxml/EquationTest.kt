package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Word writes an equation in a language of its own, in the paragraph
 * beside the runs rather than inside one, so a reader that walks Word's
 * own elements walks straight past it — and a paper whose formulas are
 * gone is a paper that no longer says what it said. Nothing this
 * converter writes can hold an equation as an equation, so it is written
 * out the way it reads.
 */
class EquationTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val mNs = "http://schemas.openxmlformats.org/officeDocument/2006/math"

    @Test
    fun `an equation in a line of text stays in the line`() {
        val text = read(
            """<w:p><w:r><w:t>Einstein wrote </w:t></w:r>
              <m:oMath>
                <m:r><m:t>E=mc</m:t></m:r>
                <m:sSup><m:e><m:r><m:t>c</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup>
              </m:oMath>
              <w:r><w:t> and left.</w:t></w:r></w:p>"""
        )
        assertEquals("Einstein wrote E=mcc^2 and left.", text)
    }

    @Test
    fun `a fraction reads as one`() {
        val text = read(
            """<w:p><m:oMath><m:f>
                <m:num><m:r><m:t>a+b</m:t></m:r></m:num>
                <m:den><m:r><m:t>2</m:t></m:r></m:den>
              </m:f></m:oMath></w:p>"""
        )
        assertEquals("(a+b)/2", text)
    }

    @Test
    fun `a root reads as one`() {
        val text = read(
            """<w:p><m:oMath><m:rad>
                <m:e><m:r><m:t>x+1</m:t></m:r></m:e>
              </m:rad></m:oMath></w:p>"""
        )
        assertEquals("\u221a(x+1)", text)
    }

    @Test
    fun `an equation on a line of its own is a paragraph of its own`() {
        val text = read(
            """<w:p><m:oMathPara><m:oMath>
                <m:sSub><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sub><m:r><m:t>i</m:t></m:r></m:sub></m:sSub>
                <m:r><m:t> = 1</m:t></m:r>
              </m:oMath></m:oMathPara></w:p>"""
        )
        assertEquals("x_i = 1", text)
    }

    private fun read(body: String): String {
        val declaration = """<?xml version="1.0" encoding="UTF-8"?>"""
        val documentXml = declaration +
            """<w:document xmlns:w="$wNs" xmlns:m="$mNs"><w:body>""" + body + "</w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return DocxReader.read(out.toByteArray())
            .blocks.filterIsInstance<Paragraph>().joinToString(" ") { it.text }
    }
}
