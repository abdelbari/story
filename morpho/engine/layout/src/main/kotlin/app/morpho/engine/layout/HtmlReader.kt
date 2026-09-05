package app.morpho.engine.layout

import java.util.Base64

/**
 * Rich text pasted in from anywhere else, read as blocks.
 *
 * What another app puts on the clipboard beside the plain text is HTML,
 * and it is HTML of every kind at once: Word's, with its `<b>` and its
 * `mso-` styles; Docs', where every run is a `<span>` whose style says
 * `font-weight:700`; a web page's; this app's own page, copied from one
 * place in a document to another. None of it is trusted and all of it is
 * read: what is understood becomes paragraphs, headings, items of lists,
 * tables and pictures, set as the markup says; what is not is text, or
 * nothing. Nothing here throws, however the markup is broken, and
 * nothing here fetches anything — a picture comes in only as the bytes
 * the markup itself carries.
 *
 * This is a reader of pasted text, not of documents: it has no notion of
 * a page, a running head or a note, and it does not need one. It is
 * bounded in every dimension a hostile clipboard could push on: the
 * length it reads, how deep the elements nest, how many blocks it makes.
 */
object HtmlReader {

    /** The most markup one paste is read from; the rest is not looked at. */
    const val MOST_LENGTH = 2_000_000

    /** How deep elements may nest before further ones are read as their own contents. */
    const val MOST_DEPTH = 64

    /** The most blocks one paste becomes, cells' blocks counted too. */
    const val MOST_BLOCKS = 100_000

    /** [html] as the blocks it describes, in order; empty where it describes none. */
    fun read(html: String): List<Block> = Reading(html.take(MOST_LENGTH)).read()

    // ---- the reading ----

    /** How the text inside an element is set, inherited down the elements. */
    private data class Look(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val superscript: Boolean = false,
        val subscript: Boolean = false,
        val link: String? = null,
        val colorRgb: Int? = null,
        val highlightRgb: Int? = null,
        val fontSizePt: Float? = null,
        val fontFamily: String? = null,
        val direction: TextDirection? = null,
        val language: String? = null,
        val pre: Boolean = false,
        /** Inside an element whose contents are not text at all. */
        val hidden: Boolean = false,
    )

    private class Open(val tag: String, val look: Look, val list: ListMarker? = null, val cell: CellBuilder? = null, val table: TableBuilder? = null)

    private class CellBuilder(val columnSpan: Int, val rowSpan: Int, val shadingRgb: Int?, val unruled: Boolean) {
        val blocks = mutableListOf<Block>()
    }

    private class TableBuilder(val direction: TextDirection?) {
        val rows = mutableListOf<TableRow>()
        val widths = mutableListOf<Float>()
        var current = mutableListOf<TableCell>()
        var rowOpen = false
        var inHead = false
        var cells = 0
        var unruled = 0

        fun endRow() {
            if (rowOpen) rows += TableRow(current, repeatsAsHeader = inHead)
            current = mutableListOf()
            rowOpen = false
        }
    }

    private class Reading(private val html: String) {
        private val stack = ArrayList<Open>()
        private val out = mutableListOf<Block>()
        private var made = 0

        // The paragraph being gathered: its runs, and how it is set.
        private val runs = mutableListOf<TextRun>()
        private var style = ParagraphStyle()
        private var paragraphOpen = false

        fun read(): List<Block> {
            var at = 0
            val n = html.length
            while (at < n && made < MOST_BLOCKS) {
                val c = html[at]
                if (c == '<' && at + 1 < n) {
                    val next = html[at + 1]
                    when {
                        next == '!' -> at = skipDeclaration(at)
                        next == '?' -> at = skipUntil(at, ">") 
                        next == '/' -> at = closeTag(at)
                        next.isLetter() -> at = openTag(at)
                        else -> { text("<"); at++ }
                    }
                } else if (c == '<') {
                    text("<")
                    at++
                } else {
                    val end = html.indexOf('<', at).let { if (it < 0) n else it }
                    text(html.substring(at, end))
                    at = end
                }
            }
            while (stack.isNotEmpty()) closeOne()
            flush()
            return out.toList()
        }

        // ---- text ----

        private fun look(): Look = stack.lastOrNull()?.look ?: Look()

        private fun text(raw: String) {
            val look = look()
            if (look.hidden || raw.isEmpty()) return
            val decoded = Entities.decode(raw)
            if (look.pre) {
                // Kept as written: every line a line break, every tab a tab.
                val lines = decoded.split("\r\n", "\r", "\n")
                for ((index, line) in lines.withIndex()) {
                    if (index > 0) addRun(TextRun(LineBreaks.MARK.toString()), look)
                    if (line.isNotEmpty()) addRun(TextRun(line), look)
                }
                return
            }
            val collapsed = WHITESPACE.replace(decoded, " ")
            if (collapsed.isEmpty()) return
            // Space at the head of a paragraph, or after a break, is markup's.
            val text = if (!paragraphOpen || endsWithBreak()) collapsed.trimStart(' ') else collapsed
            if (text.isEmpty()) return
            addRun(TextRun(text.replace(NO_BREAK_SPACE, ' ')), look)
        }

        private fun endsWithBreak(): Boolean = runs.lastOrNull()?.text?.endsWith(LineBreaks.MARK) == true || runs.isEmpty()

        private fun addRun(run: TextRun, look: Look) {
            paragraphOpen = true
            val last = runs.lastOrNull()
            // A space after a space is markup's too.
            var text = run.text
            if (last != null && last.text.endsWith(" ") && text.startsWith(" ") && run.image == null) text = text.trimStart(' ')
            if (text.isEmpty() && run.image == null) return
            runs += run.copy(
                text = text,
                bold = look.bold,
                italic = look.italic,
                underline = look.underline,
                strikethrough = look.strikethrough,
                superscript = look.superscript,
                subscript = look.subscript,
                link = look.link,
                colorRgb = look.colorRgb,
                highlightRgb = look.highlightRgb,
                fontSizePt = look.fontSizePt,
                fontFamily = look.fontFamily,
                direction = look.direction,
                language = look.language,
            )
        }

        /** The paragraph gathered so far, given to whatever holds blocks here. */
        private fun flush() {
            if (!paragraphOpen) return
            paragraphOpen = false
            val trimmed = trimmedRuns()
            val block: Block? = when {
                trimmed.isEmpty() -> null
                trimmed.size == 1 && trimmed[0].image != null && trimmed[0].text.isEmpty() && style == ParagraphStyle() -> trimmed[0].image
                else -> Paragraph(ParagraphEdit.merged(trimmed).ifEmpty { listOf(TextRun("")) }, style)
            }
            runs.clear()
            style = ParagraphStyle()
            if (block != null) give(block)
        }

        /** The runs with the trailing space markup put there taken off. */
        private fun trimmedRuns(): List<TextRun> {
            val kept = runs.toMutableList()
            while (kept.isNotEmpty()) {
                val last = kept.last()
                if (last.image != null || last.field != null) break
                val text = last.text.trimEnd(' ')
                if (text == last.text) break
                kept[kept.size - 1] = last.copy(text = text)
                if (text.isEmpty()) kept.removeAt(kept.size - 1) else break
            }
            return kept
        }

        private fun give(block: Block) {
            made++
            val cell = stack.lastOrNull { it.cell != null }?.cell
            if (cell != null) cell.blocks += block else out += block
        }

        // ---- tags ----

        private fun openTag(from: Int): Int {
            var at = from + 1
            val nameStart = at
            while (at < html.length && (html[at].isLetterOrDigit() || html[at] == '-' || html[at] == ':')) at++
            val tag = html.substring(nameStart, at).lowercase()
            val attributes = HashMap<String, String>()
            var selfClosing = false
            // Attributes until the end of the tag, however they are quoted.
            while (at < html.length) {
                val c = html[at]
                if (c == '>') { at++; break }
                if (c == '/' && at + 1 < html.length && html[at + 1] == '>') { selfClosing = true; at += 2; break }
                if (c.isWhitespace() || c == '/') { at++; continue }
                val keyStart = at
                while (at < html.length && !html[at].isWhitespace() && html[at] != '=' && html[at] != '>' && !(html[at] == '/' && at + 1 < html.length && html[at + 1] == '>')) at++
                val key = html.substring(keyStart, at).lowercase()
                while (at < html.length && html[at].isWhitespace()) at++
                var value = ""
                if (at < html.length && html[at] == '=') {
                    at++
                    while (at < html.length && html[at].isWhitespace()) at++
                    if (at < html.length && (html[at] == '"' || html[at] == '\'')) {
                        val quote = html[at]
                        val end = html.indexOf(quote, at + 1).let { if (it < 0) html.length else it }
                        value = html.substring(at + 1, end)
                        at = minOf(end + 1, html.length)
                    } else {
                        val valueStart = at
                        while (at < html.length && !html[at].isWhitespace() && html[at] != '>') at++
                        value = html.substring(valueStart, at)
                    }
                }
                if (key.isNotEmpty() && key !in attributes) attributes[key] = Entities.decode(value)
            }
            open(tag, attributes, selfClosing)
            return at
        }

        private fun closeTag(from: Int): Int {
            var at = from + 2
            val nameStart = at
            while (at < html.length && (html[at].isLetterOrDigit() || html[at] == '-' || html[at] == ':')) at++
            val tag = html.substring(nameStart, at).lowercase()
            at = skipUntil(at, ">")
            close(tag)
            return at
        }

        private fun skipDeclaration(from: Int): Int =
            if (html.startsWith("<!--", from)) skipUntil(from + 4, "-->") else skipUntil(from, ">")

        private fun skipUntil(from: Int, end: String): Int {
            val at = html.indexOf(end, from)
            return if (at < 0) html.length else at + end.length
        }

        // ---- elements ----

        private fun open(tag: String, attributes: Map<String, String>, selfClosing: Boolean) {
            val parent = look()
            if (parent.hidden && tag !in HIDDEN) return
            when (tag) {
                "br" -> { if (!parent.hidden) addRun(TextRun(LineBreaks.MARK.toString()), parent); return }
                "hr" -> { flush(); return }
                "img" -> { image(attributes, parent); return }
                "col" -> { column(attributes); return }
                in VOID -> return
            }
            if (tag in HIDDEN) {
                push(Open(tag, parent.copy(hidden = true)))
                if (selfClosing) closeOne()
                return
            }
            val styles = Styles.of(attributes["style"])
            val look = inlineLook(tag, attributes, styles, parent)
            when (tag) {
                "p", "div", "blockquote", "pre", "section", "article", "header", "footer", "main", "aside", "nav", "figure", "figcaption", "address", "center", "dd", "dt", "dl", "form", "fieldset", "details", "summary" -> {
                    flush()
                    push(Open(tag, look))
                    style = paragraphStyle(attributes, styles, kind = ParagraphKind.BODY)
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    flush()
                    push(Open(tag, look))
                    val kind = when {
                        tag == "h1" && attributes["class"]?.split(' ')?.contains("doc-title") == true -> ParagraphKind.TITLE
                        tag == "h1" -> ParagraphKind.HEADING_1
                        tag == "h2" -> ParagraphKind.HEADING_2
                        else -> ParagraphKind.HEADING_3
                    }
                    style = paragraphStyle(attributes, styles, kind)
                }
                "ul", "ol" -> {
                    flush()
                    push(Open(tag, look, list = if (tag == "ul") ListMarker.BULLET else ListMarker.NUMBERED))
                }
                "li" -> {
                    flush()
                    closeWhile { it.tag == "li" }
                    push(Open(tag, look))
                    style = itemStyle(attributes, styles)
                }
                "table" -> {
                    flush()
                    val direction = directionOf(attributes, styles)
                    push(Open(tag, look, table = TableBuilder(direction)))
                }
                "thead", "tbody", "tfoot" -> {
                    flush()
                    tableOpen()?.let { it.endRow(); it.inHead = tag == "thead" }
                    push(Open(tag, look))
                }
                "tr" -> {
                    flush()
                    val table = tableOpen() ?: return
                    closeWhile { it.tag == "td" || it.tag == "th" || it.tag == "tr" }
                    table.endRow()
                    table.rowOpen = true
                    push(Open(tag, look))
                }
                "td", "th" -> {
                    flush()
                    val table = tableOpen() ?: return
                    closeWhile { it.tag == "td" || it.tag == "th" }
                    if (!table.rowOpen) table.rowOpen = true
                    val unruled = styles["border"]?.lowercase()?.let { it == "0" || it.startsWith("0 ") || it == "none" } == true
                    val cell = CellBuilder(
                        columnSpan = attributes["colspan"]?.trim()?.toIntOrNull()?.coerceIn(1, MOST_SPAN) ?: 1,
                        rowSpan = attributes["rowspan"]?.trim()?.toIntOrNull()?.coerceIn(1, MOST_SPAN) ?: 1,
                        shadingRgb = Styles.color(styles["background-color"] ?: styles["background"] ?: attributes["bgcolor"]),
                        unruled = unruled,
                    )
                    push(Open(tag, if (tag == "th") look.copy(bold = true) else look, cell = cell))
                }
                else -> push(Open(tag, look))
            }
            if (selfClosing && stack.lastOrNull()?.tag == tag) closeOne()
        }

        private fun push(open: Open) {
            if (stack.size >= MOST_DEPTH) return
            stack += open
        }

        private fun close(tag: String) {
            val index = stack.indexOfLast { it.tag == tag }
            if (index < 0) return
            while (stack.size > index) closeOne()
        }

        private inline fun closeWhile(matches: (Open) -> Boolean) {
            while (stack.isNotEmpty() && matches(stack.last())) closeOne()
        }

        /** The innermost element closed, with what it held given where it belongs. */
        private fun closeOne() {
            // A paragraph an element holds is given while the element
            // still stands, so a cell's last paragraph is the cell's; an
            // element inside a paragraph ends nothing.
            val open = stack.last()
            if (open.cell != null || open.table != null || open.tag in BLOCKS) flush()
            stack.removeAt(stack.size - 1)
            when {
                open.cell != null -> {
                    val table = tableOpen() ?: return
                    val held = open.cell.blocks.ifEmpty { listOf(Paragraph(listOf(TextRun("")))) }
                    table.current += TableCell(held, open.cell.columnSpan, open.cell.rowSpan, open.cell.shadingRgb)
                    table.cells++
                    if (open.cell.unruled) table.unruled++
                    table.rowOpen = true
                }
                open.table != null -> {
                    open.table.endRow()
                    val rows = open.table.rows.filter { it.cells.isNotEmpty() }
                    if (rows.isNotEmpty()) {
                        val widths = open.table.widths.takeIf { it.isNotEmpty() }
                        give(
                            Table(
                                rows = rows,
                                columnWidthsPt = widths,
                                ruled = open.table.unruled < open.table.cells,
                                direction = open.table.direction,
                            ),
                        )
                    }
                }
                open.tag == "thead" || open.tag == "tbody" || open.tag == "tfoot" -> tableOpen()?.let { it.endRow(); it.inHead = false }
            }
        }

        private fun tableOpen(): TableBuilder? = stack.lastOrNull { it.table != null }?.table

        // ---- what the markup says about the look ----

        private fun inlineLook(tag: String, attributes: Map<String, String>, styles: Map<String, String>, parent: Look): Look {
            var look = parent
            when (tag) {
                "b", "strong" -> look = look.copy(bold = true)
                "i", "em", "cite", "var", "dfn" -> look = look.copy(italic = true)
                "u", "ins" -> look = look.copy(underline = true)
                "s", "strike", "del" -> look = look.copy(strikethrough = true)
                "sup" -> look = look.copy(superscript = true, subscript = false)
                "sub" -> look = look.copy(subscript = true, superscript = false)
                "mark" -> look = look.copy(highlightRgb = 0xFFFF00)
                "pre" -> look = look.copy(pre = true)
                "a" -> attributes["href"]?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") }?.let { look = look.copy(link = it) }
            }
            if (tag !in BLOCKS && tag !in TABLE_PARTS) attributes["dir"]?.let { look = look.copy(direction = direction(it)) }
            attributes["lang"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 35 }?.let { look = look.copy(language = it) }
            styles["font-weight"]?.lowercase()?.let { weight ->
                val bold = weight == "bold" || weight == "bolder" || (weight.toIntOrNull() ?: 0) >= 600
                look = look.copy(bold = bold)
            }
            styles["font-style"]?.lowercase()?.let { look = look.copy(italic = it == "italic" || it == "oblique") }
            (styles["text-decoration-line"] ?: styles["text-decoration"])?.lowercase()?.let { decoration ->
                if (decoration == "none") look = look.copy(underline = false, strikethrough = false)
                else look = look.copy(underline = look.underline || "underline" in decoration, strikethrough = look.strikethrough || "line-through" in decoration)
            }
            styles["vertical-align"]?.lowercase()?.let {
                when (it) {
                    "super" -> look = look.copy(superscript = true, subscript = false)
                    "sub" -> look = look.copy(subscript = true, superscript = false)
                    "baseline" -> look = look.copy(superscript = false, subscript = false)
                }
            }
            styles["color"]?.let { Styles.color(it)?.let { rgb -> look = look.copy(colorRgb = rgb) } }
            if (tag !in BLOCKS && tag !in TABLE_PARTS) {
                (styles["background-color"] ?: styles["background"])?.lowercase()?.let { value ->
                    look = if (value == "transparent" || value == "none") look.copy(highlightRgb = null) else Styles.color(value)?.let { look.copy(highlightRgb = it) } ?: look
                }
            }
            styles["font-size"]?.let { Styles.points(it)?.let { pt -> look = look.copy(fontSizePt = pt) } }
            styles["font-family"]?.let { Styles.family(it)?.let { family -> look = look.copy(fontFamily = family) } }
            if (tag !in BLOCKS && tag !in TABLE_PARTS) styles["direction"]?.let { look = look.copy(direction = direction(it)) }
            styles["white-space"]?.lowercase()?.let { if (it.startsWith("pre")) look = look.copy(pre = true) }
            return look
        }

        private fun paragraphStyle(attributes: Map<String, String>, styles: Map<String, String>, kind: ParagraphKind): ParagraphStyle {
            val inItem = stack.any { it.tag == "li" }
            val open = if (inItem) stack.lastOrNull { it.list != null } else null
            return ParagraphStyle(
                kind = kind,
                direction = directionOf(attributes, styles),
                alignment = when (styles["text-align"]?.lowercase()) {
                    "center" -> Alignment.CENTER
                    "right", "end" -> Alignment.END
                    "left", "start" -> Alignment.START
                    "justify" -> Alignment.JUSTIFY
                    else -> null
                },
                listMarker = open?.list,
                listLevel = if (open != null) (stack.count { it.list != null } - 1).coerceIn(0, EditorState.MOST_LIST_LEVEL) else 0,
            )
        }

        private fun itemStyle(attributes: Map<String, String>, styles: Map<String, String>): ParagraphStyle {
            val lists = stack.count { it.list != null }
            val marker = stack.lastOrNull { it.list != null }?.list ?: ListMarker.BULLET
            return ParagraphStyle(
                kind = ParagraphKind.BODY,
                direction = directionOf(attributes, styles),
                listMarker = marker,
                listLevel = (lists - 1).coerceIn(0, EditorState.MOST_LIST_LEVEL),
            )
        }

        private fun directionOf(attributes: Map<String, String>, styles: Map<String, String>): TextDirection? =
            attributes["dir"]?.let { direction(it) } ?: styles["direction"]?.let { direction(it) }

        private fun direction(value: String): TextDirection? = when (value.trim().lowercase()) {
            "rtl" -> TextDirection.RTL
            "ltr" -> TextDirection.LTR
            else -> null
        }

        // ---- pictures and columns ----

        private fun image(attributes: Map<String, String>, look: Look) {
            if (look.hidden) return
            val source = attributes["src"]?.trim() ?: return
            if (!source.startsWith("data:", ignoreCase = true)) return
            val comma = source.indexOf(',')
            if (comma < 0) return
            val header = source.substring(5, comma)
            if (!header.endsWith(";base64", ignoreCase = true)) return
            val mime = header.substring(0, header.length - 7).lowercase().ifEmpty { "image/png" }
            if (!mime.startsWith("image/")) return
            val bytes = try {
                Base64.getMimeDecoder().decode(source.substring(comma + 1))
            } catch (e: IllegalArgumentException) {
                return
            }
            if (bytes.isEmpty()) return
            val styles = Styles.of(attributes["style"])
            val image = ImageBlock(
                bytes = bytes,
                mimeType = mime,
                widthPx = attributes["width"]?.trim()?.toIntOrNull()?.coerceIn(1, 100_000) ?: 1,
                heightPx = attributes["height"]?.trim()?.toIntOrNull()?.coerceIn(1, 100_000) ?: 1,
                widthPt = styles["width"]?.let { Styles.points(it) },
                heightPt = styles["height"]?.let { Styles.points(it) },
                description = attributes["alt"]?.trim()?.takeIf { it.isNotEmpty() },
            )
            addRun(TextRun("", image = image), look)
        }

        private fun column(attributes: Map<String, String>) {
            val table = tableOpen() ?: return
            val width = Styles.points(Styles.of(attributes["style"])["width"] ?: attributes["width"] ?: return) ?: return
            if (table.widths.size < MOST_SPAN) table.widths += width
        }
    }

    /** The `style` attribute as its properties, lowercased, with the values trimmed. */
    private object Styles {
        fun of(style: String?): Map<String, String> {
            if (style.isNullOrBlank()) return emptyMap()
            val out = HashMap<String, String>()
            for (declaration in style.split(';')) {
                val colon = declaration.indexOf(':')
                if (colon <= 0) continue
                val key = declaration.substring(0, colon).trim().lowercase()
                val value = declaration.substring(colon + 1).trim().removeSuffix("!important").trim()
                if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
            }
            return out
        }

        /** A length as points: `12pt`, `16px`, `1in`, `2.5cm`, `10mm`; anything else is nothing. */
        fun points(value: String): Float? {
            val v = value.trim().lowercase()
            val number = v.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: return null
            val unit = v.substring(v.takeWhile { it.isDigit() || it == '.' }.length).trim()
            val pt = when (unit) {
                "pt" -> number
                "px", "" -> number * 0.75f
                "in" -> number * 72f
                "cm" -> number * 72f / 2.54f
                "mm" -> number * 72f / 25.4f
                "pc" -> number * 12f
                else -> return null
            }
            return pt.takeIf { it.isFinite() && it > 0f && it <= 10_000f }
        }

        /** A colour as packed RGB: `#rgb`, `#rrggbb`, `rgb(r, g, b)`, or a name; anything else is nothing. */
        fun color(value: String?): Int? {
            val v = value?.trim()?.lowercase() ?: return null
            if (v.startsWith("#")) {
                val hex = v.substring(1)
                return when (hex.length) {
                    3 -> hex.map { "$it$it" }.joinToString("").toIntOrNull(16)
                    6 -> hex.toIntOrNull(16)
                    else -> null
                }
            }
            if (v.startsWith("rgb")) {
                val parts = v.substringAfter('(').substringBefore(')').split(',', '/', ' ').filter { it.isNotBlank() }
                if (parts.size < 3) return null
                val channels = parts.take(3).map { part -> part.trim().let { if (it.endsWith("%")) (it.dropLast(1).toFloatOrNull() ?: return null) * 2.55f else it.toFloatOrNull() ?: return null } }
                return channels.fold(0) { acc, c -> (acc shl 8) or c.toInt().coerceIn(0, 255) }
            }
            return NAMED_COLORS[v]
        }

        /** The first family named, unquoted, unless it is only a kind of face. */
        fun family(value: String): String? {
            val first = value.split(',').firstOrNull()?.trim()?.trim('"', '\'')?.trim() ?: return null
            if (first.isEmpty() || first in GENERIC_FAMILIES || first.length > 80) return null
            return first
        }

        private val NAMED_COLORS = mapOf(
            "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
            "yellow" to 0xFFFF00, "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xC0C0C0, "maroon" to 0x800000,
            "navy" to 0x000080, "olive" to 0x808000, "purple" to 0x800080, "teal" to 0x008080, "orange" to 0xFFA500,
            "lime" to 0x00FF00, "aqua" to 0x00FFFF, "cyan" to 0x00FFFF, "fuchsia" to 0xFF00FF, "magenta" to 0xFF00FF,
            "windowtext" to 0x000000,
        )

        private val GENERIC_FAMILIES = setOf("serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui", "inherit", "initial", "unset")
    }

    /** Character references, the ones a clipboard actually carries. */
    private object Entities {
        fun decode(text: String): String {
            if ('&' !in text) return text
            val sb = StringBuilder(text.length)
            var at = 0
            while (at < text.length) {
                val c = text[at]
                if (c != '&') { sb.append(c); at++; continue }
                val end = text.indexOf(';', at + 1)
                if (end < 0 || end - at > 12) { sb.append(c); at++; continue }
                val name = text.substring(at + 1, end)
                val decoded: String? = when {
                    name.startsWith("#x", ignoreCase = true) -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                    name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> NAMED[name]
                }
                if (decoded == null) { sb.append(c); at++; continue }
                sb.append(decoded)
                at = end + 1
            }
            return sb.toString()
        }

        private fun codePoint(value: Int): String? =
            if (value in 1..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null

        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
            "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
            "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
            "times" to "×", "middot" to "·", "bull" to "•", "deg" to "°", "plusmn" to "±", "para" to "¶",
            "sect" to "§", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "shy" to "\u00AD",
            "zwj" to "\u200D", "zwnj" to "\u200C", "lrm" to "\u200E", "rlm" to "\u200F", "ensp" to "\u2002",
            "emsp" to "\u2003", "thinsp" to "\u2009",
        )
    }

    /** The most columns or rows a pasted cell may claim, and the most column widths read. */
    private const val MOST_SPAN = 64

    /** What `&nbsp;` is, kept through the collapsing of white space and made a space after it. */
    private const val NO_BREAK_SPACE = '\u00A0'

    private val WHITESPACE = Regex("[ \\t\\n\\r\\u000C]+")

    /** Elements whose contents are not text a reader pastes. */
    private val HIDDEN = setOf("script", "style", "head", "title", "meta", "link", "noscript", "template", "svg", "math", "iframe", "object", "embed", "button", "select", "textarea", "input", "canvas", "audio", "video")

    /** Elements that stand alone, with no contents to close. */
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    private val TABLE_PARTS = setOf("table", "tr", "td", "th")

    /** Elements that hold a paragraph, so that closing one ends it. */
    private val BLOCKS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "section", "article", "header", "footer",
        "main", "aside", "nav", "figure", "figcaption", "address", "center", "dd", "dt", "dl", "form", "fieldset", "details", "summary",
        "ul", "ol", "tr", "thead", "tbody", "tfoot",
    )
}
