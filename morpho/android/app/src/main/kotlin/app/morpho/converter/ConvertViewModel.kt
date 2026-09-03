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
import app.morpho.engine.layout.pdf.PageRanges
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
     * A finished conversion, holding its own bytes and waiting for the
     * reader to save it. Keeping this state between the conversion and the
     * save dialog is what makes a dismissed dialog survivable: the result is
     * still in hand, and a three-minute OCR run need not happen twice.
     */
    data class Converted(
        val suggestedName: String,
        val mimeType: String,
        val bytes: ByteArray,
        val needsReview: Boolean = false,
        /** The document rendered for the preview screen; what the file holds. */
        val previewHtml: String = "",
        /** The same document laid out as pages, for the preview to draw; empty when that failed. */
        val previewPdf: ByteArray = ByteArray(0),
    ) : ConvertUiState

    /**
     * A PDF that will not open without its password. The screen asks for
     * one rather than sending the reader off to strip the protection in
     * another app, which is the advice a converter gives when it cannot be
     * bothered to ask.
     */
    data class NeedsPassword(
        val fileName: String,
        /** True once a password has been tried and refused. */
        val wrongPassword: Boolean = false,
    ) : ConvertUiState

    /** The reader asked to save; the UI opens the system dialog for this. */
    data class ReadyToSave(
        val suggestedName: String,
        val mimeType: String,
        val bytes: ByteArray,
    ) : ConvertUiState

    /** The save dialog is on screen. Its payload is held outside the state. */
    data object AwaitingSave : ConvertUiState

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
    UNSUPPORTED_TYPE, SCANNED_PDF, OCR_EMPTY, TOO_LARGE, READ_ERROR, WRITE_ERROR
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

    /**
     * The password for the picked document, if it needed one. Held in
     * memory for as long as that document is the one on screen — a
     * conversion may read it twice, once for the text and again for OCR —
     * and never written anywhere.
     */
    private var pdfPassword: String = ""

    /**
     * Which pages of the picked document to convert, or null for all of
     * them. A reader who wants one chapter of a book, or one part of a
     * document too big for the phone to hold whole, says so and waits for
     * that much rather than for the lot.
     */
    private var pdfPages: IntRange? = null

    /** Whichever conversion ran last — "Try again" repeats it, not convert(). */
    private var lastOperation: (() -> Unit)? = null
    private var outputName: String = ""

    /**
     * Bumped by every pick. A conversion captures it when it starts and
     * publishes nothing once it no longer matches, so a document arriving
     * from the share sheet mid-conversion supersedes the one in flight
     * instead of racing it for the same fields and the same screen.
     */
    private var pickEpoch = 0

    /**
     * The payload of the save dialog currently on screen, held outside the
     * state machine: the dialog is a separate activity, and whatever happens
     * to this screen while it is open, the bytes it was opened for are the
     * bytes that must be written.
     */
    private var pendingSave: ConvertUiState.ReadyToSave? = null

    /** Publishes UI state only if the pick it belongs to is still current. */
    private fun publish(epoch: Int, state: ConvertUiState) {
        if (epoch == pickEpoch) _state.value = state
    }

    private fun needsReview(): Boolean = lastReport?.reviewables?.isNotEmpty() == true

    /** Fidelity Report of the last conversion's model, for the Saved notice. */
    private var lastReport: FidelityReport.Report? = null

    /**
     * The last conversion's model, how to write it again, and what to call
     * the result — kept so Review Mode can correct a block and re-write the
     * output without re-reading (or re-OCR-ing) the source.
     */
    private var lastModel: DocumentModel? = null
    /** [lastModel] rendered once for the preview; refreshed with every correction. */
    private var lastPreviewHtml: String = ""
    /** [lastModel] laid out as pages for the preview; empty when the layout failed. */
    private var lastPreviewPdf: ByteArray = ByteArray(0)
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
        lastPreviewHtml = HtmlWriter.write(corrected, outputName)
        lastPreviewPdf = previewPages(corrected)
        lastReport = report
        editedBlocks += index
        _review.value = ReviewState(report, editedBlocks.toSet())
    }

    /**
     * The model laid out as pages for the preview screen — the layout the
     * app writes when it makes a PDF. A failure here costs the preview its
     * pages, not the conversion its result: the HTML rendering stands in.
     */
    private fun previewPages(model: DocumentModel): ByteArray =
        runCatching { PdfFileExporter.render(model) }.getOrDefault(ByteArray(0))

    /** Writes the corrected model out again, straight to the save dialog. */
    fun saveCorrected() {
        if (_state.value is ConvertUiState.Converting) return
        val model = lastModel ?: return
        val write = lastWriter ?: return
        // "Try again" after a failed save must retry the save. Re-running the
        // conversion would rebuild the model and drop every correction.
        lastOperation = ::saveCorrected
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
        // Synchronously, before the IO hop: a new document supersedes
        // whatever was in flight. The epoch bump makes a running conversion
        // discard its result rather than publish it over the new pick, and
        // cancelling OCR stops minutes of work nobody is waiting for.
        pickEpoch++
        ocrCancelled.set(true)
        lastReport = null
        lastModel = null
        lastPreviewHtml = ""
        lastPreviewPdf = ByteArray(0)
        lastWriter = null
        pdfPassword = ""
        pdfPages = null
        editedBlocks.clear()
        _review.value = null

        val epoch = pickEpoch
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
            // A newer pick while this query was in flight wins outright.
            if (epoch != pickEpoch) return@launch
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
        val epoch = pickEpoch
        viewModelScope.launch(Dispatchers.IO) {
            when {
                source.isPdf -> convertPicked(
                    epoch, uri, source, "docx", DocxWriter.MIME_TYPE,
                    read = { bytes ->
                        val model =
                            AndroidPdfReader(getApplication()).extract(bytes, pdfPassword, pdfPages)
                        val hasText = model.blocks.filterIsInstance<Paragraph>()
                            .any { it.text.isNotBlank() }
                        if (!hasText) throw UnconvertibleContent(FailReason.SCANNED_PDF)
                        model
                    },
                    write = { model -> DocxWriter.toByteArray(model) },
                )
                source.isWordDocument -> convertPicked(
                    epoch, uri, source, "md", MARKDOWN_MIME,
                    read = { bytes -> DocxReader.read(bytes) },
                    write = { model -> MarkdownWriter.write(model).toByteArray(Charsets.UTF_8) },
                )
                looksTextual(source) -> convertPicked(
                    epoch, uri, source, "docx", DocxWriter.MIME_TYPE,
                    read = { bytes -> PlainTextImporter.import(bytes.toString(Charsets.UTF_8)) },
                    write = { model -> DocxWriter.toByteArray(model) },
                )
                else -> publish(epoch, ConvertUiState.Failed(FailReason.UNSUPPORTED_TYPE))
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
        epoch: Int,
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
            publish(epoch, ConvertUiState.Failed(FailReason.READ_ERROR))
            return
        }
        val model = try {
            read(input)
        } catch (e: UnconvertibleContent) {
            publish(epoch, ConvertUiState.Failed(e.reason))
            return
        } catch (e: AndroidPdfReader.EncryptedDocument) {
            publish(epoch, ConvertUiState.NeedsPassword(source.fileName, e.passwordWasTried))
            return
        } catch (e: AndroidOcrReader.Cancelled) {
            publish(epoch, pickedFile?.meta ?: ConvertUiState.Idle)
            return
        } catch (e: OutOfMemoryError) {
            // Rendering a page to a bitmap can exhaust the heap on a big
            // document. An Error is not an Exception, so without this the
            // process would simply die, taking the work and the error
            // message with it.
            publish(epoch, ConvertUiState.Failed(FailReason.TOO_LARGE))
            return
        } catch (e: Exception) {
            publish(epoch, ConvertUiState.Failed(FailReason.READ_ERROR))
            return
        }
        // Superseded by a newer pick while reading: leave every shared field
        // to the conversion that now owns them.
        if (epoch != pickEpoch) return

        val base = source.fileName.substringBeforeLast('.').ifEmpty { "converted" }
        outputName = "$base.$extension"
        lastModel = model
        lastPreviewHtml = HtmlWriter.write(model, outputName)
        lastPreviewPdf = previewPages(model)
        lastWriter = write
        lastMimeType = mimeType
        lastReport = FidelityReport.of(model)
        editedBlocks.clear()

        val output = try {
            write(model)
        } catch (e: OutOfMemoryError) {
            publish(epoch, ConvertUiState.Failed(FailReason.TOO_LARGE))
            return
        } catch (e: Exception) {
            // Writing failed, not reading: saying "couldn't read that file"
            // would send the user to re-pick a file that was read fine.
            publish(epoch, ConvertUiState.Failed(FailReason.WRITE_ERROR))
            return
        }
        publish(epoch, ConvertUiState.Converted(outputName, mimeType, output, needsReview(), lastPreviewHtml, lastPreviewPdf))
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
        val epoch = pickEpoch
        viewModelScope.launch(Dispatchers.IO) {
            convertPicked(
                epoch, uri, source, "docx", DocxWriter.MIME_TYPE,
                read = { bytes ->
                    val model = AndroidOcrReader(getApplication()).recognize(
                        bytes = bytes,
                        languages = ocrLanguages(),
                        password = pdfPassword,
                        pages = pdfPages,
                        onPage = { page, pageCount ->
                            publish(epoch, ConvertUiState.Converting(page, pageCount))
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
        val epoch = pickEpoch
        viewModelScope.launch(Dispatchers.IO) {
            convertPicked(
                epoch, uri, source, "pdf", PDF_MIME,
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
        val epoch = pickEpoch
        viewModelScope.launch(Dispatchers.IO) {
            val input = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() }
            }.getOrNull()
            if (input == null) {
                publish(epoch, ConvertUiState.Failed(FailReason.READ_ERROR))
                return@launch
            }
            val jobName = source.fileName.substringBeforeLast('.').ifEmpty { "document" }
            try {
                val html = HtmlWriter.write(modelOf(input, source), jobName)
                publish(epoch, ConvertUiState.ReadyToPrint(html, jobName))
            } catch (e: UnconvertibleContent) {
                publish(epoch, ConvertUiState.Failed(e.reason))
            } catch (e: Exception) {
                publish(epoch, ConvertUiState.Failed(FailReason.READ_ERROR))
            }
        }
    }

    /**
     * Opens the locked document with [password] and runs the conversion the
     * reader had asked for. A password that does not open it comes straight
     * back as another ask, so the answer to a typo is to type it again.
     */
    fun unlock(password: String) {
        if (_state.value !is ConvertUiState.NeedsPassword) return
        pdfPassword = password
        val operation = lastOperation
        if (operation != null) operation() else convert()
    }

    /** The reader gave up on the password; the document stands unconverted. */
    fun cancelUnlock() {
        if (_state.value !is ConvertUiState.NeedsPassword) return
        pdfPassword = ""
        _state.value = pickedFile?.meta ?: ConvertUiState.Idle
    }

    /**
     * Converts the pages [pages] names — "5-20", "7", "5-", or anything
     * that names none of them, which is the whole document. What is chosen
     * holds until another document is picked, so a second attempt after a
     * document proved too large converts the part that was asked for.
     */
    fun convertPages(pages: String) {
        pdfPages = PageRanges.parse(pages)
        convert()
    }

    /** Re-runs whichever conversion just failed. */
    fun retry() {
        lastOperation?.invoke()
    }

    /** The print job is with the system UI; return to the picked state. */
    fun onPrintHandedOff() {
        _state.value = pickedFile?.meta ?: ConvertUiState.Idle
    }

    /** The reader asked to save the finished conversion. */
    fun requestSave() {
        val converted = _state.value as? ConvertUiState.Converted ?: return
        _state.value = ConvertUiState.ReadyToSave(
            converted.suggestedName,
            converted.mimeType,
            converted.bytes,
        )
    }

    /** The UI has launched the system save dialog for the current result. */
    fun onSaveDialogLaunched() {
        val ready = _state.value as? ConvertUiState.ReadyToSave ?: return
        pendingSave = ready
        _state.value = ConvertUiState.AwaitingSave
    }

    /** The system had no save dialog, or no print service, to offer. */
    fun onSystemUiUnavailable() {
        pendingSave = null
        _state.value = ConvertUiState.Failed(FailReason.WRITE_ERROR)
    }

    /** Result of the system "create document" dialog; null = user cancelled. */
    fun onSaveTarget(target: Uri?) {
        // The payload of the dialog that just closed — not whatever the
        // screen has moved on to in the meantime.
        val pending = pendingSave
        pendingSave = null
        val bytes = pending?.bytes
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
            // Dismissing the dialog must not discard the conversion: offer
            // it again rather than making the reader convert a second time.
            _state.value = pending?.let {
                ConvertUiState.Converted(it.suggestedName, it.mimeType, it.bytes, needsReview(), lastPreviewHtml, lastPreviewPdf)
            } ?: pickedFile?.meta ?: ConvertUiState.Idle
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
                    fileName = pending.suggestedName,
                    needsReview = needsReview(),
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
