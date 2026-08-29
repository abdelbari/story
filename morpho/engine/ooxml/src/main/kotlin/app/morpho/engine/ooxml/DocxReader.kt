package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableRow
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a WordprocessingML (.docx) package back into a [DocumentModel] using
 * nothing but the JDK (java.util.zip plus namespace-aware DOM parsing). This
 * is the inverse of [DocxWriter] and the start of DOCX→PDF conversion and
 * Google-Docs round-tripping.
 *
 * Supported today mirrors what [DocxWriter] emits: paragraphs and runs
 * (bold/italic/underline), Title and Heading 1–3 paragraph styles, bullet and
 * numbered lists, simple tables with nested block content, per-paragraph
 * (`w:bidi`) and per-run (`w:rtl`) right-to-left direction, run languages
 * (`w:lang` `w:val`/`w:bidi`), and center/justify alignment (`w:jc`).
 *
 * List markers are resolved through word/numbering.xml: a paragraph's
 * `w:numPr` numId is followed to its `w:num` instance and on to that
 * abstractNum's level-0 `w:numFmt` — "bullet" becomes [ListMarker.BULLET],
 * "decimal" becomes [ListMarker.NUMBERED]. Concrete numId values are never
 * assumed, so the mapping stays correct as the writer gains num instances.
 *
 * Deliberate v0 choices:
 * - Paragraphs with no runs and no text are skipped ([DocxWriter] emits an
 *   empty spacer paragraph after each table; empty paragraphs carry no
 *   content worth round-tripping).
 * - Unknown elements, attributes, and namespaces are ignored — extra content
 *   never makes the reader throw. A missing or malformed word/numbering.xml
 *   (or an absent styles.xml) merely loses list markers; only a package
 *   without word/document.xml is rejected, with [IllegalArgumentException].
 * - Non-text run content (drawings, breaks, fields) is dropped; images land
 *   with the M1 media-part work.
 * - Every block gets confidence 1: this is a native-format read, not an
 *   extraction guess.
 */
object DocxReader {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun read(bytes: ByteArray): DocumentModel = read(ByteArrayInputStream(bytes))

    /** Reads the package and closes [input]. */
    fun read(input: InputStream): DocumentModel {
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val documentPart = requireNotNull(parts["word/document.xml"]) {
            "Not a .docx package: word/document.xml is missing."
        }
        val numbering = parts["word/numbering.xml"]?.let(::parseNumbering).orEmpty()
        val body = firstChild(parseXml(documentPart).documentElement, "body")
            ?: return DocumentModel(blocks = emptyList())
        return DocumentModel(blocks = parseBlocks(body, numbering))
    }

    // ------------------------------------------------------------------
    // word/document.xml
    // ------------------------------------------------------------------

    private fun parseBlocks(parent: Element, numbering: Map<String, ListMarker>): List<Block> {
        val blocks = mutableListOf<Block>()
        for (child in children(parent)) {
            when (child.localName) {
                "p" -> parseParagraph(child, numbering)?.let(blocks::add)
                "tbl" -> parseTable(child, numbering)?.let(blocks::add)
                else -> {} // sectPr, bookmarks, anything the reader does not know
            }
        }
        return blocks
    }

    private fun parseParagraph(p: Element, numbering: Map<String, ListMarker>): Paragraph? {
        val runs = children(p, "r").mapNotNull(::parseRun)
        if (runs.isEmpty()) return null
        return Paragraph(
            runs = runs,
            style = parseParagraphStyle(firstChild(p, "pPr"), numbering),
            confidence = 1f,
        )
    }

    private fun parseParagraphStyle(
        pPr: Element?,
        numbering: Map<String, ListMarker>,
    ): ParagraphStyle {
        if (pPr == null) return ParagraphStyle()
        val kind = when (firstChild(pPr, "pStyle")?.let { attr(it, "val") }) {
            "Title" -> ParagraphKind.TITLE
            "Heading1" -> ParagraphKind.HEADING_1
            "Heading2" -> ParagraphKind.HEADING_2
            "Heading3" -> ParagraphKind.HEADING_3
            else -> ParagraphKind.BODY
        }
        val listMarker = firstChild(pPr, "numPr")
            ?.let { firstChild(it, "numId") }
            ?.let { attr(it, "val") }
            ?.let(numbering::get)
        val alignment = when (firstChild(pPr, "jc")?.let { attr(it, "val") }) {
            "center" -> Alignment.CENTER
            "both", "distribute" -> Alignment.JUSTIFY
            "start", "left" -> Alignment.START
            "end", "right" -> Alignment.END
            else -> null
        }
        return ParagraphStyle(
            kind = kind,
            direction = if (isOn(firstChild(pPr, "bidi"))) TextDirection.RTL else null,
            listMarker = listMarker,
            alignment = alignment,
        )
    }

    /** A run with no w:t at all (drawings, breaks) carries nothing to keep. */
    private fun parseRun(r: Element): TextRun? {
        val textElements = children(r, "t")
        if (textElements.isEmpty()) return null
        val text = textElements.joinToString(separator = "") { it.textContent }
        val rPr = firstChild(r, "rPr") ?: return TextRun(text)
        val underline = firstChild(rPr, "u")?.let { attr(it, "val") ?: "single" }
        return TextRun(
            text = text,
            bold = isOn(firstChild(rPr, "b")),
            italic = isOn(firstChild(rPr, "i")),
            underline = underline != null && underline != "none",
            language = firstChild(rPr, "lang")?.let { attr(it, "val") ?: attr(it, "bidi") },
            direction = if (isOn(firstChild(rPr, "rtl"))) TextDirection.RTL else null,
        )
    }

    private fun parseTable(tbl: Element, numbering: Map<String, ListMarker>): Table? {
        val rows = children(tbl, "tr").map { tr ->
            TableRow(children(tr, "tc").map { tc -> TableCell(parseBlocks(tc, numbering)) })
        }
        if (rows.isEmpty()) return null
        return Table(rows = rows, confidence = 1f)
    }

    // ------------------------------------------------------------------
    // word/numbering.xml
    // ------------------------------------------------------------------

    /** numId → marker, resolved via each num's abstractNum level-0 numFmt. */
    private fun parseNumbering(bytes: ByteArray): Map<String, ListMarker> = try {
        val root = parseXml(bytes).documentElement
        val level0Formats = mutableMapOf<String, String>()
        for (abstractNum in children(root, "abstractNum")) {
            val id = attr(abstractNum, "abstractNumId") ?: continue
            val level0 = children(abstractNum, "lvl").firstOrNull { attr(it, "ilvl") == "0" }
            val numFmt = level0?.let { firstChild(it, "numFmt") }?.let { attr(it, "val") }
            if (numFmt != null) level0Formats[id] = numFmt
        }
        buildMap {
            for (num in children(root, "num")) {
                val numId = attr(num, "numId") ?: continue
                val abstractId = firstChild(num, "abstractNumId")?.let { attr(it, "val") }
                when (level0Formats[abstractId]) {
                    "bullet" -> put(numId, ListMarker.BULLET)
                    "decimal" -> put(numId, ListMarker.NUMBERED)
                    else -> {}
                }
            }
        }
    } catch (_: Exception) {
        emptyMap() // a broken numbering part loses markers, never the document
    }

    // ------------------------------------------------------------------
    // DOM helpers
    // ------------------------------------------------------------------

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    /** Direct WordprocessingML-namespace element children, optionally by name. */
    private fun children(parent: Element, localName: String? = null): List<Element> {
        val result = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node is Element && node.namespaceURI == W &&
                (localName == null || node.localName == localName)
            ) {
                result += node
            }
            node = node.nextSibling
        }
        return result
    }

    private fun firstChild(parent: Element, localName: String): Element? =
        children(parent, localName).firstOrNull()

    private fun attr(element: Element, name: String): String? {
        val namespaced = element.getAttributeNS(W, name)
        if (namespaced.isNotEmpty()) return namespaced
        return element.getAttribute(name).ifEmpty { null }
    }

    /** OOXML on/off toggle: present with no w:val (or a truthy one) means on. */
    private fun isOn(element: Element?): Boolean {
        if (element == null) return false
        return when (attr(element, "val")?.lowercase()) {
            null, "1", "true", "on" -> true
            else -> false
        }
    }
}
