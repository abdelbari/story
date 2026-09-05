package app.morpho.engine.ooxml

import app.morpho.engine.layout.DocumentModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * A document is a run of sections, and each says where it ends: a
 * section's properties are written on the last paragraph of it, and the
 * last section's on the body itself. Reading the body's alone gives a
 * report of forty portrait pages the shape of the landscape table at the
 * end of it.
 */
class SectionsTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** A4 upright and on its side, in twentieths of a point. */
    private val upright = """<w:pgSz w:w="11906" w:h="16838"/>"""
    private val onItsSide = """<w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/>"""

    @Test
    fun `a report with one landscape page is still a portrait report`() {
        val setup = read(
            """<w:p><w:r><w:t>Page one.</w:t></w:r></w:p>
            <w:p><w:pPr><w:sectPr>""" + upright + """</w:sectPr></w:pPr>
              <w:r><w:t>End of the first section.</w:t></w:r></w:p>
            <w:p><w:pPr><w:sectPr>""" + upright + """</w:sectPr></w:pPr>
              <w:r><w:t>End of the second.</w:t></w:r></w:p>
            <w:p><w:r><w:t>The wide table's page.</w:t></w:r></w:p>
            <w:sectPr>""" + onItsSide + """</w:sectPr>"""
        )
        assertTrue(setup != null, "the page was not measured")
        assertTrue(
            setup!!.heightPt > setup.widthPt,
            "the document took the shape of its last section: " +
                setup.widthPt.roundToInt() + " by " + setup.heightPt.roundToInt(),
        )
    }

    @Test
    fun `a document of one section is read as it always was`() {
        val setup = read(
            """<w:p><w:r><w:t>All of it.</w:t></w:r></w:p>
            <w:sectPr>""" + onItsSide + """</w:sectPr>"""
        )
        assertTrue(setup != null && setup.widthPt > setup.heightPt, "the only section was not read")
    }

    @Test
    fun `the margins come from the section that won`() {
        val setup = read(
            """<w:p><w:pPr><w:sectPr>""" + upright +
                """<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
              </w:sectPr></w:pPr><w:r><w:t>First.</w:t></w:r></w:p>
            <w:p><w:r><w:t>Second.</w:t></w:r></w:p>
            <w:sectPr>""" + onItsSide +
                """<w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720"/></w:sectPr>"""
        )
        // 1440 twentieths of a point is an inch.
        assertEquals(72f, setup!!.marginTopPt)
    }

    private fun read(body: String): app.morpho.engine.layout.PageSetup? {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<w:document xmlns:w="$wNs"><w:body>""" + body + "</w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return DocxReader.read(out.toByteArray()).pageSetup
    }
}
