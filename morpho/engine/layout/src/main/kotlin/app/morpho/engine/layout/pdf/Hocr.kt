package app.morpho.engine.layout.pdf

/**
 * What recognition says about a page, read out of the hOCR it writes.
 *
 * Asked for plain text, Tesseract hands back a string and keeps
 * everything else to itself. Asked for hOCR it writes out what it
 * actually worked out: the box every word sits in, how sure it is of
 * each, which words share a line, and — the part no box can give — its
 * own estimate of how big that line's type is, from the x-height and the
 * ascenders it measured. That estimate is the difference between finding
 * a document's headings and missing them, because a word's box is only as
 * tall as the tallest letter in it and "man" boxes at nearly half of
 * "Tagged" in the very same type.
 *
 * hOCR is machine-written and highly regular, so this scans it rather
 * than parsing it as a document: a page whose markup is a little off
 * loses a word here, where a parser would throw and lose the page. There
 * is no document to resolve either, so nothing is fetched and nothing can
 * be pointed at the device's files.
 */
object Hocr {

    /** Points in an inch, the unit everything downstream is measured in. */
    private const val POINTS_PER_INCH = 72f

    /**
     * The words of one page of [hocr], measured in points from the top
     * left, given the [dpi] the page was rendered at.
     *
     * [page] numbers the page in the document, counting from one, since
     * recognition is asked a page at a time and its own numbering starts
     * again for each.
     */
    fun wordsOf(hocr: String, page: Int, dpi: Float): List<RecognizedWord> {
        val scale = if (dpi > 0f) POINTS_PER_INCH / dpi else 1f
        val out = mutableListOf<RecognizedWord>()
        var lineSize: Float? = null
        var opensLine = false
        var at = 0
        while (true) {
            val opens = hocr.indexOf("<span", at)
            if (opens < 0) break
            val shuts = hocr.indexOf('>', opens)
            if (shuts < 0) break
            val tag = hocr.substring(opens, shuts + 1)
            val kind = attribute(tag, "class")
            val title = attribute(tag, "title").orEmpty()
            when {
                kind != null && kind in LINES -> {
                    // Recognition's own measure of the line's type, in the
                    // pixels of the image it read.
                    lineSize = numbersIn(title, "x_size").firstOrNull()?.times(scale)
                    opensLine = true
                    at = shuts + 1
                }
                kind == "ocrx_word" -> {
                    // A word ends where it says it ends, or where the next
                    // one begins, whichever comes first. An unclosed word
                    // otherwise swallows every tag up to the next close,
                    // and the word after it is lost inside this one's box:
                    // one word ragged is a fault worth recovering from,
                    // two words merged into a third place is not.
                    val closes = hocr.indexOf("</span>", shuts)
                    val opensNext = hocr.indexOf("<span", shuts)
                    val ends = when {
                        closes < 0 -> opensNext
                        opensNext in 0 until closes -> opensNext
                        else -> closes
                    }
                    val inside =
                        if (ends < 0) hocr.substring(shuts + 1) else hocr.substring(shuts + 1, ends)
                    val box = numbersIn(title, "bbox")
                    val text = wordsIn(inside)
                    if (text.isNotBlank() && box.size >= 4) {
                        out += RecognizedWord(
                            text = text,
                            page = page,
                            left = box[0] * scale,
                            top = box[1] * scale,
                            right = box[2] * scale,
                            bottom = box[3] * scale,
                            startsLine = opensLine,
                            sizePt = lineSize,
                            bold = inside.contains("<strong") || inside.contains("<b>"),
                            italic = inside.contains("<em") || inside.contains("<i>"),
                        )
                        opensLine = false
                    }
                    // Where the next word's own tag ended this one, it
                    // has still to be read, so the scan resumes on it
                    // rather than past it.
                    at = when {
                        ends < 0 -> hocr.length
                        ends == opensNext -> ends
                        else -> ends + 1
                    }
                }
                else -> at = shuts + 1
            }
        }
        return out
    }

    /**
     * The classes recognition gives a line of a page. A heading, a caption
     * and a line floating beside the text are all lines, and a reader that
     * only knew `ocr_line` would run a page's caption into the paragraph
     * above it.
     */
    private val LINES = setOf("ocr_line", "ocr_header", "ocr_caption", "ocr_textfloat")

    /**
     * The rules recognition found on one page of [hocr] — the lines a
     * table was ruled with — measured in points from the top left.
     *
     * Recognition looks for these and reports them, and nothing read them
     * until now, so a scanned table arrived at the ruled reader with no
     * rules and came back as loose paragraphs. They are `ocr_separator`
     * blocks, and they come back lopsided: the ones down the page have a
     * whole box, and the ones across it have a top, a bottom and no width
     * whatever — `bbox 0 y 0 y'`, a position and nothing else.
     *
     * That is not a defect to work around but the signature to read them
     * by: a separator with no width is one across the page, and the only
     * reach it could have had is the reach of the ones down, so it is
     * given that. A separator that arrives with a width of its own is
     * left exactly as it is, so this degrades into doing nothing if
     * recognition ever starts reporting them whole.
     *
     * Measured over four pages read with the app's own models: on a page
     * carrying prose and a ruled table this recovers the table and its
     * four rows; on two pages of columns and no rules recognition reports
     * no separators at all, so nothing changes for them.
     */
    fun rulesOf(hocr: String, page: Int, dpi: Float): List<PdfDrawing> {
        val scale = if (dpi > 0f) POINTS_PER_INCH / dpi else 1f
        val boxes = mutableListOf<PdfDrawing>()
        var at = 0
        while (true) {
            val found = hocr.indexOf(SEPARATOR, at)
            if (found < 0) break
            at = found + SEPARATOR.length
            val opens = hocr.lastIndexOf('<', found)
            val shuts = hocr.indexOf('>', found)
            if (opens < 0 || shuts < 0 || shuts < opens) continue
            val title = attribute(hocr.substring(opens, shuts), "title") ?: continue
            val box = numbersIn(title, "bbox")
            if (box.size < 4) continue
            boxes += PdfDrawing(
                page = page,
                left = box[0] * scale,
                top = box[1] * scale,
                right = box[2] * scale,
                bottom = box[3] * scale,
            )
        }
        return reaching(boxes).map(::thinned)
    }

    /**
     * A rule no thicker than the reader admits.
     *
     * What recognition reports is the ink it saw on a page rendered at
     * two hundred to the inch, and anti-aliasing makes that wider than
     * the rule underneath it — the same rules measure 1.1 points on one
     * of these pages and 3.2 on another, drawn at 0.9 and 1.0. The
     * reader's limit is about telling a rule from a filled box in a PDF,
     * where the thickness is the number the producer asked for and means
     * something. Here it does not, so a rule that is long in one
     * direction is thinned in the other rather than thrown away for
     * being a shade too fat.
     */
    private fun thinned(box: PdfDrawing): PdfDrawing = when {
        box.widthPt >= LEAST_RULE_PT && box.heightPt > PdfRuledTables.THIN_PT ->
            box.copy(bottom = box.top + PdfRuledTables.THIN_PT)
        box.heightPt >= LEAST_RULE_PT && box.widthPt > PdfRuledTables.THIN_PT ->
            box.copy(right = box.left + PdfRuledTables.THIN_PT)
        else -> box
    }

    /** Long enough in one direction to be a side of a cell rather than a mark. */
    private const val LEAST_RULE_PT = 20f

    /** The rules across the page given the reach of the rules down it. */
    private fun reaching(boxes: List<PdfDrawing>): List<PdfDrawing> {
        val down = boxes.filter { it.widthPt > 0f }
        if (down.isEmpty() || down.size == boxes.size) return boxes
        val from = down.minOf { it.left }
        val to = down.maxOf { it.right }
        if (to <= from) return boxes
        return boxes.map { if (it.widthPt > 0f) it else it.copy(left = from, right = to) }
    }

    /** What recognition calls a rule it found. */
    private const val SEPARATOR = "ocr_separator"

    /** The value of [name] in [tag], under either kind of quote. */
    private fun attribute(tag: String, name: String): String? {
        val at = tag.indexOf("$name=")
        if (at < 0) return null
        val quote = tag.getOrNull(at + name.length + 1) ?: return null
        if (quote != '"' && quote != '\'') return null
        val from = at + name.length + 2
        val to = tag.indexOf(quote, from)
        return if (to < 0) null else tag.substring(from, to)
    }

    /**
     * The numbers [key] introduces in an hOCR title, which is a list of
     * `name value value…` clauses separated by semicolons.
     */
    private fun numbersIn(title: String, key: String): List<Float> {
        for (clause in title.split(';')) {
            val words = clause.trim().split(Regex("\\s+"))
            if (words.firstOrNull() != key) continue
            return words.drop(1).mapNotNull { it.toFloatOrNull() }
        }
        return emptyList()
    }

    /** The words inside a word's markup, with its tags and entities undone. */
    private fun wordsIn(inside: String): String {
        val out = StringBuilder(inside.length)
        var at = 0
        while (at < inside.length) {
            val c = inside[at]
            when {
                c == '<' -> {
                    val shuts = inside.indexOf('>', at)
                    at = if (shuts < 0) inside.length else shuts + 1
                }
                c == '&' -> {
                    val ends = inside.indexOf(';', at)
                    val named = if (ends < 0 || ends - at > 12) null else inside.substring(at + 1, ends)
                    val letter = named?.let(::characterFor)
                    if (letter == null) {
                        out.append(c)
                        at++
                    } else {
                        out.append(letter)
                        at = ends + 1
                    }
                }
                else -> {
                    out.append(c)
                    at++
                }
            }
        }
        return out.toString().trim()
    }

    /** The character [named] stands for, or null where it names none. */
    private fun characterFor(named: String): String? = when {
        named == "amp" -> "&"
        named == "lt" -> "<"
        named == "gt" -> ">"
        named == "quot" -> "\""
        named == "apos" -> "'"
        named == "nbsp" -> " "
        named.startsWith("#x") || named.startsWith("#X") ->
            named.drop(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
        named.startsWith("#") ->
            named.drop(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
        else -> null
    }
}
