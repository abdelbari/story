package app.morpho.pdf

import android.content.Context
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
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
     * read that file" is useless advice when the fix is knowing the password.
     */
    class EncryptedDocument : Exception()

    init {
        ensureInitialized(context)
    }

    fun extract(bytes: ByteArray): DocumentModel =
        load(bytes).use { doc ->
            val tagged = doc.documentCatalog.structureTreeRoot != null

            val images = runCatching { AndroidImageCapture().capture(doc) }.getOrDefault(emptyList())

            // Fast path: read structure straight from the tags when present;
            // Figure elements resolve to captured images via marked content.
            val fromTags =
                if (tagged) {
                    runCatching { AndroidStructureTreeReader.read(doc, images) }.getOrNull()
                } else {
                    null
                }
            if (fromTags != null) return fromTags
            // Everything below ran the position heuristics, so it scores as
            // untagged — even when a tree exists but yielded nothing.
            val confidence = 0.6f

            val lines = runCatching { AndroidPositionTextStripper().capture(doc) }
                .getOrDefault(emptyList())
            if (lines.isNotEmpty()) {
                PdfLayout.reconstruct(lines, confidence, images)
            } else {
                plainTextFallback(doc, confidence, images)
            }
        }

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
        /** [PDDocument.load], with an encrypted file reported as such. */
        internal fun load(bytes: ByteArray): PDDocument =
            try {
                PDDocument.load(bytes)
            } catch (e: InvalidPasswordException) {
                throw EncryptedDocument()
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
