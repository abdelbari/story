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
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
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

    fun write(document: DocumentModel, output: OutputStream) {
        val numbering = NumberingPlan(document)
        val images = ImagePlan(document)
        ZipOutputStream(output).use { zip ->
            zip.part("[Content_Types].xml", contentTypesXml())
            zip.part("_rels/.rels", packageRelsXml())
            zip.part("word/_rels/document.xml.rels", documentRelsXml(images))
            zip.part("word/document.xml", documentXml(document, numbering, images))
            zip.part("word/styles.xml", stylesXml())
            zip.part("word/numbering.xml", numberingXml(numbering))
            zip.part("docProps/core.xml", corePropsXml())
            zip.part("docProps/app.xml", appPropsXml())
            for (entry in images.entries) {
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

        init {
            assign(document.blocks)
        }

        val numberedListIds: List<Int>
            get() = (FIRST_NUMBERED_NUM_ID until nextNumberedId).toList()

        fun numIdFor(paragraph: Paragraph): Int? = idByParagraph[paragraph]

        private fun assign(siblings: List<Block>) {
            var currentListId: Int? = null
            for (block in siblings) {
                if (block is Paragraph && block.style.listMarker == ListMarker.NUMBERED) {
                    if (currentListId == null) currentListId = nextNumberedId++
                    idByParagraph[block] = currentListId
                    continue
                }
                currentListId = null
                if (block is Paragraph && block.style.listMarker == ListMarker.BULLET) {
                    idByParagraph[block] = BULLET_NUM_ID
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
    private class ImagePlan(document: DocumentModel) {
        class Entry(
            val block: ImageBlock,
            val relId: String,
            val fileName: String,
            val docPrId: Int,
        )

        val entries = mutableListOf<Entry>()
        private val byBlock = IdentityHashMap<ImageBlock, Entry>()

        init {
            assign(document.blocks)
        }

        fun entryFor(block: ImageBlock): Entry = byBlock.getValue(block)

        private fun assign(blocks: List<Block>) {
            for (block in blocks) {
                when (block) {
                    is ImageBlock -> {
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
                            fileName = "image$index.$extension",
                            docPrId = index,
                        )
                        entries += entry
                        byBlock[block] = entry
                    }
                    is Table -> for (row in block.rows) for (cell in row.cells) assign(cell.blocks)
                    is Paragraph -> {}
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // word/document.xml
    // ------------------------------------------------------------------

    private fun documentXml(
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
    ): String {
        val sb = StringBuilder(16 * 1024)
        sb.append(XML_DECL)
        sb.append("""<w:document xmlns:w="$W"><w:body>""")
        for (block in document.blocks) {
            appendBlock(sb, block, document, numbering, images)
        }
        sb.append(sectPr(document.pageSetup))
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun appendBlock(
        sb: StringBuilder,
        block: Block,
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
    ) {
        when (block) {
            is Paragraph -> appendParagraph(sb, block, document, numbering)
            is Table -> appendTable(sb, block, document, numbering, images)
            is ImageBlock -> appendImage(sb, images.entryFor(block))
        }
    }

    private fun appendParagraph(
        sb: StringBuilder,
        paragraph: Paragraph,
        document: DocumentModel,
        numbering: NumberingPlan,
    ) {
        val effectiveDirection = paragraph.style.direction ?: document.defaultDirection
        sb.append("<w:p>")
        appendParagraphProperties(sb, paragraph.style, effectiveDirection, numbering.numIdFor(paragraph))
        for (run in paragraph.runs) {
            appendRun(sb, run, effectiveDirection)
        }
        sb.append("</w:p>")
    }

    /** Children of w:pPr are emitted in the order the OOXML schema requires. */
    private fun appendParagraphProperties(
        sb: StringBuilder,
        style: app.morpho.engine.layout.ParagraphStyle,
        effectiveDirection: TextDirection,
        numId: Int?,
    ) {
        val styleId = when {
            style.listMarker != null -> "ListParagraph"
            else -> when (style.kind) {
                ParagraphKind.TITLE -> "Title"
                ParagraphKind.HEADING_1 -> "Heading1"
                ParagraphKind.HEADING_2 -> "Heading2"
                ParagraphKind.HEADING_3 -> "Heading3"
                ParagraphKind.BODY -> null
            }
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
        val spacing = spacingXml(style)
        val indent = indentXml(style)
        val tabs = style.tabStopsPt?.filter { it > 0f }?.takeIf { it.isNotEmpty() }

        if (styleId == null && numId == null && jc == null && !rtl && spacing == null && indent == null && tabs == null) return

        sb.append("<w:pPr>")
        if (styleId != null) sb.append("""<w:pStyle w:val="$styleId"/>""")
        if (numId != null) {
            sb.append("""<w:numPr><w:ilvl w:val="0"/><w:numId w:val="$numId"/></w:numPr>""")
        }
        if (tabs != null) {
            sb.append("<w:tabs>")
            for (stop in tabs.sorted()) sb.append("""<w:tab w:val="left" w:pos="${twips(stop)}"/>""")
            sb.append("</w:tabs>")
        }
        if (rtl) sb.append("<w:bidi/>")
        spacing?.let(sb::append)
        indent?.let(sb::append)
        if (jc != null) sb.append("""<w:jc w:val="$jc"/>""")
        sb.append("</w:pPr>")
    }

    /**
     * The paragraph's measured spacing, when a reader supplied any. A line
     * pitch is written as a minimum, not an exact height: a face Word
     * substitutes for one it lacks may need more, and clipped ascenders are
     * worse than a slightly taller line.
     */
    private fun spacingXml(style: app.morpho.engine.layout.ParagraphStyle): String? {
        val before = style.spaceBeforePt?.let(::twips)
        val after = style.spaceAfterPt?.let(::twips)
        val line = style.linePitchPt?.takeIf { it > 0f }?.let(::twips)
        if (before == null && after == null && line == null) return null
        val sb = StringBuilder("<w:spacing")
        before?.let { sb.append(""" w:before="$it"""") }
        after?.let { sb.append(""" w:after="$it"""") }
        line?.let { sb.append(""" w:line="$it" w:lineRule="atLeast"""") }
        return sb.append("/>").toString()
    }

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
    private fun appendRun(sb: StringBuilder, run: TextRun, paragraphDirection: TextDirection) {
        val rtl = (run.direction ?: paragraphDirection) == TextDirection.RTL
        val family = run.fontFamily?.takeIf { it.isNotBlank() }
        val halfPoints = run.fontSizePt?.takeIf { it > 0f }?.let { (it * 2).roundToInt() }
        val hasProps = run.bold || run.italic || run.underline || rtl || run.language != null ||
            family != null || halfPoints != null || run.superscript || run.subscript

        sb.append("<w:r>")
        if (hasProps) {
            sb.append("<w:rPr>")
            // rFonts leads and sz precedes u: the schema fixes the order, and
            // Word rejects a file that breaks it.
            family?.let { f ->
                val name = xmlEscape(f)
                sb.append("""<w:rFonts w:ascii="$name" w:hAnsi="$name" w:cs="$name"/>""")
            }
            if (run.bold) sb.append("<w:b/><w:bCs/>")
            if (run.italic) sb.append("<w:i/><w:iCs/>")
            halfPoints?.let { sb.append("""<w:sz w:val="$it"/><w:szCs w:val="$it"/>""") }
            if (run.underline) sb.append("""<w:u w:val="single"/>""")
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
        // A tab is an element of its own; the character itself has no
        // meaning in w:t.
        val pieces = run.text.split('\t')
        for ((index, piece) in pieces.withIndex()) {
            if (index > 0) sb.append("<w:tab/>")
            if (piece.isEmpty()) continue
            sb.append("""<w:t xml:space="preserve">""")
            sb.append(xmlEscape(piece))
            sb.append("</w:t>")
        }
        sb.append("</w:r>")
    }

    private fun appendTable(
        sb: StringBuilder,
        table: Table,
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
    ) {
        if (table.rows.isEmpty()) return
        val columnCount = table.rows.maxOf { it.cells.size }.coerceAtLeast(1)

        sb.append("<w:tbl>")
        sb.append("<w:tblPr>")
        sb.append("""<w:tblW w:w="0" w:type="auto"/>""")
        sb.append("<w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("""<w:$edge w:val="single" w:sz="4" w:space="0" w:color="auto"/>""")
        }
        sb.append("</w:tblBorders>")
        sb.append("</w:tblPr>")
        sb.append("<w:tblGrid>")
        repeat(columnCount) { sb.append("""<w:gridCol w:w="2340"/>""") }
        sb.append("</w:tblGrid>")

        for (row in table.rows) {
            sb.append("<w:tr>")
            for (cell in row.cells) {
                appendCell(sb, cell, document, numbering, images)
            }
            // Pad short rows so every row has the full column count.
            repeat(columnCount - row.cells.size) {
                appendCell(sb, TableCell(emptyList()), document, numbering, images)
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        // WordprocessingML requires a paragraph after a table at body level.
        sb.append("<w:p/>")
    }

    private fun appendCell(
        sb: StringBuilder,
        cell: TableCell,
        document: DocumentModel,
        numbering: NumberingPlan,
        images: ImagePlan,
    ) {
        sb.append("<w:tc>")
        sb.append("""<w:tcPr><w:tcW w:w="0" w:type="auto"/></w:tcPr>""")
        for (block in cell.blocks) {
            appendBlock(sb, block, document, numbering, images)
        }
        // Every table cell must end with a paragraph. A trailing nested table
        // already appended its own spacer paragraph after </w:tbl>.
        val last = cell.blocks.lastOrNull()
        if (last !is Paragraph && last !is Table) sb.append("<w:p/>")
        sb.append("</w:tc>")
    }

    private const val EMU_PER_PX = 9525L
    /** Content area inside the A4 margins, in EMU. */
    private const val MAX_CX_EMU = 5_731_933L
    private const val MAX_CY_EMU = 8_863_330L

    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** An image as its own paragraph with an inline w:drawing. */
    private fun appendImage(sb: StringBuilder, entry: ImagePlan.Entry) {
        var cx = entry.block.widthPx.coerceAtLeast(1) * EMU_PER_PX
        var cy = entry.block.heightPx.coerceAtLeast(1) * EMU_PER_PX
        // Scale into the content area, preserving aspect ratio.
        if (cx > MAX_CX_EMU) {
            cy = cy * MAX_CX_EMU / cx
            cx = MAX_CX_EMU
        }
        if (cy > MAX_CY_EMU) {
            cx = cx * MAX_CY_EMU / cy
            cy = MAX_CY_EMU
        }
        cx = cx.coerceAtLeast(1)
        cy = cy.coerceAtLeast(1)

        val name = xmlEscape("Image ${entry.docPrId}")
        sb.append("<w:p><w:r><w:drawing>")
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
        sb.append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }

    private fun sectPr(page: PageSetup?): String {
        // The source's own page when the reader measured it; else A4
        // portrait with 2.54 cm margins (values in twentieths of a point).
        if (page == null) {
            return """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
                """<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" """ +
                """w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""
        }
        val landscape = if (page.widthPt > page.heightPt) """ w:orient="landscape"""" else ""
        return """<w:sectPr><w:pgSz w:w="${twips(page.widthPt)}" w:h="${twips(page.heightPt)}"$landscape/>""" +
            """<w:pgMar w:top="${twips(page.marginTopPt)}" w:right="${twips(page.marginRightPt)}" """ +
            """w:bottom="${twips(page.marginBottomPt)}" w:left="${twips(page.marginLeftPt)}" """ +
            """w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""
    }

    // ------------------------------------------------------------------
    // Static parts
    // ------------------------------------------------------------------

    private fun contentTypesXml(): String = XML_DECL +
        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Default Extension="png" ContentType="image/png"/>""" +
        """<Default Extension="jpeg" ContentType="image/jpeg"/>""" +
        """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
        """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
        """<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>""" +
        """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
        """<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""" +
        """</Types>"""

    private fun packageRelsXml(): String = XML_DECL +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
        """<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>""" +
        """</Relationships>"""

    private fun documentRelsXml(images: ImagePlan): String {
        val sb = StringBuilder(XML_DECL)
        sb.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sb.append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        sb.append("""<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>""")
        for (entry in images.entries) {
            sb.append(
                """<Relationship Id="${entry.relId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${entry.fileName}"/>"""
            )
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun corePropsXml(): String = XML_DECL +
        """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
        """<dc:creator>Morpho</dc:creator>""" +
        """</cp:coreProperties>"""

    private fun appPropsXml(): String = XML_DECL +
        """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">""" +
        """<Application>Morpho</Application>""" +
        """</Properties>"""

    private fun stylesXml(): String {
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
            """<w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr></w:rPrDefault>""" +
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

        val nums = StringBuilder()
        nums.append("""<w:num w:numId="$BULLET_NUM_ID"><w:abstractNumId w:val="0"/></w:num>""")
        // One w:num per numbered list, each with a level-0 startOverride.
        // Word keeps a single running count per abstractNum, so a fresh
        // instance alone does NOT restart numbering — the override does.
        for (id in numbering.numberedListIds) {
            nums.append(
                """<w:num w:numId="$id"><w:abstractNumId w:val="1"/>""" +
                    """<w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride>""" +
                    "</w:num>"
            )
        }

        return XML_DECL +
            """<w:numbering xmlns:w="$W">""" +
            """<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>""" +
            level(0, "bullet", "•") + level(1, "bullet", "◦") + level(2, "bullet", "▪") +
            "</w:abstractNum>" +
            """<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>""" +
            level(0, "decimal", "%1.") + level(1, "decimal", "%2.") + level(2, "decimal", "%3.") +
            "</w:abstractNum>" +
            nums +
            "</w:numbering>"
    }
}
