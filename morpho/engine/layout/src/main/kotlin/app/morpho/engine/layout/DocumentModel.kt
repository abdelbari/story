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
    /**
     * What the left-hand pages repeat, where they repeat something else.
     *
     * A printed book puts the title of the book on one side of the opening
     * and the title of the chapter on the other, and the page number at
     * the outer edge of each — which is the left edge on one and the right
     * on the other. Read as one head and stamped on every page, half of a
     * converted book says the wrong thing. Empty means both sides repeat
     * the same thing, which is what a journal article does.
     */
    val evenHeader: List<Block> = emptyList(),
    val evenFooter: List<Block> = emptyList(),
    /**
     * What the file says it is: its title, who wrote it, what it is about.
     * Every format keeps these somewhere of its own and every one of them
     * shows them — Word in its Properties pane, a reader in the window's
     * title bar, a search across a folder of files. A converter that drops
     * them hands back a document that has forgotten its own name.
     */
    val properties: DocumentProperties = DocumentProperties(),
    /**
     * What people said about the document while reading it, kept apart
     * from what it says. A run names the ones it is the subject of.
     */
    val comments: List<Comment> = emptyList(),
)

/**
 * What a document says about itself, apart from what it says.
 *
 * A PDF keeps these in its information dictionary, a .docx in its core
 * properties; both show them to whoever opens the file, and a search over
 * a folder looks at them before it looks at a word of the text. Null is
 * what a file that says nothing says: written back it leaves the field
 * out, rather than putting an empty one there.
 */
data class DocumentProperties(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
) {
    /** True when the file said nothing about itself at all. */
    val isEmpty: Boolean
        get() = title == null && author == null && subject == null && keywords == null

    companion object {
        /**
         * The four fields as a file gave them, with blanks and whitespace
         * taken for silence. A producer that writes an empty title has not
         * named the document, and carrying "" across writes an empty title
         * into a file that would otherwise have none.
         */
        fun of(title: String?, author: String?, subject: String?, keywords: String?) =
            DocumentProperties(
                title = tidy(title),
                author = tidy(author),
                subject = tidy(subject),
                keywords = tidy(keywords),
            )

        private fun tidy(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
    }
}

enum class TextDirection { LTR, RTL }

sealed interface Block {
    val confidence: Float
}

data class Paragraph(
    val runs: List<TextRun>,
    val style: ParagraphStyle = ParagraphStyle(),
    override val confidence: Float = 1f,
    /**
     * The names a document gave this place, so that a link elsewhere can
     * point at it: the bookmarks behind a table of contents, a "see
     * section 4", an index. Without them a converted thesis keeps its
     * contents page and every line of it leads nowhere.
     */
    val bookmarks: List<String> = emptyList(),
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
    /**
     * How the list counts at this item's level, in the words a Word file
     * uses for it: "decimal", "lowerLetter", "arabicAlpha". Null lets the
     * writer count the way an outline usually does. An Arabic document
     * that numbers its clauses أ ب ت says so here, and comes back saying
     * it rather than counting 1 2 3 like an English one.
     */
    val listFormat: String? = null,
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
    /**
     * The page this paragraph and the ones after it are set on, when it
     * begins a part of the document shaped differently from the part
     * before: the one landscape page a report turns sideways for a wide
     * table, the appendix on a larger sheet. Null for a paragraph that
     * carries on the shape already in force, which is nearly all of them.
     *
     * A document with one shape throughout has none of these and is
     * written exactly as it was before they existed.
     */
    val sectionSetup: PageSetup? = null,
)

enum class ParagraphKind { TITLE, HEADING_1, HEADING_2, HEADING_3, BODY }

enum class ListMarker { BULLET, NUMBERED }

enum class Alignment { START, CENTER, END, JUSTIFY }

data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** Struck through: what a document says was said and is no longer. */
    val strikethrough: Boolean = false,
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
    /**
     * The notes somebody left about this run, by [Comment.id]. Empty for
     * text nobody has said anything about, which is nearly all of it.
     */
    val commentIds: List<Int> = emptyList(),
)

/** What a writer fills in for a run in place of fixed text. */
enum class RunField { PAGE_NUMBER }

/**
 * A note somebody left on the document rather than in it.
 *
 * A supervisor reads a thesis and writes in the margin; a colleague
 * queries a figure; a reader marks a passage and says why. Word keeps
 * these apart from the text as comments, anchored to the words they are
 * about, and a PDF keeps the same three things on an annotation: who
 * wrote it, when, and what they said.
 *
 * Every converter drops them, and a file converted without them is the
 * document as it stood before anybody read it — which is exactly what
 * the person converting a reviewed document did not ask for.
 */
data class Comment(
    /** Names the note; the runs it is about carry the same number. */
    val id: Int,
    /** What it says. A newline in it starts another paragraph of the note. */
    val text: String,
    /** Who wrote it, as the file named them; null when it is unsigned. */
    val author: String? = null,
    /**
     * The letters Word shows in the margin against the note. Null lets a
     * writer take them from the author's name, which is what Word does
     * with a name it is given without them.
     */
    val initials: String? = null,
    /**
     * When it was written, as an ISO-8601 instant — "2026-09-03T12:00:00Z".
     * Null when the file did not record it.
     */
    val dateIso: String? = null,
)

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
    /**
     * Which way the table's columns run. An Arabic table is laid out from
     * the right: its first column is the rightmost one, and a converter
     * that lays the same cells out from the left hands back a table read
     * backwards. Null takes the direction of the document around it.
     */
    val direction: TextDirection? = null,
) : Block

data class TableRow(
    val cells: List<TableCell>,
    /**
     * Whether the row is the head of its table, repeated at the top of
     * every page the table runs onto. A long table without its head is a
     * grid of numbers with nothing to say what they are.
     */
    val repeatsAsHeader: Boolean = false,
)

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
    /**
     * Packed 0xRRGGBB of the colour the cell is filled with, or null for a
     * cell nobody coloured. A report's tables are read by their colour as
     * much as by their rules: the shaded row along the top is the head.
     */
    val shadingRgb: Int? = null,
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
    /**
     * What the picture shows, in words.
     *
     * A tagged PDF's figure carries the description its author wrote, and
     * a running head photographed because its words are drawn as outlines
     * has words that are nowhere else in the document. Without either, a
     * screen reader says "image" and stops, and Word's own accessibility
     * check calls the document out. Null where nothing is known — which is
     * most pictures, and honest.
     */
    val description: String? = null,
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
            description == other.description &&
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
        result = 31 * result + (description?.hashCode() ?: 0)
        return result
    }

    /**
     * This picture with something about it changed.
     *
     * A data class would have written this, but a picture is bytes and
     * bytes compare by identity unless somebody says otherwise, so this
     * class writes its own [equals] — and loses [copy] along with it.
     * Without one, every place that rebuilds a picture lists its fields
     * by hand, and a field added later is silently dropped by whichever
     * of them nobody remembered: which is the way a table's cells once
     * lost the colour they were filled with and how many columns they
     * covered.
     */
    fun copy(
        bytes: ByteArray = this.bytes,
        mimeType: String = this.mimeType,
        widthPx: Int = this.widthPx,
        heightPx: Int = this.heightPx,
        confidence: Float = this.confidence,
        widthPt: Float? = this.widthPt,
        heightPt: Float? = this.heightPt,
        description: String? = this.description,
    ): ImageBlock = ImageBlock(
        bytes = bytes,
        mimeType = mimeType,
        widthPx = widthPx,
        heightPx = heightPx,
        confidence = confidence,
        widthPt = widthPt,
        heightPt = heightPt,
        description = description,
    )
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
    /**
     * The first page keeps neither the running head nor the foot: a report
     * whose title page carries no header, which is most of them.
     *
     * Told nothing, a converter stamps the running head onto the title
     * page — the one page of the document a reader looks at hardest, and
     * the one page the original left clear.
     */
    val differentFirstPage: Boolean = false,
) {
    companion object {
        /**
         * The sheet a document is set on when nothing measured one.
         *
         * A PDF and a Word file both say what page they are on, so this is
         * for the documents that do not: a text file, a Markdown file, a
         * page of prose typed anywhere. There were three answers to it and
         * they were in three files — the Word writer set A4 with inch
         * margins, the drawn page set A4 with two-thirds-of-an-inch ones,
         * and the preview set no page at all, which left the print sheet
         * to use whatever the framework thought. The same notes.md became
         * three different documents depending which button was pressed.
         *
         * A4 because the app is written for readers who use it, and an
         * inch of margin because that is what a word processor opens a
         * blank document with — and because the Word file was already
         * doing it, so this is the answer that was already right. The
         * numbers are the ones that land exactly on Word's own twentieths
         * of a point: 11906 by 16838, margins of 1440, head and foot at
         * 708.
         */
        val DEFAULT = PageSetup(
            widthPt = 595.3f,
            heightPt = 841.9f,
            marginTopPt = 72f,
            marginBottomPt = 72f,
            marginLeftPt = 72f,
            marginRightPt = 72f,
            headerDistancePt = 35.4f,
            footerDistancePt = 35.4f,
        )
    }
}
