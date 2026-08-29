package app.morpho.engine.pdf

import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

/**
 * v0 PDF reader: text extraction routed through [PlainTextImporter], with
 * tagged-PDF detection. This is the seed of the plan's §5.3 pipeline — the
 * tagged fast path (reading structure from the tag tree) and the untagged
 * layout heuristics replace the plain-text routing during milestone M1.
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
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val text = stripper.getText(doc)
            val tagged = doc.documentCatalog.structureTreeRoot != null
            val confidence = if (tagged) 0.9f else 0.6f

            val base = PlainTextImporter.import(text)
            base.copy(
                blocks = base.blocks.map { block ->
                    when (block) {
                        is Paragraph -> block.copy(confidence = confidence)
                        is Table -> block.copy(confidence = confidence)
                        is ImageBlock -> block
                    }
                }
            )
        }
}
