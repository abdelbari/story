package app.morpho.engine.pdf

import app.morpho.engine.layout.Footnotes
import app.morpho.engine.layout.Links
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.pdf.PdfImage
import app.morpho.engine.layout.pdf.PdfLayout
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

    fun extract(bytes: ByteArray, password: String = ""): DocumentModel =
        load(bytes, password).use { doc ->
            // A document somebody filled in is read from its pages: the
            // answers are drawn onto them here, and a structure tree knows
            // nothing about what was drawn after it was written.
            val filled = drawFilledFields(doc)
            val tagged = doc.documentCatalog.structureTreeRoot != null && !filled

            val images = runCatching { ImageCapture().capture(doc) }.getOrDefault(emptyList())

            // Fast path: read structure straight from the tags when present;
            // Figure elements resolve to captured images via marked content.
            val fromTags =
                if (tagged) runCatching { StructureTreeReader.read(doc, images) }.getOrNull() else null
            if (fromTags != null) return Footnotes.refine(Links.refine(fromTags))
            // Everything below ran the position heuristics, so it scores as
            // untagged — even when a tree exists but yielded nothing.
            val confidence = 0.6f
            val stripper = PositionTextStripper()
            val lines = runCatching { stripper.capture(doc) }.getOrDefault(emptyList())
            // A document that names its own chapters says which lines are
            // headings; without one, only the type they were set in tells.
            val outline = DocumentOutline.read(doc)
            val model = if (lines.isNotEmpty()) {
                PdfLayout.reconstruct(
                    lines, confidence, images, stripper.pages(), stripper.rules(), outline,
                )
            } else {
                plainTextFallback(doc, confidence, images)
            }
            Footnotes.refine(Links.refine(model))
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
