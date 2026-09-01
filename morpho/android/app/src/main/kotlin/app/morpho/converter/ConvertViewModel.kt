package app.morpho.converter

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.MarkdownWriter
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.ooxml.DocxReader
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.ooxml.DocxWriter
import app.morpho.pdf.AndroidOcrReader
import app.morpho.pdf.AndroidPdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

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

    data object Converting : ConvertUiState

    /** Conversion done and the system save dialog is on screen. */
    data object AwaitingSave : ConvertUiState
    data class ReadyToSave(val suggestedName: String, val mimeType: String) : ConvertUiState

    /** Print-ready HTML for the system print sheet's "Save as PDF". */
    data class ReadyToPrint(val html: String, val jobName: String) : ConvertUiState
    data class Saved(val fileName: String) : ConvertUiState
    data class Failed(val reason: FailReason) : ConvertUiState
}

enum class FailReason { UNSUPPORTED_TYPE, SCANNED_PDF, READ_ERROR, WRITE_ERROR }

/** Thrown inside a conversion to surface a specific, honest failure reason. */
private class UnconvertibleContent(val reason: FailReason) : Exception()

/**
 * Drives the conversion slices, all fully on-device: text/Markdown → Word,
 * Word → Markdown, PDF → Word (text PDFs; scanned ones await the M3 OCR
 * milestone), and text/Markdown/Word → PDF — as a saved file
 * ([PdfFileExporter]) or through the system print sheet. This process has
 * no network permission at all.
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
    private var outputBytes: ByteArray? = null
    private var outputName: String = ""

    fun onPicked(uri: Uri) {
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
        val (uri, source) = pickedFile ?: return
        lastOperation = ::convert
        _state.value = ConvertUiState.Converting
        viewModelScope.launch(Dispatchers.IO) {
            when {
                source.isPdf -> convertPicked(uri, source, "docx", DocxWriter.MIME_TYPE) { bytes ->
                    val model = AndroidPdfReader(getApplication()).extract(bytes)
                    val hasText = model.blocks.filterIsInstance<Paragraph>()
                        .any { it.text.isNotBlank() }
                    if (!hasText) throw UnconvertibleContent(FailReason.SCANNED_PDF)
                    DocxWriter.toByteArray(model)
                }
                source.isWordDocument -> convertPicked(uri, source, "md", MARKDOWN_MIME) { bytes ->
                    MarkdownWriter.write(DocxReader.read(bytes)).toByteArray(Charsets.UTF_8)
                }
                looksTextual(source) -> convertPicked(uri, source, "docx", DocxWriter.MIME_TYPE) { bytes ->
                    DocxWriter.toByteArray(PlainTextImporter.import(bytes.toString(Charsets.UTF_8)))
                }
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

    private fun convertPicked(
        uri: Uri,
        source: ConvertUiState.Picked,
        extension: String,
        mimeType: String,
        transform: (ByteArray) -> ByteArray,
    ) {
        val input = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (input == null) {
            _state.value = ConvertUiState.Failed(FailReason.READ_ERROR)
            return
        }
        val output = try {
            transform(input)
        } catch (e: UnconvertibleContent) {
            _state.value = ConvertUiState.Failed(e.reason)
            return
        } catch (e: Exception) {
            _state.value = ConvertUiState.Failed(FailReason.READ_ERROR)
            return
        }
        outputBytes = output
        val base = source.fileName.substringBeforeLast('.').ifEmpty { "converted" }
        outputName = "$base.$extension"
        _state.value = ConvertUiState.ReadyToSave(outputName, mimeType)
    }

    /**
     * Scanned PDF → on-device OCR (Tesseract, Arabic+English) → Word. Slow
     * by nature — pages render to bitmaps and get recognized one by one —
     * but never leaves the device.
     */
    fun convertWithOcr() {
        val (uri, source) = pickedFile ?: return
        lastOperation = ::convertWithOcr
        _state.value = ConvertUiState.Converting
        viewModelScope.launch(Dispatchers.IO) {
            convertPicked(uri, source, "docx", DocxWriter.MIME_TYPE) { bytes ->
                DocxWriter.toByteArray(
                    AndroidOcrReader(getApplication()).recognize(bytes, ocrLanguages())
                )
            }
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
        val (uri, source) = pickedFile ?: return
        lastOperation = ::exportPdf
        _state.value = ConvertUiState.Converting
        viewModelScope.launch(Dispatchers.IO) {
            convertPicked(uri, source, "pdf", PDF_MIME) { bytes ->
                PdfFileExporter.render(modelOf(bytes, source))
            }
        }
    }

    /** Text, Markdown or Word input → print-ready HTML → the system print sheet. */
    fun printPdf() {
        val (uri, source) = pickedFile ?: return
        lastOperation = ::printPdf
        _state.value = ConvertUiState.Converting
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
        if (_state.value is ConvertUiState.ReadyToSave) {
            _state.value = ConvertUiState.AwaitingSave
        }
    }

    /** Result of the system "create document" dialog; null = user cancelled. */
    fun onSaveTarget(target: Uri?) {
        val bytes = outputBytes
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
                ConvertUiState.Saved(outputName)
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
