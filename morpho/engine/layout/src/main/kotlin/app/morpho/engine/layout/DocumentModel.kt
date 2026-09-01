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
    /** The page the source was laid out on, when the reader could measure it. */
    val pageSetup: PageSetup? = null,
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
    /**
     * How the paragraph sits on its page, in points, as a reader measured
     * it: how far its first line starts in from the margin, how far every
     * line does, how far the lines after the first hang in past it, the
     * space left before and after it, and the least distance between its
     * baselines. Null where the source did not say or the reader could not
     * tell, and the writer's defaults apply.
     */
    val firstLineIndentPt: Float? = null,
    val startIndentPt: Float? = null,
    val hangingIndentPt: Float? = null,
    val spaceBeforePt: Float? = null,
    val spaceAfterPt: Float? = null,
    val linePitchPt: Float? = null,
    /** Positions, in points from the start margin, of the tab stops the paragraph's tabs advance to. */
    val tabStopsPt: List<Float>? = null,
    /** A rule drawn across the page just above or just below the paragraph. */
    val ruleAbove: Boolean = false,
    val ruleBelow: Boolean = false,
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
    /** Typeface family as the source named it ("Simplified Arabic"); null = the document default. */
    val fontFamily: String? = null,
    /** Type size in points; null = the document default. */
    val fontSizePt: Float? = null,
    /** Raised or lowered off the baseline, the way a footnote mark or a chemical formula is set. */
    val superscript: Boolean = false,
    val subscript: Boolean = false,
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

/**
 * A page's size and margins in points — the sheet the writer lays the
 * document out on. Readers that can measure the source's page fill this in
 * so the converted file keeps the same page; otherwise writers use A4 with
 * one-inch margins.
 */
data class PageSetup(
    val widthPt: Float,
    val heightPt: Float,
    val marginTopPt: Float,
    val marginBottomPt: Float,
    val marginLeftPt: Float,
    val marginRightPt: Float,
)
