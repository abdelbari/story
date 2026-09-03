package app.morpho.pdf

import android.content.Context
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import kotlin.math.sqrt

/**
 * On-device OCR for scanned PDFs (milestone M3): pages render to bitmaps
 * through the PDFBox port and Tesseract 5 (Tesseract4Android) reads them —
 * fully offline, keeping the Zero-Upload guarantee. Fast models for all
 * five app languages (ara, eng, fra, spa, deu) ship in the module's assets;
 * Tesseract needs real files, so the requested ones are copied once into
 * the app's files directory before first use.
 *
 * Recognized text flows through [PlainTextImporter], so paragraph splitting,
 * list detection and the UAX #9 direction pass all apply. Every block scores
 * [OCR_CONFIDENCE]: OCR output is a guess by construction, and the Fidelity
 * Report should say so. Rendering is 200 dpi — a memory/accuracy balance to
 * revisit with device testing.
 */
class AndroidOcrReader(private val context: Context) {

    /** Raised when [recognize] stops early because the caller asked it to. */
    class Cancelled : Exception()

    init {
        AndroidPdfReader.ensureInitialized(context)
    }

    /**
     * Recognizes every page of a PDF with no usable text layer. Returns the
     * document model of the recognized text; throws on unreadable input or
     * when Tesseract cannot initialize.
     *
     * [onPage] is called with the 1-based page about to be read and the page
     * count, from the calling thread, so a caller can show real progress on
     * a job that takes seconds per page. [shouldContinue] is checked at each
     * page boundary — the finest granularity Tesseract allows without
     * abandoning a page mid-recognition — and a false answer raises
     * [Cancelled] rather than returning a half-read document as if it were
     * whole.
     */
    fun recognize(
        bytes: ByteArray,
        languages: String = DEFAULT_LANGUAGES,
        password: String = "",
        pages: IntRange? = null,
        onPage: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true },
    ): DocumentModel {
        val dataParent = ensureTrainedData(languages)
        val pageTexts = mutableListOf<String>()
        var sheet: PageSetup? = null
        AndroidPdfReader.load(bytes, password).use { doc ->
            val renderer = PDFRenderer(doc)
            val tess = TessBaseAPI()
            try {
                check(tess.init(dataParent.absolutePath, languages)) {
                    "Tesseract failed to initialize for $languages"
                }
                // Reading a page takes seconds, so a reader who asked for
                // one chapter waits for that chapter and no longer.
                val wanted = (pages ?: 1..doc.numberOfPages)
                    .filter { it in 1..doc.numberOfPages }
                    .ifEmpty { listOf(1) }
                for ((ordinal, number) in wanted.withIndex()) {
                    val index = number - 1
                    if (!shouldContinue()) throw Cancelled()
                    onPage(ordinal + 1, wanted.size)
                    // The sheet these pages were rendered from. Without it
                    // a converted scan is laid out on whatever Word opens
                    // with, and a page numbered from 47 starts again at 1.
                    if (sheet == null) sheet = sheetOf(doc, index)
                    val bitmap = renderer.renderImageWithDPI(index, dpiFor(doc, index))
                    try {
                        tess.setImage(bitmap)
                        pageTexts += tess.getUTF8Text().orEmpty()
                    } finally {
                        bitmap.recycle()
                    }
                    // Again after the page: the check above already passed
                    // for the page in flight, and on a single-page document
                    // that is the only check there would ever be.
                    if (!shouldContinue()) throw Cancelled()
                }
            } finally {
                tess.recycle()
            }
        }
        // Read as the pages they are, not as one long text: what every
        // page repeats at its head or foot is the page's own, and a
        // paragraph that carried on over a turn is joined back up.
        val model = PlainTextImporter.importPages(pageTexts, sheet)
        fun scored(blocks: List<Block>) = blocks.map { block ->
            when (block) {
                is Paragraph -> block.copy(confidence = OCR_CONFIDENCE)
                is Table -> block.copy(confidence = OCR_CONFIDENCE)
                is ImageBlock -> block
            }
        }
        return model.copy(
            blocks = scored(model.blocks),
            // A recognised running head is a guess like everything else
            // recognition hands back, and the Fidelity Report should say so.
            header = scored(model.header),
            footer = scored(model.footer),
        )
    }

    /**
     * The sheet the page at [index] is set on, as the document states it.
     *
     * Margins are not stated anywhere a scan can be asked, so the sheet
     * carries none: an invented margin lays every line of the converted
     * document out to the wrong width, which is worse than none at all.
     */
    private fun sheetOf(doc: PDDocument, index: Int): PageSetup? {
        val box = runCatching { doc.getPage(index).cropBox }.getOrNull() ?: return null
        if (box.width <= 0f || box.height <= 0f) return null
        return PageSetup(
            widthPt = box.width,
            heightPt = box.height,
            marginTopPt = 0f,
            marginBottomPt = 0f,
            marginLeftPt = 0f,
            marginRightPt = 0f,
        )
    }

    /**
     * [RENDER_DPI], unless the page is big enough that rendering it there
     * would allocate an unreasonable bitmap — a poster-sized page at 200 dpi
     * runs to hundreds of megabytes and takes the process down. Oversized
     * pages are rendered at whatever resolution fits the budget instead:
     * degraded recognition beats an out-of-memory crash, and ordinary
     * page sizes are far below the cap and unaffected.
     */
    private fun dpiFor(doc: PDDocument, index: Int): Float {
        val box = runCatching { doc.getPage(index).cropBox }.getOrNull() ?: return RENDER_DPI
        val widthInches = box.width / POINTS_PER_INCH
        val heightInches = box.height / POINTS_PER_INCH
        if (widthInches <= 0f || heightInches <= 0f) return RENDER_DPI
        val pixels = widthInches * heightInches * RENDER_DPI * RENDER_DPI
        if (pixels <= MAX_PAGE_PIXELS) return RENDER_DPI
        return RENDER_DPI * sqrt(MAX_PAGE_PIXELS / pixels)
    }

    /** Copies the bundled language packs to real files, once per language. */
    private fun ensureTrainedData(languages: String): File {
        val parent = File(context.filesDir, "ocr")
        val tessdata = File(parent, TESSDATA_DIR)
        tessdata.mkdirs()
        for (language in languages.split('+')) {
            val target = File(tessdata, "$language$TRAINED_DATA_SUFFIX")
            if (target.length() > 0L) continue
            // Copy aside and rename: these files run to megabytes, and a copy
            // interrupted by the process dying would leave a short but
            // non-empty file that the length check above skips forever after,
            // breaking OCR permanently until the user clears app data.
            val partial = File(tessdata, "$language$TRAINED_DATA_SUFFIX.part")
            try {
                context.assets.open("$TESSDATA_DIR/$language$TRAINED_DATA_SUFFIX").use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                // Most likely the device is out of space; do not leave
                // megabytes of half-copy behind on a device that just said so.
                partial.delete()
                throw e
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                error("Could not install the $language OCR model")
            }
        }
        return parent
    }

    companion object {
        /** Arabic first — it is the app's reason to exist — with Latin text. */
        const val DEFAULT_LANGUAGES = "ara+eng"

        /** OCR output is a guess by construction; the heatmap should show it. */
        const val OCR_CONFIDENCE = 0.5f

        private const val RENDER_DPI = 200f

        /** ~8 MP, i.e. a 32 MB ARGB_8888 bitmap. A4 at 200 dpi is 3.9 MP. */
        private const val MAX_PAGE_PIXELS = 8_000_000f
        private const val POINTS_PER_INCH = 72f
        private const val TESSDATA_DIR = "tessdata"
        private const val TRAINED_DATA_SUFFIX = ".traineddata"
    }
}
