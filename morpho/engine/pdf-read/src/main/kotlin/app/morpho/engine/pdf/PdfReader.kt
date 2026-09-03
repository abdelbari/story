package app.morpho.engine.pdf

import app.morpho.engine.layout.Footnotes
import app.morpho.engine.layout.Links
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.pdf.PageFigures
import app.morpho.engine.layout.pdf.PdfDrawing
import app.morpho.engine.layout.pdf.PdfImage
import app.morpho.engine.layout.pdf.PdfLayout
import app.morpho.engine.layout.pdf.PdfOutlineEntry
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper

/**
 * PDF reader, plan §5.3: tagged PDFs take the fast path — structure and
 * reading order come straight from the tags ([StructureTreeReader]), with
 * Figure elements resolving to the images [ImageCapture] collected. For
 * untagged PDFs, glyph positions are captured line-by-line
 * ([PositionTextStripper]) and clustered into paragraphs with heading
 * detection ([PdfLayout]). When position capture yields nothing (empty
 * page, exotic PDF), extraction falls back to plain text routed through
 * [PlainTextImporter], so it never regresses below the naive path.
 *
 * Confidence encodes honesty about the path that actually ran: blocks read
 * from the tags get 0.9; every heuristic path gets 0.6, even when a tree
 * exists but yielded nothing. The Fidelity Report surfaces exactly that.
 */
class PdfReader {

    /**
     * Raised for a PDF that will not open without a password. Worth a type
     * of its own: "couldn't read that file" is useless advice when the fix
     * is knowing the password. [passwordWasTried] tells a caller which
     * question to ask — for one, what the password is; for the other,
     * that the one just given was not it.
     */
    class EncryptedDocument(val passwordWasTried: Boolean = false) : Exception()

    data class PdfInspection(
        val pageCount: Int,
        /** True when the PDF carries a structure tree (tagged PDF). */
        val isTagged: Boolean,
    )

    fun inspect(bytes: ByteArray, password: String = ""): PdfInspection =
        load(bytes, password).use { doc ->
            PdfInspection(
                pageCount = doc.numberOfPages,
                isTagged = doc.documentCatalog.structureTreeRoot != null,
            )
        }

    /**
     * The document [bytes] hold, or the part of it [pages] names.
     *
     * [pages] is a range of 1-based page numbers: a reader who wants one
     * chapter of a book, or one part of a document too big for the phone
     * to hold whole, converts what they need instead of all of it. Null
     * reads the document entire, which is what it means to convert a file.
     */
    fun extract(bytes: ByteArray, password: String = "", pages: IntRange? = null): DocumentModel =
        load(bytes, password).use { whole ->
            if (pages == null) return extractFrom(whole)
            // The pages asked for, as a document of their own: read that way
            // everything else here — the tags, the pictures, the outline —
            // sees the part as the whole it now is, and nothing has to be
            // taught to count from the middle.
            // The part has no outline of its own — a document made here has
            // none — so the outline of the whole is carried across, with
            // its entries counted from the first page asked for.
            val outline = DocumentOutline.read(whole).mapNotNull { entry ->
                when {
                    entry.page == 0 -> entry
                    entry.page in pages -> entry.copy(page = entry.page - pages.first + 1)
                    else -> null
                }
            }
            partOf(whole, pages).use { part -> return extractFrom(part, outline) }
        }

    /**
     * The pages of [document] that [pages] names, as a document of their
     * own. It shares its pages with [document], which must outlive it.
     */
    private fun partOf(document: PDDocument, pages: IntRange): PDDocument {
        val part = PDDocument()
        val last = document.numberOfPages
        for (number in pages) {
            if (number in 1..last) part.addPage(document.getPage(number - 1))
        }
        // The part keeps the whole document's tags. Every element of the
        // tree names the page it belongs to by the page itself, and the
        // part holds those same pages, so the tree still points where it
        // pointed; an element naming a page left behind simply finds no
        // words. Without this a part is a document with no tags at all,
        // and asking for a few pages of a tagged file quietly converted
        // them the way a scan is converted.
        val whole = document.documentCatalog.cosObject
        val partCatalog = part.documentCatalog.cosObject
        for (item in listOf(COSName.STRUCT_TREE_ROOT, COSName.MARK_INFO)) {
            whole.getDictionaryObject(item)?.let { partCatalog.setItem(item, it) }
        }
        // Asking for pages a document does not have is asking for nothing;
        // reading its first page beats handing back an empty document.
        if (part.numberOfPages == 0 && last > 0) part.addPage(document.getPage(0))
        return part
    }

    /** [outline] stands in for the document's own, for a part read out of one. */
    private fun extractFrom(doc: PDDocument, outline: List<PdfOutlineEntry>? = null): DocumentModel =
        run {
            // A document somebody filled in is read from its pages: the
            // answers are drawn onto them here, and a structure tree knows
            // nothing about what was drawn after it was written.
            val filled = drawFilledFields(doc)
            val tagged = doc.documentCatalog.structureTreeRoot != null && !filled

            val images = attempt { ImageCapture().capture(doc) } ?: emptyList()

            // Fast path: read structure straight from the tags when present;
            // Figure elements resolve to captured images via marked content.
            val fromTags =
                if (tagged) attempt { StructureTreeReader.read(doc, images) } else null
            if (fromTags != null) return spoken(doc, Footnotes.refine(Links.refine(fromTags)))
            // Everything below ran the position heuristics, so it scores as
            // untagged — even when a tree exists but yielded nothing.
            val confidence = 0.6f
            val stripper = PositionTextStripper()
            val lines = attempt { stripper.capture(doc) } ?: emptyList()
            // A document that names its own chapters says which lines are
            // headings; without one, only the type they were set in tells.
            val chapters = outline ?: DocumentOutline.read(doc)
            // What the page drew rather than placed: a chart, a diagram, a
            // signature. Photographed where it stood and handed on as a
            // picture, so everything downstream puts it in the reading
            // where the page put it.
            val figures = attempt {
                drawn(doc, PageFigures.of(stripper.drawings(), lines, stripper.pages()))
            } ?: emptyList()
            val model = if (lines.isNotEmpty()) {
                PdfLayout.reconstruct(
                    lines, confidence, images + figures, stripper.pages(), stripper.rules(), chapters,
                    // Page numbers count from one; the renderer counts from
                    // zero, and the two have been confused before.
                    crop = { page, left, top, right, bottom, masks, trim ->
                        PageImages.crop(doc, page - 1, left, top, right, bottom, masks, trim)
                    },
                )
            } else {
                plainTextFallback(doc, confidence, images)
            }
            spoken(doc, Footnotes.refine(Links.refine(model)))
        }

    /**
     * [model] with the language the file says it is written in.
     *
     * A PDF names it in its catalogue and the readers have always used it
     * to decide which way the lines run — and then thrown it away. Word,
     * told nothing, proofs a document in the language of whoever opens it,
     * so an Arabic paper arrives with every word of it underlined in red;
     * and a preview with no language on it is read aloud in the wrong one.
     */
    private fun spoken(document: PDDocument, model: DocumentModel): DocumentModel {
        val language = runCatching { document.documentCatalog.language }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return model
        return model.copy(defaultLanguage = language)
    }

    /**
     * Each figure as the picture of it the page draws, at the size it was
     * drawn — cropped from the page itself, since there is nothing else to
     * take it from. A figure the page will not draw is left out rather
     * than guessed at.
     */
    private fun drawn(document: PDDocument, figures: List<PdfDrawing>): List<PdfImage> =
        figures.take(MOST_FIGURES).mapNotNull { figure ->
            val cropped = PageImages.crop(
                document, figure.page - 1,
                figure.left, figure.top, figure.right, figure.bottom,
                trim = true,
            ) ?: return@mapNotNull null
            PdfImage(
                page = figure.page,
                topY = cropped.top,
                bytes = cropped.image.bytes,
                mimeType = cropped.image.mimeType,
                widthPx = cropped.image.widthPx,
                heightPx = cropped.image.heightPx,
            )
        }

    /**
     * How many figures of one document are drawn. Each costs a render of
     * the band it sits in, and a document with hundreds of them is a
     * drawing rather than a document — the reader would spend a phone's
     * afternoon on it and produce a file nobody wants.
     */
    private val MOST_FIGURES = 60

    private fun plainTextFallback(
        doc: PDDocument,
        confidence: Float,
        images: List<PdfImage>,
    ): DocumentModel {
        val stripper = PDFTextStripper()
        stripper.sortByPosition = true
        val base = PlainTextImporter.import(stripper.getText(doc))
        val imageBlocks = images
            .sortedWith(compareBy({ it.page }, { it.topY }))
            .map { ImageBlock(it.bytes, it.mimeType, it.widthPx, it.heightPx, confidence) }
        return base.copy(
            blocks = base.blocks.map { block ->
                when (block) {
                    is Paragraph -> block.copy(confidence = confidence)
                    is Table -> block.copy(confidence = confidence)
                    is ImageBlock -> block
                }
            } + imageBlocks
        )
    }

    /**
     * Draws what somebody typed into a form onto the page they typed it on.
     *
     * A filled-in PDF form keeps its answers in the fields rather than in
     * the page: the government form, the application, the registration all
     * look filled in and extract blank, because what a reader sees is the
     * field's appearance and what the page holds is the empty form. Asking
     * the document to flatten its form draws every appearance onto its
     * page, in place, where the reader reads it like any other text. A form
     * that cannot be flattened is left as it was rather than lost.
     *
     * True when something was drawn, which is to say the document is one
     * somebody filled in.
     */
    private fun drawFilledFields(document: PDDocument): Boolean = runCatching {
        val form = document.documentCatalog?.acroForm ?: return false
        val filled = form.fields.any { field ->
            runCatching { field.valueAsString }.getOrNull()?.isNotBlank() == true
        }
        if (!filled) return false
        form.flatten()
        true
    }.getOrDefault(false)

    /**
     * [PDDocument.load], with a document that needs a password reported as
     * needing one. A PDF locked with an owner password alone — printing
     * or copying restricted, opening not — opens on the empty password,
     * as it is meant to.
     */
    private fun load(bytes: ByteArray, password: String): PDDocument =
        try {
            PDDocument.load(bytes, password)
        } catch (e: InvalidPasswordException) {
            throw EncryptedDocument(passwordWasTried = password.isNotEmpty())
        }
}
