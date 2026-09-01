package app.morpho.pdf

import android.content.Context
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.ImageBlock
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.Table
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File

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

    init {
        AndroidPdfReader.ensureInitialized(context)
    }

    /** True when every language pack in [languages] is bundled. */
    fun supports(languages: String): Boolean = languages.split('+').all { language ->
        runCatching {
            context.assets.open("$TESSDATA_DIR/$language$TRAINED_DATA_SUFFIX").use { }
        }.isSuccess
    }

    /**
     * Recognizes every page of a PDF with no usable text layer. Returns the
     * document model of the recognized text; throws on unreadable input or
     * when Tesseract cannot initialize.
     */
    fun recognize(bytes: ByteArray, languages: String = DEFAULT_LANGUAGES): DocumentModel {
        val dataParent = ensureTrainedData(languages)
        val pageTexts = mutableListOf<String>()
        PDDocument.load(bytes).use { doc ->
            val renderer = PDFRenderer(doc)
            val tess = TessBaseAPI()
            try {
                check(tess.init(dataParent.absolutePath, languages)) {
                    "Tesseract failed to initialize for $languages"
                }
                for (index in 0 until doc.numberOfPages) {
                    val bitmap = renderer.renderImageWithDPI(index, RENDER_DPI)
                    try {
                        tess.setImage(bitmap)
                        pageTexts += tess.getUTF8Text().orEmpty()
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                tess.recycle()
            }
        }
        val model = PlainTextImporter.import(pageTexts.joinToString(separator = "\n\n"))
        return model.copy(
            blocks = model.blocks.map { block ->
                when (block) {
                    is Paragraph -> block.copy(confidence = OCR_CONFIDENCE)
                    is Table -> block.copy(confidence = OCR_CONFIDENCE)
                    is ImageBlock -> block
                }
            }
        )
    }

    /** Copies the bundled language packs to real files, once per language. */
    private fun ensureTrainedData(languages: String): File {
        val parent = File(context.filesDir, "ocr")
        val tessdata = File(parent, TESSDATA_DIR)
        tessdata.mkdirs()
        for (language in languages.split('+')) {
            val target = File(tessdata, "$language$TRAINED_DATA_SUFFIX")
            if (target.length() > 0L) continue
            context.assets.open("$TESSDATA_DIR/$language$TRAINED_DATA_SUFFIX").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
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
        private const val TESSDATA_DIR = "tessdata"
        private const val TRAINED_DATA_SUFFIX = ".traineddata"
    }
}
