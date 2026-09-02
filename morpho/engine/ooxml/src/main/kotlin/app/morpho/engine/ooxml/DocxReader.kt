package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.ParagraphStyle
import app.morpho.engine.layout.RunField
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
    /** One inch: what a section that names a page size but no margins gets. */
    private const val DEFAULT_MARGIN_PT = 72f
    private const val MAX_PART_BYTES = 32 * 1024 * 1024
    private const val MAX_MEDIA_PART_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_MEDIA_BYTES = 64 * 1024 * 1024
    private val NEEDED_PARTS =
        setOf(
            "word/document.xml",
            "word/numbering.xml",
            "word/styles.xml",
            "word/_rels/document.xml.rels",
        )
    /** A running header or footer part, or the relationships of one: word/header1.xml, word/_rels/footer2.xml.rels. */
    private val FURNITURE_PART = Regex("word/(?:_rels/)?(?:header|footer)\\d*\\.xml(?:\\.rels)?")
    private const val NOTES_PART = "word/footnotes.xml"
    private const val EMU_PER_PT = 12700L
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val EMU_PER_PX = 9525L
    private val MIME_BY_EXTENSION = mapOf("png" to "image/png", "jpeg" to "image/jpeg", "jpg" to "image/jpeg")
    /** Elements that hold runs without changing them; a hyperlink is handled on its own, since it says where its runs point. */
    private val RUN_CONTAINERS = setOf("ins", "smartTag", "sdt", "sdtContent")

    fun read(bytes: ByteArray): DocumentModel = read(ByteArrayInputStream(bytes))

    /** Reads the package and closes [input]. */
    fun read(input: InputStream): DocumentModel {
        try {
            val parts = readNeededParts(input)
            val documentPart = parts["word/document.xml"]
                ?: throw IllegalArgumentException("Not a .docx package: word/document.xml is missing.")
            val numbering = parts["word/numbering.xml"]?.let(::parseNumbering).orEmpty()
            val styles = StyleSheet(parts["word/styles.xml"])
            val media = MediaStore(parts, "word/_rels/document.xml.rels")
            val body = firstChild(parseXml(documentPart).documentElement, "body")
                ?: return DocumentModel(blocks = emptyList())
            val sectPr = firstChild(body, "sectPr")
            // The notes part, read first: a mark in the text refers to a
            // note by number, and the note lives out here.
            val notes = parts[NOTES_PART]?.let { bytes ->
                runCatching {
                    children(parseXml(bytes).documentElement, "footnote")
                        .filter { attr(it, "type") == null }
                        .mapNotNull { note ->
                            val id = attr(note, "id")?.trim()?.toIntOrNull() ?: return@mapNotNull null
                            id to parseBlocks(note, numbering, media, depth = 0, styles = styles)
                        }
                        .toMap()
                }.getOrNull()
            }.orEmpty()
            return DocumentModel(
                blocks = parseBlocks(body, numbering, media, depth = 0, notes = notes, styles = styles),
                pageSetup = sectPr?.let(::parsePageSetup),
                header = sectPr?.let { furniture(it, "headerReference", parts, media, numbering, styles) }.orEmpty(),
                footer = sectPr?.let { furniture(it, "footerReference", parts, media, numbering, styles) }.orEmpty(),
            )
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
                if (!entry.isDirectory &&
                    (entry.name in NEEDED_PARTS || entry.name == NOTES_PART || FURNITURE_PART.matches(entry.name))
                ) {
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

    /**
     * The running header or footer the section refers to — its default one,
     * else the first — as blocks, with a picture in a line kept in the line
     * as a run and a PAGE field kept as a field, so the writer can set it
     * again the way it was.
     */
    private fun furniture(
        sectPr: Element,
        reference: String,
        parts: Map<String, ByteArray>,
        media: MediaStore,
        numbering: Map<String, Map<Int, ListMarker>>,
        styles: StyleSheet,
    ): List<Block> {
        val references = children(sectPr, reference)
        val chosen = references.firstOrNull { attr(it, "type") == "default" } ?: references.firstOrNull() ?: return emptyList()
        val relId = chosen.getAttributeNS(R_NS, "id").ifEmpty { return emptyList() }
        val partName = media.partFor(relId) ?: return emptyList()
        val bytes = parts[partName] ?: return emptyList()
        val root = runCatching { parseXml(bytes).documentElement }.getOrNull() ?: return emptyList()
        val rels = "word/_rels/" + partName.removePrefix("word/") + ".rels"
        // A paragraph that is nothing but a picture is the picture, as it was written.
        return parseBlocks(root, numbering, MediaStore(parts, rels), depth = 0, inline = true, styles = styles).map { block ->
            val only = (block as? Paragraph)?.runs?.singleOrNull()
            val picture = only?.image
            if (only != null && only.text.isEmpty() && picture != null) picture else block
        }
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
    private class MediaStore(parts: Map<String, ByteArray>, relsPart: String) {
        private val targetByRelId: Map<String, String> =
            parts[relsPart]?.let(::parseRelationships).orEmpty()
        private val parts = parts

        /** Where a relationship points, as it was written: a part of the package, or an address outside it. */
        fun targetFor(relId: String): String? = targetByRelId[relId]

        /** The package part a relationship of this part points at, by name. */
        fun partFor(relId: String): String? {
            val target = targetByRelId[relId] ?: return null
            return when {
                target.startsWith("/") -> target.removePrefix("/")
                else -> "word/$target"
            }
        }

        fun imageFor(relId: String): Triple<ByteArray, String, Unit>? {
            val target = targetByRelId[relId] ?: return null
            val extension = target.substringAfterLast('.', "").lowercase()
            val mime = MIME_BY_EXTENSION[extension] ?: return null
            val normalized = partFor(relId) ?: return null
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

    /** [inline] keeps a paragraph's pictures in its line as runs — how a running header carries its artwork — instead of after it. */
    private fun parseBlocks(
        parent: Element,
        numbering: Map<String, Map<Int, ListMarker>>,
        media: MediaStore,
        depth: Int,
        inline: Boolean = false,
        notes: Map<Int, List<Block>> = emptyMap(),
        styles: StyleSheet,
        fromTable: Inherited = Inherited.NONE,
    ): List<Block> {
        require(depth <= MAX_NESTING_DEPTH) {
            "Block nesting deeper than $MAX_NESTING_DEPTH levels; refusing to parse."
        }
        val blocks = mutableListOf<Block>()
        for (child in children(parent)) {
            when (child.localName) {
                "p" -> {
                    parseParagraph(child, numbering, media, inline, notes, styles, fromTable)
                        ?.let(blocks::add)
                    if (!inline) blocks += parseImages(child, media)
                }
                "tbl" -> parseTable(child, numbering, media, depth, notes, styles)?.let(blocks::add)
                else -> {} // sectPr, bookmarks, anything the reader does not know
            }
        }
        return blocks
    }

    /** PNG/JPEG drawings in a paragraph, emitted after its text. */
    private fun parseImages(p: Element, media: MediaStore): List<ImageBlock> =
        descendantsNS(p, W, "drawing").mapNotNull { imageOf(it, media) }

    /** The picture a drawing embeds, at the size its extent gives it, or null when it is not one the reader keeps. */
    private fun imageOf(drawing: Element, media: MediaStore): ImageBlock? {
        val blip = descendantsNS(drawing, A_NS, "blip").firstOrNull() ?: return null
        val relId = blip.getAttributeNS(R_NS, "embed").ifEmpty { null } ?: return null
        val (bytes, mime) = media.imageFor(relId)?.let { it.first to it.second } ?: return null
        val extent = descendantsNS(drawing, WP_NS, "extent").firstOrNull()
        val cx = extent?.getAttribute("cx")?.toLongOrNull() ?: 0L
        val cy = extent?.getAttribute("cy")?.toLongOrNull() ?: 0L
        return ImageBlock(
            bytes = bytes,
            mimeType = mime,
            widthPx = (cx / EMU_PER_PX).toInt().coerceAtLeast(1),
            heightPx = (cy / EMU_PER_PX).toInt().coerceAtLeast(1),
            confidence = 1f,
            widthPt = (cx.toFloat() / EMU_PER_PT).takeIf { it > 0f },
            heightPt = (cy.toFloat() / EMU_PER_PT).takeIf { it > 0f },
        )
    }

    /** All descendants in [ns] with [localName], any depth, document order. */
    private fun descendantsNS(parent: Element, ns: String, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = parent.getElementsByTagNameNS(ns, localName)
        for (i in 0 until nodes.length) result += nodes.item(i) as Element
        return result
    }

    /** With [inline], the paragraph's pictures stay in its line as runs rather than following it. */
    private fun parseParagraph(
        p: Element,
        numbering: Map<String, Map<Int, ListMarker>>,
        media: MediaStore? = null,
        inline: Boolean = false,
        notes: Map<Int, List<Block>> = emptyMap(),
        styles: StyleSheet,
        fromTable: Inherited = Inherited.NONE,
    ): Paragraph? {
        val pPr = firstChild(p, "pPr")
        val styleId = firstChild(pPr, "pStyle")?.let { attr(it, "val") }
        // What the document says, then what the style says, then what the
        // paragraph writes on itself: the last one to speak wins.
        val properties = styles.defaultParagraph + fromTable.paragraph +
            styles.paragraph(styleId) + own(pPr)
        val runProperties = styles.defaultRun + fromTable.run + styles.run(styleId)
        val style = parseParagraphStyle(properties, styleId, styles.name(styleId), numbering)
        val runs = collectRuns(
            p,
            paragraphRtl = style.direction == TextDirection.RTL,
            depth = 0,
            media = media,
            inline = inline,
            notes = notes,
            styles = styles,
            inherited = runProperties,
        )
        if (runs.isEmpty()) return null
        return Paragraph(runs = runs, style = style, confidence = 1f)
    }

    /**
     * Runs directly in [parent] plus those inside run containers; a PAGE
     * field's runs are fields, and the runs of a hyperlink carry where it
     * points, which the part's relationships hold rather than the text.
     */
    private fun collectRuns(
        parent: Element,
        paragraphRtl: Boolean,
        depth: Int,
        media: MediaStore? = null,
        inline: Boolean = false,
        notes: Map<Int, List<Block>> = emptyMap(),
        styles: StyleSheet,
        inherited: Map<String, Element> = emptyMap(),
    ): List<TextRun> {
        require(depth <= MAX_NESTING_DEPTH) {
            "Run-container nesting deeper than $MAX_NESTING_DEPTH levels; refusing to parse."
        }
        val runs = mutableListOf<TextRun>()
        for (child in children(parent)) {
            when (child.localName) {
                "r" -> parseRun(child, paragraphRtl, media.takeIf { inline }, notes, styles, inherited)?.let(runs::add)
                "fldSimple" -> {
                    val inner = collectRuns(child, paragraphRtl, depth + 1, media, inline, notes, styles, inherited)
                    val instruction = attr(child, "instr").orEmpty().trim().uppercase()
                    runs += if (instruction.startsWith("PAGE")) inner.map { it.copy(field = RunField.PAGE_NUMBER) } else inner
                }
                "hyperlink" -> {
                    val inner = collectRuns(child, paragraphRtl, depth + 1, media, inline, notes, styles, inherited)
                    val target = child.getAttributeNS(R_NS, "id").ifEmpty { null }?.let { media?.targetFor(it) }
                        ?: attr(child, "anchor")?.let { "#$it" }
                    runs += if (target != null) inner.map { it.copy(link = target) } else inner
                }
                in RUN_CONTAINERS -> runs += collectRuns(child, paragraphRtl, depth + 1, media, inline, notes, styles, inherited)
                else -> {}
            }
        }
        return runs
    }

    private fun parseParagraphStyle(
        properties: Map<String, Element>,
        styleId: String?,
        styleName: String?,
        numbering: Map<String, Map<Int, ListMarker>>,
    ): ParagraphStyle {
        if (properties.isEmpty() && styleId == null) return ParagraphStyle()
        // What the style is called says what a paragraph is — its id where
        // the producer writes English ids, else the built-in name it carries
        // whatever the language. Failing both, the level it sits at in the
        // outline, which a heading of any producer's making has.
        val kind = when (styleId ?: "") {
            "Title" -> ParagraphKind.TITLE
            "Heading1" -> ParagraphKind.HEADING_1
            "Heading2" -> ParagraphKind.HEADING_2
            "Heading3" -> ParagraphKind.HEADING_3
            else -> when (styleName) {
                "title" -> ParagraphKind.TITLE
                "heading 1" -> ParagraphKind.HEADING_1
                "heading 2" -> ParagraphKind.HEADING_2
                "heading 3", "heading 4", "heading 5", "heading 6" -> ParagraphKind.HEADING_3
                else -> when (properties["outlineLvl"]?.let { attr(it, "val") }?.toIntOrNull()) {
                    0 -> ParagraphKind.HEADING_1
                    1 -> ParagraphKind.HEADING_2
                    2, 3, 4, 5 -> ParagraphKind.HEADING_3
                    else -> ParagraphKind.BODY
                }
            }
        }
        val numbered = properties["numPr"]
        // Word writes numId 0 to take a paragraph out of a list it would
        // otherwise inherit from its style.
        val levels = numbered
            ?.let { firstChild(it, "numId") }
            ?.let { attr(it, "val") }
            ?.takeIf { it != "0" }
            ?.let(numbering::get)
        val listLevel = numbered
            ?.let { firstChild(it, "ilvl") }
            ?.let { attr(it, "val") }
            ?.toIntOrNull()
            ?.coerceIn(0, DEEPEST_LIST_LEVEL)
            ?: 0
        // A level the numbering never defined still belongs to its list, and
        // is marked the way the list's outermost level is.
        val listMarker = levels?.let { it[listLevel] ?: it[0] }
        val alignment = when (properties["jc"]?.let { attr(it, "val") }) {
            "center" -> Alignment.CENTER
            "both", "distribute" -> Alignment.JUSTIFY
            "start", "left" -> Alignment.START
            "end", "right" -> Alignment.END
            else -> null
        }
        val ind = properties["ind"]
        val spacing = properties["spacing"]
        // A line rule of "auto" is a multiple of the font's own height, not
        // a distance, so only an exact or minimum height reads as a pitch.
        val lineRule = spacing?.let { attr(it, "lineRule") } ?: "auto"
        val pageBreakBefore = isOn(properties["pageBreakBefore"])
        return ParagraphStyle(
            kind = kind,
            direction = if (isOn(properties["bidi"])) TextDirection.RTL else null,
            listMarker = listMarker,
            listLevel = if (listMarker == null) 0 else listLevel,
            alignment = alignment,
            firstLineIndentPt = ind?.let { twips(attr(it, "firstLine")) },
            startIndentPt = ind?.let { twips(attr(it, "start") ?: attr(it, "left")) },
            hangingIndentPt = ind?.let { twips(attr(it, "hanging")) },
            spaceBeforePt = spacing?.let { twips(attr(it, "before")) },
            spaceAfterPt = spacing?.let { twips(attr(it, "after")) },
            linePitchPt = spacing?.takeIf { lineRule == "atLeast" || lineRule == "exact" }
                ?.let { twips(attr(it, "line")) },
            tabStopsPt = properties["tabs"]?.let { tabs ->
                children(tabs, "tab").filter { attr(it, "val") != "clear" }.mapNotNull { twips(attr(it, "pos")) }
            }?.takeIf { it.isNotEmpty() },
            ruleAbove = properties["pBdr"]?.let { firstChild(it, "top") }?.let { isBorder(it) } ?: false,
            ruleBelow = properties["pBdr"]?.let { firstChild(it, "bottom") }?.let { isBorder(it) } ?: false,
            pageBreakBefore = pageBreakBefore,
        )
    }

    /** The section's page size and margins, when it states them. */
    private fun parsePageSetup(sectPr: Element): PageSetup? {
        val size = firstChild(sectPr, "pgSz") ?: return null
        val width = twips(attr(size, "w")) ?: return null
        val height = twips(attr(size, "h")) ?: return null
        val margins = firstChild(sectPr, "pgMar")
        fun margin(name: String): Float = margins?.let { twips(attr(it, name)) } ?: DEFAULT_MARGIN_PT
        return PageSetup(
            widthPt = width,
            heightPt = height,
            marginTopPt = margin("top"),
            marginBottomPt = margin("bottom"),
            marginLeftPt = margin("left"),
            marginRightPt = margin("right"),
            headerDistancePt = margins?.let { twips(attr(it, "header")) },
            footerDistancePt = margins?.let { twips(attr(it, "footer")) },
            firstPageNumber = firstChild(sectPr, "pgNumType")?.let { attr(it, "start") }?.trim()?.toIntOrNull() ?: 1,
        )
    }

    /**
     * The document's styles, as formatting a paragraph or a run inherits.
     *
     * Most of a real Word document's look is not written on its paragraphs
     * at all: the document says its text is Calibri at eleven points and
     * its headings are something else, and each paragraph names a style. A
     * reader that looks only at what a paragraph writes on itself sees a
     * document with no faces, no sizes and no headings — and converts it
     * into one.
     *
     * Every style is resolved once, through the chain of styles it is based
     * on, into the properties it ends up with. A property a paragraph or
     * run writes on itself still wins: that is what direct formatting is.
     */
    private class StyleSheet(bytes: ByteArray?) {
        /** Properties by their element name, which is how they are looked up. */
        private val paragraphById = HashMap<String, Map<String, Element>>()
        private val runById = HashMap<String, Map<String, Element>>()
        private val basedOn = HashMap<String, String>()
        private val nameById = HashMap<String, String>()
        private val paragraphOwn = HashMap<String, Map<String, Element>>()
        private val runOwn = HashMap<String, Map<String, Element>>()
        private val tableOwn = HashMap<String, Map<String, Element>>()
        private val tableById = HashMap<String, Map<String, Element>>()

        /** What every paragraph and run starts from, before any style names it. */
        var defaultParagraph: Map<String, Element> = emptyMap()
            private set
        var defaultRun: Map<String, Element> = emptyMap()
            private set

        init {
            val root = bytes?.let { runCatching { parseXml(it).documentElement }.getOrNull() }
            if (root != null) {
                firstChild(root, "docDefaults")?.let { defaults ->
                    defaultParagraph = propertiesOf(firstChild(defaults, "pPrDefault"), "pPr")
                    defaultRun = propertiesOf(firstChild(defaults, "rPrDefault"), "rPr")
                }
                for (style in children(root, "style")) {
                    val id = attr(style, "styleId") ?: continue
                    paragraphOwn[id] = propertiesOf(style, "pPr")
                    runOwn[id] = propertiesOf(style, "rPr")
                    tableOwn[id] = propertiesOf(style, "tblPr")
                    firstChild(style, "basedOn")?.let { attr(it, "val") }?.let { basedOn[id] = it }
                    firstChild(style, "name")?.let { attr(it, "val") }?.let { nameById[id] = it.lowercase() }
                }
            }
        }

        /**
         * The name Word knows a style by, which is the same in every language
         * even where the style's own id is not: a French document's "Titre1"
         * is named "heading 1" all the same.
         */
        fun name(styleId: String?): String? = styleId?.let { nameById[it] }

        /** The properties a paragraph of [styleId] inherits, its own chain resolved. */
        fun paragraph(styleId: String?): Map<String, Element> =
            styleId?.let { resolve(it, paragraphOwn, paragraphById) }.orEmpty()

        /** The properties a run inherits from [styleId], which may be a paragraph's style or a run's own. */
        fun run(styleId: String?): Map<String, Element> =
            styleId?.let { resolve(it, runOwn, runById) }.orEmpty()

        /**
         * What a table of [styleId] is drawn like. Word puts a table's rules
         * in its style, not on the table — a table inserted with the
         * default Table Grid writes no border of its own — so a reader
         * that looks only at the table draws none of the lines Word shows.
         */
        fun table(styleId: String?): Map<String, Element> =
            styleId?.let { resolve(it, tableOwn, tableById) }.orEmpty()

        private fun resolve(
            styleId: String,
            own: Map<String, Map<String, Element>>,
            cache: MutableMap<String, Map<String, Element>>,
        ): Map<String, Element> {
            cache[styleId]?.let { return it }
            // A file whose styles are based on each other in a circle is not
            // worth chasing round; what has been gathered stands.
            val chain = mutableListOf<String>()
            var id: String? = styleId
            while (id != null && id !in chain && chain.size < MOST_STYLES_IN_A_CHAIN) {
                chain += id
                id = basedOn[id]
            }
            val resolved = HashMap<String, Element>()
            for (step in chain.asReversed()) resolved += own[step].orEmpty()
            cache[styleId] = resolved
            return resolved
        }

        private fun propertiesOf(parent: Element?, name: String): Map<String, Element> {
            val properties = parent?.let { firstChild(it, name) } ?: return emptyMap()
            return children(properties).mapNotNull { child ->
                child.localName?.let { it to child }
            }.toMap()
        }
    }

    /**
     * What a table's style gives the paragraphs and runs in its cells,
     * which sits under the paragraph's own style and over the document's
     * defaults, as Word resolves them.
     */
    private class Inherited(
        val paragraph: Map<String, Element>,
        val run: Map<String, Element>,
    ) {
        companion object {
            val NONE = Inherited(emptyMap(), emptyMap())
        }
    }

    /** The properties an element writes on itself, by their name. */
    private fun own(properties: Element?): Map<String, Element> {
        if (properties == null) return emptyMap()
        return children(properties).mapNotNull { child -> child.localName?.let { it to child } }.toMap()
    }

    /** What each of Word's sixteen highlighter colours is, packed 0xRRGGBB. */
    private val HIGHLIGHT_COLORS = mapOf(
        "black" to 0x000000, "blue" to 0x0000FF, "cyan" to 0x00FFFF, "darkBlue" to 0x000080,
        "darkCyan" to 0x008080, "darkGray" to 0x808080, "darkGreen" to 0x008000,
        "darkMagenta" to 0x800080, "darkRed" to 0x800000, "darkYellow" to 0x808000,
        "green" to 0x00FF00, "lightGray" to 0xC0C0C0, "magenta" to 0xFF00FF,
        "red" to 0xFF0000, "white" to 0xFFFFFF, "yellow" to 0xFFFF00,
    )

    /** However deep a file nests its lists, no deeper than Word's own nine levels. */
    private const val DEEPEST_LIST_LEVEL = 8

    /** However deep a file claims its styles are based on each other, no deeper than this. */
    private const val MOST_STYLES_IN_A_CHAIN = 32

    /** One cell as the file writes it, before the merges are read out of the grid. */
    private class Cell(
        val blocks: List<Block>,
        val columnSpan: Int,
        val startsMerge: Boolean,
        val continuesMerge: Boolean,
    )

    /**
     * How many rows a merge that begins at ([row], [column]) reaches down:
     * itself and every row below whose cell in that same column of the
     * grid — not the same place in the row's own list — continues it.
     */
    private fun mergeDepth(grid: List<List<Cell>>, columns: List<List<Int>>, row: Int, column: Int): Int {
        var depth = 1
        for (below in row + 1 until grid.size) {
            val index = columns[below].indexOf(column)
            val cell = if (index >= 0) grid[below][index] else null
            if (cell == null || !cell.continuesMerge) break
            depth++
        }
        return depth
    }

    /** However many a cell claims to cover, no more than this: a broken file will not build a table of millions. */
    private const val MOST_SPANNED_CELLS = 256

    /** A border element that draws something: any style but none or nil. */
    private fun isBorder(border: Element): Boolean =
        attr(border, "val")?.let { it != "none" && it != "nil" } ?: false

    /** A length in twentieths of a point, as OOXML measures, in points; null when absent or not a number. */
    private fun twips(value: String?): Float? =
        value?.trim()?.toFloatOrNull()?.let { it / 20f }

    /** A run with no w:t at all (drawings, breaks) carries nothing to keep — unless [media] is given and it draws a picture. */
    private fun parseRun(
        r: Element,
        paragraphRtl: Boolean,
        media: MediaStore? = null,
        notes: Map<Int, List<Block>> = emptyMap(),
        styles: StyleSheet,
        inherited: Map<String, Element> = emptyMap(),
    ): TextRun? {
        if (media != null) {
            val picture = firstChild(r, "drawing")?.let { imageOf(it, media) }
            if (picture != null) return TextRun("", image = picture)
        }
        // The note a mark refers to lives in a part of its own; the mark
        // itself is the run's text, when the reference says one follows.
        val note = firstChild(r, "footnoteReference")
            ?.let { attr(it, "id")?.trim()?.toIntOrNull() }
            ?.let { notes[it] }
        // A run of tabs alone is text too: Word sets a line of dates with one.
        val textElements = children(r).filter { it.localName == "t" || it.localName == "tab" }
        if (textElements.isEmpty()) return null
        val text = textElements.joinToString(separator = "") { if (it.localName == "tab") "\t" else it.textContent }
        // In OOXML the absence of w:rtl means a left-to-right run even inside
        // a bidi paragraph, while the IR's null means "inherit" — so inside an
        // RTL paragraph, LTR is recorded explicitly to keep round-trips true.
        val paragraphDirection: TextDirection? = if (paragraphRtl) TextDirection.LTR else null
        val rPr = firstChild(r, "rPr")
        // What the paragraph hands the run, then the run's own style, then
        // what the run writes on itself: the last one to speak wins.
        val properties = inherited +
            styles.run(firstChild(rPr, "rStyle")?.let { attr(it, "val") }) +
            own(rPr)
        val underline = properties["u"]?.let { attr(it, "val") ?: "single" }
        val rtl = isOn(properties["rtl"])
        // The face a run is set in is the one for its script: a right-to-left
        // run reads the complex-script face, any other the ASCII one.
        val fonts = properties["rFonts"]
        val family = fonts?.let {
            if (rtl) attr(it, "cs") ?: attr(it, "ascii") else attr(it, "ascii") ?: attr(it, "hAnsi") ?: attr(it, "cs")
        }?.takeIf { it.isNotBlank() }
        val halfPoints = properties[if (rtl) "szCs" else "sz"]?.let { attr(it, "val") }?.toFloatOrNull()
            ?: properties["sz"]?.let { attr(it, "val") }?.toFloatOrNull()
        val vertical = properties["vertAlign"]?.let { attr(it, "val") }
        // "auto" means the colour a reader picks for the background, which
        // is the document's own default — the same thing as saying nothing.
        val color = properties["color"]?.let { attr(it, "val") }
            ?.takeIf { it.length == 6 && !it.equals("auto", ignoreCase = true) }
            ?.toIntOrNull(16)
        // A marking is Word's highlighter, which knows sixteen colours by
        // name, or shading, which takes any colour and draws the same.
        val highlight = properties["highlight"]?.let { attr(it, "val") }?.let(HIGHLIGHT_COLORS::get)
            ?: properties["shd"]?.let { attr(it, "fill") }
                ?.takeIf { it.length == 6 && !it.equals("auto", ignoreCase = true) }
                ?.toIntOrNull(16)
        return TextRun(
            text = text,
            bold = isOn(properties["b"]),
            italic = isOn(properties["i"]),
            underline = underline != null && underline != "none",
            language = properties["lang"]?.let { attr(it, "val") ?: attr(it, "bidi") },
            direction = if (rtl) TextDirection.RTL else paragraphDirection,
            fontFamily = family,
            fontSizePt = halfPoints?.takeIf { it > 0f }?.let { it / 2f },
            superscript = vertical == "superscript",
            subscript = vertical == "subscript",
            colorRgb = color,
            highlightRgb = highlight,
            note = note,
        )
    }

    private fun parseTable(
        tbl: Element,
        numbering: Map<String, Map<Int, ListMarker>>,
        media: MediaStore,
        depth: Int,
        notes: Map<Int, List<Block>> = emptyMap(),
        styles: StyleSheet,
    ): Table? {
        // A cell that continues a merge from the row above holds nothing of
        // its own; the model keeps only the cell that began the merge, and
        // says how far down it reaches.
        val tblPr = firstChild(tbl, "tblPr")
        val tableStyleId = firstChild(tblPr, "tblStyle")?.let { attr(it, "val") }
        val fromTable = Inherited(styles.paragraph(tableStyleId), styles.run(tableStyleId))
        var cellsAreRuled = false
        val cells = children(tbl, "tr").map { tr ->
            children(tr, "tc").map { tc ->
                val properties = firstChild(tc, "tcPr")
                val merge = firstChild(properties, "vMerge")
                val continues = merge != null && (attr(merge, "val") ?: "continue") != "restart"
                // A table nobody gave a style to may still be ruled a cell
                // at a time, which is how a hand-drawn table is written.
                firstChild(properties, "tcBorders")?.let { drawn ->
                    if (children(drawn).any(::isBorder)) cellsAreRuled = true
                }
                Cell(
                    blocks = parseBlocks(
                        tc, numbering, media, depth + 1,
                        notes = notes, styles = styles, fromTable = fromTable,
                    ),
                    columnSpan = firstChild(properties, "gridSpan")?.let { attr(it, "val") }?.toIntOrNull()
                        ?.coerceIn(1, MOST_SPANNED_CELLS) ?: 1,
                    startsMerge = merge != null && !continues,
                    continuesMerge = continues,
                )
            }
        }
        // Every place of the grid has a cell in the file, continuations
        // included, so a cell's column is what the cells before it cover.
        val columns = cells.map { row ->
            var column = 0
            row.map { cell ->
                val at = column
                column += cell.columnSpan
                at
            }
        }
        val rows = cells.mapIndexed { rowIndex, row ->
            TableRow(
                row.mapIndexedNotNull { index, cell ->
                    if (cell.continuesMerge) return@mapIndexedNotNull null
                    TableCell(
                        blocks = cell.blocks,
                        columnSpan = cell.columnSpan,
                        rowSpan = if (cell.startsMerge) {
                            mergeDepth(cells, columns, rowIndex, columns[rowIndex][index])
                        } else {
                            1
                        },
                    )
                }
            )
        }
        if (rows.isEmpty()) return null
        val grid = firstChild(tbl, "tblGrid")
            ?.let { children(it, "gridCol").mapNotNull { col -> twips(attr(col, "w")) } }
            ?.takeIf { it.isNotEmpty() && it.all { width -> width > 0f } }
        // Borders live in the table's style until the table overrules it;
        // "none" and "nil" draw nothing, which is a table nobody ruled.
        val drawn = styles.table(tableStyleId) + own(tblPr)
        val borders = drawn["tblBorders"]
        val ruled = (borders != null && children(borders).any(::isBorder)) || cellsAreRuled
        return Table(rows = rows, confidence = 1f, columnWidthsPt = grid, ruled = ruled)
    }

    // ------------------------------------------------------------------
    // word/numbering.xml
    // ------------------------------------------------------------------

    /**
     * numId → the marker at each of its levels, resolved through each num's
     * abstractNum. A list is not one marker but a ladder of them — Word's
     * own default numbers the outer level and letters the one inside it —
     * and every way of counting other than a bullet is a numbered list: a
     * clause lettered (a) is as numbered as one numbered 1.
     */
    private fun parseNumbering(bytes: ByteArray): Map<String, Map<Int, ListMarker>> = try {
        val root = parseXml(bytes).documentElement
        val byAbstractId = mutableMapOf<String, Map<Int, ListMarker>>()
        for (abstractNum in children(root, "abstractNum")) {
            val id = attr(abstractNum, "abstractNumId") ?: continue
            byAbstractId[id] = buildMap {
                for (lvl in children(abstractNum, "lvl")) {
                    val level = attr(lvl, "ilvl")?.toIntOrNull()?.takeIf { it >= 0 } ?: continue
                    val format = firstChild(lvl, "numFmt")?.let { attr(it, "val") } ?: continue
                    markerFor(format)?.let { put(level, it) }
                }
            }
        }
        buildMap {
            for (num in children(root, "num")) {
                val numId = attr(num, "numId") ?: continue
                val abstractId = firstChild(num, "abstractNumId")?.let { attr(it, "val") }
                byAbstractId[abstractId]?.takeIf { it.isNotEmpty() }?.let { put(numId, it) }
            }
        }
    } catch (_: Exception) {
        emptyMap() // a broken numbering part loses markers, never the document
    }

    /**
     * What a level's `w:numFmt` marks its items with. "none" is a list that
     * prints no marker at all, which is indentation rather than a list.
     */
    private fun markerFor(format: String): ListMarker? = when (format) {
        "bullet" -> ListMarker.BULLET
        "none" -> null
        else -> ListMarker.NUMBERED
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

    private fun firstChild(parent: Element?, localName: String): Element? =
        parent?.let { children(it, localName).firstOrNull() }

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
