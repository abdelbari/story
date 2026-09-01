package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The look a reader measured — faces, sizes, raised marks, indents,
 * spacing, tab stops, the page — written into a .docx and read back.
 */
class LookRoundTripTest {

    private val document = DocumentModel(
        blocks = listOf(
            Paragraph(
                runs = listOf(
                    TextRun("ربيحة نبار ", bold = true, fontFamily = "Simplified Arabic", fontSizePt = 12f),
                    TextRun("1", bold = true, fontFamily = "Simplified Arabic", fontSizePt = 8f, superscript = true),
                    TextRun("2", fontFamily = "Simplified Arabic", fontSizePt = 8f, subscript = true),
                ),
                style = ParagraphStyle(
                    direction = TextDirection.RTL,
                    firstLineIndentPt = 36f,
                    spaceBeforePt = 0f,
                    spaceAfterPt = 6f,
                    linePitchPt = 21.5f,
                ),
            ),
            Paragraph(
                runs = listOf(TextRun("تاريخ:2022-04-21\tتاريخ:2022-05-19")),
                style = ParagraphStyle(
                    direction = TextDirection.RTL,
                    startIndentPt = 60f,
                    hangingIndentPt = 30f,
                    tabStopsPt = listOf(182.5f),
                    ruleBelow = true,
                ),
            ),
        ),
        defaultDirection = TextDirection.RTL,
        pageSetup = PageSetup(595.3f, 841.9f, 61.1f, 91.7f, 56.6f, 84.8f),
    )

    @Test
    fun `the writer puts each property where the schema wants it`() {
        val xml = documentXml(DocxWriter.toByteArray(document))
        assertTrue(xml.contains("""<w:rFonts w:ascii="Simplified Arabic" w:hAnsi="Simplified Arabic" w:cs="Simplified Arabic"/><w:b/><w:bCs/><w:sz w:val="24"/><w:szCs w:val="24"/><w:rtl/>"""), xml)
        assertTrue(xml.contains("""<w:sz w:val="16"/><w:szCs w:val="16"/><w:vertAlign w:val="superscript"/><w:rtl/>"""), xml)
        assertTrue(xml.contains("""<w:vertAlign w:val="subscript"/>"""), xml)
        assertTrue(xml.contains("""<w:bidi/><w:spacing w:before="0" w:after="120" w:line="430" w:lineRule="atLeast"/><w:ind w:firstLine="720"/>"""), xml)
        assertTrue(xml.contains("""<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="auto"/></w:pBdr><w:tabs><w:tab w:val="left" w:pos="3650"/></w:tabs><w:bidi/><w:ind w:left="1200" w:hanging="600"/>"""), xml)
        assertTrue(xml.contains("""</w:t><w:tab/><w:t xml:space="preserve">"""), xml)
        assertTrue(xml.contains("""<w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1222" w:right="1696" w:bottom="1834" w:left="1132" """), xml)
    }

    @Test
    fun `the reader gets it all back`() {
        val back = DocxReader.read(DocxWriter.toByteArray(document))
        val (first, second) = back.blocks.filterIsInstance<Paragraph>()

        val (name, mark, lowered) = first.runs
        assertEquals("Simplified Arabic", name.fontFamily)
        assertEquals(12f, name.fontSizePt)
        assertTrue(name.bold)
        assertTrue(mark.superscript && !mark.subscript)
        assertEquals(8f, mark.fontSizePt)
        assertTrue(lowered.subscript && !lowered.superscript)
        assertEquals(36f, first.style.firstLineIndentPt)
        assertEquals(0f, first.style.spaceBeforePt)
        assertEquals(6f, first.style.spaceAfterPt)
        assertEquals(21.5f, first.style.linePitchPt)

        assertEquals("تاريخ:2022-04-21\tتاريخ:2022-05-19", second.text)
        assertEquals(60f, second.style.startIndentPt)
        assertEquals(30f, second.style.hangingIndentPt)
        assertEquals(listOf(182.5f), second.style.tabStopsPt)
        assertTrue(second.style.ruleBelow && !second.style.ruleAbove)

        val page = back.pageSetup
        assertNotNull(page)
        assertEquals(595.3f, page!!.widthPt)
        assertEquals(841.9f, page.heightPt)
        assertEquals(61.1f, page.marginTopPt)
        assertEquals(91.7f, page.marginBottomPt)
        assertEquals(56.6f, page.marginLeftPt)
        assertEquals(84.8f, page.marginRightPt)
    }

    @Test
    fun `a document that measured nothing keeps the defaults`() {
        val xml = documentXml(DocxWriter.toByteArray(DocumentModel(listOf(Paragraph(listOf(TextRun("plain")))))))
        assertTrue(xml.contains("""<w:p><w:r><w:t xml:space="preserve">plain</w:t></w:r></w:p>"""), xml)
        assertTrue(xml.contains("""<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" """), xml)
    }

    private fun documentXml(docx: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("word/document.xml missing")
    }
}
