package app.morpho.engine.layout

import java.util.Base64

/**
 * A document as text, exactly, and back again.
 *
 * What a conversion in memory becomes when the process that holds it is
 * killed with the save dialog open — which Android does to any app in
 * the background — is nothing, and a reader who ran a three-minute
 * recognition to get it runs it again. So the document is written out
 * as it stands, every field of every block, to be read back as the same
 * value: not a file format anybody else opens, and not a rendering, but
 * the model itself in the plainest text there is.
 *
 * Exact means equal: what is read back is `==` to what was written,
 * pictures' bytes included, so a test can say so of any document at all
 * and does, over a thousand random ones. A number is written as JSON's
 * one kind of number and read back as the kind the field holds, which
 * loses nothing, since a float widened to a double and narrowed again
 * is the float it was.
 *
 * Read as if somebody else wrote it — a file in the app's own store is
 * still a file — so a shape this does not know, a value of the wrong
 * kind, a picture that is not one, are refused with [Json.Malformed]
 * and nothing else: never a cast that failed halfway through a block.
 * The text says which shape it is in ([FORMAT]) so that a later one can
 * be told from this one rather than guessed at.
 */
object DocumentJson {

    /** The shape of the text; a document in another shape is refused. */
    const val FORMAT = 1

    fun write(document: DocumentModel): String = Json.write(toMap(document))

    fun read(json: String): DocumentModel = fromMap(parsed(json))

    /** [document] as the plain values [Json] writes, for a caller embedding it in something larger. */
    fun toMap(document: DocumentModel): Map<String, Any?> = mapOf(
        "morpho" to FORMAT,
        "blocks" to document.blocks.map(::block),
        "defaultLanguage" to document.defaultLanguage,
        "defaultDirection" to document.defaultDirection.name,
        "pageSetup" to document.pageSetup?.let(::page),
        "header" to document.header.map(::block),
        "footer" to document.footer.map(::block),
        "evenHeader" to document.evenHeader.map(::block),
        "evenFooter" to document.evenFooter.map(::block),
        "properties" to mapOf(
            "title" to document.properties.title,
            "author" to document.properties.author,
            "subject" to document.properties.subject,
            "keywords" to document.properties.keywords,
        ),
        "comments" to document.comments.map {
            mapOf("id" to it.id, "text" to it.text, "author" to it.author, "initials" to it.initials, "dateIso" to it.dateIso)
        },
    )

    /** The document [map] holds, or [Json.Malformed] where it does not hold one. */
    fun fromMap(map: Map<*, *>): DocumentModel {
        if (int(map, "morpho") != FORMAT) throw Json.Malformed("a document in another shape")
        val properties = obj(map, "properties")
        return DocumentModel(
            blocks = list(map, "blocks").map(::readBlock),
            defaultLanguage = text(map, "defaultLanguage"),
            defaultDirection = enum<TextDirection>(map, "defaultDirection") ?: TextDirection.LTR,
            pageSetup = map["pageSetup"]?.let { readPage(it) },
            header = list(map, "header").map(::readBlock),
            footer = list(map, "footer").map(::readBlock),
            evenHeader = list(map, "evenHeader").map(::readBlock),
            evenFooter = list(map, "evenFooter").map(::readBlock),
            properties = DocumentProperties(
                title = text(properties, "title"),
                author = text(properties, "author"),
                subject = text(properties, "subject"),
                keywords = text(properties, "keywords"),
            ),
            comments = list(map, "comments").map { item ->
                val c = item as? Map<*, *> ?: throw Json.Malformed("a comment that is not one")
                Comment(
                    id = int(c, "id") ?: throw Json.Malformed("a comment with no id"),
                    text = text(c, "text") ?: throw Json.Malformed("a comment with no text"),
                    author = text(c, "author"),
                    initials = text(c, "initials"),
                    dateIso = text(c, "dateIso"),
                )
            },
        )
    }

    private fun parsed(json: String): Map<*, *> =
        Json.parse(json) as? Map<*, *> ?: throw Json.Malformed("not a document")

    // ---- writing ----

    private fun block(block: Block): Map<String, Any?> = when (block) {
        is Paragraph -> mapOf(
            "kind" to "paragraph",
            "runs" to block.runs.map(::run),
            "style" to style(block.style),
            "confidence" to block.confidence,
            "bookmarks" to block.bookmarks,
        )
        is Table -> mapOf(
            "kind" to "table",
            "rows" to block.rows.map { row ->
                mapOf(
                    "cells" to row.cells.map { cell ->
                        mapOf(
                            "blocks" to cell.blocks.map(::block),
                            "columnSpan" to cell.columnSpan,
                            "rowSpan" to cell.rowSpan,
                            "shadingRgb" to cell.shadingRgb,
                        )
                    },
                    "repeatsAsHeader" to row.repeatsAsHeader,
                )
            },
            "confidence" to block.confidence,
            "columnWidthsPt" to block.columnWidthsPt,
            "ruled" to block.ruled,
            "direction" to block.direction?.name,
        )
        is ImageBlock -> image(block) + ("kind" to "image")
    }

    private fun image(image: ImageBlock): Map<String, Any?> = mapOf(
        "bytes" to Base64.getEncoder().encodeToString(image.bytes),
        "mimeType" to image.mimeType,
        "widthPx" to image.widthPx,
        "heightPx" to image.heightPx,
        "confidence" to image.confidence,
        "widthPt" to image.widthPt,
        "heightPt" to image.heightPt,
        "description" to image.description,
    )

    private fun run(run: TextRun): Map<String, Any?> = mapOf(
        "text" to run.text,
        "bold" to run.bold,
        "italic" to run.italic,
        "underline" to run.underline,
        "strikethrough" to run.strikethrough,
        "language" to run.language,
        "direction" to run.direction?.name,
        "fontFamily" to run.fontFamily,
        "fontSizePt" to run.fontSizePt,
        "superscript" to run.superscript,
        "subscript" to run.subscript,
        "colorRgb" to run.colorRgb,
        "highlightRgb" to run.highlightRgb,
        "field" to run.field?.name,
        "image" to run.image?.let(::image),
        "link" to run.link,
        "note" to run.note?.map(::block),
        "commentIds" to run.commentIds,
    )

    private fun style(style: ParagraphStyle): Map<String, Any?> = mapOf(
        "kind" to style.kind.name,
        "direction" to style.direction?.name,
        "listMarker" to style.listMarker?.name,
        "listLevel" to style.listLevel,
        "listFormat" to style.listFormat,
        "alignment" to style.alignment?.name,
        "firstLineIndentPt" to style.firstLineIndentPt,
        "startIndentPt" to style.startIndentPt,
        "hangingIndentPt" to style.hangingIndentPt,
        "spaceBeforePt" to style.spaceBeforePt,
        "spaceAfterPt" to style.spaceAfterPt,
        "linePitchPt" to style.linePitchPt,
        "tabStopsPt" to style.tabStopsPt,
        "ruleAbove" to style.ruleAbove,
        "ruleBelow" to style.ruleBelow,
        "pageBreakBefore" to style.pageBreakBefore,
        "sectionSetup" to style.sectionSetup?.let(::page),
    )

    private fun page(page: PageSetup): Map<String, Any?> = mapOf(
        "widthPt" to page.widthPt,
        "heightPt" to page.heightPt,
        "marginTopPt" to page.marginTopPt,
        "marginBottomPt" to page.marginBottomPt,
        "marginLeftPt" to page.marginLeftPt,
        "marginRightPt" to page.marginRightPt,
        "headerDistancePt" to page.headerDistancePt,
        "footerDistancePt" to page.footerDistancePt,
        "firstPageNumber" to page.firstPageNumber,
        "differentFirstPage" to page.differentFirstPage,
    )

    // ---- reading, refusing what is not the shape ----

    private fun readBlock(value: Any?): Block {
        val map = value as? Map<*, *> ?: throw Json.Malformed("a block that is not one")
        return when (text(map, "kind")) {
            "paragraph" -> Paragraph(
                runs = list(map, "runs").map(::readRun),
                style = readStyle(obj(map, "style")),
                confidence = float(map, "confidence") ?: 1f,
                bookmarks = strings(map, "bookmarks"),
            )
            "table" -> Table(
                rows = list(map, "rows").map { item ->
                    val row = item as? Map<*, *> ?: throw Json.Malformed("a row that is not one")
                    TableRow(
                        cells = list(row, "cells").map { c ->
                            val cell = c as? Map<*, *> ?: throw Json.Malformed("a cell that is not one")
                            TableCell(
                                blocks = list(cell, "blocks").map(::readBlock),
                                columnSpan = (int(cell, "columnSpan") ?: 1).also { if (it < 1) throw Json.Malformed("a cell covering no column") },
                                rowSpan = (int(cell, "rowSpan") ?: 1).also { if (it < 1) throw Json.Malformed("a cell covering no row") },
                                shadingRgb = rgb(cell, "shadingRgb"),
                            )
                        },
                        repeatsAsHeader = bool(row, "repeatsAsHeader") ?: false,
                    )
                },
                confidence = float(map, "confidence") ?: 1f,
                columnWidthsPt = map["columnWidthsPt"]?.let { floats(map, "columnWidthsPt") },
                ruled = bool(map, "ruled") ?: true,
                direction = enum<TextDirection>(map, "direction"),
            )
            "image" -> readImage(map)
            else -> throw Json.Malformed("a block of a kind this does not know")
        }
    }

    private fun readImage(map: Map<*, *>): ImageBlock {
        val encoded = text(map, "bytes") ?: throw Json.Malformed("a picture with no bytes")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw Json.Malformed("a picture whose bytes are not bytes")
        }
        return ImageBlock(
            bytes = bytes,
            mimeType = text(map, "mimeType") ?: throw Json.Malformed("a picture with no type"),
            widthPx = int(map, "widthPx") ?: throw Json.Malformed("a picture with no width"),
            heightPx = int(map, "heightPx") ?: throw Json.Malformed("a picture with no height"),
            confidence = float(map, "confidence") ?: 1f,
            widthPt = float(map, "widthPt"),
            heightPt = float(map, "heightPt"),
            description = text(map, "description"),
        )
    }

    private fun readRun(value: Any?): TextRun {
        val map = value as? Map<*, *> ?: throw Json.Malformed("a run that is not one")
        return TextRun(
            text = text(map, "text") ?: throw Json.Malformed("a run with no text"),
            bold = bool(map, "bold") ?: false,
            italic = bool(map, "italic") ?: false,
            underline = bool(map, "underline") ?: false,
            strikethrough = bool(map, "strikethrough") ?: false,
            language = text(map, "language"),
            direction = enum<TextDirection>(map, "direction"),
            fontFamily = text(map, "fontFamily"),
            fontSizePt = float(map, "fontSizePt"),
            superscript = bool(map, "superscript") ?: false,
            subscript = bool(map, "subscript") ?: false,
            colorRgb = rgb(map, "colorRgb"),
            highlightRgb = rgb(map, "highlightRgb"),
            field = enum<RunField>(map, "field"),
            image = map["image"]?.let { readImage(it as? Map<*, *> ?: throw Json.Malformed("a picture that is not one")) },
            link = text(map, "link"),
            note = map["note"]?.let { list(map, "note").map(::readBlock) },
            commentIds = list(map, "commentIds").map { (it as? Double)?.let(::wholeOf) ?: throw Json.Malformed("a comment id that is not one") },
        )
    }

    private fun readStyle(map: Map<*, *>): ParagraphStyle = ParagraphStyle(
        kind = enum<ParagraphKind>(map, "kind") ?: ParagraphKind.BODY,
        direction = enum<TextDirection>(map, "direction"),
        listMarker = enum<ListMarker>(map, "listMarker"),
        listLevel = int(map, "listLevel") ?: 0,
        listFormat = text(map, "listFormat"),
        alignment = enum<Alignment>(map, "alignment"),
        firstLineIndentPt = float(map, "firstLineIndentPt"),
        startIndentPt = float(map, "startIndentPt"),
        hangingIndentPt = float(map, "hangingIndentPt"),
        spaceBeforePt = float(map, "spaceBeforePt"),
        spaceAfterPt = float(map, "spaceAfterPt"),
        linePitchPt = float(map, "linePitchPt"),
        tabStopsPt = map["tabStopsPt"]?.let { floats(map, "tabStopsPt") },
        ruleAbove = bool(map, "ruleAbove") ?: false,
        ruleBelow = bool(map, "ruleBelow") ?: false,
        pageBreakBefore = bool(map, "pageBreakBefore") ?: false,
        sectionSetup = map["sectionSetup"]?.let { readPage(it) },
    )

    private fun readPage(value: Any?): PageSetup {
        val map = value as? Map<*, *> ?: throw Json.Malformed("a page that is not one")
        fun required(key: String) = float(map, key) ?: throw Json.Malformed("a page with no $key")
        return PageSetup(
            widthPt = required("widthPt"),
            heightPt = required("heightPt"),
            marginTopPt = required("marginTopPt"),
            marginBottomPt = required("marginBottomPt"),
            marginLeftPt = required("marginLeftPt"),
            marginRightPt = required("marginRightPt"),
            headerDistancePt = float(map, "headerDistancePt"),
            footerDistancePt = float(map, "footerDistancePt"),
            firstPageNumber = int(map, "firstPageNumber") ?: 1,
            differentFirstPage = bool(map, "differentFirstPage") ?: false,
        )
    }

    // ---- the kinds a field may hold; anything else is refused ----

    private fun text(map: Map<*, *>, key: String): String? = when (val v = map[key]) {
        null -> null
        is String -> v
        else -> throw Json.Malformed("$key is not text")
    }

    private fun bool(map: Map<*, *>, key: String): Boolean? = when (val v = map[key]) {
        null -> null
        is Boolean -> v
        else -> throw Json.Malformed("$key is not yes or no")
    }

    private fun number(map: Map<*, *>, key: String): Double? = when (val v = map[key]) {
        null -> null
        is Double -> v
        else -> throw Json.Malformed("$key is not a number")
    }

    private fun float(map: Map<*, *>, key: String): Float? = number(map, key)?.let {
        if (it.isNaN() || it.isInfinite()) throw Json.Malformed("$key is not a number a document holds")
        it.toFloat()
    }

    private fun wholeOf(value: Double): Int {
        if (value != Math.rint(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw Json.Malformed("not a whole number")
        return value.toInt()
    }

    private fun int(map: Map<*, *>, key: String): Int? = number(map, key)?.let(::wholeOf)

    private fun rgb(map: Map<*, *>, key: String): Int? = int(map, key)?.also {
        if (it < 0 || it > 0xFFFFFF) throw Json.Malformed("$key is not a colour")
    }

    private fun list(map: Map<*, *>, key: String): List<*> = when (val v = map[key]) {
        null -> emptyList<Any?>()
        is List<*> -> v
        else -> throw Json.Malformed("$key is not a list")
    }

    private fun obj(map: Map<*, *>, key: String): Map<*, *> = when (val v = map[key]) {
        null -> emptyMap<String, Any?>()
        is Map<*, *> -> v
        else -> throw Json.Malformed("$key is not an object")
    }

    private fun strings(map: Map<*, *>, key: String): List<String> =
        list(map, key).map { it as? String ?: throw Json.Malformed("$key holds something that is not text") }

    private fun floats(map: Map<*, *>, key: String): List<Float> = list(map, key).map {
        val d = it as? Double ?: throw Json.Malformed("$key holds something that is not a number")
        if (d.isNaN() || d.isInfinite()) throw Json.Malformed("$key holds a number a document does not")
        d.toFloat()
    }

    private inline fun <reified T : Enum<T>> enum(map: Map<*, *>, key: String): T? {
        val name = text(map, key) ?: return null
        return enumValues<T>().firstOrNull { it.name == name } ?: throw Json.Malformed("$key is not a $name this knows")
    }
}
