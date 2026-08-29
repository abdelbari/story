package app.morpho.engine.ooxml

import app.morpho.engine.layout.Alignment
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.ListMarker
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.TableCell
import app.morpho.engine.layout.TextDirection
import app.morpho.engine.layout.TextRun
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
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
 * Heading 1–3 styles, bullet and numbered lists, simple tables, per-paragraph
 * and per-run right-to-left direction (`w:bidi`/`w:rtl`), and run languages.
 * Images land with the M1 media-part work and are rejected loudly until then —
 * silently dropping content is never acceptable.
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
        ZipOutputStream(output).use { zip ->
            zip.part("[Content_Types].xml", contentTypesXml())
            zip.part("_rels/.rels", packageRelsXml())
            zip.part("word/_rels/document.xml.rels", documentRelsXml())
            zip.part("word/document.xml", documentXml(document))
            zip.part("word/styles.xml", stylesXml())
            zip.part("word/numbering.xml", numberingXml())
            zip.part("docProps/core.xml", corePropsXml())
            zip.part("docProps/app.xml", appPropsXml())
        }
    }

    private fun ZipOutputStream.part(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    // ------------------------------------------------------------------
    // word/document.xml
    // ------------------------------------------------------------------

    private fun documentXml(document: DocumentModel): String {
        val sb = StringBuilder(16 * 1024)
        sb.append(XML_DECL)
        sb.append("""<w:document xmlns:w="$W"><w:body>""")
        for (block in document.blocks) {
            appendBlock(sb, block, document)
        }
        sb.append(sectPr())
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun appendBlock(sb: StringBuilder, block: Block, document: DocumentModel) {
        when (block) {
            is Paragraph -> appendParagraph(sb, block, document)
            is Table -> appendTable(sb, block, document)
            is ImageBlock -> throw UnsupportedOperationException(
                "ImageBlock is not supported yet: the media part lands with milestone M1. " +
                    "Refusing to write a document that would silently lose content."
            )
        }
    }

    private fun appendParagraph(sb: StringBuilder, paragraph: Paragraph, document: DocumentModel) {
        val effectiveDirection = paragraph.style.direction ?: document.defaultDirection
        sb.append("<w:p>")
        appendParagraphProperties(sb, paragraph.style, effectiveDirection)
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
        val numId = when (style.listMarker) {
            ListMarker.BULLET -> 1
            ListMarker.NUMBERED -> 2
            null -> null
        }
        val jc = when (style.alignment) {
            Alignment.CENTER -> "center"
            Alignment.JUSTIFY -> "both"
            // START/END follow the paragraph direction by default in Word;
            // omitting w:jc keeps the correct behavior for both LTR and RTL.
            Alignment.START, Alignment.END, null -> null
        }
        val rtl = effectiveDirection == TextDirection.RTL

        if (styleId == null && numId == null && jc == null && !rtl) return

        sb.append("<w:pPr>")
        if (styleId != null) sb.append("""<w:pStyle w:val="$styleId"/>""")
        if (numId != null) {
            sb.append("""<w:numPr><w:ilvl w:val="0"/><w:numId w:val="$numId"/></w:numPr>""")
        }
        if (rtl) sb.append("<w:bidi/>")
        if (jc != null) sb.append("""<w:jc w:val="$jc"/>""")
        sb.append("</w:pPr>")
    }

    /** Children of w:rPr are emitted in the order the OOXML schema requires. */
    private fun appendRun(sb: StringBuilder, run: TextRun, paragraphDirection: TextDirection) {
        val rtl = (run.direction ?: paragraphDirection) == TextDirection.RTL
        val hasProps = run.bold || run.italic || run.underline || rtl || run.language != null

        sb.append("<w:r>")
        if (hasProps) {
            sb.append("<w:rPr>")
            if (run.bold) sb.append("<w:b/><w:bCs/>")
            if (run.italic) sb.append("<w:i/><w:iCs/>")
            if (run.underline) sb.append("""<w:u w:val="single"/>""")
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
        sb.append("""<w:t xml:space="preserve">""")
        sb.append(xmlEscape(run.text))
        sb.append("</w:t></w:r>")
    }

    private fun appendTable(sb: StringBuilder, table: Table, document: DocumentModel) {
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
                appendCell(sb, cell, document)
            }
            // Pad short rows so every row has the full column count.
            repeat(columnCount - row.cells.size) {
                appendCell(sb, TableCell(emptyList()), document)
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        // WordprocessingML requires a paragraph after a table at body level.
        sb.append("<w:p/>")
    }

    private fun appendCell(sb: StringBuilder, cell: TableCell, document: DocumentModel) {
        sb.append("<w:tc>")
        sb.append("""<w:tcPr><w:tcW w:w="0" w:type="auto"/></w:tcPr>""")
        var wroteParagraph = false
        for (block in cell.blocks) {
            appendBlock(sb, block, document)
            if (block is Paragraph) wroteParagraph = true
        }
        // Every table cell must end with a paragraph.
        if (!wroteParagraph || cell.blocks.lastOrNull() !is Paragraph) sb.append("<w:p/>")
        sb.append("</w:tc>")
    }

    private fun sectPr(): String =
        // A4 portrait, 2.54 cm margins (values in twentieths of a point).
        """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
            """<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" """ +
            """w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""

    // ------------------------------------------------------------------
    // Static parts
    // ------------------------------------------------------------------

    private fun contentTypesXml(): String = XML_DECL +
        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
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

    private fun documentRelsXml(): String = XML_DECL +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>""" +
        """</Relationships>"""

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

    private fun numberingXml(): String {
        fun level(ilvl: Int, numFmt: String, lvlText: String): String =
            """<w:lvl w:ilvl="$ilvl"><w:start w:val="1"/><w:numFmt w:val="$numFmt"/>""" +
                """<w:lvlText w:val="$lvlText"/><w:lvlJc w:val="left"/>""" +
                """<w:pPr><w:ind w:left="${720 * (ilvl + 1)}" w:hanging="360"/></w:pPr></w:lvl>"""

        return XML_DECL +
            """<w:numbering xmlns:w="$W">""" +
            """<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>""" +
            level(0, "bullet", "•") + level(1, "bullet", "◦") + level(2, "bullet", "▪") +
            "</w:abstractNum>" +
            """<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>""" +
            level(0, "decimal", "%1.") + level(1, "decimal", "%2.") + level(2, "decimal", "%3.") +
            "</w:abstractNum>" +
            """<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>""" +
            """<w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>""" +
            "</w:numbering>"
    }
}
