package app.morpho.pdf

import android.content.Context
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.Footnotes
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Links
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.pdf.PdfImage
import app.morpho.engine.layout.pdf.PdfLayout
import app.morpho.engine.layout.pdf.PdfOutlineEntry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * On-device PDF reader: the Android twin of the engine's PdfReader
 * (:engine:pdf-read), built on the tom-roush PDFBox port. The layout
 * heuristics themselves ([PdfLayout]) are shared, library-agnostic code from
 * :engine:layout; only the ~100-line position stripper is mirrored. Keep the
 * twins in sync until the shared-source split lands.
 *
 * Confidence follows the engine convention: 0.9 when the tags were actually
 * read, 0.6 for every heuristic path — even when a tree exists but yielded
 * nothing. A scanned PDF (no text layer) yields a model with no text; the
 * app offers [AndroidOcrReader] for those.
 */
class AndroidPdfReader(context: Context) {

    /**
     * Raised for a PDF that needs a password. Worth its own type: "couldn't
     * read that file" is useless advice when the fix is knowing the
     * password. [passwordWasTried] tells the screen which question to ask
     * — for one, what the password is; for the other, that the one just
     * typed was not it.
     */
    class EncryptedDocument(val passwordWasTried: Boolean = false) : Exception()

    init {
        ensureInitialized(context)
    }

    /**
     * The document [bytes] hold, or the part of it [pages] names.
     *
     * [pages] is a range of 1-based page numbers: a reader who wants one
     * chapter of a book, or one part of a document too big for the phone
     * to hold whole, converts what they need rather than all of it. Null
     * reads the document entire, which is what converting a file means.
     */
    fun extract(bytes: ByteArray, password: String = "", pages: IntRange? = null): DocumentModel =
        load(bytes, password).use { whole ->
            if (pages == null) return extractFrom(whole)
            // The pages asked for, as a document of their own: read that way
            // everything else — the tags, the pictures, the outline — sees
            // the part as the whole it now is, and nothing has to be taught
            // to count from the middle.
            // The part has no outline of its own — a document made here has
            // none — so the outline of the whole is carried across, with
            // its entries counted from the first page asked for.
            val outline = AndroidDocumentOutline.read(whole).mapNotNull { entry ->
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

            val images = attempt { AndroidImageCapture().capture(doc) } ?: emptyList()

            // Fast path: read structure straight from the tags when present;
            // Figure elements resolve to captured images via marked content.
            val fromTags =
                if (tagged) {
                    attempt { AndroidStructureTreeReader.read(doc, images) }
                } else {
                    null
                }
            // The same passes the engine's reader makes over what it read:
            // the addresses a page writes out become links, and the notes
            // under its rules go to the marks that call them.
            if (fromTags != null) return spoken(doc, Footnotes.refine(Links.refine(fromTags)))
            // Everything below ran the position heuristics, so it scores as
            // untagged — even when a tree exists but yielded nothing.
            val confidence = 0.6f

            val stripper = AndroidPositionTextStripper()
            val lines = attempt { stripper.capture(doc) } ?: emptyList()
            // A document that names its own chapters says which lines are
            // headings; without one, only the type they were set in tells.
            val chapters = outline ?: AndroidDocumentOutline.read(doc)
            val model = if (lines.isNotEmpty()) {
                PdfLayout.reconstruct(
                    lines, confidence, images, stripper.pages(), stripper.rules(), chapters,
                    // Page numbers count from one; the renderer counts from
                    // zero, and the two have been confused before.
                    crop = { page, left, top, right, bottom, masks, trim ->
                        AndroidPageImages.crop(doc, page - 1, left, top, right, bottom, masks, trim)
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

    companion object {
        /**
         * [PDDocument.load], with a document that needs a password reported
         * as needing one. A PDF locked with an owner password alone —
         * printing or copying restricted, opening not — opens on the
         * empty password, as it is meant to.
         */
        internal fun load(bytes: ByteArray, password: String = ""): PDDocument =
            try {
                PDDocument.load(bytes, password)
            } catch (e: InvalidPasswordException) {
                throw EncryptedDocument(passwordWasTried = password.isNotEmpty())
            }

        @Volatile
        private var initialized = false

        /** PDFBoxResourceLoader must run once before any parsing. */
        fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }
}
