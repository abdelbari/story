package app.morpho.engine.layout

/**
 * Morpho's intermediate representation. Every reader (PDF, DOCX, text, OCR)
 * produces a [DocumentModel]; every writer consumes one. Text is always stored
 * in logical order (Unicode order), never visual order — BiDi reordering is a
 * rendering concern, not a storage concern.
 *
 * Every block carries a [Block.confidence] in 0..1, set by the reader that
 * produced it. This single field is what powers the Fidelity Report heatmap:
 * writers pass it through untouched, and the review UI colors blocks by it.
 */
data class DocumentModel(
    val blocks: List<Block>,
    val defaultLanguage: String? = null,
    val defaultDirection: TextDirection = TextDirection.LTR,
)

enum class TextDirection { LTR, RTL }

sealed interface Block {
    val confidence: Float
}

data class Paragraph(
    val runs: List<TextRun>,
    val style: ParagraphStyle = ParagraphStyle(),
    override val confidence: Float = 1f,
) : Block {
    val text: String get() = runs.joinToString(separator = "") { it.text }
}

data class ParagraphStyle(
    val kind: ParagraphKind = ParagraphKind.BODY,
    /** null = inherit the document's default direction. */
    val direction: TextDirection? = null,
    val listMarker: ListMarker? = null,
    val alignment: Alignment? = null,
)

enum class ParagraphKind { TITLE, HEADING_1, HEADING_2, HEADING_3, BODY }

enum class ListMarker { BULLET, NUMBERED }

enum class Alignment { START, CENTER, END, JUSTIFY }

data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** BCP-47 tag, e.g. "ar", "fr-FR". null = inherit. */
    val language: String? = null,
    /** null = inherit the paragraph's effective direction. */
    val direction: TextDirection? = null,
)

data class Table(
    val rows: List<TableRow>,
    override val confidence: Float = 1f,
) : Block

data class TableRow(val cells: List<TableCell>)

data class TableCell(val blocks: List<Block>)

class ImageBlock(
    val bytes: ByteArray,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    override val confidence: Float = 1f,
) : Block {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageBlock) return false
        return mimeType == other.mimeType &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            confidence == other.confidence &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + confidence.hashCode()
        return result
    }
}
