package app.morpho.engine.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlainTextImporterTest {

    private fun paragraphs(model: DocumentModel): List<Paragraph> =
        model.blocks.filterIsInstance<Paragraph>()

    @Test
    fun `blank lines split paragraphs and soft wraps are unwrapped`() {
        val model = PlainTextImporter.import("line one\nline two\n\nsecond paragraph\n")
        val paras = paragraphs(model)
        assertEquals(2, paras.size)
        assertEquals("line one line two", paras[0].text)
        assertEquals("second paragraph", paras[1].text)
    }

    @Test
    fun `windows and old mac line endings are normalized`() {
        val model = PlainTextImporter.import("a\r\nb\r\rc")
        val paras = paragraphs(model)
        assertEquals(listOf("a b", "c"), paras.map { it.text })
    }

    @Test
    fun `markdown headings map to heading kinds`() {
        val model = PlainTextImporter.import("# One\n## Two\n### Three\nbody\n")
        val paras = paragraphs(model)
        assertEquals(ParagraphKind.HEADING_1, paras[0].style.kind)
        assertEquals(ParagraphKind.HEADING_2, paras[1].style.kind)
        assertEquals(ParagraphKind.HEADING_3, paras[2].style.kind)
        assertEquals(ParagraphKind.BODY, paras[3].style.kind)
        assertEquals("One", paras[0].text)
    }

    @Test
    fun `bullet and numbered items become list paragraphs`() {
        val model = PlainTextImporter.import("- first\n* second\n1. third\n2) fourth\n")
        val paras = paragraphs(model)
        assertEquals(ListMarker.BULLET, paras[0].style.listMarker)
        assertEquals(ListMarker.BULLET, paras[1].style.listMarker)
        assertEquals(ListMarker.NUMBERED, paras[2].style.listMarker)
        assertEquals(ListMarker.NUMBERED, paras[3].style.listMarker)
        assertEquals("third", paras[2].text)
    }

    @Test
    fun `a year at the start of a sentence is not a numbered item`() {
        val model = PlainTextImporter.import("2024. That was the year it began.")
        val paras = paragraphs(model)
        assertNull(paras[0].style.listMarker)
    }

    @Test
    fun `arabic paragraphs are tagged RTL and latin ones LTR`() {
        val model = PlainTextImporter.import("Hello world\n\nمرحبا بالعالم\n")
        val paras = paragraphs(model)
        assertEquals(TextDirection.LTR, paras[0].style.direction)
        assertEquals(TextDirection.RTL, paras[1].style.direction)
    }

    @Test
    fun `mostly arabic document gets an RTL default direction`() {
        val model = PlainTextImporter.import("مرحبا\n\nالعالم\n\nHello\n")
        assertEquals(TextDirection.RTL, model.defaultDirection)
    }

    @Test
    fun `mostly latin document keeps an LTR default direction`() {
        val model = PlainTextImporter.import("Hello\n\nWorld\n\nمرحبا\n")
        assertEquals(TextDirection.LTR, model.defaultDirection)
    }
}
