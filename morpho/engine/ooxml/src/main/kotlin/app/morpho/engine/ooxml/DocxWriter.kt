package app.morpho.engine.ooxml

import kotlin.math.roundToInt
import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.RunField
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TableGrid
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [DocumentModel] as a minimal, valid WordprocessingML (.docx)
 * package using nothing but the JDK: a .docx file is a ZIP of XML parts.
 *
 * Morpho deliberately does not use Apache POI or docx4j on device — both are
 * desktop-oriented, slow to start, and add 10–20 MB. This writer covers the
 * subset of WordprocessingML the conversion engine emits and grows with it.
 *
 * Supported today: paragraphs and runs (bold/italic/underline), Title and
 * Heading 1–3 styles, bullet and numbered lists (each contiguous numbered
 * list gets its own `w:num` instance so its numbering restarts at 1), simple
 * tables, per-paragraph and per-run right-to-left direction
 * (`w:bidi`/`w:rtl`), and run languages.
 * PNG and JPEG [ImageBlock]s are written as media parts with inline
 * `w:drawing` markup, scaled down to the content area when oversized; any
 * other image type is rejected loudly — silently dropping content is never
 * acceptable.
 */
object DocxWriter {

    const val MIME_TYPE: String =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun toByteArray(document: DocumentModel): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        write(document, out)
        return out.toByteArray()
    }

    /**
     * Which parts a package needs beyond the ones every document has, so
     * the content types, the relationships and the files themselves agree
     * — three lists that have to say the same thing, and a document Word
     * refuses to open when they do not.
     */
    private class Parts(document: DocumentModel, val notes: Boolean) {
        val header = document.header.isNotEmpty()
        val footer = document.footer.isNotEmpty()
        val evenHeader = document.evenHeader.isNotEmpty()
        val evenFooter = document.evenFooter.isNotEmpty()

        /** The left-hand pages carry their own, so Word must be told to look. */
        val mirrored = evenHeader || evenFooter
    }

    fun write(document: DocumentModel, output: OutputStream) {
        val numbering = NumberingPlan(document)
        val images = ImagePlan(document)
        val links = LinkPlan(document)
        val notes = NotePlan(document)
        val parts = Parts(document, notes.entries.isNotEmpty())
        ZipOutputStream(output).use { zip ->
            zip.part("[Content_Types].xml", contentTypesXml(parts))
            zip.part("_rels/.rels", packageRelsXml())
            zip.part("word/_rels/document.xml.rels", documentRelsXml(images, links, parts))
            zip.part("word/document.xml", documentXml(document, numbering, images, links, notes))
            if (notes.entries.isNotEmpty()) {
                zip.part("word/footnotes.xml", footnotesXml(document, numbering, images, links, notes))
            }
            zip.part("word/styles.xml", stylesXml(document))
            zip.part("word/numbering.xml", numberingXml(numbering))
            if (parts.mirrored) zip.part("word/settings.xml", settingsXml())
            zip.part("docProps/core.xml", corePropsXml(document))
            zip.part("docProps/app.xml", appPropsXml())
            // A running header or footer is a part of its own, with its own
            // relationships to the pictures it shows. A book gives its
            // left-hand pages another of each.
            fun furniture(on: Boolean, tag: String, name: String, blocks: List<Block>, part: String) {
                if (!on) return
                zip.part("word/$name.xml", furnitureXml(tag, blocks, document, numbering, images, links, part))
                partRelsXml(images, links, part)?.let { zip.part("word/_rels/$name.xml.rels", it) }
            }
            furniture(parts.header, "hdr", "header1", document.header, ImagePlan.PART_HEADER)
            furniture(parts.footer, "ftr", "footer1", document.footer, ImagePlan.PART_FOOTER)
            furniture(parts.evenHeader, "hdr", "header2", document.evenHeader, ImagePlan.PART_EVEN_HEADER)
            furniture(parts.evenFooter, "ftr", "footer2", document.evenFooter, ImagePlan.PART_EVEN_FOOTER)
            // A picture drawn many times over is stored once and pointed at
            // by every drawing of it.
            for (entry in images.media()) {
                zip.partBytes("word/media/${entry.fileName}", entry.block.bytes)
            }
        }
    }

    private fun ZipOutputStream.part(name: String, content: String) {
        partBytes(name, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ZipOutputStream.partBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    // ------------------------------------------------------------------
    // Numbering assignment
    // ------------------------------------------------------------------

    private const val BULLET_NUM_ID = 1
    private const val FIRST_NUMBERED_NUM_ID = 2

    /** However deep a document nests its lists, no deeper than Word's own nine levels. */
    private const val DEEPEST_LIST_LEVEL = 8

    /** The sixteen colours Word's highlighter knows, by the packed colour each is. */
    private val HIGHLIGHT_NAMES = mapOf(
        0x000000 to "black", 0x0000FF to "blue", 0x00FFFF to "cyan", 0x000080 to "darkBlue",
        0x008080 to "darkCyan", 0x808080 to "darkGray", 0x008000 to "darkGreen",
        0x800080 to "darkMagenta", 0x800000 to "darkRed", 0x808000 to "darkYellow",
        0x00FF00 to "green", 0xC0C0C0 to "lightGray", 0xFF00FF to "magenta",
        0xFF0000 to "red", 0xFFFFFF to "white", 0xFFFF00 to "yellow",
    )

    /** What each level of a bulleted list is marked with, in turn. */
    private val BULLET_CHARACTERS = listOf("•", "◦", "▪")

    /** How each level of a numbered list counts: 1. then a) then i., as an outline is set. */
    private val NUMBER_FORMATS = listOf("decimal", "lowerLetter", "lowerRoman")

    /**
     * The num id of every list paragraph, computed in one pre-pass over the
     * document so [DocxWriter] itself stays stateless: a plan lives for a
     * single [write] call and is threaded through as a parameter.
     *
     * All bullet paragraphs share [BULLET_NUM_ID]. Each contiguous run of
     * numbered paragraphs — uninterrupted by any other block among the same
     * siblings, whether at body level or inside one table cell — gets its own
     * id starting at [FIRST_NUMBERED_NUM_ID], in document order. Word restarts
     * numbering per `w:num` instance, so a fresh id per list is what makes
     * every numbered list start at 1. Paragraphs are keyed by identity:
     * equal-valued paragraphs in different lists must not share an id.
     */
    private class NumberingPlan(document: DocumentModel) {
        private val idByParagraph = IdentityHashMap<Paragraph, Int>()
        private var nextNumberedId = FIRST_NUMBERED_NUM_ID
        /**
         * How each list counts at each of its levels, where the document
         * says so: an Arabic list lettered أ ب ت is written back lettered
         * rather than numbered 1 2 3.
         */
        private val formats = HashMap<Int, MutableMap<Int, String>>()

        init {
            assign(document.blocks)
        }

        val numberedListIds: List<Int>
            get() = (FIRST_NUMBERED_NUM_ID until nextNumberedId).toList()

        fun numIdFor(paragraph: Paragraph): Int? = idByParagraph[paragraph]

        /** How the list [numId] counts at each level it says anything about. */
        fun formatsFor(numId: Int): Map<Int, String> = formats[numId].orEmpty()

        private fun assign(siblings: List<Block>) {
            var currentListId: Int? = null
            for (block in siblings) {
                if (block is Paragraph && block.style.listMarker == ListMarker.NUMBERED) {
                    if (currentListId == null) currentListId = nextNumberedId++
                    idByParagraph[block] = currentListId
                    block.style.listFormat?.let { format ->
                        val level = block.style.listLevel.coerceIn(0, DEEPEST_LIST_LEVEL)
                        formats.getOrPut(currentListId) { mutableMapOf() }.putIfAbsent(level, format)
                    }
                    continue
                }
                val listId = currentListId
                currentListId = null
                if (block is Paragraph && block.style.listMarker == ListMarker.BULLET) {
                    idByParagraph[block] = BULLET_NUM_ID
                    // A bullet among numbered items belongs to the same list;
                    // the numbering carries on past it rather than starting
                    // again at 1 underneath.
                    currentListId = listId
                }
                if (block is Table) {
                    for (row in block.rows) {
                        for (cell in row.cells) assign(cell.blocks)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Image assignment
    // ------------------------------------------------------------------

    private val EXTENSION_BY_MIME = mapOf("image/png" to "png", "image/jpeg" to "jpeg")

    /**
     * Media file names and relationship ids for every [ImageBlock], assigned
     * in one pre-pass (blocks in table cells included) so the writer itself
     * stays stateless. Only PNG and JPEG are supported; anything else fails
     * loudly rather than silently dropping content.
     */
    /**
     * Where each part's links point. Word keeps a link's target out of the
     * document and in the part's relationships, so every distinct target
     * gets a relationship of its own, and a run that points somewhere
     * names that relationship.
     */
    /**
     * The notes the document's marks carry, numbered in the order the marks
     * appear. Word keeps notes in a part of their own and refers to them by
     * number, while the mark a reader sees stays the source's own — a star,
     * a dagger, the digit the page printed.
     */
    private class NotePlan(document: DocumentModel) {
        class Entry(val id: Int, val mark: String, val blocks: List<Block>)

        val entries = mutableListOf<Entry>()
        private val byRun = IdentityHashMap<TextRun, Entry>()

        init {
            assign(document.blocks)
        }

        fun entryFor(run: TextRun): Entry? = byRun[run]

        private fun assign(blocks: List<Block>) {
            for (block in blocks) {
                when (block) {
                    is Paragraph -> for (run in block.runs) {
                        val note = run.note?.takeIf { it.isNotEmpty() } ?: continue
                        val entry = Entry(entries.size + 1, run.text.trim(), note)
                        entries += entry
                        byRun[run] = entry
                    }
                    is Table -> for (row in block.rows) for (cell in row.cells) assign(cell.blocks)
                    is ImageBlock -> {}
                }
            }
        }
    }

    /**
     * Both ends of every link: the addresses that leave the file, which
     * need a relationship each, and the bookmarks inside it, which a link
     * reaches by name and which need an id each.
     */
    private class LinkPlan(document: DocumentModel) {
        class Entry(val target: String, val relId: String, val part: String)

        val entries = mutableListOf<Entry>()
        private val byPartAndTarget = HashMap<Pair<String, String>, Entry>()
        /** Word wants a number on every bookmark, unique across the file. */
        private val bookmarkIds = LinkedHashMap<String, Int>()

        init {
            assign(document.blocks, ImagePlan.PART_DOCUMENT)
            assign(document.header, ImagePlan.PART_HEADER)
            assign(document.footer, ImagePlan.PART_FOOTER)
            assign(document.evenHeader, ImagePlan.PART_EVEN_HEADER)
            assign(document.evenFooter, ImagePlan.PART_EVEN_FOOTER)
        }

        fun relIdFor(target: String, part: String): String? = byPartAndTarget[part to target]?.relId

        fun entriesFor(part: String): List<Entry> = entries.filter { it.part == part }

        /** The name and id of each bookmark on [paragraph], ready to write. */
        fun bookmarksOn(paragraph: Paragraph): List<Pair<String, Int>> =
            paragraph.bookmarks.mapNotNull { raw ->
                bookmarkName(raw)?.let { name -> bookmarkIds[name]?.let { name to it } }
            }

        private fun assign(blocks: List<Block>, part: String) {
            for (block in blocks) {
                when (block) {
                    is Paragraph -> {
                        for (name in block.bookmarks) {
                            bookmarkName(name)?.let { bookmarkIds.getOrPut(it) { bookmarkIds.size } }
                        }
                        // A link into the document itself needs no relationship:
                        // it names a bookmark, not a place outside the file.
                        for (run in block.runs) {
                            run.link?.takeIf { !it.startsWith("#") }?.let { register(it, part) }
                        }
                    }
                    is Table -> for (row in block.rows) for (cell in row.cells) assign(cell.blocks, part)
                    is ImageBlock -> {}
                }
            }
        }

        private fun register(target: String, part: String) {
            byPartAndTarget.getOrPut(part to target) {
                Entry(target, "rIdLnk" + (entries.size + 1), part).also { entries += it }
            }
        }

        companion object {
            /**
             * [raw] as Word will accept it as a bookmark name: letters,
             * digits and underscores, starting with a letter or an
             * underscore, and no longer than Word's limit. Null when
             * nothing of the name survives that.
             *
             * A link and the place it points at are put through this same
             * function, so whatever it does to a name it does to both and
             * the two still meet.
             */
            fun bookmarkName(raw: String): String? {
                val kept = raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
                val started = if (kept.firstOrNull()?.isDigit() == true) "_$kept" else kept
                return started.take(40).takeIf { it.isNotEmpty() && it != "_".repeat(it.length) }
            }
        }
    }

    private class ImagePlan(document: DocumentModel) {
        class Entry(
            val block: ImageBlock,
            val relId: String,
            val fileName: String,
            val docPrId: Int,
            /** The part whose relationships carry this picture. */
            val part: String,
        )

        val entries = mutableListOf<Entry>()
        private val byBlock = IdentityHashMap<ImageBlock, Entry>()
        /**
         * The file each picture was stored as, by what the picture holds. A
         * logo drawn on every page of a document is one picture: storing it
         * once and pointing every drawing at it is the difference between a
         * file of a few hundred kilobytes and one of many megabytes.
         */
        private val fileByContent = HashMap<Long, MutableList<Pair<ByteArray, String>>>()

        init {
            assign(document.blocks, PART_DOCUMENT)
            assign(document.header, PART_HEADER)
            assign(document.footer, PART_FOOTER)
            assign(document.evenHeader, PART_EVEN_HEADER)
            assign(document.evenFooter, PART_EVEN_FOOTER)
        }

        fun entryFor(block: ImageBlock): Entry = byBlock.getValue(block)

        fun entriesFor(part: String): List<Entry> = entries.filter { it.part == part }

        private fun assign(blocks: List<Block>, part: String) {
            for (block in blocks) {
                when (block) {
                    is ImageBlock -> register(block, part)
                    is Table -> for (row in block.rows) for (cell in row.cells) assign(cell.blocks, part)
                    is Paragraph -> for (run in block.runs) run.image?.let { register(it, part) }
                }
            }
        }

        private fun register(block: ImageBlock, part: String) {
            val extension = EXTENSION_BY_MIME[block.mimeType]
                ?: throw UnsupportedOperationException(
                    "Image type ${block.mimeType} is not supported yet (PNG and " +
                        "JPEG are). Refusing to write a document that would " +
                        "silently lose content."
                )
            val index = entries.size + 1
            val entry = Entry(
                block = block,
                relId = "rIdImg$index",
                fileName = fileNameFor(block.bytes, "image$index.$extension"),
                docPrId = index,
                part = part,
            )
            entries += entry
            byBlock[block] = entry
        }

        /** [candidate], unless the same picture is already stored as another file. */
        private fun fileNameFor(bytes: ByteArray, candidate: String): String {
            val key = bytes.size.toLong() * 31 + bytes.contentHashCode()
            val kept = fileByContent.getOrPut(key) { mutableListOf() }
            kept.firstOrNull { it.first.contentEquals(bytes) }?.let { return it.second }
            kept += bytes to candidate
            return candidate
        }

        /** Every picture that has to be stored, once each. */
        fun media(): List<Entry> {
            val seen = HashSet<String>()
            return entries.filter { seen.add(it.fileName) }
        }

        companion object {
            const val PART_DOCUMENT = "document"
            const val PART_HEADER = "header"
            const val PART_FOOTER = "footer"
            /** The left-hand pages' own, where a book gives them one. */
            const val PART_EVEN_HEADER = "evenHeader"
            const val PART_EVEN_FOOTER = "evenFooter"
        }
    }

    // ------------------------------------------------------------------
    // word/document.xml
    // ------------------------------------------------------------------

    private fun documentXml(
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
        links: LinkPlan,
        notes: NotePlan,
    ): String {
        val sb = StringBuilder(16 * 1024)
        sb.append(XML_DECL)
        sb.append("""<w:document xmlns:w="$W" xmlns:r="$R_NS"><w:body>""")
        // A document set on more than one sheet is written as sections:
        // the properties of a section go on the last paragraph of it, and
        // the last section's go on the body. A document of one shape has
        // one section and is written exactly as it always was.
        // The shape the document opens on: its own, unless the first
        // paragraph says otherwise.
        var inForce = (document.blocks.firstOrNull() as? Paragraph)?.style?.sectionSetup
            ?: document.pageSetup
        for ((index, block) in document.blocks.withIndex()) {
            val starting = (document.blocks.getOrNull(index + 1) as? Paragraph)?.style?.sectionSetup
            appendBlock(
                sb, block, document, numbering, images, links, ImagePlan.PART_DOCUMENT, notes,
                endsSection = if (starting != null) inForce else null,
            )
            if (starting != null) {
                // A section can only end on a paragraph; where one ends on
                // a table, an empty paragraph carries its properties.
                if (block !is Paragraph) {
                    sb.append("<w:p><w:pPr>").append(sectPr(document, inForce)).append("</w:pPr></w:p>")
                }
                inForce = starting
            }
        }
        sb.append(sectPr(document, inForce))
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    /**
     * The notes part. Word keeps the marks that separate notes from the
     * text in it as notes of their own, at ids below one, and every note
     * the document refers to after them — each opening with the mark the
     * page printed, since the reference says a mark of its own follows.
     */
    private fun footnotesXml(
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
        links: LinkPlan,
        notes: NotePlan,
    ): String {
        val sb = StringBuilder(4 * 1024)
        sb.append(XML_DECL)
        sb.append("""<w:footnotes xmlns:w="$W" xmlns:r="$R_NS">""")
        for ((id, kind) in listOf(-1 to "separator", 0 to "continuationSeparator")) {
            sb.append("""<w:footnote w:type="$kind" w:id="$id"><w:p><w:pPr>""")
            sb.append("""<w:spacing w:after="0" w:line="240" w:lineRule="auto"/>""")
            sb.append("""</w:pPr><w:r><w:${kind}/></w:r></w:p></w:footnote>""")
        }
        for (entry in notes.entries) {
            sb.append("""<w:footnote w:id="${entry.id}">""")
            val opening = StringBuilder()
            if (entry.mark.isNotEmpty()) {
                opening.append("""<w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr>""")
                opening.append("""<w:t xml:space="preserve">${xmlEscape(entry.mark)} </w:t></w:r>""")
            }
            var first = true
            for (block in entry.blocks) {
                val start = sb.length
                appendBlock(sb, block, document, numbering, images, links, ImagePlan.PART_DOCUMENT)
                // The mark goes inside the note's first paragraph, after
                // whatever properties it carries.
                if (first && opening.isNotEmpty()) {
                    val open = sb.indexOf("<w:p>", start)
                    val properties = sb.indexOf("</w:pPr>", start)
                    val at = if (properties in start until sb.length) properties + "</w:pPr>".length
                    else if (open in start until sb.length) open + "<w:p>".length
                    else -1
                    if (at >= 0) sb.insert(at, opening)
                    first = false
                }
            }
            sb.append("</w:footnote>")
        }
        sb.append("</w:footnotes>")
        return sb.toString()
    }

    /** A header (w:hdr) or footer (w:ftr) part: the blocks every page repeats. */
    private fun furnitureXml(
        root: String,
        blocks: List<Block>,
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
        links: LinkPlan,
        part: String,
    ): String {
        val sb = StringBuilder(4 * 1024)
        sb.append(XML_DECL)
        sb.append("""<w:$root xmlns:w="$W" xmlns:r="$R_NS">""")
        for (block in blocks) appendBlock(sb, block, document, numbering, images, links, part)
        sb.append("</w:$root>")
        return sb.toString()
    }

    private fun appendBlock(
        sb: StringBuilder,
        block: Block,
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
        links: LinkPlan,
        part: String,
        notes: NotePlan? = null,
        /** The section this block ends, when the block after it starts another. */
        endsSection: PageSetup? = null,
    ) {
        when (block) {
            is Paragraph ->
                appendParagraph(sb, block, document, numbering, images, links, part, notes, endsSection)
            is Table -> appendTable(sb, block, document, numbering, images, links, part, notes)
            is ImageBlock -> appendImage(sb, images.entryFor(block), document, part)
        }
    }

    private fun appendParagraph(
        sb: StringBuilder,
        paragraph: Paragraph,
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
        links: LinkPlan,
        part: String,
        notes: NotePlan? = null,
        endsSection: PageSetup? = null,
    ) {
        val effectiveDirection = paragraph.style.direction ?: document.defaultDirection
        sb.append("<w:p>")
        val largest = paragraph.runs.maxOfOrNull { it.fontSizePt ?: 0f } ?: 0f
        appendParagraphProperties(
            sb, paragraph.style, effectiveDirection, document.defaultDirection,
            numbering.numIdFor(paragraph), largest,
            endsSection?.let { sectPr(document, it) },
        )
        // The names this place answers to, opened and closed around its
        // words so that a contents page or a cross-reference lands on it.
        // Only a heading's style sets bold; every other run stands alone.
        val styleIsBold = paragraph.style.kind in BOLD_STYLES
        val bookmarks = links.bookmarksOn(paragraph)
        for ((name, id) in bookmarks) {
            sb.append("""<w:bookmarkStart w:id="$id" w:name="${xmlEscape(name)}"/>""")
        }
        // Runs that point at the same place are one link, as a reader sees
        // it: an address split across runs by a change of face is still one
        // address to click.
        var index = 0
        while (index < paragraph.runs.size) {
            val target = paragraph.runs[index].link
            if (target == null) {
                appendRun(sb, paragraph.runs[index], effectiveDirection, images, document, notes, styleIsBold, part)
                index++
                continue
            }
            var last = index
            while (last + 1 < paragraph.runs.size && paragraph.runs[last + 1].link == target) last++
            // A link into the document itself points at a bookmark rather
            // than at a place on the web: written as a relationship it would
            // send a reader looking for a website called "#introduction".
            val anchor = target.takeIf { it.startsWith("#") }
                ?.let { LinkPlan.bookmarkName(it.removePrefix("#")) }
            val relId = if (anchor == null) links.relIdFor(target, part) else null
            when {
                anchor != null -> sb.append("""<w:hyperlink w:anchor="${xmlEscape(anchor)}">""")
                relId != null -> sb.append("""<w:hyperlink r:id="$relId">""")
            }
            for (i in index..last) {
                appendRun(sb, paragraph.runs[i], effectiveDirection, images, document, notes, styleIsBold, part)
            }
            if (anchor != null || relId != null) sb.append("</w:hyperlink>")
            index = last + 1
        }
        for ((_, id) in bookmarks) sb.append("""<w:bookmarkEnd w:id="$id"/>""")
        sb.append("</w:p>")
    }

    /** Children of w:pPr are emitted in the order the OOXML schema requires. */
    private fun appendParagraphProperties(
        sb: StringBuilder,
        style: app.morpho.engine.layout.ParagraphStyle,
        effectiveDirection: TextDirection,
        /** Which way the section round this paragraph runs, which it inherits unless it says otherwise. */
        inherited: TextDirection,
        numId: Int?,
        largestSizePt: Float = 0f,
        /** The properties of the section this paragraph ends, written last as the schema wants. */
        sectionProperties: String? = null,
    ) {
        // A numbered heading is a heading. A report, a thesis and a
        // standard number their chapters by a list their heading styles
        // belong to, and Word writes both the heading style and the
        // numbering on such a paragraph; naming it List Paragraph instead
        // said it was an ordinary item of a list, and every numbered
        // chapter of every such document came back as body text.
        val styleId = when (style.kind) {
            ParagraphKind.TITLE -> "Title"
            ParagraphKind.HEADING_1 -> "Heading1"
            ParagraphKind.HEADING_2 -> "Heading2"
            ParagraphKind.HEADING_3 -> "Heading3"
            ParagraphKind.BODY -> if (style.listMarker != null) "ListParagraph" else null
        }
        val jc = when (style.alignment) {
            Alignment.CENTER -> "center"
            Alignment.JUSTIFY -> "both"
            // START is Word's default, so w:jc is omitted; END uses the
            // logical "end" value, correct in both LTR and RTL paragraphs.
            Alignment.END -> "end"
            Alignment.START, null -> null
        }
        val rtl = effectiveDirection == TextDirection.RTL
        // A paragraph running the other way from its section has something
        // to say even when it has nothing else: left to the section, the
        // one English paragraph of an Arabic document comes back turned
        // round.
        val turnedBack = !rtl && inherited == TextDirection.RTL
        val spacing = spacingXml(style, largestSizePt)
        val indent = indentXml(style)
        val tabs = style.tabStopsPt?.filter { it > 0f }?.takeIf { it.isNotEmpty() }
        val rules = style.ruleAbove || style.ruleBelow

        if (styleId == null && numId == null && jc == null && !rtl && !turnedBack &&
            spacing == null && indent == null &&
            tabs == null && !rules && !style.pageBreakBefore && sectionProperties == null
        ) return

        sb.append("<w:pPr>")
        if (styleId != null) sb.append("""<w:pStyle w:val="$styleId"/>""")
        // The schema puts a page break here: after the style, before the
        // numbering and everything that follows it.
        if (style.pageBreakBefore) sb.append("<w:pageBreakBefore/>")
        if (numId != null) {
            val level = style.listLevel.coerceIn(0, DEEPEST_LIST_LEVEL)
            sb.append("""<w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$numId"/></w:numPr>""")
        }
        if (rules) {
            // A hairline in Word's eighths of a point, a point clear of the text.
            sb.append("<w:pBdr>")
            if (style.ruleAbove) sb.append("""<w:top w:val="single" w:sz="6" w:space="1" w:color="auto"/>""")
            if (style.ruleBelow) sb.append("""<w:bottom w:val="single" w:sz="6" w:space="1" w:color="auto"/>""")
            sb.append("</w:pBdr>")
        }
        if (tabs != null) {
            sb.append("<w:tabs>")
            for (stop in tabs.sorted()) sb.append("""<w:tab w:val="left" w:pos="${twips(stop)}"/>""")
            sb.append("</w:tabs>")
        }
        // A paragraph that runs the other way from the section it stands
        // in has to say so. Word reads a paragraph with nothing to say
        // about direction as running the way its section does, so in a
        // right-to-left document the one English paragraph — an address,
        // an abstract, a line of code — comes back turned round unless it
        // is written as left-to-right outright.
        if (rtl) sb.append("<w:bidi/>")
        else if (turnedBack) sb.append("<w:bidi w:val=\"0\"/>")
        spacing?.let(sb::append)
        indent?.let(sb::append)
        if (jc != null) sb.append("""<w:jc w:val="$jc"/>""")
        // The section's own properties come last of all, which is where
        // the schema puts them and where Word looks for them.
        sectionProperties?.let(sb::append)
        sb.append("</w:pPr>")
    }

    /**
     * The paragraph's measured spacing, when a reader supplied any. A line
     * pitch is written as an exact height: it is the distance the source
     * put between its baselines, and a face Word substitutes for one it
     * lacks must not be allowed to push every line below it down the page
     * — that is how a converted paper ends up breaking its pages in
     * different places from the original. Word puts the extra room of an
     * exact line above the text and clips what will not fit, so the pitch
     * is never written below what the paragraph's largest type needs.
     */
    private fun spacingXml(style: app.morpho.engine.layout.ParagraphStyle, largestSizePt: Float): String? {
        val before = style.spaceBeforePt?.let(::twips)
        val after = style.spaceAfterPt?.let(::twips)
        val line = style.linePitchPt?.takeIf { it > 0f }
            ?.let { maxOf(it, LEAST_LINE_SHARE * largestSizePt) }
            ?.let(::twips)
        if (before == null && after == null && line == null) return null
        val sb = StringBuilder("<w:spacing")
        before?.let { sb.append(""" w:before="$it"""") }
        after?.let { sb.append(""" w:after="$it"""") }
        line?.let { sb.append(""" w:line="$it" w:lineRule="exact"""") }
        return sb.append("/>").toString()
    }

    /** An exact line is never shorter than this share of its largest type size. */
    private const val LEAST_LINE_SHARE = 1.15f

    /**
     * The paragraph's indents. `w:left` is the start edge — Word lays a
     * bidi paragraph's "left" indent along its right margin — so one
     * attribute serves both directions; `w:hanging` pulls the first line
     * back out by that much, `w:firstLine` pushes it further in.
     */
    private fun indentXml(style: app.morpho.engine.layout.ParagraphStyle): String? {
        val start = style.startIndentPt?.let(::twips)
        val hanging = style.hangingIndentPt?.takeIf { it > 0f }?.let(::twips)
        val firstLine = style.firstLineIndentPt?.takeIf { it > 0f }?.let(::twips)
        if (start == null && hanging == null && firstLine == null) return null
        val sb = StringBuilder("<w:ind")
        start?.let { sb.append(""" w:left="$it"""") }
        if (hanging != null) sb.append(""" w:hanging="$hanging"""")
        else firstLine?.let { sb.append(""" w:firstLine="$it"""") }
        return sb.append("/>").toString()
    }

    /** Points to twentieths of a point, the unit OOXML measures in. */
    private fun twips(points: Float): Int = (points * 20f).roundToInt().coerceAtLeast(0)

    /** Children of w:rPr are emitted in the order the OOXML schema requires. */
    /** The paragraph styles whose own formatting is bold, which a light run must undo. */
    private val BOLD_STYLES = setOf(ParagraphKind.HEADING_1, ParagraphKind.HEADING_2, ParagraphKind.HEADING_3)

    /** A packed 0xRRGGBB colour as WordprocessingML writes one: six upper-case hex digits, no hash. */
    private fun hexColor(rgb: Int): String = "%06X".format(rgb and 0xFFFFFF)

    private fun appendRun(
        sb: StringBuilder,
        run: TextRun,
        paragraphDirection: TextDirection,
        images: ImagePlan,
        document: DocumentModel,
        notes: NotePlan? = null,
        /** Whether the paragraph's style sets bold, which only a heading's does. */
        styleIsBold: Boolean = false,
        part: String = "",
    ) {
        run.image?.let { image ->
            sb.append("<w:r>")
            appendDrawing(sb, images.entryFor(image), room(document, part))
            sb.append("</w:r>")
            return
        }
        val rtl = (run.direction ?: paragraphDirection) == TextDirection.RTL
        val family = run.fontFamily?.takeIf { it.isNotBlank() }
        val halfPoints = run.fontSizePt?.takeIf { it > 0f }?.let { (it * 2).roundToInt() }
        // A run that is light inside a style that is bold has something to
        // say after all: that it is light.
        val undoesBold = styleIsBold && !run.bold
        val hasProps = run.bold || run.italic || run.underline || run.strikethrough ||
            rtl || run.language != null ||
            family != null || halfPoints != null || run.superscript || run.subscript ||
            run.colorRgb != null || run.highlightRgb != null || undoesBold

        // A field is a run Word fills in; what it last showed goes in as the
        // text, as the cached result a field carries.
        val field = run.field
        if (field != null) sb.append("""<w:fldSimple w:instr="${fieldInstruction(field)}">""")
        sb.append("<w:r>")
        if (hasProps) {
            sb.append("<w:rPr>")
            // rFonts leads and sz precedes u: the schema fixes the order, and
            // Word rejects a file that breaks it.
            family?.let { f ->
                val name = xmlEscape(f)
                sb.append("""<w:rFonts w:ascii="$name" w:hAnsi="$name" w:cs="$name"/>""")
            }
            // A heading's style is bold, so a run the document does not set
            // bold has to say so outright: the digit of a numbered heading
            // the page set light comes back bold otherwise, and nothing in
            // the file says it was ever anything else.
            when {
                run.bold -> sb.append("<w:b/><w:bCs/>")
                styleIsBold -> sb.append("""<w:b w:val="0"/><w:bCs w:val="0"/>""")
            }
            if (run.italic) sb.append("<w:i/><w:iCs/>")
            if (run.strikethrough) sb.append("<w:strike/>")
            run.colorRgb?.let { sb.append("""<w:color w:val="${hexColor(it)}"/>""") }
            halfPoints?.let { sb.append("""<w:sz w:val="$it"/><w:szCs w:val="$it"/>""") }
            // Word's highlighter knows sixteen colours by name and nothing
            // else; a marking of any other colour is shading, which draws
            // the same and keeps the colour the reader chose. The schema
            // fixes where each goes: highlight before the underline, shading
            // after it.
            val marked = run.highlightRgb?.and(0xFFFFFF)
            val named = marked?.let { HIGHLIGHT_NAMES[it] }
            named?.let { sb.append("""<w:highlight w:val="$it"/>""") }
            if (run.underline) sb.append("""<w:u w:val="single"/>""")
            if (marked != null && named == null) {
                sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="${hexColor(marked)}"/>""")
            }
            if (run.superscript) sb.append("""<w:vertAlign w:val="superscript"/>""")
            else if (run.subscript) sb.append("""<w:vertAlign w:val="subscript"/>""")
            if (rtl) sb.append("<w:rtl/>")
            run.language?.let { lang ->
                val attr = xmlEscape(lang)
                if (rtl) {
                    sb.append("""<w:lang w:bidi="$attr"/>""")
                } else {
                    sb.append("""<w:lang w:val="$attr"/>""")
                }
            }
            sb.append("</w:rPr>")
        }
        // A mark that carries a note refers to it, and keeps its own shape:
        // Word numbers notes itself, but the page printed a star, so the
        // star follows the reference as the mark of its own.
        val note = notes?.entryFor(run)
        if (note != null) {
            sb.append("""<w:footnoteReference w:customMarkFollows="1" w:id="${note.id}"/>""")
        }
        // A tab is an element of its own; the character itself has no
        // meaning in w:t.
        val text = if (field != null && run.text.isEmpty()) fieldPlaceholder(field, document) else run.text
        val pieces = text.split('\t')
        for ((index, piece) in pieces.withIndex()) {
            if (index > 0) sb.append("<w:tab/>")
            if (piece.isEmpty()) continue
            sb.append("""<w:t xml:space="preserve">""")
            sb.append(xmlEscape(piece))
            sb.append("</w:t>")
        }
        sb.append("</w:r>")
        if (field != null) sb.append("</w:fldSimple>")
    }

    private fun fieldInstruction(field: RunField): String = when (field) {
        RunField.PAGE_NUMBER -> " PAGE "
    }

    private fun fieldPlaceholder(field: RunField, document: DocumentModel): String = when (field) {
        RunField.PAGE_NUMBER -> (document.pageSetup?.firstPageNumber ?: 1).toString()
    }

    private fun appendTable(
        sb: StringBuilder,
        table: Table,
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
        links: LinkPlan,
        part: String,
        notes: NotePlan? = null,
    ) {
        if (table.rows.isEmpty()) return
        // The places of the grid, worked out once and shared with every
        // other writer: a merged cell leaves places beside and under it
        // that the format wants written out.
        val grid = TableGrid.of(table)
        val columnCount = grid.columns
        // The widths a reader measured off the page, in twentieths of a
        // point; a table nothing measured shares the text width equally,
        // which is what Word does with an "auto" grid.
        val widths = table.columnWidthsPt
            ?.takeIf { it.size == columnCount && it.all { width -> width > 0f } }
            ?.map { (it * 20).roundToInt().coerceAtLeast(1) }

        sb.append("<w:tbl>")
        sb.append("<w:tblPr>")
        // A table of Arabic is laid out from the right: its first column is
        // the rightmost one. The schema puts this before the table's width.
        if ((table.direction ?: document.defaultDirection) == TextDirection.RTL) {
            sb.append("<w:bidiVisual/>")
        }
        if (widths != null) {
            sb.append("""<w:tblW w:w="${widths.sum()}" w:type="dxa"/>""")
        } else {
            sb.append("""<w:tblW w:w="0" w:type="auto"/>""")
        }
        if (table.ruled) {
            sb.append("<w:tblBorders>")
            for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
                sb.append("""<w:$edge w:val="single" w:sz="4" w:space="0" w:color="auto"/>""")
            }
            sb.append("</w:tblBorders>")
        }
        sb.append("</w:tblPr>")
        sb.append("<w:tblGrid>")
        if (widths != null) {
            for (width in widths) sb.append("""<w:gridCol w:w="$width"/>""")
        } else {
            repeat(columnCount) { sb.append("""<w:gridCol w:w="2340"/>""") }
        }
        sb.append("</w:tblGrid>")

        for ((index, row) in grid.rows.withIndex()) {
            sb.append("<w:tr>")
            // The head of a table is drawn again at the top of every page
            // the table runs onto, which is Word's own doing once it is
            // told which rows those are.
            if (index < TableGrid.headRows(table)) {
                sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            }
            for (place in row) {
                val width = widths?.let { all ->
                    (place.column until place.column + place.span).sumOf { all.getOrElse(it) { 0 } }
                }
                when (place) {
                    is TableGrid.Filled -> appendCell(
                        sb, place.cell, document, numbering, images, links, part, width, notes,
                        span = place.span,
                        merge = if (place.rowSpan > 1) Merge.START else Merge.NONE,
                    )
                    is TableGrid.Covered -> appendCell(
                        sb, TableCell(emptyList()), document, numbering, images, links, part, width, notes,
                        merge = Merge.CONTINUE,
                    )
                    is TableGrid.Empty -> appendCell(
                        sb, TableCell(emptyList()), document, numbering, images, links, part, width, notes,
                    )
                }
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        // WordprocessingML requires a paragraph after a table at body level.
        sb.append("<w:p/>")
    }

    /** Whether a cell begins a merge down the rows, continues one, or neither. */
    private enum class Merge { NONE, START, CONTINUE }

    private fun appendCell(
        sb: StringBuilder,
        cell: TableCell,
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
        links: LinkPlan,
        part: String,
        widthTwips: Int? = null,
        notes: NotePlan? = null,
        span: Int = 1,
        merge: Merge = Merge.NONE,
    ) {
        sb.append("<w:tc>")
        sb.append("<w:tcPr>")
        if (widthTwips != null) {
            sb.append("""<w:tcW w:w="$widthTwips" w:type="dxa"/>""")
        } else {
            sb.append("""<w:tcW w:w="0" w:type="auto"/>""")
        }
        // The schema puts the width first, then how many columns the cell
        // covers, then whether it continues a merge from the row above.
        if (span > 1) sb.append("""<w:gridSpan w:val="$span"/>""")
        when (merge) {
            Merge.START -> sb.append("""<w:vMerge w:val="restart"/>""")
            Merge.CONTINUE -> sb.append("<w:vMerge/>")
            Merge.NONE -> {}
        }
        // The colour the cell is filled with, written on the cell rather
        // than left to a style, so it holds wherever the file is opened.
        cell.shadingRgb?.let {
            sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="${hexColor(it)}"/>""")
        }
        sb.append("</w:tcPr>")
        for (block in cell.blocks) {
            appendBlock(sb, block, document, numbering, images, links, part, notes)
        }
        // Every table cell must end with a paragraph. A trailing nested table
        // already appended its own spacer paragraph after </w:tbl>.
        val last = cell.blocks.lastOrNull()
        if (last !is Paragraph && last !is Table) sb.append("<w:p/>")
        sb.append("</w:tc>")
    }

    private const val EMU_PER_PX = 9525L

    /** EMUs in a point: 914400 in an inch, 72 points in an inch. */

    private const val EMU_PER_PT = 12700L
    /** Content area inside the A4 margins, in EMU: the page a document that says nothing is set on. */
    private const val MAX_CX_EMU = 5_731_933L
    private const val MAX_CY_EMU = 8_863_330L

    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** An image as its own paragraph with an inline w:drawing. */
    private fun appendImage(
        sb: StringBuilder,
        entry: ImagePlan.Entry,
        document: DocumentModel? = null,
        part: String = "",
    ) {
        sb.append("<w:p>")
        // A picture standing alone in a running head or foot is the head:
        // it wants no space around it and no line spacing of Word's own,
        // or the head comes back taller than the one it was cropped from
        // and the text under it starts lower down the page.
        if (part in FURNITURE_PARTS) {
            sb.append("<w:pPr>")
            if (document?.defaultDirection == TextDirection.RTL) sb.append("<w:bidi/>")
            sb.append("""<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>""")
            sb.append("</w:pPr>")
        }
        sb.append("<w:r>")
        appendDrawing(sb, entry, document?.let { room(it, part) } ?: (MAX_CX_EMU to MAX_CY_EMU))
        sb.append("</w:r></w:p>")
    }

    /**
     * How large a picture may be drawn in [part], in EMU across and down.
     *
     * A picture in the text is held to the text: the column it is set in.
     * A picture in a running head or foot is not — a head is set against
     * the page and reaches into the margins as often as not, which is why
     * a journal's, drawn as artwork, is kept as the picture it is. Held to
     * the text column it comes back a little narrower than it was, which
     * is a running head that no longer lines up with the page it heads.
     */
    private fun room(document: DocumentModel, part: String): Pair<Long, Long> {
        val page = document.pageSetup ?: return MAX_CX_EMU to MAX_CY_EMU
        val furniture = part in FURNITURE_PARTS
        val across =
            if (furniture) page.widthPt else page.widthPt - page.marginLeftPt - page.marginRightPt
        val down =
            if (furniture) page.heightPt else page.heightPt - page.marginTopPt - page.marginBottomPt
        return (across.coerceAtLeast(1f) * EMU_PER_PT).toLong() to
            (down.coerceAtLeast(1f) * EMU_PER_PT).toLong()
    }

    /** One inline picture, in a run of the caller's, held to the room [part] gives it. */
    private fun appendDrawing(
        sb: StringBuilder,
        entry: ImagePlan.Entry,
        room: Pair<Long, Long> = MAX_CX_EMU to MAX_CY_EMU,
    ) {
        val block = entry.block
        // Shown at the size the reader measured on the page when it did,
        // else at its pixel size as CSS would show it.
        var cx = block.widthPt?.takeIf { it > 0f }?.let { (it * EMU_PER_PT).toLong() }
            ?: (block.widthPx.coerceAtLeast(1) * EMU_PER_PX)
        var cy = block.heightPt?.takeIf { it > 0f }?.let { (it * EMU_PER_PT).toLong() }
            ?: (block.heightPx.coerceAtLeast(1) * EMU_PER_PX)
        val (maxCx, maxCy) = room
        // Scale into the room there is, preserving aspect ratio.
        if (cx > maxCx) {
            cy = cy * maxCx / cx
            cx = maxCx
        }
        if (cy > maxCy) {
            cx = cx * maxCy / cy
            cy = maxCy
        }
        cx = cx.coerceAtLeast(1)
        cy = cy.coerceAtLeast(1)

        val name = xmlEscape("Image ${entry.docPrId}")
        sb.append("<w:drawing>")
        sb.append("""<wp:inline xmlns:wp="$WP_NS" distT="0" distB="0" distL="0" distR="0">""")
        sb.append("""<wp:extent cx="$cx" cy="$cy"/>""")
        sb.append("""<wp:docPr id="${entry.docPrId}" name="$name"/>""")
        sb.append("""<a:graphic xmlns:a="$A_NS">""")
        sb.append("""<a:graphicData uri="$PIC_NS">""")
        sb.append("""<pic:pic xmlns:pic="$PIC_NS">""")
        sb.append("""<pic:nvPicPr><pic:cNvPr id="${entry.docPrId}" name="$name"/><pic:cNvPicPr/></pic:nvPicPr>""")
        sb.append("""<pic:blipFill><a:blip xmlns:r="$R_NS" r:embed="${entry.relId}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""")
        sb.append("""<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
        sb.append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""")
        sb.append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>")
    }

    /**
     * Children of w:sectPr in schema order: the header and footer
     * references, the sheet, its margins, the numbering, and whether the
     * first page keeps any of it.
     */
    private fun sectPr(document: DocumentModel, page: PageSetup?): String {
        val sb = StringBuilder("<w:sectPr>")
        if (document.header.isNotEmpty()) sb.append("""<w:headerReference w:type="default" r:id="$HEADER_REL_ID"/>""")
        if (document.evenHeader.isNotEmpty()) sb.append("""<w:headerReference w:type="even" r:id="$EVEN_HEADER_REL_ID"/>""")
        if (document.footer.isNotEmpty()) sb.append("""<w:footerReference w:type="default" r:id="$FOOTER_REL_ID"/>""")
        if (document.evenFooter.isNotEmpty()) sb.append("""<w:footerReference w:type="even" r:id="$EVEN_FOOTER_REL_ID"/>""")
        // The source's own page when the reader measured it; else A4
        // portrait with 2.54 cm margins (values in twentieths of a point).
        if (page == null) {
            sb.append("""<w:pgSz w:w="11906" w:h="16838"/>""")
            sb.append("""<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" """)
            sb.append("""w:header="708" w:footer="708" w:gutter="0"/>""")
        } else {
            val landscape = if (page.widthPt > page.heightPt) """ w:orient="landscape"""" else ""
            sb.append("""<w:pgSz w:w="${twips(page.widthPt)}" w:h="${twips(page.heightPt)}"$landscape/>""")
            sb.append("""<w:pgMar w:top="${twips(page.marginTopPt)}" w:right="${twips(page.marginRightPt)}" """)
            sb.append("""w:bottom="${twips(page.marginBottomPt)}" w:left="${twips(page.marginLeftPt)}" """)
            val header = page.headerDistancePt?.let(::twips) ?: 708
            val footer = page.footerDistancePt?.let(::twips) ?: 708
            sb.append("""w:header="$header" w:footer="$footer" w:gutter="0"/>""")
            if (page.firstPageNumber != 1) sb.append("""<w:pgNumType w:start="${page.firstPageNumber}"/>""")
        }
        // A first page of its own, with no reference declared for it, is a
        // first page that shows neither head nor foot — which is what a
        // title page that carried none should come back as. It goes last:
        // w:titlePg follows the numbering in the schema's own order, and
        // Word reads a section whose children are out of order as no
        // section at all.
        if (page?.differentFirstPage == true) sb.append("<w:titlePg/>")
        // Which way the document runs. Word keeps it here, once, for the
        // whole section — every paragraph then runs that way unless it
        // says otherwise — and a document written without it comes back
        // from Word laid out from the left however much Arabic is in it:
        // its tables read backwards and its running head sits at the
        // wrong margin. It goes after w:titlePg, which is where the
        // schema puts it, and a section whose children are out of order
        // is a section Word will not read at all.
        if (document.defaultDirection == TextDirection.RTL) sb.append("<w:bidi/>")
        return sb.append("</w:sectPr>").toString()
    }

    /** The parts that hold a page's own furniture rather than the document's text. */
    private val FURNITURE_PARTS = setOf(
        ImagePlan.PART_HEADER,
        ImagePlan.PART_FOOTER,
        ImagePlan.PART_EVEN_HEADER,
        ImagePlan.PART_EVEN_FOOTER,
    )

    private const val HEADER_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"
    private const val FOOTER_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"
    private const val HEADER_REL_TYPE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/header"
    private const val FOOTER_REL_TYPE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"

    private const val HEADER_REL_ID = "rIdHdr1"
    private const val FOOTER_REL_ID = "rIdFtr1"
    private const val EVEN_HEADER_REL_ID = "rIdHdr2"
    private const val EVEN_FOOTER_REL_ID = "rIdFtr2"
    private const val SETTINGS_REL_ID = "rIdSet1"
    private const val NOTES_REL_ID = "rIdFtn1"

    // ------------------------------------------------------------------
    // Static parts
    // ------------------------------------------------------------------

    private fun contentTypesXml(parts: Parts): String = XML_DECL +
        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Default Extension="png" ContentType="image/png"/>""" +
        """<Default Extension="jpeg" ContentType="image/jpeg"/>""" +
        """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
        """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
        """<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>""" +
        (if (parts.header) """<Override PartName="/word/header1.xml" ContentType="$HEADER_TYPE"/>""" else "") +
        (if (parts.footer) """<Override PartName="/word/footer1.xml" ContentType="$FOOTER_TYPE"/>""" else "") +
        (if (parts.evenHeader) """<Override PartName="/word/header2.xml" ContentType="$HEADER_TYPE"/>""" else "") +
        (if (parts.evenFooter) """<Override PartName="/word/footer2.xml" ContentType="$FOOTER_TYPE"/>""" else "") +
        (if (parts.mirrored) """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""" else "") +
        (if (parts.notes) """<Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>""" else "") +
        """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
        """<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""" +
        """</Types>"""

    /**
     * The document's settings. Only written when there is something to
     * say: a book whose left-hand pages carry their own head and foot,
     * which Word looks for only when told that the two sides differ.
     */
    private fun settingsXml(): String = XML_DECL +
        """<w:settings xmlns:w="$W"><w:evenAndOddHeaders/></w:settings>"""

    private fun packageRelsXml(): String = XML_DECL +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
        """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>""" +
        """</Relationships>"""

    private fun documentRelsXml(images: ImagePlan, links: LinkPlan, parts: Parts): String {
        val sb = StringBuilder(XML_DECL)
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sb.append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("""<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>""")
        if (parts.header) sb.append("""<Relationship Id="$HEADER_REL_ID" Type="$HEADER_REL_TYPE" Target="header1.xml"/>""")
        if (parts.footer) sb.append("""<Relationship Id="$FOOTER_REL_ID" Type="$FOOTER_REL_TYPE" Target="footer1.xml"/>""")
        if (parts.evenHeader) sb.append("""<Relationship Id="$EVEN_HEADER_REL_ID" Type="$HEADER_REL_TYPE" Target="header2.xml"/>""")
        if (parts.evenFooter) sb.append("""<Relationship Id="$EVEN_FOOTER_REL_ID" Type="$FOOTER_REL_TYPE" Target="footer2.xml"/>""")
        if (parts.mirrored) sb.append("""<Relationship Id="$SETTINGS_REL_ID" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>""")
        if (parts.notes) sb.append("""<Relationship Id="$NOTES_REL_ID" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>""")
        for (entry in images.entriesFor(ImagePlan.PART_DOCUMENT)) {
            sb.append(
                """<Relationship Id="${entry.relId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${entry.fileName}"/>"""
            )
        }
        appendLinkRels(sb, links, ImagePlan.PART_DOCUMENT)
        sb.append("</Relationships>")
        return sb.toString()
    }

    /** A link points outside the package, so its relationship says so. */
    private fun appendLinkRels(sb: StringBuilder, links: LinkPlan, part: String) {
        for (entry in links.entriesFor(part)) {
            sb.append(
                """<Relationship Id="${entry.relId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${xmlEscape(entry.target)}" TargetMode="External"/>"""
            )
        }
    }

    /** The relationships of a header or footer part: its pictures and its links, if it has any. */
    private fun partRelsXml(images: ImagePlan, links: LinkPlan, part: String): String? {
        val entries = images.entriesFor(part)
        if (entries.isEmpty() && links.entriesFor(part).isEmpty()) return null
        val sb = StringBuilder(XML_DECL)
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (entry in entries) {
            sb.append(
                """<Relationship Id="${entry.relId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${entry.fileName}"/>"""
            )
        }
        appendLinkRels(sb, links, part)
        sb.append("</Relationships>")
        return sb.toString()
    }

    /**
     * What the document says about itself, as Word's Properties pane
     * shows it.
     *
     * The converter used to sign every file it wrote as its own work,
     * with no title at all — so a paper converted from a PDF arrived
     * called nothing, by nobody. What the source said is written instead,
     * and the converter's name goes where it belongs: on the application
     * that last touched the file, not on its author. A source that named
     * nothing still leaves the converter as the creator, since a file has
     * to say something and this is the truth about that one.
     */
    private fun corePropsXml(document: DocumentModel): String {
        val said = document.properties
        val sb = StringBuilder(XML_DECL)
        sb.append("""<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """)
        sb.append("""xmlns:dc="http://purl.org/dc/elements/1.1/">""")
        said.title?.let { sb.append("<dc:title>").append(xmlEscape(it)).append("</dc:title>") }
        sb.append("<dc:creator>").append(xmlEscape(said.author ?: "Morpho")).append("</dc:creator>")
        said.subject?.let { sb.append("<dc:subject>").append(xmlEscape(it)).append("</dc:subject>") }
        said.keywords?.let { sb.append("<cp:keywords>").append(xmlEscape(it)).append("</cp:keywords>") }
        sb.append("<cp:lastModifiedBy>Morpho</cp:lastModifiedBy>")
        sb.append("</cp:coreProperties>")
        return sb.toString()
    }

    private fun appPropsXml(): String = XML_DECL +
        """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">""" +
        """<Application>Morpho</Application>""" +
        """</Properties>"""

    /** The document's own language, as Word's default run properties name it. */
    private fun defaultLanguage(document: DocumentModel): String {
        val language = document.defaultLanguage?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        val attr = xmlEscape(language)
        return if (document.defaultDirection == TextDirection.RTL) {
            """<w:lang w:bidi="$attr"/>"""
        } else {
            """<w:lang w:val="$attr"/>"""
        }
    }

    private fun stylesXml(document: DocumentModel): String {
        fun heading(id: String, name: String, size: Int, outline: Int): String =
            """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/>""" +
                """<w:basedOn w:val="Normal"/><w:next w:val="Normal"/>""" +
                """<w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/>""" +
                """<w:outlineLvl w:val="$outline"/></w:pPr>""" +
                """<w:rPr><w:b/><w:bCs/><w:sz w:val="$size"/><w:szCs w:val="$size"/></w:rPr>""" +
                """</w:style>"""

        return XML_DECL +
            """<w:styles xmlns:w="$W">""" +
            "<w:docDefaults>" +
            """<w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Arial"/>""" +
            """<w:sz w:val="22"/><w:szCs w:val="22"/>""" +
            // The language the document is written in. Word proofs a file
            // in whatever language it is told, and told nothing it uses the
            // language of whoever opens it — so an Arabic paper opens with
            // every word of it underlined in red. A right-to-left document
            // names its language in w:bidi, which is the slot Word reads a
            // complex script's language from.
            defaultLanguage(document) +
            "</w:rPr></w:rPrDefault>" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault>""" +
            "</w:docDefaults>" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/>""" +
            """<w:basedOn w:val="Normal"/><w:next w:val="Normal"/>""" +
            """<w:pPr><w:spacing w:after="80"/></w:pPr>""" +
            """<w:rPr><w:sz w:val="56"/><w:szCs w:val="56"/></w:rPr></w:style>""" +
            heading("Heading1", "heading 1", 32, 0) +
            heading("Heading2", "heading 2", 28, 1) +
            heading("Heading3", "heading 3", 26, 2) +
            """<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/>""" +
            """<w:basedOn w:val="Normal"/>""" +
            """<w:pPr><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>""" +
            "</w:styles>"
    }

    // ------------------------------------------------------------------
    // word/numbering.xml
    // ------------------------------------------------------------------

    private fun numberingXml(numbering: NumberingPlan): String {
        fun level(ilvl: Int, numFmt: String, lvlText: String): String =
            """<w:lvl w:ilvl="$ilvl"><w:start w:val="1"/><w:numFmt w:val="$numFmt"/>""" +
                """<w:lvlText w:val="$lvlText"/><w:lvlJc w:val="left"/>""" +
                """<w:pPr><w:ind w:left="${720 * (ilvl + 1)}" w:hanging="360"/></w:pPr></w:lvl>"""

        // Word's own ladders, level by level, as deep as a list may nest:
        // three bullets in turn, and numbers that go 1. a. i. the way every
        // outline is set.
        val bullets = (0..DEEPEST_LIST_LEVEL).joinToString(separator = "") { ilvl ->
            level(ilvl, "bullet", BULLET_CHARACTERS[ilvl % BULLET_CHARACTERS.size])
        }
        val numbers = (0..DEEPEST_LIST_LEVEL).joinToString(separator = "") { ilvl ->
            val format = NUMBER_FORMATS[ilvl % NUMBER_FORMATS.size]
            // %n counts the nth level, so the placeholder follows the level.
            level(ilvl, format, "%${ilvl + 1}" + if (format == "lowerLetter") ")" else ".")
        }

        /** What a level of [format] draws: the count, and what follows it. */
        fun lvlTextFor(ilvl: Int, format: String): String {
            val counter = "%" + (ilvl + 1)
            return if (format == "lowerLetter" || format == "upperLetter") "$counter)" else "$counter."
        }

        val nums = StringBuilder()
        nums.append("""<w:num w:numId="$BULLET_NUM_ID"><w:abstractNumId w:val="0"/></w:num>""")
        // One w:num per numbered list, each with a level-0 startOverride.
        // Word keeps a single running count per abstractNum, so a fresh
        // instance alone does NOT restart numbering — the override does.
        for (id in numbering.numberedListIds) {
            val counts = numbering.formatsFor(id)
            nums.append("""<w:num w:numId="$id"><w:abstractNumId w:val="1"/>""")
            // The level-0 override is what restarts the count; a level that
            // counts its own way says so in an override of its own, which is
            // how a list keeps its lettering rather than being renumbered.
            nums.append("""<w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/>""")
            counts[0]?.let { nums.append(level(0, it, lvlTextFor(0, it))) }
            nums.append("</w:lvlOverride>")
            for ((ilvl, format) in counts.filterKeys { it > 0 }.toSortedMap()) {
                nums.append("""<w:lvlOverride w:ilvl="$ilvl">""")
                nums.append(level(ilvl, format, lvlTextFor(ilvl, format)))
                nums.append("</w:lvlOverride>")
            }
            nums.append("</w:num>")
        }

        return XML_DECL +
            """<w:numbering xmlns:w="$W">""" +
            """<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>""" +
            bullets +
            "</w:abstractNum>" +
            """<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>""" +
            numbers +
            "</w:abstractNum>" +
            nums +
            "</w:numbering>"
    }
}
