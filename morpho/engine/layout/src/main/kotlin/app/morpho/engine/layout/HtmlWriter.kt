package app.morpho.engine.layout

import java.util.Base64

/**
 * Writes a [DocumentModel] as a self-contained, print-ready HTML document —
 * the first half of the Word→PDF pipeline: the app renders this in a WebView
 * and hands it to the Android print framework, which gives Blink-quality
 * BiDi, shaping and line breaking for free (plan §5.2).
 *
 * The root element carries the document's direction and language; paragraphs
 * whose direction differs from the default carry their own `dir`, and runs
 * carry `dir`/`lang` spans when they differ from their paragraph. Contiguous
 * list items group into `ul`/`ol` (so numbering restarts per list), tables
 * render with collapsed borders, and images embed as data URIs.
 *
 * The markup is deliberately XHTML-conformant (void tags self-closed, all
 * attributes quoted) so tests can parse it with an XML parser. Control
 * characters that XML/HTML cannot represent are dropped, as in the OOXML
 * writer.
 */
object HtmlWriter {

    fun write(document: DocumentModel, title: String? = null): String {
        val defaultDirection = document.defaultDirection
        val dir = if (defaultDirection == TextDirection.RTL) "rtl" else "ltr"
        val lang = document.defaultLanguage?.let { """ lang="${escape(it)}"""" }.orEmpty()

        val sb = StringBuilder(16 * 1024)
        sb.append("<!DOCTYPE html>\n")
        sb.append("""<html dir="$dir"$lang><head><meta charset="utf-8"/>""")
        sb.append("<title>").append(escape(title ?: "Document")).append("</title>")
        sb.append("<style>").append(CSS).append("</style></head><body>\n")

        appendBlocks(sb, document.blocks, defaultDirection)

        sb.append("</body></html>\n")
        return sb.toString()
    }

    private const val CSS =
        "body{font-family:'Noto Naskh Arabic','Times New Roman',serif;" +
            "font-size:12pt;line-height:1.6;margin:48px;}" +
            "h1,h2,h3{line-height:1.25;margin:18pt 0 6pt;}" +
            "h1{font-size:20pt;}h2{font-size:16pt;}h3{font-size:13.5pt;}" +
            "h1.doc-title{font-size:26pt;font-weight:normal;}" +
            "p{margin:0 0 9pt;}" +
            "ul,ol{margin:0 0 9pt;padding-inline-start:24pt;}" +
            "li{margin:0 0 3pt;}" +
            "table{border-collapse:collapse;margin:0 0 9pt;width:100%;}" +
            "td,th{border:1px solid #555;padding:4pt 8pt;vertical-align:top;}" +
            "img{max-width:100%;height:auto;}" +
            "p.image{text-align:center;}"

    private fun appendBlocks(
        sb: StringBuilder,
        blocks: List<Block>,
        defaultDirection: TextDirection,
    ) {
        var openList: ListMarker? = null

        fun closeList() {
            when (openList) {
                ListMarker.BULLET -> sb.append("</ul>\n")
                ListMarker.NUMBERED -> sb.append("</ol>\n")
                null -> {}
            }
            openList = null
        }

        for (block in blocks) {
            when (block) {
                is Paragraph -> {
                    val marker = block.style.listMarker
                    if (marker != openList) {
                        closeList()
                        when (marker) {
                            ListMarker.BULLET -> sb.append("<ul>\n")
                            ListMarker.NUMBERED -> sb.append("<ol>\n")
                            null -> {}
                        }
                        openList = marker
                    }
                    appendParagraph(sb, block, defaultDirection, asListItem = marker != null)
                }
                is Table -> {
                    closeList()
                    appendTable(sb, block, defaultDirection)
                }
                is ImageBlock -> {
                    closeList()
                    appendImage(sb, block)
                }
            }
        }
        closeList()
    }

    private fun appendParagraph(
        sb: StringBuilder,
        paragraph: Paragraph,
        defaultDirection: TextDirection,
        asListItem: Boolean,
    ) {
        val effective = paragraph.style.direction ?: defaultDirection
        val dirAttr =
            if (effective != defaultDirection) {
                """ dir="${if (effective == TextDirection.RTL) "rtl" else "ltr"}""""
            } else {
                ""
            }
        val (tag, classAttr) = when {
            asListItem -> "li" to ""
            else -> when (paragraph.style.kind) {
                ParagraphKind.TITLE -> "h1" to """ class="doc-title""""
                ParagraphKind.HEADING_1 -> "h1" to ""
                ParagraphKind.HEADING_2 -> "h2" to ""
                ParagraphKind.HEADING_3 -> "h3" to ""
                ParagraphKind.BODY -> "p" to ""
            }
        }
        sb.append("<").append(tag).append(classAttr).append(dirAttr).append(">")
        for (run in paragraph.runs) appendRun(sb, run, effective)
        sb.append("</").append(tag).append(">\n")
    }

    private fun appendRun(sb: StringBuilder, run: TextRun, paragraphDirection: TextDirection) {
        var html = escape(run.text)
        if (run.underline) html = "<u>$html</u>"
        if (run.italic) html = "<em>$html</em>"
        if (run.bold) html = "<strong>$html</strong>"

        val runDirection = run.direction
        val needsDir = runDirection != null && runDirection != paragraphDirection
        val needsLang = run.language != null
        if (needsDir || needsLang) {
            val dirAttr =
                if (needsDir) {
                    """ dir="${if (runDirection == TextDirection.RTL) "rtl" else "ltr"}""""
                } else {
                    ""
                }
            val langAttr = if (needsLang) """ lang="${escape(run.language!!)}"""" else ""
            html = "<span$dirAttr$langAttr>$html</span>"
        }
        sb.append(html)
    }

    private fun appendTable(sb: StringBuilder, table: Table, defaultDirection: TextDirection) {
        if (table.rows.isEmpty()) return
        sb.append("<table>\n")
        for (row in table.rows) {
            sb.append("<tr>")
            for (cell in row.cells) {
                sb.append("<td>")
                appendBlocks(sb, cell.blocks, defaultDirection)
                sb.append("</td>")
            }
            sb.append("</tr>\n")
        }
        sb.append("</table>\n")
    }

    private fun appendImage(sb: StringBuilder, image: ImageBlock) {
        sb.append("""<p class="image"><img src="data:""")
        sb.append(escape(image.mimeType))
        sb.append(";base64,")
        sb.append(Base64.getEncoder().encodeToString(image.bytes))
        sb.append("""" width="${image.widthPx.coerceAtLeast(1)}"""")
        sb.append(""" height="${image.heightPx.coerceAtLeast(1)}" alt=""/></p>""").append("\n")
    }

    private fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&#39;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch.code < 0x20 || ch == '\uFFFE' || ch == '\uFFFF' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
