package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a WordprocessingML (.docx) package back into a [DocumentModel] using
 * nothing but the JDK (java.util.zip plus namespace-aware DOM parsing). This
 * is the inverse of [DocxWriter] and the start of DOCX→PDF conversion and
 * Google-Docs round-tripping.
 *
 * Supported today mirrors what [DocxWriter] emits, plus what real-world Word
 * files wrap around it: paragraphs and runs (bold/italic/underline) —
 * including runs inside `w:hyperlink`, `w:ins`, `w:smartTag` and `w:sdt`
 * containers — Title and Heading 1–3 paragraph styles, bullet and numbered
 * lists, simple tables with nested block content, per-paragraph (`w:bidi`)
 * and per-run (`w:rtl`) direction, run languages (`w:lang` `w:val`/`w:bidi`),
 * and alignment (`w:jc`). Runs inside `w:del` are deliberately skipped:
 * deleted text is not document content.
 *
 * List markers are resolved through word/numbering.xml: a paragraph's
 * `w:numPr` numId is followed to its `w:num` instance and on to that
 * abstractNum's level-0 `w:numFmt` — "bullet" becomes [ListMarker.BULLET],
 * "decimal" becomes [ListMarker.NUMBERED]. Concrete numId values are never
 * assumed.
 *
 * Untrusted-input hardening: only the parts the reader needs are inflated,
 * each capped at [MAX_PART_BYTES] (decompression bombs are rejected), block
 * and run-container nesting is capped at [MAX_NESTING_DEPTH], and DOCTYPE
 * declarations are refused. Anything that is not a readable package —
 * garbage bytes, truncated zip, malformed XML, exceeded caps — throws
 * [IllegalArgumentException] (wrapping the parser's own error where there is
 * one); no other exception type escapes.
 *
 * Deliberate v0 choices:
 * - The main part is located at the fixed OPC path word/document.xml; the
 *   officeDocument relationship is not followed yet (Word can, rarely, name
 *   the part differently — a known limitation).
 * - Paragraphs with no runs and no text are skipped ([DocxWriter] emits an
 *   empty spacer paragraph after each table).
 * - Inside a `w:bidi` paragraph, a run without `w:rtl` reads back as
 *   explicitly LTR — in OOXML the absence of `w:rtl` means left-to-right,
 *   while the IR's null means "inherit", which there would mean RTL.
 * - A missing or malformed word/numbering.xml merely loses list markers.
 * - PNG and JPEG images referenced by `w:drawing` are read back as
 *   [ImageBlock]s emitted after their paragraph's text (inline position is
 *   not modeled yet); other media types (EMF/WMF vector images from Word,
 *   for instance) are skipped like any other unknown content. Media parts
 *   are capped at [MAX_MEDIA_PART_BYTES] each and [MAX_TOTAL_MEDIA_BYTES]
 *   overall. Other non-text run content (breaks, fields) is dropped.
 * - Every block gets confidence 1: this is a native-format read.
 */
object DocxReader {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val MAX_NESTING_DEPTH = 64
    private const val MAX_PART_BYTES = 32 * 1024 * 1024
    private const val MAX_MEDIA_PART_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_MEDIA_BYTES = 64 * 1024 * 1024
    private val NEEDED_PARTS =
        setOf("word/document.xml", "word/numbering.xml", "word/_rels/document.xml.rels")
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val EMU_PER_PX = 9525L
    private val MIME_BY_EXTENSION = mapOf("png" to "image/png", "jpeg" to "image/jpeg", "jpg" to "image/jpeg")
    private val RUN_CONTAINERS = setOf("hyperlink", "ins", "smartTag", "sdt", "sdtContent")

    fun read(bytes: ByteArray): DocumentModel = read(ByteArrayInputStream(bytes))

    /** Reads the package and closes [input]. */
    fun read(input: InputStream): DocumentModel {
        try {
            val parts = readNeededParts(input)
            val documentPart = parts["word/document.xml"]
                ?: throw IllegalArgumentException("Not a .docx package: word/document.xml is missing.")
            val numbering = parts["word/numbering.xml"]?.let(::parseNumbering).orEmpty()
            val media = MediaStore(parts)
            val body = firstChild(parseXml(documentPart).documentElement, "body")
                ?: return DocumentModel(blocks = emptyList())
            return DocumentModel(blocks = parseBlocks(body, numbering, media, depth = 0))
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a readable .docx package.", e)
        }
    }

    private fun readNeededParts(input: InputStream): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        var totalMedia = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name in NEEDED_PARTS) {
                    parts[entry.name] = readBounded(zip, entry.name, MAX_PART_BYTES)
                } else if (!entry.isDirectory && entry.name.startsWith("word/media/")) {
                    val bytes = readBounded(zip, entry.name, MAX_MEDIA_PART_BYTES)
                    totalMedia += bytes.size
                    require(totalMedia <= MAX_TOTAL_MEDIA_BYTES) {
                        "Media parts inflate beyond $MAX_TOTAL_MEDIA_BYTES bytes in total; refusing to read them."
                    }
                    parts[entry.name] = bytes
                }
                zip.closeEntry()
            }
        }
        return parts
    }

    private fun readBounded(zip: ZipInputStream, name: String, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            require(out.size() <= maxBytes) {
                "Part $name inflates beyond $maxBytes bytes; refusing to read it."
            }
        }
        return out.toByteArray()
    }

    /** Image relationships plus the media bytes they point at. */
    private class MediaStore(parts: Map<String, ByteArray>) {
        private val targetByRelId: Map<String, String> =
            parts["word/_rels/document.xml.rels"]?.let(::parseRelationships).orEmpty()
        private val parts = parts

        fun imageFor(relId: String): Triple<ByteArray, String, Unit>? {
            val target = targetByRelId[relId] ?: return null
            val extension = target.substringAfterLast('.', "").lowercase()
            val mime = MIME_BY_EXTENSION[extension] ?: return null
            val normalized = when {
                target.startsWith("/") -> target.removePrefix("/")
                else -> "word/$target"
            }
            val bytes = parts[normalized] ?: return null
            return Triple(bytes, mime, Unit)
        }

        private fun parseRelationships(bytes: ByteArray): Map<String, String> = try {
            val root = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
            buildMap {
                val relationships = root.getElementsByTagNameNS(REL_NS, "Relationship")
                for (i in 0 until relationships.length) {
                    val relationship = relationships.item(i) as Element
                    val id = relationship.getAttribute("Id")
                    val target = relationship.getAttribute("Target")
                    if (id.isNotEmpty() && target.isNotEmpty()) put(id, target)
                }
            }
        } catch (_: Exception) {
            emptyMap() // broken rels lose images, never the document
        }
    }

    // ------------------------------------------------------------------
    // word/document.xml
    // ------------------------------------------------------------------

    private fun parseBlocks(
        parent: Element,
        numbering: Map<String, ListMarker>,
        media: MediaStore,
        depth: Int,
    ): List<Block> {
        require(depth <= MAX_NESTING_DEPTH) {
            "Block nesting deeper than $MAX_NESTING_DEPTH levels; refusing to parse."
        }
        val blocks = mutableListOf<Block>()
        for (child in children(parent)) {
            when (child.localName) {
                "p" -> {
                    parseParagraph(child, numbering)?.let(blocks::add)
                    blocks += parseImages(child, media)
                }
                "tbl" -> parseTable(child, numbering, media, depth)?.let(blocks::add)
                else -> {} // sectPr, bookmarks, anything the reader does not know
            }
        }
        return blocks
    }

    /** PNG/JPEG drawings in a paragraph, emitted after its text. */
    private fun parseImages(p: Element, media: MediaStore): List<ImageBlock> {
        val images = mutableListOf<ImageBlock>()
        for (drawing in descendantsNS(p, W, "drawing")) {
            val blip = descendantsNS(drawing, A_NS, "blip").firstOrNull() ?: continue
            val relId = blip.getAttributeNS(R_NS, "embed").ifEmpty { null } ?: continue
            val (bytes, mime) = media.imageFor(relId)?.let { it.first to it.second } ?: continue
            val extent = descendantsNS(drawing, WP_NS, "extent").firstOrNull()
            val cx = extent?.getAttribute("cx")?.toLongOrNull() ?: 0L
            val cy = extent?.getAttribute("cy")?.toLongOrNull() ?: 0L
            images += ImageBlock(
                bytes = bytes,
                mimeType = mime,
                widthPx = (cx / EMU_PER_PX).toInt().coerceAtLeast(1),
                heightPx = (cy / EMU_PER_PX).toInt().coerceAtLeast(1),
                confidence = 1f,
            )
        }
        return images
    }

    /** All descendants in [ns] with [localName], any depth, document order. */
    private fun descendantsNS(parent: Element, ns: String, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = parent.getElementsByTagNameNS(ns, localName)
        for (i in 0 until nodes.length) result += nodes.item(i) as Element
        return result
    }

    private fun parseParagraph(p: Element, numbering: Map<String, ListMarker>): Paragraph? {
        val style = parseParagraphStyle(firstChild(p, "pPr"), numbering)
        val runs = collectRuns(p, paragraphRtl = style.direction == TextDirection.RTL, depth = 0)
        if (runs.isEmpty()) return null
        return Paragraph(runs = runs, style = style, confidence = 1f)
    }

    /** Runs directly in [parent] plus those inside run containers. */
    private fun collectRuns(parent: Element, paragraphRtl: Boolean, depth: Int): List<TextRun> {
        require(depth <= MAX_NESTING_DEPTH) {
            "Run-container nesting deeper than $MAX_NESTING_DEPTH levels; refusing to parse."
        }
        val runs = mutableListOf<TextRun>()
        for (child in children(parent)) {
            when (child.localName) {
                "r" -> parseRun(child, paragraphRtl)?.let(runs::add)
                in RUN_CONTAINERS -> runs += collectRuns(child, paragraphRtl, depth + 1)
                else -> {}
            }
        }
        return runs
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
    private fun parseRun(r: Element, paragraphRtl: Boolean): TextRun? {
        val textElements = children(r, "t")
        if (textElements.isEmpty()) return null
        val text = textElements.joinToString(separator = "") { it.textContent }
        // In OOXML the absence of w:rtl means a left-to-right run even inside
        // a bidi paragraph, while the IR's null means "inherit" — so inside an
        // RTL paragraph, LTR is recorded explicitly to keep round-trips true.
        val inherited: TextDirection? = if (paragraphRtl) TextDirection.LTR else null
        val rPr = firstChild(r, "rPr") ?: return TextRun(text, direction = inherited)
        val underline = firstChild(rPr, "u")?.let { attr(it, "val") ?: "single" }
        return TextRun(
            text = text,
            bold = isOn(firstChild(rPr, "b")),
            italic = isOn(firstChild(rPr, "i")),
            underline = underline != null && underline != "none",
            language = firstChild(rPr, "lang")?.let { attr(it, "val") ?: attr(it, "bidi") },
            direction = if (isOn(firstChild(rPr, "rtl"))) TextDirection.RTL else inherited,
        )
    }

    private fun parseTable(
        tbl: Element,
        numbering: Map<String, ListMarker>,
        media: MediaStore,
        depth: Int,
    ): Table? {
        val rows = children(tbl, "tr").map { tr ->
            TableRow(
                children(tr, "tc").map { tc ->
                    TableCell(parseBlocks(tc, numbering, media, depth + 1))
                }
            )
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
