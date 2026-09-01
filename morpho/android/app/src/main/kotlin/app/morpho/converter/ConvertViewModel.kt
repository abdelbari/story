package app.morpho.converter

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.FidelityReport
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.MarkdownWriter
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.ooxml.DocxReader
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.ParagraphKind
import app.morpho.engine.ooxml.DocxWriter
import app.morpho.pdf.AndroidOcrReader
import app.morpho.pdf.AndroidPdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ConvertUiState {
    data object Idle : ConvertUiState
    data class Picked(
        val fileName: String,
        val mime: String?,
        /** True when the input is a Word document, so conversion targets Markdown. */
        val isWordDocument: Boolean,
        /** True when the input is a PDF (conversion targets Word; no PDF export). */
        val isPdf: Boolean,
    ) : ConvertUiState

    /** Page counts are 0 for work that is not page-by-page (everything but OCR). */
    data class Converting(val page: Int = 0, val pageCount: Int = 0) : ConvertUiState

    /**
     * A finished conversion, carrying its own bytes rather than leaving the
     * save handler to read a field that a later conversion may have
     * replaced: whatever the dialog was opened for is what gets written.
     * Array identity is the right equality here, and what the data class
     * generates.
     */
    data class ReadyToSave(
        val suggestedName: String,
        val mimeType: String,
        val bytes: ByteArray,
    ) : ConvertUiState

    /** The same payload, with the system save dialog now on screen. */
    data class AwaitingSave(
        val suggestedName: String,
        val mimeType: String,
        val bytes: ByteArray,
    ) : ConvertUiState

    /** Print-ready HTML for the system print sheet's "Save as PDF". */
    data class ReadyToPrint(val html: String, val jobName: String) : ConvertUiState
    data class Saved(
        val fileName: String,
        /** True when the Fidelity Report flagged blocks worth reviewing. */
        val needsReview: Boolean = false,
    ) : ConvertUiState
    data class Failed(val reason: FailReason) : ConvertUiState
}

enum class FailReason {
    UNSUPPORTED_TYPE, SCANNED_PDF, ENCRYPTED_PDF, OCR_EMPTY, TOO_LARGE, READ_ERROR, WRITE_ERROR
}

/** What Review Mode shows: the report, and which blocks the reader corrected. */
data class ReviewState(
    val report: FidelityReport.Report,
    val edited: Set<Int> = emptySet(),
)

/** Thrown inside a conversion to surface a specific, honest failure reason. */
private class UnconvertibleContent(val reason: FailReason) : Exception()

/**
 * Drives the conversion slices, all fully on-device: text/Markdown → Word,
 * Word → Markdown, PDF → Word (scanned PDFs go through Tesseract OCR), and
 * text/Markdown/Word → PDF — as a saved file ([PdfFileExporter]) or through
 * the system print sheet. Every conversion records a [FidelityReport] so
 * the Saved state can say honestly when blocks deserve review. This
 * process has no network permission at all.
 */
class ConvertViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ConvertUiState>(ConvertUiState.Idle)
    val state: StateFlow<ConvertUiState> = _state.asStateFlow()

    /**
     * The picked document and its metadata, published as one reference so a
     * second pick racing in (share sheet vs. picker result) can never pair
     * one file's bytes with another file's type routing.
     */
    private data class PickedFile(val uri: Uri, val meta: ConvertUiState.Picked)

    private var pickedFile: PickedFile? = null

    /** Whichever conversion ran last — "Try again" repeats it, not convert(). */
    private var lastOperation: (() -> Unit)? = null
    private var outputName: String = ""

    /** Fidelity Report of the last conversion's model, for the Saved notice. */
    private var lastReport: FidelityReport.Report? = null

    /**
     * The last conversion's model, how to write it again, and what to call
     * the result — kept so Review Mode can correct a block and re-write the
     * output without re-reading (or re-OCR-ing) the source.
     */
    private var lastModel: DocumentModel? = null
    private var lastWriter: ((DocumentModel) -> ByteArray)? = null
    private var lastMimeType: String = DocxWriter.MIME_TYPE
    private val editedBlocks = mutableSetOf<Int>()

    /**
     * Set by [cancelOcr]; the recognizer reads it between pages. An
     * AtomicBoolean rather than job cancellation because the work sits in a
     * native call that Kotlin cannot interrupt mid-page.
     */
    private val ocrCancelled = AtomicBoolean(false)

    /** Asks a running OCR job to stop after the page it is on. */
    fun cancelOcr() {
        ocrCancelled.set(true)
    }

    /**
     * The recognizer runs a blocking native call with no suspension point,
     * so cancelling [viewModelScope] cannot reach it. Without this, leaving
     * the app mid-OCR leaves Tesseract chewing through a document nobody is
     * waiting for.
     */
    override fun onCleared() {
        super.onCleared()
        ocrCancelled.set(true)
    }

    private val _review = MutableStateFlow<ReviewState?>(null)

    /** Non-null while Review Mode is open, holding what it shows. */
    val review: StateFlow<ReviewState?> = _review.asStateFlow()

    /** Opens Review Mode on the last conversion's report, if there is one. */
    fun showReview() {
        val report = lastReport ?: return
        _review.value = ReviewState(report, editedBlocks.toSet())
    }

    fun hideReview() {
        _review.value = null
    }

    /**
     * Corrects what a block was taken to be — a heading the reader recorded
     * as body text, say. Confidence is deliberately left alone: the reader's
     * doubt was about the characters, and relabelling a block does not make
     * them any more certain, so the report keeps telling the truth.
     */
    fun reclassify(index: Int, kind: ParagraphKind) {
        val model = lastModel ?: return
        val block = model.blocks.getOrNull(index) as? Paragraph ?: return
        if (block.style.kind == kind) return
        val blocks = model.blocks.toMutableList()
        blocks[index] = block.copy(style = block.style.copy(kind = kind))
        val corrected = model.copy(blocks = blocks)
        val report = FidelityReport.of(corrected)
        lastModel = corrected
        lastReport = report
        editedBlocks += index
        _review.value = ReviewState(report, editedBlocks.toSet())
    }

    /** Writes the corrected model out again, straight to the save dialog. */
    fun saveCorrected() {
        val model = lastModel ?: return
        val write = lastWriter ?: return
        _review.value = null
        _state.value = ConvertUiState.Converting()
        viewModelScope.launch(Dispatchers.IO) {
            // runCatching catches Throwable, so an OutOfMemoryError here is
            // already a failed conversion rather than a dead process.
            val bytes = runCatching { write(model) }.getOrNull()
            if (bytes == null) {
                _state.value = ConvertUiState.Failed(FailReason.WRITE_ERROR)
                return@launch
            }
            _state.value = ConvertUiState.ReadyToSave(outputName, lastMimeType, bytes)
        }
    }

    fun onPicked(uri: Uri) {
        // Synchronously, before the IO hop: a second document arriving from
        // the share sheet must invalidate the previous conversion's model
        // immediately, or a correction tap racing the query could write a
        // report for a file that is no longer picked.
        lastReport = null
        lastModel = null
        lastWriter = null
        editedBlocks.clear()
        _review.value = null

        viewModelScope.launch(Dispatchers.IO) {
            // DocumentsProviders can be slow (cloud providers, network
            // shares); never query them on the main thread.
            val resolver = getApplication<Application>().contentResolver
            var name = "document"
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
                    }
            }
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            val state = ConvertUiState.Picked(
                fileName = name,
                mime = mime,
                isWordDocument = mime == DocxWriter.MIME_TYPE ||
                    name.lowercase().endsWith(".docx"),
                isPdf = mime == "application/pdf" || name.lowercase().endsWith(".pdf"),
            )
            pickedFile = PickedFile(uri, state)
            _state.value = state
        }
    }

    fun convert() {
        // Two taps inside one frame would otherwise start two conversions
        // at once, for no benefit and to the user's confusion.
        if (_state.value is ConvertUiState.Converting) return

        val (uri, source) = pickedFile ?: return
        lastOperation = ::convert
        _state.value = ConvertUiState.Converting()
        viewModelScope.launch(Dispatchers.IO) {
            when {
                source.isPdf -> convertPicked(
                    uri, source, "docx", DocxWriter.MIME_TYPE,
                    read = { bytes ->
                        val model = AndroidPdfReader(getApplication()).extract(bytes)
                        val hasText = model.blocks.filterIsInstance<Paragraph>()
                            .any { it.text.isNotBlank() }
                        if (!hasText) throw UnconvertibleContent(FailReason.SCANNED_PDF)
                        model
                    },
                    write = { model -> DocxWriter.toByteArray(model) },
                )
                source.isWordDocument -> convertPicked(
                    uri, source, "md", MARKDOWN_MIME,
                    read = { bytes -> DocxReader.read(bytes) },
                    write = { model -> MarkdownWriter.write(model).toByteArray(Charsets.UTF_8) },
                )
                looksTextual(source) -> convertPicked(
                    uri, source, "docx", DocxWriter.MIME_TYPE,
                    read = { bytes -> PlainTextImporter.import(bytes.toString(Charsets.UTF_8)) },
                    write = { model -> DocxWriter.toByteArray(model) },
                )
                else -> _state.value = ConvertUiState.Failed(FailReason.UNSUPPORTED_TYPE)
            }
        }
    }

    /**
     * A null or blank provider MIME alone is no evidence of text — an
     * unknown binary would convert to garbage. Extensions decide.
     */
    private fun looksTextual(source: ConvertUiState.Picked): Boolean {
        val lowerName = source.fileName.lowercase()
        return source.mime.orEmpty().startsWith("text/") ||
            listOf(".txt", ".md", ".markdown").any { lowerName.endsWith(it) }
    }

    /** Model for the PDF-export paths; refuses inputs that aren't documents. */
    private fun modelOf(bytes: ByteArray, source: ConvertUiState.Picked): DocumentModel = when {
        source.isWordDocument -> DocxReader.read(bytes)
        looksTextual(source) -> PlainTextImporter.import(bytes.toString(Charsets.UTF_8))
        else -> throw UnconvertibleContent(FailReason.UNSUPPORTED_TYPE)
    }

    /**
     * Every conversion is the same shape — read the input into a model, then
     * write the model out — and keeping the halves apart is what lets Review
     * Mode re-write a corrected model without touching the source again.
     */
    private fun convertPicked(
        uri: Uri,
        source: ConvertUiState.Picked,
        extension: String,
        mimeType: String,
        read: (ByteArray) -> DocumentModel,
        write: (DocumentModel) -> ByteArray,
    ) {
        val input = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (input == null) {
            _state.value = ConvertUiState.Failed(FailReason.READ_ERROR)
            return
        }
        val model = try {
            read(input)
        } catch (e: UnconvertibleContent) {
            _state.value = ConvertUiState.Failed(e.reason)
            return
        } catch (e: AndroidPdfReader.EncryptedDocument) {
            _state.value = ConvertUiState.Failed(FailReason.ENCRYPTED_PDF)
            return
        } catch (e: AndroidOcrReader.Cancelled) {
            _state.value = pickedFile?.meta ?: ConvertUiState.Idle
            return
        } catch (e: OutOfMemoryError) {
            // Rendering a page to a bitmap can exhaust the heap on a big
            // document. An Error is not an Exception, so without this the
            // process would simply die, taking the work and the error
            // message with it.
            _state.value = ConvertUiState.Failed(FailReason.TOO_LARGE)
            return
        } catch (e: Exception) {
            _state.value = ConvertUiState.Failed(FailReason.READ_ERROR)
            return
        }
        lastModel = model
        lastWriter = write
        lastMimeType = mimeType
        lastReport = FidelityReport.of(model)
        editedBlocks.clear()

        val output = try {
            write(model)
        } catch (e: OutOfMemoryError) {
            _state.value = ConvertUiState.Failed(FailReason.TOO_LARGE)
            return
        } catch (e: Exception) {
            // Writing failed, not reading: saying "couldn't read that file"
            // would send the user to re-pick a file that was read fine.
            _state.value = ConvertUiState.Failed(FailReason.WRITE_ERROR)
            return
        }
        val base = source.fileName.substringBeforeLast('.').ifEmpty { "converted" }
        outputName = "$base.$extension"
        _state.value = ConvertUiState.ReadyToSave(outputName, mimeType, output)
    }

    /**
     * Scanned PDF → on-device OCR (Tesseract, Arabic+English) → Word. Slow
     * by nature — pages render to bitmaps and get recognized one by one —
     * but never leaves the device.
     */
    fun convertWithOcr() {
        // Two taps inside one frame would otherwise start two conversions
        // at once, for no benefit and to the user's confusion.
        if (_state.value is ConvertUiState.Converting) return

        val (uri, source) = pickedFile ?: return
        lastOperation = ::convertWithOcr
        _state.value = ConvertUiState.Converting()
        ocrCancelled.set(false)
        viewModelScope.launch(Dispatchers.IO) {
            convertPicked(
                uri, source, "docx", DocxWriter.MIME_TYPE,
                read = { bytes ->
                    val model = AndroidOcrReader(getApplication()).recognize(
                        bytes = bytes,
                        languages = ocrLanguages(),
                        onPage = { page, pageCount ->
                            _state.value = ConvertUiState.Converting(page, pageCount)
                        },
                        shouldContinue = { !ocrCancelled.get() },
                    )
                    // Recognizing nothing is a real outcome — a blank scan, or
                    // a script with no model. Saying so beats saving an empty
                    // document that looks like a successful conversion.
                    val recognized = model.blocks.filterIsInstance<Paragraph>()
                        .any { it.text.isNotBlank() }
                    if (!recognized) throw UnconvertibleContent(FailReason.OCR_EMPTY)
                    model
                },
                write = { model -> DocxWriter.toByteArray(model) },
            )
        }
    }

    /**
     * The OCR language set follows the app language — a second model rides
     * along so mixed documents still read: English with the RTL locale,
     * Arabic with the English one. A per-scan language picker is a later
     * refinement.
     */
    private fun ocrLanguages(): String = when (Locale.getDefault().language) {
        "ar" -> "ara+eng"
        "fr" -> "fra+eng"
        "es" -> "spa+eng"
        "de" -> "deu+eng"
        else -> "eng+ara"
    }

    /** Text, Markdown or Word input → a real .pdf file via the save dialog. */
    fun exportPdf() {
        // Two taps inside one frame would otherwise start two conversions
        // at once, for no benefit and to the user's confusion.
        if (_state.value is ConvertUiState.Converting) return

        val (uri, source) = pickedFile ?: return
        lastOperation = ::exportPdf
        _state.value = ConvertUiState.Converting()
        viewModelScope.launch(Dispatchers.IO) {
            convertPicked(
                uri, source, "pdf", PDF_MIME,
                read = { bytes -> modelOf(bytes, source) },
                write = { model -> PdfFileExporter.render(model) },
            )
        }
    }

    /** Text, Markdown or Word input → print-ready HTML → the system print sheet. */
    fun printPdf() {
        // Two taps inside one frame would otherwise start two conversions
        // at once, for no benefit and to the user's confusion.
        if (_state.value is ConvertUiState.Converting) return

        val (uri, source) = pickedFile ?: return
        lastOperation = ::printPdf
        _state.value = ConvertUiState.Converting()
        viewModelScope.launch(Dispatchers.IO) {
            val input = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() }
            }.getOrNull()
            if (input == null) {
                _state.value = ConvertUiState.Failed(FailReason.READ_ERROR)
                return@launch
            }
            val jobName = source.fileName.substringBeforeLast('.').ifEmpty { "document" }
            try {
                val html = HtmlWriter.write(modelOf(input, source), jobName)
                _state.value = ConvertUiState.ReadyToPrint(html, jobName)
            } catch (e: UnconvertibleContent) {
                _state.value = ConvertUiState.Failed(e.reason)
            } catch (e: Exception) {
                _state.value = ConvertUiState.Failed(FailReason.READ_ERROR)
            }
        }
    }

    /** Re-runs whichever conversion just failed. */
    fun retry() {
        lastOperation?.invoke()
    }

    /** The print job is with the system UI; return to the picked state. */
    fun onPrintHandedOff() {
        _state.value = pickedFile?.meta ?: ConvertUiState.Idle
    }

    /** The UI has launched the system save dialog for the current result. */
    fun onSaveDialogLaunched() {
        val ready = _state.value as? ConvertUiState.ReadyToSave ?: return
        _state.value =
            ConvertUiState.AwaitingSave(ready.suggestedName, ready.mimeType, ready.bytes)
    }

    /** Result of the system "create document" dialog; null = user cancelled. */
    fun onSaveTarget(target: Uri?) {
        // The payload of the dialog that just closed — not whatever the most
        // recent conversion happens to have left behind.
        val awaiting = _state.value as? ConvertUiState.AwaitingSave
        val bytes = awaiting?.bytes
        if (target == null || bytes == null) {
            if (target != null) {
                // The dialog created a document but the conversion is gone
                // (e.g. process death behind the dialog): remove the stub
                // instead of leaving a 0-byte file behind.
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        DocumentsContract.deleteDocument(
                            getApplication<Application>().contentResolver, target
                        )
                    }
                }
            }
            _state.value = pickedFile?.meta ?: ConvertUiState.Idle
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                // "wt" truncates; the default "w" may keep the tail of a
                // longer pre-existing file on some DocumentsProviders,
                // corrupting the output.
                getApplication<Application>().contentResolver
                    .openOutputStream(target, "wt")
                    ?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            _state.value = if (ok) {
                ConvertUiState.Saved(
                    fileName = awaiting.suggestedName,
                    needsReview = lastReport?.reviewables?.isNotEmpty() == true,
                )
            } else {
                ConvertUiState.Failed(FailReason.WRITE_ERROR)
            }
        }
    }

    companion object {
        const val MARKDOWN_MIME = "text/markdown"
        const val PDF_MIME = "application/pdf"
    }
}
