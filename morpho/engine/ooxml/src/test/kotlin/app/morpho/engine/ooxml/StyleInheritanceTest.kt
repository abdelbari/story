package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Nearly every real Word document says how it looks in its styles rather
 * than on each run, and a style may be based on another, which is based on
 * the document's own defaults. A reader that only reads what a paragraph
 * writes on itself sees an unstyled document; these hold it to the chain.
 */
class StyleInheritanceTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    @Test
    fun `a run with nothing of its own takes the document defaults`() {
        val doc = readDocx(
            defaults = """<w:rPr><w:rFonts w:ascii="Cambria"/><w:sz w:val="26"/></w:rPr>""",
            body = """<w:p><w:r><w:t>plain words</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertEquals("Cambria", run.fontFamily)
        assertEquals(13f, run.fontSizePt)
    }

    @Test
    fun `a style based on another gathers the whole chain`() {
        val doc = readDocx(
            defaults = """<w:rPr><w:rFonts w:ascii="Cambria"/><w:sz w:val="20"/></w:rPr>""",
            styles = """
                <w:style w:type="paragraph" w:styleId="Body">
                  <w:rPr><w:rFonts w:ascii="Calibri"/><w:sz w:val="22"/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="Quote">
                  <w:basedOn w:val="Body"/>
                  <w:rPr><w:i/><w:color w:val="C00000"/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Quote"/></w:pPr>
                <w:r><w:t>as the poet says</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        // Face and size come from the style it is based on, the slope and
        // the colour from the style itself.
        assertEquals("Calibri", run.fontFamily)
        assertEquals(11f, run.fontSizePt)
        assertTrue(run.italic)
        assertEquals(0xC00000, run.colorRgb)
    }

    @Test
    fun `what a run writes on itself beats what its style says`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="Loud">
                  <w:rPr><w:b/><w:sz w:val="40"/><w:color w:val="0000FF"/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Loud"/></w:pPr>
                <w:r><w:rPr><w:b w:val="0"/><w:sz w:val="24"/></w:rPr>
                <w:t>quietly, though</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertFalse(run.bold)
        assertEquals(12f, run.fontSizePt)
        // What the run leaves alone it still inherits.
        assertEquals(0x0000FF, run.colorRgb)
    }

    @Test
    fun `a run style sits between the paragraph and the run`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="Body">
                  <w:rPr><w:rFonts w:ascii="Calibri"/><w:sz w:val="22"/></w:rPr>
                </w:style>
                <w:style w:type="character" w:styleId="Code">
                  <w:rPr><w:rFonts w:ascii="Consolas"/><w:sz w:val="20"/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Body"/></w:pPr>
                <w:r><w:t>call </w:t></w:r>
                <w:r><w:rPr><w:rStyle w:val="Code"/></w:rPr><w:t>read()</w:t></w:r></w:p>""",
        )
        val runs = doc.blocks.filterIsInstance<Paragraph>().first().runs
        assertEquals("Calibri", runs[0].fontFamily)
        assertEquals("Consolas", runs[1].fontFamily)
        assertEquals(10f, runs[1].fontSizePt)
    }

    @Test
    fun `a heading is known by its outline level when its name is not Heading`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="Titre1">
                  <w:pPr><w:outlineLvl w:val="0"/></w:pPr>
                  <w:rPr><w:b/><w:sz w:val="32"/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="Titre2">
                  <w:basedOn w:val="Titre1"/>
                  <w:pPr><w:outlineLvl w:val="1"/></w:pPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Titre1"/></w:pPr><w:r><w:t>Chapitre</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="Titre2"/></w:pPr><w:r><w:t>Section</w:t></w:r></w:p>""",
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(ParagraphKind.HEADING_1, paragraphs[0].style.kind)
        assertEquals(ParagraphKind.HEADING_2, paragraphs[1].style.kind)
        // The size of the style it is based on reaches the second heading too.
        assertEquals(16f, paragraphs[1].runs.first().fontSizePt)
    }

    @Test
    fun `an Arabic style names the face for its own script`() {
        val doc = readDocx(
            defaults = """<w:rPr><w:rFonts w:ascii="Calibri" w:cs="Traditional Arabic"/>
                <w:sz w:val="22"/><w:szCs w:val="28"/></w:rPr>""",
            styles = """
                <w:style w:type="paragraph" w:styleId="Arabe">
                  <w:pPr><w:bidi/></w:pPr>
                  <w:rPr><w:rtl/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Arabe"/></w:pPr>
                <w:r><w:t>مرحبا</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertEquals("Traditional Arabic", run.fontFamily)
        assertEquals(14f, run.fontSizePt)
    }

    @Test
    fun `styles based on each other in a circle do not hang the reader`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="A">
                  <w:basedOn w:val="B"/><w:rPr><w:b/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="B">
                  <w:basedOn w:val="A"/><w:rPr><w:sz w:val="30"/></w:rPr>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="A"/></w:pPr><w:r><w:t>round we go</w:t></w:r></w:p>""",
        )
        val run = doc.blocks.filterIsInstance<Paragraph>().first().runs.first()
        assertTrue(run.bold)
        assertEquals(15f, run.fontSizePt)
    }

    @Test
    fun `a heading is known by the name Word gives its style in any language`() {
        val doc = readDocx(
            styles = """
                <w:style w:type="paragraph" w:styleId="Ttulo1">
                  <w:name w:val="heading 1"/>
                  <w:rPr><w:b/></w:rPr>
                </w:style>
                <w:style w:type="paragraph" w:styleId="Ttulo">
                  <w:name w:val="Title"/>
                </w:style>
            """,
            body = """<w:p><w:pPr><w:pStyle w:val="Ttulo"/></w:pPr><w:r><w:t>El libro</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr><w:r><w:t>Primero</w:t></w:r></w:p>""",
        )
        val paragraphs = doc.blocks.filterIsInstance<Paragraph>()
        assertEquals(ParagraphKind.TITLE, paragraphs[0].style.kind)
        assertEquals(ParagraphKind.HEADING_1, paragraphs[1].style.kind)
    }

    // ------------------------------------------------------------------

    private fun readDocx(body: String, defaults: String = "", styles: String = ""): DocumentModel {
        val stylesXml = DECLARATION +
            """<w:styles xmlns:w="$wNs">""" +
            (if (defaults.isBlank()) "" else "<w:docDefaults><w:rPrDefault>" + defaults + "</w:rPrDefault></w:docDefaults>") +
            styles +
            "</w:styles>"
        val documentXml = DECLARATION +
            """<w:document xmlns:w="$wNs"><w:body>""" + body + "</w:body></w:document>"
        return DocxReader.read(zipOf("word/styles.xml" to stylesXml, "word/document.xml" to documentXml))
    }

    private fun zipOf(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val DECLARATION = """<?xml version="1.0" encoding="UTF-8"?>"""
    }
}
