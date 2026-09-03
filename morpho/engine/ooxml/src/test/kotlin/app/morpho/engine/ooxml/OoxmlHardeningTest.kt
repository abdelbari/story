package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Regression tests for the adversarial-review fixes in :ooxml. */
class OoxmlHardeningTest {

    private val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun entries(docx: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun zipOf(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun numbered(text: String) =
        Paragraph(listOf(TextRun(text)), ParagraphStyle(listMarker = ListMarker.NUMBERED))

    private fun directChildren(parent: Element, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == wNs && node.localName == localName) result += node
            node = node.nextSibling
        }
        return result
    }

    // ------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------

    @Test
    fun `every numbered list num instance carries a level-0 startOverride`() {
        val doc = DocumentModel(
            listOf(numbered("a"), Paragraph(listOf(TextRun("gap"))), numbered("b"))
        )
        val numbering = parse(entries(DocxWriter.toByteArray(doc)).getValue("word/numbering.xml"))
        val nums = numbering.getElementsByTagNameNS(wNs, "num")
        var checked = 0
        for (i in 0 until nums.length) {
            val num = nums.item(i) as Element
            val numId = num.getAttributeNS(wNs, "numId")
            if (numId == "1") continue // the shared bullet instance
            val overrides = directChildren(num, "lvlOverride")
            assertEquals(1, overrides.size, "numId $numId lacks lvlOverride")
            val start = directChildren(overrides[0], "startOverride")
            assertEquals(1, start.size, "numId $numId lacks startOverride")
            assertEquals("1", start[0].getAttributeNS(wNs, "val"))
            checked++
        }
        assertEquals(2, checked, "expected two numbered list instances")
    }

    @Test
    fun `end alignment is written as jc end and read back`() {
        val doc = DocumentModel(
            listOf(
                Paragraph(
                    listOf(TextRun("signature")),
                    ParagraphStyle(alignment = Alignment.END),
                )
            )
        )
        val bytes = DocxWriter.toByteArray(doc)
        val xml = parse(entries(bytes).getValue("word/document.xml"))
        val jc = xml.getElementsByTagNameNS(wNs, "jc")
        assertEquals(1, jc.length)
        assertEquals("end", (jc.item(0) as Element).getAttributeNS(wNs, "val"))
        val back = DocxReader.read(bytes).blocks.filterIsInstance<Paragraph>()[0]
        assertEquals(Alignment.END, back.style.alignment)
    }

    @Test
    fun `xml-illegal control characters are dropped and the package stays well-formed`() {
        val doc = DocumentModel(
            listOf(
                Paragraph(listOf(TextRun("a\u0000b\u0007c"))),
                Paragraph(listOf(TextRun("x\uFFFEy\tz"))),
            )
        )
        val bytes = DocxWriter.toByteArray(doc)
        val xml = parse(entries(bytes).getValue("word/document.xml"))
        val text = xml.documentElement.textContent
        assertTrue(text.contains("abc"), "controls not dropped: $text")
        // A tab is written as w:tab, an element of its own, and reads back
        // as the character; U+FFFE is not XML and is gone.
        assertTrue(text.contains("xyz"), "U+FFFE must not survive: $text")
        assertEquals("xy\tz", DocxReader.read(bytes).blocks.filterIsInstance<Paragraph>()[1].text)
    }

    @Test
    fun `a cell ending with a nested table gets exactly one trailing spacer paragraph`() {
        val inner = Table(listOf(TableRow(listOf(TableCell(listOf(Paragraph(listOf(TextRun("deep")))))))))
        val outer = Table(
            listOf(TableRow(listOf(TableCell(listOf(Paragraph(listOf(TextRun("inner"))), inner)))))
        )
        val xml = parse(
            entries(DocxWriter.toByteArray(DocumentModel(listOf(outer)))).getValue("word/document.xml")
        )
        val outerCell = xml.getElementsByTagNameNS(wNs, "tc").item(0) as Element
        // Direct children: the "inner" paragraph + exactly one spacer after the
        // nested table — not a duplicate pair.
        assertEquals(2, directChildren(outerCell, "p").size)
    }

    // ------------------------------------------------------------------
    // Reader
    // ------------------------------------------------------------------

    private fun minimalDoc(body: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:document xmlns:w="$wNs"><w:body>$body</w:body></w:document>"""

    @Test
    fun `runs inside hyperlinks and tracked insertions are kept`() {
        val docx = zipOf(
            "word/document.xml" to minimalDoc(
                "<w:p><w:hyperlink><w:r><w:t>linked</w:t></w:r></w:hyperlink>" +
                    """<w:ins><w:r><w:t xml:space="preserve"> added</w:t></w:r></w:ins>""" +
                    """<w:r><w:t xml:space="preserve"> tail</w:t></w:r></w:p>"""
            )
        )
        val para = DocxReader.read(docx).blocks.filterIsInstance<Paragraph>()[0]
        assertEquals("linked added tail", para.text)
    }

    @Test
    fun `an explicitly LTR run inside an RTL paragraph round-trips as LTR`() {
        val doc = DocumentModel(
            listOf(
                Paragraph(
                    runs = listOf(
                        TextRun("عربي", direction = TextDirection.RTL),
                        TextRun(" Latin", direction = TextDirection.LTR),
                    ),
                    style = ParagraphStyle(direction = TextDirection.RTL),
                )
            )
        )
        val back = DocxReader.read(DocxWriter.toByteArray(doc)).blocks.filterIsInstance<Paragraph>()[0]
        assertEquals(TextDirection.RTL, back.style.direction)
        assertEquals(TextDirection.RTL, back.runs[0].direction)
        assertEquals(TextDirection.LTR, back.runs[1].direction)
    }

    @Test
    fun `a damaged Word file is refused, never fatal`() {
        // Half a download, an attachment cut short, a byte lost in
        // transit: reading one may fail, and it must fail as an exception
        // the app can catch and report — not as an error nothing catches,
        // and not by going round for ever. Fixed seed, so a failure can be
        // repeated exactly.
        val whole = DocxWriter.toByteArray(
            DocumentModel(
                (1..20).map { Paragraph(listOf(TextRun("Line $it of an ordinary document."))) }
            )
        )
        val random = java.util.Random(20260903L)
        org.junit.jupiter.api.assertTimeoutPreemptively(java.time.Duration.ofSeconds(60)) {
            for (round in 0 until 45) {
                val broken = whole.copyOf()
                val damaged = when (round % 3) {
                    0 -> broken.copyOf(1 + random.nextInt(broken.size - 1))
                    1 -> broken.also { file ->
                        repeat(1 + random.nextInt(20)) {
                            file[random.nextInt(file.size)] = random.nextInt(256).toByte()
                        }
                    }
                    else -> broken.also { file ->
                        val at = random.nextInt(file.size)
                        val length = minOf(file.size - at, 1 + random.nextInt(200))
                        java.util.Arrays.fill(file, at, at + length, 0)
                    }
                }
                try {
                    DocxReader.read(damaged)
                } catch (expected: Exception) {
                    // Refused, which is the app's cue to say so.
                } catch (fatal: Throwable) {
                    throw AssertionError("round $round: reading a damaged file threw $fatal", fatal)
                }
            }
        }
    }

    @Test
    fun `garbage bytes and malformed xml both throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            DocxReader.read(byteArrayOf(1, 2, 3, 4, 5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocxReader.read(zipOf("word/document.xml" to "<w:document"))
        }
    }

    @Test
    fun `absurdly deep table nesting is rejected instead of overflowing the stack`() {
        val depth = 200
        val body = "<w:tbl><w:tr><w:tc>".repeat(depth) +
            "<w:p><w:r><w:t>x</w:t></w:r></w:p>" +
            "</w:tc></w:tr></w:tbl>".repeat(depth)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            DocxReader.read(zipOf("word/document.xml" to minimalDoc(body)))
        }
        assertTrue(ex.message.orEmpty().contains("nesting"), "message: ${ex.message}")
    }

    @Test
    fun `a decompression bomb part is rejected by the size cap`() {
        val bomb = minimalDoc("<w:p><w:r><w:t>" + " ".repeat(32 * 1024 * 1024 + 100) + "</w:t></w:r></w:p>")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            DocxReader.read(zipOf("word/document.xml" to bomb))
        }
        assertTrue(ex.message.orEmpty().contains("inflates"), "message: ${ex.message}")
    }

    @Test
    fun `unneeded package parts such as media are never inflated`() {
        // A huge media entry must not affect reading (it is skipped, not stored).
        val docx = zipOf(
            "word/document.xml" to minimalDoc("<w:p><w:r><w:t>ok</w:t></w:r></w:p>"),
            "word/media/image1.png" to "x".repeat(1024),
        )
        assertEquals("ok", DocxReader.read(docx).blocks.filterIsInstance<Paragraph>()[0].text)
    }
}
