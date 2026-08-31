package app.morpho.converter

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.morpho.engine.layout.MarkdownWriter
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.ooxml.DocxReader
import app.morpho.engine.ooxml.DocxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConvertUiState {
    data object Idle : ConvertUiState
    data class Picked(
        val fileName: String,
        val mime: String?,
        /** True when the input is a Word document, so conversion targets Markdown. */
        val isWordDocument: Boolean,
    ) : ConvertUiState

    data object Converting : ConvertUiState

    /** Conversion done and the system save dialog is on screen. */
    data object AwaitingSave : ConvertUiState
    data class ReadyToSave(val suggestedName: String, val mimeType: String) : ConvertUiState
    data class Saved(val fileName: String) : ConvertUiState
    data class Failed(val reason: FailReason) : ConvertUiState
}

enum class FailReason { UNSUPPORTED_TYPE, PDF_NOT_YET, READ_ERROR, WRITE_ERROR }

/**
 * Drives the v0 conversion slices, both fully on-device: text/Markdown →
 * Word (.docx), and Word (.docx) → Markdown. This process has no network
 * permission at all.
 */
class ConvertViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ConvertUiState>(ConvertUiState.Idle)
    val state: StateFlow<ConvertUiState> = _state.asStateFlow()

    private var pickedUri: Uri? = null
    private var picked: ConvertUiState.Picked? = null
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
            pickedUri = uri
            val state = ConvertUiState.Picked(
                fileName = name,
                mime = mime,
                isWordDocument = mime == DocxWriter.MIME_TYPE ||
                    name.lowercase().endsWith(".docx"),
            )
            picked = state
            _state.value = state
        }
    }

    fun convert() {
        val uri = pickedUri ?: return
        val source = picked ?: return
        _state.value = ConvertUiState.Converting
        viewModelScope.launch(Dispatchers.IO) {
            val mime = source.mime.orEmpty()
            val lowerName = source.fileName.lowercase()
            val isPdf = mime == "application/pdf" || lowerName.endsWith(".pdf")
            // A null or blank provider MIME alone is no evidence of text — an
            // unknown binary would convert to garbage. Extensions decide.
            val looksTextual = mime.startsWith("text/") ||
                listOf(".txt", ".md", ".markdown").any { lowerName.endsWith(it) }

            when {
                isPdf -> _state.value = ConvertUiState.Failed(FailReason.PDF_NOT_YET)
                source.isWordDocument -> convertPicked(uri, source, "md", MARKDOWN_MIME) { bytes ->
                    MarkdownWriter.write(DocxReader.read(bytes)).toByteArray(Charsets.UTF_8)
                }
                looksTextual -> convertPicked(uri, source, "docx", DocxWriter.MIME_TYPE) { bytes ->
                    DocxWriter.toByteArray(PlainTextImporter.import(bytes.toString(Charsets.UTF_8)))
                }
                else -> _state.value = ConvertUiState.Failed(FailReason.UNSUPPORTED_TYPE)
            }
        }
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
        val output = runCatching { transform(input) }.getOrNull()
        if (output == null) {
            _state.value = ConvertUiState.Failed(FailReason.READ_ERROR)
            return
        }
        outputBytes = output
        val base = source.fileName.substringBeforeLast('.').ifEmpty { "converted" }
        outputName = "$base.$extension"
        _state.value = ConvertUiState.ReadyToSave(outputName, mimeType)
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
            _state.value = picked ?: ConvertUiState.Idle
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
    }
}
