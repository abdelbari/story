package app.morpho.pdf

import android.content.Context
import app.morpho.engine.layout.Block
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.PageSetup
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import app.morpho.engine.layout.pdf.Hocr
import app.morpho.engine.layout.pdf.PdfLayout
import app.morpho.engine.layout.pdf.PdfPageSheet
import app.morpho.engine.layout.pdf.RecognizedText
import app.morpho.engine.layout.pdf.RecognizedWord
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
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
 * What recognition finds goes through the same reading an untagged PDF
 * gets: headings from type size, paragraphs from spacing, columns from
 * gutters, tables from the alignment of words. None of that machinery was
 * ever specific to PDFs — it takes positioned lines — and recognition can
 * say where every word sits, if asked for hOCR instead of a string. Asked
 * for a string, it throws all of that away at the call, and the importer
 * on the other end, built for text files, can only find structure in the
 * Markdown conventions recognised text never has: a scanned paper came
 * back as one long paragraph, no headings, no columns, no tables.
 *
 * The base score is [OCR_CONFIDENCE], because OCR output is a guess by
 * construction and the Fidelity Report should say so; the reading moves
 * it from there, so a table it worked out from the alignment of words
 * reads as less sure than a paragraph. Rendering is 200 dpi — a
 * memory/accuracy balance to revisit with device testing.
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
        val words = mutableListOf<RecognizedWord>()
        val sheets = mutableListOf<PdfPageSheet>()
        val pageTexts = mutableListOf<String>()
        var sheet: PageSetup? = null
        AndroidPdfReader.load(bytes, password).use { doc ->
            val renderer = PDFRenderer(doc)
            val tess = TessBaseAPI()
            try {
                check(tess.init(dataParent.absolutePath, languages)) {
                    "Tesseract failed to initialize for $languages"
                }
                // Work the page out before reading it. Left unasked,
                // recognition reads the whole image as ONE BLOCK of text
                // and does no layout analysis at all — that is the
                // library's default, stated in its own documentation for
                // this call and set in Tesseract's own source. A two-column
                // page then reads straight across the gutter, half a
                // sentence from each column at a time, and nothing is
                // classified, so a heading, a caption and a line of body
                // text all come back alike. Every scan this app has ever
                // read was read that way.
                tess.setPageSegMode(PAGE_SEGMENTATION)
                // Ask for the font of every word as well as its box. The
                // fast models this app ships answer nothing — the newer
                // recogniser reports no font at all — but it is one call,
                // it cannot fail, and a model that does answer is the
                // difference between finding a paper's bold headings and
                // missing them.
                tess.setVariable(FONT_INFO, "1")
                // Reading a page takes seconds, so a reader who asked for
                // one chapter waits for that chapter and no longer.
                val wanted = (pages ?: 1..doc.numberOfPages)
                    .filter { it in 1..doc.numberOfPages }
                    .ifEmpty { listOf(1) }
                for ((ordinal, number) in wanted.withIndex()) {
                    val index = number - 1
                    if (!shouldContinue()) throw Cancelled()
                    onPage(ordinal + 1, wanted.size)
                    // The sheet each page was rendered from. Without it a
                    // converted scan is laid out on whatever Word opens
                    // with, its running heads cannot be told from its text
                    // — nothing knows where the foot of the page is — and
                    // a page numbered from 47 starts again at 1. Pages are
                    // numbered as the reader asked for them, so a chapter
                    // converted on its own is a document of its own.
                    if (sheet == null) sheet = sheetOf(doc, index)
                    boxOf(doc, index)?.let { sheets += PdfPageSheet(ordinal + 1, it.width, it.height) }
                    val dpi = dpiFor(doc, index)
                    val bitmap = renderer.renderImageWithDPI(index, dpi)
                    try {
                        tess.setImage(bitmap)
                        // What the page was rendered at, which recognition
                        // otherwise has to guess from the image: its own
                        // default for this is nothing, and a wrong guess
                        // moves every threshold it works out a text line
                        // from. This reader knows the number exactly — it
                        // just chose it — so it is the same value the
                        // bitmap was made with and not a second opinion.
                        tess.setVariable(SOURCE_DPI, dpi.toInt().toString())
                        words += Hocr.wordsOf(tess.getHOCRText(index).orEmpty(), ordinal + 1, dpi)
                        // Recognition's plain text, kept only while its
                        // hOCR has yielded nothing at all: a build whose
                        // hOCR this cannot read must still convert the
                        // scan the way it always did, and one whose hOCR
                        // it can read need not carry every page twice.
                        if (words.isEmpty()) pageTexts += tess.getUTF8Text().orEmpty()
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
        fun scored(blocks: List<Block>) = blocks.map { block ->
            when (block) {
                is Paragraph -> block.copy(confidence = OCR_CONFIDENCE)
                is Table -> block.copy(confidence = OCR_CONFIDENCE)
                is ImageBlock -> block
            }
        }
        if (words.isEmpty()) {
            // Read as the pages they are, not as one long text: what every
            // page repeats at its head or foot is the page's own, and a
            // paragraph that carried on over a turn is joined back up.
            val model = PlainTextImporter.importPages(pageTexts, sheet)
            return model.copy(
                blocks = scored(model.blocks),
                header = scored(model.header),
                footer = scored(model.footer),
            )
        }
        val model = PdfLayout.reconstruct(
            lines = RecognizedText.linesOf(words),
            confidence = OCR_CONFIDENCE,
            sheets = sheets,
        )
        // The body is left as the reading scored it: it knows more about
        // each block than this does, and moved every score up or down
        // from [OCR_CONFIDENCE] for a reason. A running head is not scored
        // there, because a PDF that spells one out is certain of it — and
        // a scan is a guess like everything else recognition hands back.
        return model.copy(
            header = scored(model.header),
            footer = scored(model.footer),
            evenHeader = scored(model.evenHeader),
            evenFooter = scored(model.evenFooter),
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
        val box = boxOf(doc, index) ?: return null
        return PageSetup(
            widthPt = box.width,
            heightPt = box.height,
            marginTopPt = 0f,
            marginBottomPt = 0f,
            marginLeftPt = 0f,
            marginRightPt = 0f,
        )
    }

    /** The page at [index] as the file measures it, where it measures it at all. */
    private fun boxOf(doc: PDDocument, index: Int): PDRectangle? {
        val box = runCatching { doc.getPage(index).cropBox }.getOrNull() ?: return null
        return if (box.width <= 0f || box.height <= 0f) null else box
    }

    /**
     * [RENDER_DPI], unless the page is big enough that rendering it there
     * would allocate an unreasonable bitmap — a poster-sized page at 200 dpi
     * runs to hundreds of megabytes and takes the process down. Oversized
     * pages are rendered at whatever resolution fits the budget instead:
     * degraded recognition beats an out-of-memory crash, and ordinary
     * page sizes are far below the cap and unaffected.
     *
     * What the cap actually costs, worked out over the paper sizes a page
     * this large really comes in: A3 and everything under it renders at
     * the full [RENDER_DPI]; A2 falls to 143, A1 to 101, and A0 — a poster
     * — to 72, which is the lowest resolution recognition itself will work
     * at and the one it substitutes when it is told nothing. So the budget
     * and recognition's own floor very nearly meet, and only a page no
     * printer makes falls below.
     */
    private fun dpiFor(doc: PDDocument, index: Int): Float {
        val box = boxOf(doc, index) ?: return RENDER_DPI
        val widthInches = box.width / POINTS_PER_INCH
        val heightInches = box.height / POINTS_PER_INCH
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

        /**
         * What each app language recognises with, by the code a locale
         * gives for it.
         *
         * A second model rides along so a mixed document still reads:
         * English with the RTL locale, Arabic with every other one. The
         * first named is the one Tesseract leans on, which is why the
         * pairs are ordered rather than sorted.
         *
         * This is a table rather than a `when` because the packs it names
         * are files that have to be in the module's assets, and a branch
         * naming one that is not there does not fail a build or a review —
         * it fails on the phone, at `assets.open`, taking not that
         * language but recognition itself down for everyone with that
         * locale. As a table the two can be held to each other, which is
         * what [app.morpho.port.OcrLanguageTest] does.
         */
        val LANGUAGES_BY_LOCALE: Map<String, String> = mapOf(
            "ar" to DEFAULT_LANGUAGES,
            "fr" to "fra+eng",
            "es" to "spa+eng",
            "de" to "deu+eng",
        )

        /**
         * The set to recognise with where the phone is set to [language] —
         * an ISO-639 code as `Locale.getLanguage` gives it.
         *
         * A language with no pack of its own reads with English first and
         * Arabic behind it: the app's own two, in the order that suits a
         * reader whose phone is in neither. A per-scan language picker is
         * a later refinement.
         */
        fun languagesFor(language: String): String =
            LANGUAGES_BY_LOCALE[language] ?: OTHERWISE

        /** What a phone set to none of the four reads with. */
        const val OTHERWISE = "eng+ara"

        /** OCR output is a guess by construction; the heatmap should show it. */
        const val OCR_CONFIDENCE = 0.5f

        /** Tesseract's name for "tell me the font of each word too". */
        private const val FONT_INFO = "hocr_font_info"

        /** And its name for "the image you are reading was made at this". */
        private const val SOURCE_DPI = "user_defined_dpi"

        /**
         * How much of a page recognition is asked to work out.
         *
         * Everything: the columns, the blocks and the lines, which is what
         * the reading this feeds is built to take. Asked for nothing,
         * recognition reads a page as a single block of text — its own
         * default, and the one thing it must not do here, since a
         * two-column paper then reads across the gutter and no line of it
         * is a heading, a caption or anything else.
         *
         * Not the mode that detects orientation and script as well: that
         * one reads `osd.traineddata`, which is not among the packs this
         * app ships, and asking for a pack that is not there is how
         * recognition stops working for a whole locale.
         */
        const val PAGE_SEGMENTATION = TessBaseAPI.PageSegMode.PSM_AUTO

        private const val RENDER_DPI = 200f

        /** ~8 MP, i.e. a 32 MB ARGB_8888 bitmap. A4 at 200 dpi is 3.9 MP. */
        private const val MAX_PAGE_PIXELS = 8_000_000f
        private const val POINTS_PER_INCH = 72f
        private const val TESSDATA_DIR = "tessdata"
        private const val TRAINED_DATA_SUFFIX = ".traineddata"
    }
}
