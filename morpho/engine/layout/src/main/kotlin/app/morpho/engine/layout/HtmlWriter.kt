package app.morpho.engine.layout

import java.util.IdentityHashMap

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

    /**
     * The notes of the document being written, by the run that carries
     * each. A writer is a singleton and a note's number is a property of
     * the whole document, not of the run, so it is kept here for the
     * length of one write and thrown away after it.
     */
    private val noteNumbers = ThreadLocal<Map<TextRun, Int>>()

    fun write(document: DocumentModel, title: String? = null): String =
        try {
            noteNumbers.set(numberNotes(document.blocks))
            writeDocument(document, title)
        } finally {
            noteNumbers.remove()
        }

    private fun writeDocument(document: DocumentModel, title: String?): String {
        val defaultDirection = document.defaultDirection
        val dir = if (defaultDirection == TextDirection.RTL) "rtl" else "ltr"
        val lang = document.defaultLanguage?.let { """ lang="${escape(it)}"""" }.orEmpty()

        val sb = StringBuilder(16 * 1024)
        sb.append("<!DOCTYPE html>\n")
        sb.append("""<html dir="$dir"$lang><head><meta charset="utf-8"/>""")
        sb.append("<title>").append(escape(title ?: "Document")).append("</title>")
        sb.append("<style>").append(CSS).append(pageCss(document.pageSetup)).append("</style></head><body>\n")

        // The running header and footer, once each: a flowing page has no
        // page tops to repeat them on, so they stand at the head and the
        // foot of the document, where a reader expects them.
        if (document.header.isNotEmpty()) {
            sb.append("""<header class="page-header">""").append("\n")
            appendBlocks(sb, document.header, defaultDirection)
            sb.append("</header>\n")
        }
        appendBlocks(sb, document.blocks, defaultDirection)
        appendNotes(sb, document.blocks, defaultDirection)
        if (document.footer.isNotEmpty()) {
            sb.append("""<footer class="page-footer">""").append("\n")
            appendBlocks(sb, document.footer, defaultDirection)
            sb.append("</footer>\n")
        }

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
            // A list inside a list is marked its own way, as an outline is
            // set: 1. then a) then i., and a bullet that changes with it.
            "ol ol{list-style-type:lower-alpha;}ol ol ol{list-style-type:lower-roman;}" +
            "ul ul{list-style-type:circle;}ul ul ul{list-style-type:square;}" +
            "li{margin:0 0 3pt;}" +
            "table{border-collapse:collapse;margin:0 0 9pt;table-layout:fixed;}" +
            "section.footnotes{border-top:0.75pt solid;margin-top:12pt;padding-top:4pt;font-size:0.85em;}" +
            "a.note-mark{text-decoration:none;vertical-align:super;font-size:0.75em;}" +
            "td,th{border:1px solid #555;padding:4pt 8pt;vertical-align:top;}" +
            "img{max-width:100%;height:auto;}" +
            "p.image{text-align:center;}" +
            "img.inline{vertical-align:baseline;}" +
            ".page-header{margin-bottom:12pt;}.page-footer{margin-top:12pt;}"

    private fun appendBlocks(
        sb: StringBuilder,
        blocks: List<Block>,
        defaultDirection: TextDirection,
    ) {
        // The lists standing open, outermost first: a list inside a list is
        // a list inside a list item, which is how HTML nests them.
        val open = ArrayDeque<ListMarker>()

        fun tagOf(marker: ListMarker) = if (marker == ListMarker.BULLET) "ul" else "ol"

        fun closeList() {
            while (open.isNotEmpty()) sb.append("</" + tagOf(open.removeLast()) + ">\n")
        }

        for (block in blocks) {
            when (block) {
                is Paragraph -> {
                    val marker = block.style.listMarker
                    if (marker == null) {
                        closeList()
                    } else {
                        val depth = block.style.listLevel + 1
                        while (open.size > depth) sb.append("</" + tagOf(open.removeLast()) + ">\n")
                        // A list that changes its marker where it stands is a
                        // new list, not the one that was open.
                        if (open.size == depth && open.last() != marker) {
                            sb.append("</" + tagOf(open.removeLast()) + ">\n")
                        }
                        while (open.size < depth) {
                            sb.append("<" + tagOf(marker) + ">\n")
                            open.addLast(marker)
                        }
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
        val styles = buildList {
            when (paragraph.style.alignment) {
                Alignment.CENTER -> add("text-align:center")
                Alignment.JUSTIFY -> add("text-align:justify")
                Alignment.END -> add("text-align:end")
                Alignment.START, null -> {}
            }
            // What the reader measured on the page, so the preview sits the
            // way the source did: a hanging indent is a start padding with
            // the first line pulled back out of it.
            val style = paragraph.style
            val hanging = style.hangingIndentPt?.takeIf { it > 0f }
            val start = style.startIndentPt?.takeIf { it > 0f }
            if (start != null) add("padding-inline-start:${pt(start)}")
            if (hanging != null) add("text-indent:-${pt(hanging)}")
            else style.firstLineIndentPt?.takeIf { it > 0f }?.let { add("text-indent:${pt(it)}") }
            style.spaceBeforePt?.let { add("margin-top:${pt(it)}") }
            style.spaceAfterPt?.let { add("margin-bottom:${pt(it)}") }
            style.linePitchPt?.takeIf { it > 0f }?.let { add("line-height:${pt(it)}") }
            // Where the source began a page, so does the print.
            if (style.pageBreakBefore) add("break-before:page;page-break-before:always")
            if (style.ruleAbove) add("border-top:0.75pt solid;padding-top:1pt")
            if (style.ruleBelow) add("border-bottom:0.75pt solid;padding-bottom:1pt")
            // A tab character only advances when white space is kept; where
            // the paragraph knows its stops, the text after each tab is
            // placed at its stop instead (see appendTabbed).
            if (paragraph.runs.any { '\t' in it.text }) {
                if (style.tabStopsPt.isNullOrEmpty()) add("white-space:pre-wrap") else add("position:relative")
            }
        }
        val styleAttr = if (styles.isEmpty()) "" else """ style="${styles.joinToString(";")}""""
        sb.append("<").append(tag).append(classAttr).append(dirAttr).append(styleAttr).append(">")
        val stops = paragraph.style.tabStopsPt?.filter { it > 0f }?.sorted().orEmpty()
        if (stops.isNotEmpty() && paragraph.runs.any { '\t' in it.text }) {
            appendTabbed(sb, paragraph.runs, stops, effective)
        } else {
            for (run in paragraph.runs) appendRun(sb, run, effective)
        }
        sb.append("</").append(tag).append(">\n")
    }

    /**
     * A paragraph set to tab stops — three dates spread across a line —
     * with each stretch after a tab placed at its stop from the start
     * edge, which is where a tab takes the text in Word. HTML has no tab
     * stops of its own; a stretch past the last stop follows the one
     * before it.
     */
    private fun appendTabbed(
        sb: StringBuilder,
        runs: List<TextRun>,
        stops: List<Float>,
        paragraphDirection: TextDirection,
    ) {
        val segments = mutableListOf<MutableList<TextRun>>(mutableListOf())
        for (run in runs) {
            val pieces = run.text.split('\t')
            for ((index, piece) in pieces.withIndex()) {
                if (index > 0) segments.add(mutableListOf())
                if (piece.isNotEmpty()) segments.last() += run.copy(text = piece)
            }
        }
        for ((index, segment) in segments.withIndex()) {
            val stop = stops.getOrNull(index - 1)
            if (index > 0 && stop != null) {
                sb.append("""<span style="position:absolute;inset-inline-start:${pt(stop)}">""")
            } else if (index > 0) {
                sb.append(" ")
            }
            for (run in segment) appendRun(sb, run, paragraphDirection)
            if (index > 0 && stop != null) sb.append("</span>")
        }
    }

    private fun appendRun(sb: StringBuilder, run: TextRun, paragraphDirection: TextDirection) {
        run.image?.let { image ->
            sb.append(imageTag(image, inline = true))
            return
        }
        // A field's last value stands in: a page has no number in a page
        // that scrolls.
        var html = escape(run.text.ifEmpty { if (run.field != null) "1" else "" })
        if (run.superscript) html = "<sup>$html</sup>"
        else if (run.subscript) html = "<sub>$html</sub>"
        if (run.underline) html = "<u>$html</u>"
        if (run.strikethrough) html = "<s>$html</s>"
        if (run.italic) html = "<em>$html</em>"
        if (run.bold) html = "<strong>$html</strong>"

        val runDirection = run.direction
        val needsDir = runDirection != null && runDirection != paragraphDirection
        val needsLang = run.language != null
        // The preview shows the document in its own type, so what the reader
        // sees is what the file will hold: the family the source named and
        // its size in points, with the body stack as the fallback.
        val styles = buildList {
            run.fontFamily?.takeIf { it.isNotBlank() }?.let {
                add("font-family:'${escape(it.replace("'", ""))}','Noto Naskh Arabic','Times New Roman',serif")
            }
            run.fontSizePt?.takeIf { it > 0f }?.let { add("font-size:${pt(it)}") }
            run.colorRgb?.let { add("color:${hexColor(it)}") }
            run.highlightRgb?.let { add("background-color:${hexColor(it)}") }
        }
        if (needsDir || needsLang || styles.isNotEmpty()) {
            val dirAttr =
                if (needsDir) {
                    """ dir="${if (runDirection == TextDirection.RTL) "rtl" else "ltr"}""""
                } else {
                    ""
                }
            val langAttr = if (needsLang) """ lang="${escape(run.language!!)}"""" else ""
            val styleAttr = if (styles.isNotEmpty()) """ style="${styles.joinToString(";")}"""" else ""
            html = "<span$dirAttr$langAttr$styleAttr>$html</span>"
        }
        // A link the source carried: the preview is a page, so it behaves
        // like one. The look stays the run's own — an address a document
        // prints in black stays black.
        run.link?.let { html = "<a href=\"${escape(it)}\">$html</a>" }
        // A mark that carries a note leads to it: HTML has no notes of its
        // own, so they are gathered at the end, as a printed page gathers
        // endnotes, and the mark is what takes a reader there.
        noteNumberOf(run)?.let { number ->
            html = "<a class=\"note-mark\" id=\"note-mark-$number\" href=\"#note-$number\">$html</a>"
        }
        sb.append(html)
    }

    /** Every note in the document, numbered in the order its mark appears. */
    private fun numberNotes(blocks: List<Block>): Map<TextRun, Int> {
        val numbers = IdentityHashMap<TextRun, Int>()
        fun walk(list: List<Block>) {
            for (block in list) {
                when (block) {
                    is Paragraph -> for (run in block.runs) {
                        if (!run.note.isNullOrEmpty()) numbers[run] = numbers.size + 1
                    }
                    is Table -> for (row in block.rows) for (cell in row.cells) walk(cell.blocks)
                    is ImageBlock -> {}
                }
            }
        }
        walk(blocks)
        return numbers
    }

    private fun noteNumberOf(run: TextRun): Int? = noteNumbers.get()?.get(run)

    /**
     * The notes, under a rule at the end. A page sets its notes at its own
     * foot; a page that scrolls has no foot to set them at, so they are
     * gathered here, each one led back to the mark that called it.
     */
    private fun appendNotes(sb: StringBuilder, blocks: List<Block>, defaultDirection: TextDirection) {
        val numbers = noteNumbers.get().orEmpty()
        if (numbers.isEmpty()) return
        val byNumber = numbers.entries.sortedBy { it.value }
        sb.append("""<section class="footnotes">""").append("\n")
        for ((run, number) in byNumber) {
            sb.append("""<div class="footnote" id="note-$number">""")
            sb.append("""<a class="note-mark" href="#note-mark-$number">""")
            sb.append(escape(run.text.trim().ifEmpty { number.toString() }))
            sb.append("</a> ")
            appendBlocks(sb, run.note.orEmpty(), defaultDirection)
            sb.append("</div>\n")
        }
        sb.append("</section>\n")
    }

    /** A packed 0xRRGGBB colour as CSS writes one. */
    private fun hexColor(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)

    /**
     * The source's page, when a reader measured it, as the sheet the print
     * framework lays this out on and the margins the preview keeps.
     */
    private fun pageCss(page: PageSetup?): String {
        if (page == null) return ""
        val margins = "${pt(page.marginTopPt)} ${pt(page.marginRightPt)} " +
            "${pt(page.marginBottomPt)} ${pt(page.marginLeftPt)}"
        // The margins belong to the sheet when this is printed — the app
        // prints it to make a PDF, with the framework's own margins turned
        // off — and to the body only on screen, where there is no sheet.
        // Setting both unconditionally would print every margin twice.
        return "@page{size:${pt(page.widthPt)} ${pt(page.heightPt)};margin:$margins;}" +
            "@media screen{body{margin:$margins;}}"
    }

    private fun pt(points: Float): String = "%.1fpt".format(java.util.Locale.ROOT, points)

    private fun appendTable(sb: StringBuilder, table: Table, defaultDirection: TextDirection) {
        if (table.rows.isEmpty()) return
        // The widths a reader measured, and the rules the page drew — a
        // table found by the alignment of its columns has none, and lines
        // the source never drew would be ink of our own invention.
        val widths = table.columnWidthsPt?.takeIf { widths -> widths.isNotEmpty() && widths.all { it > 0f } }
        val styles = buildList {
            if (widths != null) add("width:${pt(widths.sum())}") else add("width:100%")
            if (!table.ruled) add("border:0")
        }
        // A table of Arabic runs from the right, whatever the page does.
        val tableDirection = table.direction ?: defaultDirection
        val dir = if (tableDirection == TextDirection.RTL) """ dir="rtl"""" else ""
        sb.append("""<table$dir style="${styles.joinToString(";")}">""").append("\n")
        if (widths != null) {
            for (width in widths) sb.append("""<col style="width:${pt(width)}">""")
            sb.append("\n")
        }
        for (row in table.rows) {
            sb.append("<tr>")
            for (cell in row.cells) {
                sb.append("<td")
                if (cell.columnSpan > 1) sb.append(""" colspan="${cell.columnSpan}"""")
                if (cell.rowSpan > 1) sb.append(""" rowspan="${cell.rowSpan}"""")
                val cellStyles = buildList {
                    if (!table.ruled) add("border:0")
                    cell.shadingRgb?.let { add("background-color:${hexColor(it)}") }
                }
                if (cellStyles.isNotEmpty()) {
                    sb.append(""" style="${cellStyles.joinToString(";")}"""")
                }
                sb.append(">")
                appendBlocks(sb, cell.blocks, defaultDirection)
                sb.append("</td>")
            }
            sb.append("</tr>\n")
        }
        sb.append("</table>\n")
    }

    private fun appendImage(sb: StringBuilder, image: ImageBlock) {
        sb.append("""<p class="image">""").append(imageTag(image, inline = false)).append("</p>\n")
    }

    /** A picture as a data URI, shown at the size the reader measured when it did. */
    private fun imageTag(image: ImageBlock, inline: Boolean): String {
        val sb = StringBuilder()
        sb.append("<img")
        if (inline) sb.append(""" class="inline"""")
        sb.append(""" src="data:""")
        sb.append(escape(image.mimeType))
        sb.append(";base64,")
        sb.append(Base64.getEncoder().encodeToString(image.bytes))
        sb.append("\"")
        val widthPt = image.widthPt?.takeIf { it > 0f }
        val heightPt = image.heightPt?.takeIf { it > 0f }
        if (widthPt != null && heightPt != null) {
            sb.append(""" style="width:${pt(widthPt)};height:${pt(heightPt)}"""")
        } else {
            sb.append(""" width="${image.widthPx.coerceAtLeast(1)}"""")
            sb.append(""" height="${image.heightPx.coerceAtLeast(1)}"""")
        }
        sb.append(""" alt=""/>""")
        return sb.toString()
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
