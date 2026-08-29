package app.morpho.converter

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.ooxml.DocxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConvertUiState {
    data object Idle : ConvertUiState
    data class Picked(val fileName: String, val mime: String?) : ConvertUiState
    data object Converting : ConvertUiState
    data class ReadyToSave(val suggestedName: String) : ConvertUiState
    data class Saved(val fileName: String) : ConvertUiState
    data class Failed(val reason: FailReason) : ConvertUiState
}

enum class FailReason { UNSUPPORTED_TYPE, PDF_NOT_YET, READ_ERROR, WRITE_ERROR }

/**
 * Drives the v0 vertical slice: pick a text/Markdown document, convert it with
 * the on-device engine, save the .docx wherever the user chooses. Everything
 * runs locally; this process has no network permission at all.
 */
class ConvertViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ConvertUiState>(ConvertUiState.Idle)
    val state: StateFlow<ConvertUiState> = _state.asStateFlow()

    private var pickedUri: Uri? = null
    private var picked: ConvertUiState.Picked? = null
    private var docxBytes: ByteArray? = null
    private var outputName: String = ""

    fun onPicked(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        var name = "document"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
        }
        pickedUri = uri
        val state = ConvertUiState.Picked(fileName = name, mime = resolver.getType(uri))
        picked = state
        _state.value = state
    }

    fun convert() {
        val uri = pickedUri ?: return
        val source = picked ?: return
        _state.value = ConvertUiState.Converting
        viewModelScope.launch(Dispatchers.IO) {
            val mime = source.mime.orEmpty()
            val lowerName = source.fileName.lowercase()
            val isPdf = mime == "application/pdf" || lowerName.endsWith(".pdf")
            val looksTextual = mime.startsWith("text/") || mime.isEmpty() ||
                listOf(".txt", ".md", ".markdown").any { lowerName.endsWith(it) }

            when {
                isPdf -> _state.value = ConvertUiState.Failed(FailReason.PDF_NOT_YET)
                !looksTextual -> _state.value = ConvertUiState.Failed(FailReason.UNSUPPORTED_TYPE)
                else -> {
                    val text = runCatching {
                        getApplication<Application>().contentResolver.openInputStream(uri)
                            ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull()
                    if (text == null) {
                        _state.value = ConvertUiState.Failed(FailReason.READ_ERROR)
                    } else {
                        docxBytes = DocxWriter.toByteArray(PlainTextImporter.import(text))
                        val base = source.fileName.substringBeforeLast('.').ifEmpty { "converted" }
                        outputName = "$base.docx"
                        _state.value = ConvertUiState.ReadyToSave(outputName)
                    }
                }
            }
        }
    }

    /** Result of the system "create document" dialog; null = user cancelled. */
    fun onSaveTarget(target: Uri?) {
        val bytes = docxBytes
        if (target == null || bytes == null) {
            _state.value = picked ?: ConvertUiState.Idle
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(target)
                    ?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            _state.value = if (ok) {
                ConvertUiState.Saved(outputName)
            } else {
                ConvertUiState.Failed(FailReason.WRITE_ERROR)
            }
        }
    }
}
