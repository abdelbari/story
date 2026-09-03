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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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

    fun extract(bytes: ByteArray, password: String = ""): DocumentModel =
        load(bytes, password).use { doc ->
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
            if (fromTags != null) return Footnotes.refine(Links.refine(fromTags))
            // Everything below ran the position heuristics, so it scores as
            // untagged — even when a tree exists but yielded nothing.
            val confidence = 0.6f

            val stripper = AndroidPositionTextStripper()
            val lines = attempt { stripper.capture(doc) } ?: emptyList()
            // A document that names its own chapters says which lines are
            // headings; without one, only the type they were set in tells.
            val outline = AndroidDocumentOutline.read(doc)
            val model = if (lines.isNotEmpty()) {
                PdfLayout.reconstruct(
                    lines, confidence, images, stripper.pages(), stripper.rules(), outline,
                )
            } else {
                plainTextFallback(doc, confidence, images)
            }
            Footnotes.refine(Links.refine(model))
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
