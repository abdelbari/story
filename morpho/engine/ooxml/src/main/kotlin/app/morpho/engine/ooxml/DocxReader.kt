package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Bidi
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.Comment
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.DocumentProperties
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.LineBreaks
import app.morpho.engine.layout.ListLabels
import app.morpho.engine.layout.Links
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
import java.util.IdentityHashMap
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
    /** The kind of break `<w:br/>` is when it does not say: a line break. */
    private const val TEXT_WRAPPING = "textWrapping"
    /** One inch: what a section that names a page size but no margins gets. */
    private const val DEFAULT_MARGIN_PT = 72f
    private const val MAX_PART_BYTES = 32 * 1024 * 1024
    private const val MAX_MEDIA_PART_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_MEDIA_BYTES = 64 * 1024 * 1024
    /** The package's own relationships, which say where its document is. */
    private const val PACKAGE_RELS = "_rels/.rels"

    /** Where a package keeps what the document says about itself. */
    private const val CORE_PROPS = "docProps/core.xml"
    private const val OFFICE_DOCUMENT =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    /** Where a .docx keeps its document unless its relationships say otherwise. */
    private const val CONVENTIONAL_MAIN_PART = "word/document.xml"
    /**
     * The parts a document is made of, by the shape of their names.
     *
     * A package is read once, and which directory it keeps its document in
     * is not known until its relationships have been read out of it — so
     * the shapes are matched while reading and the directory is settled
     * afterwards. A .docx has one such directory, `word/`, so matching any
     * of them costs nothing and reads a package that names its own
     * differently.
     */
    private val DOCUMENT_PART = Regex("[^/]+/document\\d*\\.xml")
    private val SIDE_PART = Regex("[^/]+/(?:styles|numbering|footnotes|endnotes|comments)\\.xml")
    private val FURNITURE_PART = Regex("[^/]+/(?:header|footer)\\d*\\.xml")
    private val RELATIONSHIPS_PART = Regex("[^/]+/_rels/[^/]+\\.rels")
    private val MEDIA_PART = Regex("[^/]+/media/[^/]+")
    private const val EMU_PER_PT = 12700L
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MATH_NS = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    /** The language Word wrote its pictures in before DrawingML, and still writes some in. */
    private const val VML_NS = "urn:schemas-microsoft-com:vml"
    private const val EMU_PER_PX = 9525L
    private val MIME_BY_EXTENSION = mapOf("png" to "image/png", "jpeg" to "image/jpeg", "jpg" to "image/jpeg")
    /**
     * Elements that hold a paragraph's runs and are not the runs.
     *
     * A hyperlink and a field are handled on their own, since each says
     * something about the runs it holds. These say nothing: what is
     * inside them is the document's text and belongs to it. A wrapper the
     * reader does not know is walked past in silence, and its words never
     * reach the document at all — which is how a paragraph can come back
     * empty from a file that plainly has words in it.
     *
     * Two wrappers are deliberately not here. `w:del` holds text somebody
     * deleted with changes tracked, and `w:moveFrom` holds text that has
     * been moved away: both are what the document used to say. Reading
     * them back in would put a struck clause and a moved paragraph into a
     * document that no longer has them, which is the worst kind of wrong
     * a converter can be.
     */
    private val RUN_CONTAINERS = setOf(
        // Tracked changes: an insertion is in the document, and so is text
        // moved to where it now stands.
        "ins", "moveTo",
        // Wrappers a producer puts round runs to carry something of its
        // own: a smart tag, a content control, custom XML of a template.
        "smartTag", "sdt", "sdtContent", "customXml",
        // A direction override — which is what a producer marking a
        // right-to-left run writes, so an Arabic document is exactly the
        // kind that loses text by this being missed.
        "dir", "bdo",
    )

    fun read(bytes: ByteArray): DocumentModel = read(ByteArrayInputStream(bytes))

    /** Reads the package and closes [input]. */
    fun read(input: InputStream): DocumentModel {
        try {
            val parts = readNeededParts(input)
            // Where the package says its document is, which is not always
            // where a document usually is.
            val documentName = mainPartName(parts)
            val documentPart = parts[documentName]
                ?: throw IllegalArgumentException("Not a .docx package: $documentName is missing.")
            val at = documentName.substringBeforeLast('/', "")
            val numbering = parts[beside(at, "numbering.xml")]?.let(::parseNumbering).orEmpty()
            val styles = StyleSheet(parts[beside(at, "styles.xml")])
            val media = MediaStore(parts, relationshipsOf(documentName), at)
            val body = firstChild(parseXml(documentPart).documentElement, "body")
                ?: return DocumentModel(blocks = emptyList())
            val sectPr = mainSection(body)
            // The notes parts, read first: a mark in the text refers to a
            // note by number, and the note lives out here. A document counts
            // its footnotes and its endnotes apart, so note 1 may be two
            // different notes and each is kept under the kind it belongs to.
            val notes = Notes(
                notesOf(parts[beside(at, "footnotes.xml")], "footnote", numbering, media, styles) +
                    notesOf(parts[beside(at, "endnotes.xml")], "endnote", numbering, media, styles)
            )
            // What people said about the document while reading it. Read
            // before the text, since a run has to be told which notes it is
            // the subject of as it is made.
            val remarks = commentsOf(parts[beside(at, "comments.xml")], numbering, media, styles)
            val blocks = parseBlocks(
                body, numbering, media, depth = 0, notes = notes, styles = styles,
                sections = sectionShapes(body),
                anchors = commentAnchors(body, remarks),
            )
            // An address a document merely writes out is made a link, the
            // way it is for a PDF and for a plain text file. The same
            // sentence in the same document was clickable when it arrived
            // as a PDF and plain when it arrived as a .docx, and an author
            // who typed an address without making a link of it did not mean
            // it to be uncopyable — which is the whole argument for finding
            // them in the first place. A run that carries a link already is
            // left alone, so nothing the file itself said is overruled.
            return Links.refine(
                DocumentModel(
                    blocks = blocks,
                    defaultLanguage = styles.language,
                    // Which way the document runs, as its section says. Word
                    // marks a right-to-left document with one element in its
                    // section properties, and a reader that never asked hands
                    // back an Arabic document laid out from the left: its
                    // tables read backwards, its running head sits at the
                    // wrong margin, and every paragraph that did not say so
                    // for itself is turned round.
                    //
                    // Where the section says nothing — which is most files, a
                    // real Arabic paper among them, since Word writes the mark
                    // on the paragraphs and leaves the section bare — the
                    // document's own words decide, as they do for a page with
                    // no tags and for a plain text file.
                    defaultDirection = firstChild(sectPr, "bidi")?.let {
                        if (isOn(it)) TextDirection.RTL else TextDirection.LTR
                    } ?: Bidi.dominantDirection(wordsOf(blocks)) ?: TextDirection.LTR,
                    pageSetup = sectPr?.let(::parsePageSetup),
                    header = sectPr?.let { furniture(it, "headerReference", parts, media, numbering, styles, at) }.orEmpty(),
                    footer = sectPr?.let { furniture(it, "footerReference", parts, media, numbering, styles, at) }.orEmpty(),
                    evenHeader = sectPr?.let {
                        furniture(it, "headerReference", parts, media, numbering, styles, at, side = "even")
                    }.orEmpty(),
                    evenFooter = sectPr?.let {
                        furniture(it, "footerReference", parts, media, numbering, styles, at, side = "even")
                    }.orEmpty(),
                    properties = said(parts),
                    comments = remarks,
                )
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a readable .docx package.", e)
        }
    }

    /**
     * What the package says the document is: its title, who wrote it, what
     * it is about. Word shows these in its Properties pane and a search
     * over a folder reads them before a word of the text, and a converter
     * that drops them hands back a document that has forgotten its name.
     *
     * A package with no such part, or one that cannot be parsed, says
     * nothing — which is what a great many .docx files do say.
     */
    private fun said(parts: Map<String, ByteArray>): DocumentProperties {
        val bytes = parts[CORE_PROPS] ?: return DocumentProperties()
        val root = runCatching { parseXml(bytes).documentElement }.getOrNull() ?: return DocumentProperties()
        // Core properties are written in namespaces of their own — Dublin
        // Core for the title and the author — so they are looked up by
        // name rather than through the WordprocessingML helpers.
        fun value(name: String): String? {
            val nodes = root.childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node is Element && node.localName == name) return node.textContent
            }
            return null
        }
        // The converter signs what it writes; read back as an author it
        // would replace the one the file had, converted file by converted
        // file, until nobody wrote anything.
        val author = value("creator")?.takeIf { it.trim() != "Morpho" }
        return DocumentProperties.of(value("title"), author, value("subject"), value("keywords"))
    }

    private fun readNeededParts(input: InputStream): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        var totalMedia = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory &&
                    (
                        entry.name == PACKAGE_RELS || entry.name == CORE_PROPS ||
                            DOCUMENT_PART.matches(entry.name) || SIDE_PART.matches(entry.name) ||
                            FURNITURE_PART.matches(entry.name) || RELATIONSHIPS_PART.matches(entry.name)
                        )
                ) {
                    parts[entry.name] = readBounded(zip, entry.name, MAX_PART_BYTES)
                } else if (!entry.isDirectory && MEDIA_PART.matches(entry.name)) {
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
        numbering: Map<String, Map<Int, ListLevel>>,
        styles: StyleSheet,
        at: String,
        /**
         * Which side's. A book names one head for its right-hand pages and
         * another for its left, and reading only the first would put one of
         * them on every page of the converted document.
         */
        side: String = "default",
    ): List<Block> {
        val references = children(sectPr, reference)
        // Failing a reference for this side, the default side takes any
        // that is not the other side's: a document that names only a first
        // page's head still has one, and losing it would be worse than
        // repeating it.
        val chosen = references.firstOrNull { attr(it, "type") == side }
            ?: (if (side == "default") references.firstOrNull { attr(it, "type") != "even" } else null)
            ?: return emptyList()
        val relId = chosen.getAttributeNS(R_NS, "id").ifEmpty { return emptyList() }
        val partName = media.partFor(relId) ?: return emptyList()
        val bytes = parts[partName] ?: return emptyList()
        val root = runCatching { parseXml(bytes).documentElement }.getOrNull() ?: return emptyList()
        val rels = relationshipsOf(partName)
        // A paragraph that is nothing but a picture is the picture, as it was written.
        return parseBlocks(root, numbering, MediaStore(parts, rels, at), depth = 0, inline = true, styles = styles).map { block ->
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
    /** [name] beside a part kept in directory [at], or at the package root when there is none. */
    private fun beside(at: String, name: String): String = if (at.isEmpty()) name else "$at/$name"

    /** Where a part's own relationships are kept: `word/_rels/document.xml.rels` beside `word/document.xml`. */
    private fun relationshipsOf(part: String): String {
        val at = part.substringBeforeLast('/', "")
        return beside(at, "_rels/" + part.substringAfterLast('/') + ".rels")
    }

    /**
     * Where the package says its document is.
     *
     * OPC names the main part by a relationship rather than by a path, and
     * Word writes `word/document2.xml` after it has repaired a file — a
     * document Word itself opens without a word, which a reader that knows
     * only the conventional path calls "not a .docx". Where the package
     * says nothing, or names a part it does not hold, the conventional
     * path is what a .docx means.
     */
    private fun mainPartName(parts: Map<String, ByteArray>): String {
        val declared = parts[PACKAGE_RELS]
            ?.let { runCatching { targetOf(it, OFFICE_DOCUMENT) }.getOrNull() }
            ?.removePrefix("/")
            ?.takeIf { parts.containsKey(it) }
        return declared ?: CONVENTIONAL_MAIN_PART
    }

    /** Where the relationship of [type] in a relationships part points, as it was written. */
    private fun targetOf(bytes: ByteArray, type: String): String? {
        val root = parseXml(bytes).documentElement
        val relationships = root.getElementsByTagNameNS(REL_NS, "Relationship")
        for (index in 0 until relationships.length) {
            val relationship = relationships.item(index) as Element
            if (relationship.getAttribute("Type") == type) {
                return relationship.getAttribute("Target").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private class MediaStore(parts: Map<String, ByteArray>, relsPart: String, private val at: String) {
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
                at.isEmpty() -> target
                else -> "$at/$target"
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

    /**
     * Which notes each run of the text is the subject of.
     *
     * Word writes a comment as three marks standing beside the words it
     * is about: one where the stretch opens, one where it closes, and a
     * reference to the note after the close. Nothing on the runs
     * themselves says that anybody has commented on them, so the body is
     * walked once in the order it is written and every run met while a
     * stretch is open is remembered against the note.
     *
     * The walk is over the whole body rather than paragraph by paragraph
     * because a stretch may open in one paragraph and close in another —
     * which is what a note about a whole passage looks like in the file.
     */
    private class CommentAnchors(private val covering: IdentityHashMap<Element, List<Int>>) {
        /** The notes the run [r] is the subject of, by [Comment.id]. */
        fun on(r: Element): List<Int> = covering[r].orEmpty()

        companion object {
            /** What a document with no comments in it says about every run. */
            val NONE = CommentAnchors(IdentityHashMap())
        }
    }

    /**
     * Where each note in [remarks] reaches, read off the marks in [body].
     *
     * A mark naming a note the comments part does not hold is ignored:
     * Word leaves such a mark behind after a comment is deleted, and a
     * reader that trusted it would anchor a note that no longer exists.
     */
    private fun commentAnchors(body: Element, remarks: List<Comment>): CommentAnchors {
        if (remarks.isEmpty()) return CommentAnchors.NONE
        val known = remarks.mapTo(HashSet()) { it.id }
        val covering = IdentityHashMap<Element, List<Int>>()
        val open = LinkedHashSet<Int>()
        fun walk(element: Element, depth: Int) {
            if (depth > MAX_NESTING_DEPTH) return
            for (child in elementChildren(element)) {
                if (child.namespaceURI != W) continue
                when (child.localName) {
                    "commentRangeStart" ->
                        attr(child, "id")?.toIntOrNull()?.takeIf { it in known }?.let { open += it }
                    "commentRangeEnd" ->
                        attr(child, "id")?.toIntOrNull()?.let { open -= it }
                    "r" -> if (open.isNotEmpty()) covering[child] = open.toList()
                    else -> walk(child, depth + 1)
                }
            }
        }
        walk(body, 0)
        return CommentAnchors(covering)
    }

    /**
     * What each note in the comments part says, and who said it.
     *
     * The note's own words are read as paragraphs of the document, so a
     * note left in Arabic comes back the way it was written. A note of
     * several paragraphs is kept as one piece of text with a newline
     * between them, which is what the model holds and what every writer
     * can show.
     */
    private fun commentsOf(
        part: ByteArray?,
        numbering: Map<String, Map<Int, ListLevel>>,
        media: MediaStore,
        styles: StyleSheet,
    ): List<Comment> {
        val bytes = part ?: return emptyList()
        val root = runCatching { parseXml(bytes).documentElement }.getOrNull() ?: return emptyList()
        val remarks = mutableListOf<Comment>()
        for (element in elementChildren(root)) {
            if (element.namespaceURI != W || element.localName != "comment") continue
            val id = attr(element, "id")?.toIntOrNull() ?: continue
            val text = parseBlocks(element, numbering, media, depth = 0, styles = styles)
                .filterIsInstance<Paragraph>()
                .joinToString("\n") { it.text }
            remarks += Comment(
                id = id,
                text = text,
                author = attr(element, "author")?.takeIf { it.isNotBlank() },
                initials = attr(element, "initials")?.takeIf { it.isNotBlank() },
                dateIso = attr(element, "date")?.takeIf { it.isNotBlank() },
            )
        }
        return remarks
    }

    /** [inline] keeps a paragraph's pictures in its line as runs — how a running header carries its artwork — instead of after it. */
    private fun parseBlocks(
        parent: Element,
        numbering: Map<String, Map<Int, ListLevel>>,
        media: MediaStore,
        depth: Int,
        inline: Boolean = false,
        notes: Notes = Notes(),
        styles: StyleSheet,
        fromTable: Inherited = Inherited.NONE,
        /** The shape of each section of the body, in order; empty anywhere but the body. */
        sections: List<PageSetup?> = emptyList(),
        anchors: CommentAnchors = CommentAnchors.NONE,
    ): List<Block> {
        require(depth <= MAX_NESTING_DEPTH) {
            "Block nesting deeper than $MAX_NESTING_DEPTH levels; refusing to parse."
        }
        val blocks = mutableListOf<Block>()
        // A page break somebody typed belongs to whatever comes after it.
        var brokenTo = false
        // So does a bookmark opened between paragraphs, which is how Word
        // writes one that was put around several of them at once.
        var openedNames = mutableListOf<String>()
        // Word says a section's properties on the last paragraph of it, so
        // the paragraph after one of those starts the next section. The
        // document's own shape is the one most of it is set on, so the
        // first section only says anything when it differs from that.
        var sectionsPassed = 0
        var startsSection: PageSetup? = sections.firstOrNull()
            ?.takeIf { first -> sections.size > 1 && first != mostCommon(sections) }

        /** [block], starting a page and answering to the names opened before it. */
        fun add(block: Block) {
            val broken = brokenTo && block is Paragraph
            brokenTo = brokenTo && !broken
            blocks += if (block is Paragraph) {
                var style = block.style
                if (broken) style = style.copy(pageBreakBefore = true)
                startsSection?.let { style = style.copy(sectionSetup = it) }
                startsSection = null
                val named = block.copy(style = style, bookmarks = openedNames + block.bookmarks)
                openedNames = mutableListOf()
                named
            } else {
                block
            }
        }

        for (child in children(parent)) {
            when (child.localName) {
                "p" -> {
                    // Ctrl+Enter, which is how most page breaks in most
                    // documents are made, writes a break into a run rather
                    // than a property on a paragraph: a paragraph that is
                    // nothing but the break was dropped for having no words
                    // in it, and the break went with it.
                    val beforeText = pageBreakBeforeText(child)
                    if (beforeText == true) brokenTo = true
                    parseParagraph(child, numbering, media, inline, notes, styles, fromTable, anchors)
                        ?.let(::add)
                    // A break after this paragraph's words leaves it on the
                    // page it began and starts the next one on a fresh page.
                    if (beforeText == false) brokenTo = true
                    // A paragraph carrying a section's properties is the
                    // last of that section, whatever else it holds — even
                    // when it holds nothing and no block came of it.
                    if (sections.isNotEmpty() && firstChild(firstChild(child, "pPr"), "sectPr") != null) {
                        sectionsPassed++
                        startsSection = sections.getOrNull(sectionsPassed)
                    }
                    if (!inline) blocks += parseImages(child, media)
                    // What a text box holds is text of the document, and it
                    // is written inside the run it is anchored to rather
                    // than in the body: a poster, a CV, a form laid out in
                    // boxes converts to almost nothing without this.
                    for (box in textBoxesIn(child)) {
                        parseBlocks(
                            box, numbering, media, depth + 1,
                            inline = inline, notes = notes, styles = styles, fromTable = fromTable,
                            anchors = anchors,
                        ).forEach(::add)
                    }
                }
                "tbl" -> parseTable(child, numbering, media, depth, notes, styles, anchors)?.let(::add)
                // A content control wraps what it holds rather than
                // replacing it: a cover page, a table of contents, the
                // fields of a template. What is inside is the document.
                // Custom XML wraps whole paragraphs the same way, and
                // walking past one loses every paragraph it holds.
                "customXml" -> parseBlocks(
                    child, numbering, media, depth + 1,
                    inline = inline, notes = notes, styles = styles, fromTable = fromTable,
                    anchors = anchors,
                ).forEach(::add)
                "sdt" -> firstChild(child, "sdtContent")?.let { held ->
                    parseBlocks(
                        held, numbering, media, depth + 1,
                        inline = inline, notes = notes, styles = styles, fromTable = fromTable,
                        anchors = anchors,
                    ).forEach(::add)
                }
                "bookmarkStart" -> attr(child, "name")
                    ?.takeIf { it.isNotBlank() && it != "_GoBack" }
                    ?.let { openedNames += it }
                else -> {} // sectPr, bookmarkEnd, anything the reader does not know
            }
        }
        return blocks
    }

    /** PNG/JPEG drawings in a paragraph, emitted after its text. */
    private fun parseImages(p: Element, media: MediaStore): List<ImageBlock> = picturesIn(p, media)

    /**
     * Every picture under [element], each counted once.
     *
     * Word writes a shape twice where it can: the way it prefers, and a
     * fallback drawn the old way for a reader that does not know the new
     * one. Both hold the same picture, so a walk that gathers every
     * `w:drawing` and every `w:pict` it can see puts it into the document
     * twice — which is what the same walk would do to a text box, and why
     * [textBoxesIn] has always chosen one branch and left the other.
     */
    private fun picturesIn(element: Element, media: MediaStore, depth: Int = 0): List<ImageBlock> {
        if (depth > MAX_NESTING_DEPTH) return emptyList()
        val found = mutableListOf<ImageBlock>()
        for (child in elementChildren(element)) {
            when (child.localName) {
                "AlternateContent" -> {
                    val chosen = elementChildren(child).firstOrNull { it.localName == "Choice" }
                        ?: elementChildren(child).firstOrNull { it.localName == "Fallback" }
                    if (chosen != null) found += picturesIn(chosen, media, depth + 1)
                }
                "drawing" -> imageOf(child, media)?.let(found::add)
                // A picture drawn the old way, and the preview picture an
                // embedded object shows for itself — an equation from the
                // old editor, a chart pasted from a spreadsheet. The thing
                // itself cannot be carried; the picture of it can, and is
                // what a reader of the document sees.
                "pict", "object" -> legacyImageOf(child, media)?.let(found::add)
                // A text box holds text rather than a picture of it, and is
                // read where the blocks are read.
                "txbxContent" -> {}
                else -> found += picturesIn(child, media, depth + 1)
            }
        }
        return found
    }

    /**
     * The picture a legacy `w:pict` holds.
     *
     * Word drew pictures this way before DrawingML and still does for
     * some of them — anything pasted in compatibility mode, an equation
     * saved as a picture, the output of a good many converters. Looked
     * for only under `w:drawing`, every one of them is dropped: a
     * converted document simply has no picture where the original plainly
     * has one, and nothing says so.
     *
     * The size is in the shape's own style rather than in an extent, so
     * it is read from there and left to the writer where it says nothing
     * this reader understands.
     */
    private fun legacyImageOf(pict: Element, media: MediaStore): ImageBlock? {
        val data = descendantsNS(pict, VML_NS, "imagedata").firstOrNull() ?: return null
        val relId = data.getAttributeNS(R_NS, "id").ifEmpty { null } ?: return null
        val (bytes, mime) = media.imageFor(relId)?.let { it.first to it.second } ?: return null
        val style = (data.parentNode as? Element)?.getAttribute("style").orEmpty()
        val widthPt = styleLength(style, "width")
        val heightPt = styleLength(style, "height")
        return ImageBlock(
            bytes = bytes,
            mimeType = mime,
            widthPx = widthPt?.let { (it * PX_PER_PT).toInt() }?.coerceAtLeast(1) ?: 1,
            heightPx = heightPt?.let { (it * PX_PER_PT).toInt() }?.coerceAtLeast(1) ?: 1,
            confidence = 1f,
            widthPt = widthPt,
            heightPt = heightPt,
        )
    }

    /** Screen dots to a point, as a browser and Word both count them. */
    private const val PX_PER_PT = 96f / 72f

    /**
     * [name]'s length out of a shape's CSS-shaped style, in points, or
     * null where it says nothing or says it in a unit this does not know.
     */
    private fun styleLength(style: String, name: String): Float? {
        val said = style.split(';')
            .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')?.trim() ?: return null
        val number = Regex("-?[0-9]*\\.?[0-9]+").find(said)?.value?.toFloatOrNull() ?: return null
        val unit = said.dropWhile { it == '-' || it.isDigit() || it == '.' }.trim().lowercase()
        val points = when (unit) {
            "pt", "" -> number
            "px" -> number / PX_PER_PT
            "in" -> number * 72f
            "cm" -> number * 72f / 2.54f
            "mm" -> number * 72f / 25.4f
            "pc" -> number * 12f
            else -> return null
        }
        return points.takeIf { it > 0f && it.isFinite() }
    }

    /** The picture a drawing embeds, at the size its extent gives it, or null when it is not one the reader keeps. */
    private fun imageOf(drawing: Element, media: MediaStore): ImageBlock? {
        val blip = descendantsNS(drawing, A_NS, "blip").firstOrNull() ?: return null
        val relId = blip.getAttributeNS(R_NS, "embed").ifEmpty { null } ?: return null
        val (bytes, mime) = media.imageFor(relId)?.let { it.first to it.second } ?: return null
        val extent = descendantsNS(drawing, WP_NS, "extent").firstOrNull()
        val cx = extent?.getAttribute("cx")?.toLongOrNull() ?: 0L
        val cy = extent?.getAttribute("cy")?.toLongOrNull() ?: 0L
        // What the picture shows, as Word keeps it: the alternative text
        // on the drawing's own properties. It is the only words a
        // photographed running head has, and reading it back is what
        // keeps them through a second conversion.
        val said = descendantsNS(drawing, WP_NS, "docPr").firstOrNull()
            ?.getAttribute("descr")?.trim()?.takeIf { it.isNotEmpty() }
        return ImageBlock(
            bytes = bytes,
            mimeType = mime,
            widthPx = (cx / EMU_PER_PX).toInt().coerceAtLeast(1),
            heightPx = (cy / EMU_PER_PX).toInt().coerceAtLeast(1),
            confidence = 1f,
            widthPt = (cx.toFloat() / EMU_PER_PT).takeIf { it > 0f },
            heightPt = (cy.toFloat() / EMU_PER_PT).takeIf { it > 0f },
            description = said,
        )
    }

    /**
     * An equation as a line of text: what somebody would have typed to
     * write it out.
     *
     * Word writes an equation in a language of its own, and nothing this
     * converter writes can hold it as an equation — but a formula dropped
     * out of a paper is a paper that no longer says what it said, so it is
     * written out the way it reads: a fraction as a/b, a power as a^2, a
     * root as √(x), and every symbol as the character it already is.
     */
    private fun mathTextOf(element: Element, depth: Int = 0): String {
        if (depth > MAX_NESTING_DEPTH) return ""
        fun partsOf(parent: Element, name: String): String =
            elementChildren(parent).filter { it.localName == name }
                .joinToString(separator = "") { mathTextOf(it, depth + 1) }

        return when (element.localName) {
            "t" -> element.textContent
            // A fraction: numerator over denominator, bracketed where it
            // takes more than one character to say.
            "f" -> {
                val over = bracketed(partsOf(element, "num"))
                val under = bracketed(partsOf(element, "den"))
                if (over.isEmpty() && under.isEmpty()) "" else "$over/$under"
            }
            "sSup" -> partsOf(element, "e") + "^" + bracketed(partsOf(element, "sup"))
            "sSub" -> partsOf(element, "e") + "_" + bracketed(partsOf(element, "sub"))
            "sSubSup" ->
                partsOf(element, "e") + "_" + bracketed(partsOf(element, "sub")) +
                    "^" + bracketed(partsOf(element, "sup"))
            "rad" -> "√(" + partsOf(element, "e") + ")"
            "d" -> "(" + elementChildren(element).filter { it.localName == "e" }
                .joinToString(separator = ", ") { mathTextOf(it, depth + 1) } + ")"
            else -> elementChildren(element).joinToString(separator = "") { mathTextOf(it, depth + 1) }
        }
    }

    /** A part of a formula in brackets, unless it is one character and needs none. */
    private fun bracketed(text: String): String =
        if (text.length <= 1) text else "($text)"

    /**
     * Whether [p] carries a page break somebody typed, and if so whether it
     * comes before any of the paragraph's own words: null for a paragraph
     * with no break in it, true for a break before the text, false for one
     * after it.
     */
    /**
     * Whether [element] is the break that moves to the next line inside a
     * paragraph — Word's shift+Enter, and the one the format leaves
     * untyped because it is the ordinary kind.
     *
     * The two that carry a type break the page and the column, and are
     * not text: a page break is read as the paragraph property it amounts
     * to, in [pageBreakBeforeText]. Reading them here as well would put a
     * stray line into every paragraph that starts a page.
     */
    private fun isLineBreak(element: Element): Boolean =
        element.localName == "br" && (attr(element, "type") ?: TEXT_WRAPPING) == TEXT_WRAPPING

    private fun pageBreakBeforeText(p: Element): Boolean? {
        var sawText = false
        var answer: Boolean? = null

        fun walk(parent: Element, depth: Int) {
            if (depth > MAX_NESTING_DEPTH || answer != null) return
            for (child in children(parent)) {
                when (child.localName) {
                    "t", "tab" -> if (child.textContent.isNotEmpty() || child.localName == "tab") {
                        sawText = true
                    }
                    "br" -> if (attr(child, "type") == "page") {
                        answer = !sawText
                        return
                    }
                    // A break may be written inside a tracked insertion or
                    // a content control like anything else in a paragraph.
                    "r", in RUN_CONTAINERS, "hyperlink", "fldSimple" -> walk(child, depth + 1)
                }
                if (answer != null) return
            }
        }

        walk(p, 0)
        return answer
    }

    /**
     * The text boxes anchored in [element], in document order.
     *
     * Word writes a text box inside the run it is anchored to, twice over:
     * once as it draws one now and once as a Word of 2007 would, wrapped in
     * a choice between them. Reading both would say everything in the box
     * twice, so only the one Word itself would use is read.
     */
    private fun textBoxesIn(element: Element, depth: Int = 0): List<Element> {
        if (depth > MAX_NESTING_DEPTH) return emptyList()
        val found = mutableListOf<Element>()
        for (child in elementChildren(element)) {
            when (child.localName) {
                "AlternateContent" -> {
                    val chosen = elementChildren(child).firstOrNull { it.localName == "Choice" }
                        ?: elementChildren(child).firstOrNull { it.localName == "Fallback" }
                    if (chosen != null) found += textBoxesIn(chosen, depth + 1)
                }
                "txbxContent" -> found += child
                else -> found += textBoxesIn(child, depth + 1)
            }
        }
        return found
    }

    /** Every element directly inside [parent], whatever namespace it is written in. */
    private fun elementChildren(parent: Element): List<Element> {
        val result = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node is Element) result += node
            node = node.nextSibling
        }
        return result
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
        numbering: Map<String, Map<Int, ListLevel>>,
        media: MediaStore? = null,
        inline: Boolean = false,
        notes: Notes = Notes(),
        styles: StyleSheet,
        fromTable: Inherited = Inherited.NONE,
        anchors: CommentAnchors = CommentAnchors.NONE,
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
            anchors = anchors,
        )
        if (runs.isEmpty()) return null
        return Paragraph(runs = runs, style = style, confidence = 1f, bookmarks = bookmarksOf(p))
    }

    /**
     * The names bookmarked on [p]: what a table of contents, a
     * cross-reference or an index points at. Word's own `_GoBack` marks
     * where the writer was last typing and means nothing to a reader.
     */
    private fun bookmarksOf(p: Element): List<String> =
        children(p)
            .filter { it.namespaceURI == W && it.localName == "bookmarkStart" }
            .mapNotNull { attr(it, "name") }
            .filter { it.isNotBlank() && it != "_GoBack" }

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
        notes: Notes = Notes(),
        styles: StyleSheet,
        inherited: Map<String, Element> = emptyMap(),
        anchors: CommentAnchors = CommentAnchors.NONE,
    ): List<TextRun> {
        require(depth <= MAX_NESTING_DEPTH) {
            "Run-container nesting deeper than $MAX_NESTING_DEPTH levels; refusing to parse."
        }
        val runs = mutableListOf<TextRun>()
        // A field written the long way round: a run that begins it, runs
        // holding the instruction, a run that separates, the runs holding
        // what it last worked out to, and a run that ends it. Word writes
        // a page number and often a link this way, and read as the plain
        // text of its result a numbered footer says the same number on
        // every page it is stamped on.
        var fieldDepth = 0
        var inResult = false
        var resultFrom = 0
        val instruction = StringBuilder()
        // Every element, not only Word's own: an equation is written in a
        // language of its own, in the paragraph beside the runs rather than
        // inside one, and a walk that sees only Word's elements walks past it.
        for (child in elementChildren(parent)) {
            if (child.namespaceURI == MATH_NS) {
                if (child.localName == "oMath" || child.localName == "oMathPara") {
                    mathTextOf(child).takeIf { it.isNotBlank() }?.let { runs += TextRun(it) }
                }
                continue
            }
            if (child.namespaceURI != W) continue
            // A run that says something about a field rather than holding
            // the document's words, and the runs of a field's instruction,
            // are the field's own scaffolding and are not text.
            if (child.localName == "r") {
                val mark = firstChild(child, "fldChar")?.let { attr(it, "fldCharType") }
                when (mark) {
                    "begin" -> {
                        if (fieldDepth == 0) {
                            instruction.setLength(0)
                            inResult = false
                            resultFrom = runs.size
                        }
                        fieldDepth++
                        continue
                    }
                    "separate" -> {
                        if (fieldDepth == 1) {
                            inResult = true
                            resultFrom = runs.size
                        }
                        continue
                    }
                    "end" -> {
                        fieldDepth = (fieldDepth - 1).coerceAtLeast(0)
                        if (fieldDepth == 0) {
                            applyField(runs, resultFrom, instruction.toString())
                            inResult = false
                        }
                        continue
                    }
                }
                if (fieldDepth > 0 && !inResult) {
                    for (part in children(child, "instrText")) instruction.append(part.textContent)
                    continue
                }
            }
            when (child.localName) {
                "r" ->
                    parseRun(child, paragraphRtl, media.takeIf { inline }, notes, styles, inherited, anchors)
                        ?.let(runs::add)
                "fldSimple" -> {
                    val inner =
                        collectRuns(child, paragraphRtl, depth + 1, media, inline, notes, styles, inherited, anchors)
                    val instruction = attr(child, "instr").orEmpty().trim().uppercase()
                    runs += if (instruction.startsWith("PAGE")) inner.map { it.copy(field = RunField.PAGE_NUMBER) } else inner
                }
                "hyperlink" -> {
                    val inner =
                        collectRuns(child, paragraphRtl, depth + 1, media, inline, notes, styles, inherited, anchors)
                    val target = child.getAttributeNS(R_NS, "id").ifEmpty { null }?.let { media?.targetFor(it) }
                        ?: attr(child, "anchor")?.let { "#$it" }
                    runs += if (target != null) inner.map { it.copy(link = target) } else inner
                }
                in RUN_CONTAINERS -> {
                    // A direction override says which way its runs read
                    // whatever the paragraph around them does, and the
                    // face they are set in follows from that.
                    val turned = if (child.localName == "dir" || child.localName == "bdo") {
                        when (attr(child, "val")?.lowercase()) {
                            "rtl" -> TextDirection.RTL
                            "ltr" -> TextDirection.LTR
                            else -> null
                        }
                    } else {
                        null
                    }
                    val inner = collectRuns(
                        child, turned?.let { it == TextDirection.RTL } ?: paragraphRtl,
                        depth + 1, media, inline, notes, styles, inherited, anchors,
                    )
                    runs += if (turned == null) inner else inner.map { it.copy(direction = turned) }
                }
                else -> {}
            }
        }
        return runs
    }

    /**
     * What a field's instruction makes of the runs holding its result.
     *
     * A page number is a field the writer fills in rather than text, so it
     * counts the pages it is stamped on instead of repeating the one the
     * document was last saved showing. A link written as a field points
     * where its instruction says, the way one written as `w:hyperlink`
     * does. Every other field is left as the words it worked out to,
     * which is what a reader of the document sees.
     */
    private fun applyField(runs: MutableList<TextRun>, from: Int, instruction: String) {
        if (from >= runs.size) return
        val said = instruction.trim()
        val name = said.substringBefore(' ').uppercase()
        val change: (TextRun) -> TextRun = when {
            name == "PAGE" -> { run -> run.copy(field = RunField.PAGE_NUMBER) }
            name == "HYPERLINK" -> {
                val target = linkOf(said) ?: return
                ({ run -> run.copy(link = run.link ?: target) })
            }
            else -> return
        }
        for (index in from until runs.size) runs[index] = change(runs[index])
    }

    /**
     * Where a HYPERLINK field points: the address in quotes after it, or
     * the place in this document its `\l` switch names.
     */
    private fun linkOf(instruction: String): String? {
        val quoted = Regex("\"([^\"]*)\"").findAll(instruction).map { it.groupValues[1] }.toList()
        val anchor = Regex("""\\l\s+"?([^"\s]+)"?""").find(instruction)?.groupValues?.get(1)
        val address = quoted.firstOrNull { it.isNotBlank() && it != anchor }
        return when {
            address != null -> address
            anchor != null -> "#" + anchor
            else -> null
        }
    }

    private fun parseParagraphStyle(
        properties: Map<String, Element>,
        styleId: String?,
        styleName: String?,
        numbering: Map<String, Map<Int, ListLevel>>,
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
        val counting = levels?.let { it[listLevel] ?: it[0] }
        val listMarker = counting?.marker
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
            // Present and off is a paragraph saying outright that it runs
            // left to right, which in a right-to-left document is the only
            // way to say it; absent is a paragraph with nothing to say,
            // which runs the way its section does.
            direction = properties["bidi"]?.let {
                if (isOn(it)) TextDirection.RTL else TextDirection.LTR
            },
            listMarker = listMarker,
            listLevel = if (listMarker == null) 0 else listLevel,
            listFormat = counting?.format?.takeIf { listMarker == ListMarker.NUMBERED },
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
    /**
     * The section most of the document is written in.
     *
     * A document is a run of sections, and each says where it ends: the
     * properties of a section are written on the last paragraph of it, and
     * the last section's on the body itself. Reading the body's alone gives
     * a report of forty portrait pages the shape of the landscape table at
     * the end of it, so the sections are counted and the page most of them
     * are set on wins. Ties go to the first, which is where a document
     * begins.
     */
    private fun mainSection(body: Element): Element? {
        val sections = descendantsNS(body, W, "sectPr")
        if (sections.size <= 1) return sections.firstOrNull()
        val bySize = sections.groupBy { section ->
            firstChild(section, "pgSz")?.let { attr(it, "w") to attr(it, "h") }
        }
        return bySize.values.maxByOrNull { it.size }?.first() ?: sections.first()
    }

    /**
     * The shape each section of the body is set on, in the order the
     * sections come.
     *
     * Word says a section's properties on the last paragraph of it and
     * the last section's on the body itself, so the shapes are simply the
     * sectPr elements in document order — and the paragraph that carries
     * one is the end of its section, which makes the paragraph after it
     * the start of the next.
     */
    /** The shape the most sections share, which is the document's own. */
    private fun mostCommon(sections: List<PageSetup?>): PageSetup? =
        sections.groupBy { it?.widthPt to it?.heightPt }
            .maxByOrNull { (_, alike) -> alike.size }
            ?.value?.first()

    private fun sectionShapes(body: Element): List<PageSetup?> =
        descendantsNS(body, W, "sectPr").map(::parsePageSetup)

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
            // A section with a first page of its own keeps neither head nor
            // foot on it unless it names one for it, which is how a title
            // page comes to be clear.
            differentFirstPage = isOn(firstChild(sectPr, "titlePg")),
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
        /** A table style's own formatting for a place in the table, by style and place. */
        private val conditional = HashMap<String, Element>()

        /** What every paragraph and run starts from, before any style names it. */
        var defaultParagraph: Map<String, Element> = emptyMap()
            private set
        var defaultRun: Map<String, Element> = emptyMap()
            private set

        /**
         * The language the document is written in, as its default run
         * properties name it. A right-to-left document names it in w:bidi,
         * which is where Word keeps a complex script's language, and a
         * left-to-right one in w:val.
         */
        val language: String?
            get() = defaultRun["lang"]?.let { attr(it, "bidi") ?: attr(it, "val") }
                ?.trim()?.takeIf { it.isNotEmpty() }

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
                    // A table style says what the head of a table looks
                    // like, and the head of a report's table is a row of
                    // colour with white type on it.
                    for (place in children(style, "tblStylePr")) {
                        val type = attr(place, "type") ?: continue
                        conditional["$id/$type"] = place
                    }
                    firstChild(style, "basedOn")?.let { attr(it, "val") }?.let { basedOn[id] = it }
                    firstChild(style, "name")?.let { attr(it, "val") }?.let { nameById[id] = it.lowercase() }
                }
            }
        }

        /**
         * What a table of [styleId] does to the cells of [place] — "firstRow"
         * for the head of a table, "firstCol" for the column down its side —
         * or null where the style says nothing about it.
         */
        fun tablePlace(styleId: String?, place: String): Element? =
            styleId?.let { id ->
                var at: String? = id
                val seen = mutableListOf<String>()
                while (at != null && at !in seen && seen.size < MOST_STYLES_IN_A_CHAIN) {
                    conditional["$at/$place"]?.let { return it }
                    seen += at
                    at = basedOn[at]
                }
                null
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
        val shadingRgb: Int? = null,
    )

    /** The colour a w:shd inside [properties] fills with, or null for none. */
    private fun fillOf(properties: Element?): Int? =
        firstChild(properties, "shd")
            ?.takeIf { (attr(it, "val") ?: "clear") != "nil" }
            ?.let { attr(it, "fill") }
            ?.takeIf { it.length == 6 && !it.equals("auto", ignoreCase = true) }
            ?.toIntOrNull(16)

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

    /**
     * Which note a mark refers to: a document counts its footnotes and its
     * endnotes separately, so the number alone does not say which note.
     */
    private data class NoteRef(val kind: String, val id: Int)

    /** The notes of one part, by the reference that names each. */
    private fun notesOf(
        bytes: ByteArray?,
        kind: String,
        numbering: Map<String, Map<Int, ListLevel>>,
        media: MediaStore,
        styles: StyleSheet,
    ): Map<NoteRef, List<Block>> {
        if (bytes == null) return emptyMap()
        return runCatching {
            children(parseXml(bytes).documentElement, kind)
                // The separator a page draws above its notes is written as
                // a note of its own; it is furniture, not a note.
                .filter { attr(it, "type") == null }
                .mapNotNull { note ->
                    val id = attr(note, "id")?.trim()?.toIntOrNull() ?: return@mapNotNull null
                    NoteRef(kind, id) to parseBlocks(note, numbering, media, depth = 0, styles = styles)
                }
                .toMap()
        }.getOrNull().orEmpty()
    }

    /**
     * The notes a document keeps out of its text, and the marks that call
     * them.
     *
     * Word draws a note's number itself, so the run that refers to a note
     * carries no text at all: a reader that keeps only what is written
     * loses the mark and the note with it. The marks are counted here as
     * Word counts them — footnotes 1, 2, 3, endnotes i, ii, iii — in the
     * order the references appear, which is the order they are read in.
     */
    private class Notes(private val byRef: Map<NoteRef, List<Block>> = emptyMap()) {
        private var footnotes = 0
        private var endnotes = 0

        /** The note the run's [reference] points at, if it carries one. */
        fun of(r: Element, reference: String, kind: String): List<Block>? =
            firstChild(r, reference)
                ?.let { attr(it, "id")?.trim()?.toIntOrNull() }
                ?.let { byRef[NoteRef(kind, it)] }

        /** The mark to draw for the next note of [kind], as Word would number it. */
        fun nextMark(kind: String): String =
            if (kind == "endnote") ListLabels.roman(++endnotes) else (++footnotes).toString()

        /**
         * [blocks] without the mark the note repeats at its head. Word
         * draws a note's own number itself, but a note marked by hand —
         * a star, a dagger — has that mark written into it as text, and
         * it is the same mark the reference carries. Kept, it would be
         * said twice: once as the mark and once as the note's first
         * character.
         */
        fun withoutMark(blocks: List<Block>, mark: String): List<Block> {
            val wanted = mark.trim()
            if (wanted.isEmpty()) return blocks
            val first = blocks.firstOrNull() as? Paragraph ?: return blocks
            val opening = first.runs.firstOrNull() ?: return blocks
            if (opening.text.trim() != wanted) return blocks
            val rest = first.runs.drop(1)
            if (rest.isEmpty() || rest.all { it.text.isBlank() }) return blocks
            val trimmed = listOf(rest.first().let { it.copy(text = it.text.trimStart()) }) + rest.drop(1)
            return listOf(first.copy(runs = trimmed)) + blocks.drop(1)
        }
    }

    /** A run with no w:t at all (drawings, breaks) carries nothing to keep — unless [media] is given and it draws a picture. */
    private fun parseRun(
        r: Element,
        paragraphRtl: Boolean,
        media: MediaStore? = null,
        notes: Notes = Notes(),
        styles: StyleSheet,
        inherited: Map<String, Element> = emptyMap(),
        anchors: CommentAnchors = CommentAnchors.NONE,
    ): TextRun? {
        if (media != null) {
            // A picture in a run is the run: whichever way it is drawn, and
            // once however many ways the file draws it.
            val picture = picturesIn(r, media).firstOrNull()
            if (picture != null) return TextRun("", image = picture, commentIds = anchors.on(r))
        }
        // The note a mark refers to lives in a part of its own; the mark
        // itself is the run's text, when the reference says one follows.
        val footnote = notes.of(r, "footnoteReference", "footnote")
        val note = footnote ?: notes.of(r, "endnoteReference", "endnote")
        // A run of tabs alone is text too: Word sets a line of dates with one.
        // So is a run of nothing but a line break, which is exactly how Word
        // writes the break shift+Enter makes.
        val textElements = children(r).filter {
            it.localName == "t" || it.localName == "tab" || isLineBreak(it)
        }
        // A run that refers to a note and writes nothing is Word leaving the
        // number to itself; the mark it would have drawn is made here, since
        // a page has to show something for the note to hang from.
        val drawnMark = if (textElements.isEmpty() && note != null) {
            notes.nextMark(if (footnote != null) "footnote" else "endnote")
        } else {
            null
        }
        if (textElements.isEmpty() && drawnMark == null) return null
        val text = drawnMark
            ?: textElements.joinToString(separator = "") {
                when (it.localName) {
                    "tab" -> "\t"
                    "br" -> LineBreaks.MARK.toString()
                    else -> it.textContent
                }
            }
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
            // Struck once or twice through is struck through either way.
            strikethrough = isOn(properties["strike"]) || isOn(properties["dstrike"]),
            language = properties["lang"]?.let { attr(it, "val") ?: attr(it, "bidi") },
            direction = if (rtl) TextDirection.RTL else paragraphDirection,
            fontFamily = family,
            fontSizePt = halfPoints?.takeIf { it > 0f }?.let { it / 2f },
            // A mark the reader had to make for itself is raised, whatever
            // the run says: Word's own style raises it, and a document that
            // does not name that style still means a footnote mark.
            superscript = vertical == "superscript" || drawnMark != null,
            subscript = vertical == "subscript",
            colorRgb = color,
            highlightRgb = highlight,
            // A note that carries its mark as text says it once, on the
            // run that refers to it, not again at its own head. A note
            // Word numbers itself carries no mark as text at all — it
            // opens with the element that draws the number — so nothing
            // of its own words is taken from it, however they begin.
            note = note?.let { if (drawnMark == null) notes.withoutMark(it, text) else it },
            // Nothing on a run says it has been commented on; the marks
            // that say so stand beside it, and the walk that read them
            // remembered which run each one reaches.
            commentIds = anchors.on(r),
        )
    }

    private fun parseTable(
        tbl: Element,
        numbering: Map<String, Map<Int, ListLevel>>,
        media: MediaStore,
        depth: Int,
        notes: Notes = Notes(),
        styles: StyleSheet,
        anchors: CommentAnchors = CommentAnchors.NONE,
    ): Table? {
        // A cell that continues a merge from the row above holds nothing of
        // its own; the model keeps only the cell that began the merge, and
        // says how far down it reaches.
        val tblPr = firstChild(tbl, "tblPr")
        val tableStyleId = firstChild(tblPr, "tblStyle")?.let { attr(it, "val") }
        val fromTable = Inherited(styles.paragraph(tableStyleId), styles.run(tableStyleId))
        var cellsAreRuled = false
        // What the style fills the head of the table with, when the table
        // says it has one. Word writes the look of a head in the style and
        // nothing at all on the cells, so a report's coloured header row is
        // invisible to a reader that looks only at the cells.
        val look = firstChild(tblPr, "tblLook")
        val hasHead = look == null || attr(look, "firstRow") == "1" ||
            (attr(look, "val")?.let { it.toIntOrNull(16)?.and(0x0020) != 0 } ?: false)
        val headFill = if (!hasHead) null else {
            styles.tablePlace(tableStyleId, "firstRow")
                ?.let { firstChild(it, "tcPr") }
                ?.let { fillOf(it) }
        }
        val cells = children(tbl, "tr").mapIndexed { rowIndex, tr ->
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
                        notes = notes, styles = styles, fromTable = fromTable, anchors = anchors,
                    ),
                    columnSpan = firstChild(properties, "gridSpan")?.let { attr(it, "val") }?.toIntOrNull()
                        ?.coerceIn(1, MOST_SPANNED_CELLS) ?: 1,
                    startsMerge = merge != null && !continues,
                    continuesMerge = continues,
                    // What the cell says it is filled with, else what the
                    // style fills the head of the table with.
                    shadingRgb = fillOf(properties) ?: headFill.takeIf { rowIndex == 0 },
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
        val heads = children(tbl, "tr").map { tr ->
            isOn(firstChild(firstChild(tr, "trPr"), "tblHeader"))
        }
        val rows = cells.mapIndexed { rowIndex, row ->
            TableRow(
                repeatsAsHeader = heads.getOrElse(rowIndex) { false },
                cells = row.mapIndexedNotNull { index, cell ->
                    if (cell.continuesMerge) return@mapIndexedNotNull null
                    TableCell(
                        blocks = cell.blocks,
                        columnSpan = cell.columnSpan,
                        shadingRgb = cell.shadingRgb,
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
        return Table(
            rows = rows,
            confidence = 1f,
            columnWidthsPt = grid,
            ruled = ruled,
            // Word says a table is laid out from the right on the table
            // itself, not on the paragraphs inside it, and a table that
            // does not say so is laid out from the left however the
            // document around it reads.
            direction = if (isOn(drawn["bidiVisual"])) TextDirection.RTL else TextDirection.LTR,
        )
    }

    // ------------------------------------------------------------------
    // word/numbering.xml
    // ------------------------------------------------------------------

    /** One level of a list: what it marks its items with, and how it counts them. */
    private data class ListLevel(val marker: ListMarker, val format: String)

    /**
     * numId → what each of its levels does, resolved through each num's
     * abstractNum. A list is not one marker but a ladder of them — Word's
     * own default numbers the outer level and letters the one inside it —
     * and every way of counting other than a bullet is a numbered list: a
     * clause lettered (a) is as numbered as one numbered 1, and the way it
     * counts is kept so it can be drawn and written back as it was.
     */
    private fun parseNumbering(bytes: ByteArray): Map<String, Map<Int, ListLevel>> = try {
        val root = parseXml(bytes).documentElement
        val byAbstractId = mutableMapOf<String, Map<Int, ListLevel>>()
        for (abstractNum in children(root, "abstractNum")) {
            val id = attr(abstractNum, "abstractNumId") ?: continue
            byAbstractId[id] = buildMap {
                for (lvl in children(abstractNum, "lvl")) {
                    val level = attr(lvl, "ilvl")?.toIntOrNull()?.takeIf { it >= 0 } ?: continue
                    val format = firstChild(lvl, "numFmt")?.let { attr(it, "val") } ?: continue
                    markerFor(format)?.let { put(level, ListLevel(it, format)) }
                }
            }
        }
        buildMap {
            for (num in children(root, "num")) {
                val numId = attr(num, "numId") ?: continue
                val abstractId = firstChild(num, "abstractNumId")?.let { attr(it, "val") }
                val levels = byAbstractId[abstractId].orEmpty().toMutableMap()
                // A list may count its own way rather than the way the
                // numbering it is based on counts, and says so here.
                for (override in children(num, "lvlOverride")) {
                    val level = attr(override, "ilvl")?.toIntOrNull()?.takeIf { it >= 0 } ?: continue
                    val format = firstChild(override, "lvl")
                        ?.let { firstChild(it, "numFmt") }
                        ?.let { attr(it, "val") }
                        ?: continue
                    markerFor(format)?.let { levels[level] = ListLevel(it, format) }
                }
                if (levels.isNotEmpty()) put(numId, levels)
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

    /** Every word the document holds, for deciding which way it runs. */
    private fun wordsOf(blocks: List<Block>): String {
        val out = StringBuilder()
        fun walk(held: List<Block>) {
            for (block in held) {
                if (out.length > MOST_WORDS_TO_JUDGE) return
                when (block) {
                    is Paragraph -> out.append(block.text).append(' ')
                    is Table -> block.rows.forEach { row -> row.cells.forEach { walk(it.blocks) } }
                    is ImageBlock -> {}
                }
            }
        }
        walk(blocks)
        return out.toString()
    }

    /** Enough of a document to say which way it runs; a long one says the same as its first pages. */
    private const val MOST_WORDS_TO_JUDGE = 20_000

    /** OOXML on/off toggle: present with no w:val (or a truthy one) means on. */
    private fun isOn(element: Element?): Boolean {
        if (element == null) return false
        return when (attr(element, "val")?.lowercase()) {
            null, "1", "true", "on" -> true
            else -> false
        }
    }
}
