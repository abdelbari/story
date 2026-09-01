package app.morpho.engine.pdf

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.pdf.PdfImage
import app.morpho.engine.layout.pdf.PdfLayout
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

/**
 * PDF reader: position-aware extraction with layout reconstruction — the
 * first real slice of the plan's §5.3 untagged-PDF pipeline. Glyph positions
 * are captured line-by-line ([PositionTextStripper]) and clustered into
 * paragraphs with heading detection ([PdfLayout]). When position capture
 * yields nothing (empty page, exotic PDF), extraction falls back to plain
 * text routed through [PlainTextImporter], so it never regresses below the
 * naive path. The tagged fast path (reading the structure tree directly)
 * is still to come.
 *
 * Confidence encodes honesty about the current stage: blocks from a tagged
 * PDF get 0.9, untagged extraction gets 0.6, and the Fidelity Report surfaces
 * exactly that.
 */
class PdfReader {

    data class PdfInspection(
        val pageCount: Int,
        /** True when the PDF carries a structure tree (tagged PDF). */
        val isTagged: Boolean,
    )

    fun inspect(bytes: ByteArray): PdfInspection =
        PDDocument.load(bytes).use { doc ->
            PdfInspection(
                pageCount = doc.numberOfPages,
                isTagged = doc.documentCatalog.structureTreeRoot != null,
            )
        }

    fun extract(bytes: ByteArray): DocumentModel =
        PDDocument.load(bytes).use { doc ->
            val tagged = doc.documentCatalog.structureTreeRoot != null
            val confidence = if (tagged) 0.9f else 0.6f

            // Fast path: read structure straight from the tags when present.
            // (Figures inside tagged PDFs are still to come; images are
            // captured on the heuristic paths below.)
            val fromTags =
                if (tagged) runCatching { StructureTreeReader.read(doc) }.getOrNull() else null
            if (fromTags != null) return fromTags

            val images = runCatching { ImageCapture().capture(doc) }.getOrDefault(emptyList())
            val lines = runCatching { PositionTextStripper().capture(doc) }
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
}
