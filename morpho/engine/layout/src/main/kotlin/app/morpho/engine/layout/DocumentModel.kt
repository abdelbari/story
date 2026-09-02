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
    /**
     * What every page repeats at its head and at its foot — a running
     * title, a rule, a page number — kept apart from the text, which is
     * what the blocks are. Empty when the source had none or the reader
     * could not tell.
     */
    val header: List<Block> = emptyList(),
    val footer: List<Block> = emptyList(),
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
    /**
     * How deep a list item sits: 0 for the outermost, 1 for an item of a
     * list inside it, and so on. A document's lists are nested more often
     * than not — a report's numbered clauses with lettered sub-clauses,
     * a thesis's aims under its objectives — and a converter that keeps
     * only one level hands all of them back as a single flat list.
     */
    val listLevel: Int = 0,
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
    /**
     * Whether the source began a page with this paragraph. A converted
     * document then breaks where its original broke, so its pages hold what
     * the same pages held, instead of drifting a line at a time.
     */
    val pageBreakBefore: Boolean = false,
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
    /**
     * The colour the run is set in, packed 0xRRGGBB. Null is the colour a
     * document uses unless it says otherwise — black — so a reader that
     * measures none, and a page that paints in plain black, agree.
     */
    val colorRgb: Int? = null,
    /**
     * Packed 0xRRGGBB of the colour drawn behind the text, or null where
     * nothing is. A reader's marking of a PDF, or Word's own highlighter:
     * it is the reader's reading of the document, and a converted file
     * without it is the document before it was read.
     */
    val highlightRgb: Int? = null,
    /**
     * A value the writer fills in rather than the text: the number of the
     * page this run lands on. [text] then holds what the source showed
     * where it was read, for a writer with no fields of its own.
     */
    val field: RunField? = null,
    /** A picture set in the line like a character; [text] is empty. */
    val image: ImageBlock? = null,
    /**
     * Where the run points, as a URI — "mailto:…" for an address, "https://…"
     * for a page. Null for text that goes nowhere, which is most text.
     */
    val link: String? = null,
    /**
     * The note this run's mark refers to, which a writer sets at the foot
     * of the page the mark lands on. The mark itself stays the run's text,
     * so a writer with no notes of its own still shows what the page did.
     */
    val note: List<Block>? = null,
)

/** What a writer fills in for a run in place of fixed text. */
enum class RunField { PAGE_NUMBER }

data class Table(
    val rows: List<TableRow>,
    override val confidence: Float = 1f,
    /**
     * The width of each column in points, as a reader measured it off the
     * page. Null when nothing measured them, and a writer shares the text
     * width out equally — which is what a table of two columns, one of
     * dates and one of paragraphs, never looks like.
     */
    val columnWidthsPt: List<Float>? = null,
    /**
     * Whether the page draws rules around the cells. A table found by the
     * alignment of its columns rather than by lines on the page has none,
     * and drawing them would add ink the source never had.
     */
    val ruled: Boolean = true,
) : Block

data class TableRow(val cells: List<TableCell>)

data class TableCell(
    val blocks: List<Block>,
    /**
     * How many of the table's columns and rows the cell covers. A merged
     * cell is one cell that spans several: a heading over two columns, a
     * label beside three rows. The rows hold only the cells that begin —
     * the ones a merge covers are not there — so a writer that needs a
     * rectangle of cells fills the covered places itself.
     */
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
)

class ImageBlock(
    val bytes: ByteArray,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    override val confidence: Float = 1f,
    /**
     * The size the picture is shown at, in points, when the reader knows
     * it — a crop of a page rendered at high resolution is placed at the
     * size it had on the page, not at its pixel count. Null means the
     * writer's own choice.
     */
    val widthPt: Float? = null,
    val heightPt: Float? = null,
) : Block {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageBlock) return false
        return mimeType == other.mimeType &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            confidence == other.confidence &&
            widthPt == other.widthPt &&
            heightPt == other.heightPt &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (widthPt?.hashCode() ?: 0)
        result = 31 * result + (heightPt?.hashCode() ?: 0)
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
    /** From the top edge of the page to the top of the running header, and from the bottom edge to the foot of the footer. */
    val headerDistancePt: Float? = null,
    val footerDistancePt: Float? = null,
    /** The number the first page carries — a journal article starts where the issue left off. */
    val firstPageNumber: Int = 1,
)
