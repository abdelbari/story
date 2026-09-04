package app.morpho.converter

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.morpho.engine.layout.DocumentFormats
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.FidelityReport
import app.morpho.engine.layout.HtmlWriter
import app.morpho.engine.layout.MarkdownWriter
import app.morpho.engine.layout.ParagraphEdit
import app.morpho.engine.layout.PlainTextImporter
import app.morpho.engine.layout.pdf.PageRanges
import app.morpho.engine.ooxml.DocxReader
import app.morpho.engine.layout.Paragraph
import app.morpho.engine.layout.Reading
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
        /**
         * True when the input is a picture of a document — a photograph of
         * a page, a scan saved as an image. There is nothing to read but
         * what recognition finds, so this goes straight to it rather than
         * offering a conversion that would come back empty.
         */
        val isImage: Boolean = false,
        /**
         * How many pictures were handed over together, which are between
         * them one document. One for everything else.
         */
        val pictures: Int = 1,
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
    UNSUPPORTED_TYPE, SCANNED_PDF, OCR_EMPTY, UNREADABLE_PICTURE, TOO_LARGE, READ_ERROR, WRITE_ERROR
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
     * What the reader picked, published as one reference so a second pick
     * racing in (share sheet vs. picker result) can never pair one file's
     * bytes with another file's type routing.
     *
     * One document, or the several pictures that are between them one
     * document. [uri] is the first of them, which is the one everything but
     * recognition reads: a Word file or a PDF arrives on its own, and a
     * reader who somehow sent several gets the first converted rather than
     * nothing at all.
     */
    private data class PickedFile(val uris: List<Uri>, val meta: ConvertUiState.Picked) {
        val uri: Uri get() = uris.first()
    }

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
     * Set by [cancelConversion]; the reading and the recognizer both read
     * it between pages. An AtomicBoolean rather than job cancellation
     * because recognition sits in a native call Kotlin cannot interrupt
     * mid-page, and because the reading is the same shape.
     */
    private val cancelled = AtomicBoolean(false)

    /** Asks whatever is running to stop after the page it is on. */
    fun cancelConversion() {
        cancelled.set(true)
    }

    /**
     * What a long reading says about itself: which page it has reached,
     * and whether it is still wanted.
     *
     * A book of two hundred pages is the better part of a minute on a
     * phone, and it used to be a minute of a spinner that said nothing and
     * offered nothing. The screen already knows how to show a page count
     * and a cancel — recognition, which is slower still, was given both
     * first — so this is the reading asking for the same two.
     */
    private fun watching(epoch: Int) = Reading(
        onPage = { page, pageCount -> publish(epoch, ConvertUiState.Converting(page, pageCount)) },
        shouldContinue = { !cancelled.get() },
    )

    /**
     * The recognizer runs a blocking native call with no suspension point,
     * so cancelling [viewModelScope] cannot reach it. Without this, leaving
     * the app mid-OCR leaves Tesseract chewing through a document nobody is
     * waiting for.
     */
    override fun onCleared() {
        super.onCleared()
        cancelled.set(true)
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
        republish(model.copy(blocks = blocks), index)
    }

    /**
     * Corrects what a block says — a word recognition read wrong, a line
     * two pages ran together.
     *
     * The words go over the runs rather than replacing them, so the
     * paragraph keeps its bold, its links and the mark on its note; see
     * [ParagraphEdit]. Confidence is left alone for the same reason
     * relabelling leaves it alone: the reader has corrected these
     * characters and said nothing about the rest, and a report that
     * quietly grew more confident every time somebody touched it would be
     * worth nothing.
     */
    fun retext(index: Int, text: String) {
        val model = lastModel ?: return
        val block = model.blocks.getOrNull(index) as? Paragraph ?: return
        if (block.text == text) return
        val blocks = model.blocks.toMutableList()
        blocks[index] = ParagraphEdit.retext(block, text)
        republish(model.copy(blocks = blocks), index)
    }

    /**
     * The whole of what a block says, for the reader about to correct it.
     *
     * The report carries an excerpt — eighty code points, enough to know
     * one paragraph from another in a list — and handing that to an editor
     * would save the paragraph back with its tail cut off, which is a
     * conversion that loses text while looking like a correction. So the
     * editor asks here instead, and the excerpt stays what it is: a label.
     */
    fun textOf(index: Int): String =
        (lastModel?.blocks?.getOrNull(index) as? Paragraph)?.text.orEmpty()

    /**
     * A corrected model in place of the one that was there, with
     * everything that was made from it made again.
     *
     * The preview, the pages and the report are all readings of the
     * model, and a correction that changed the model and left them would
     * show the reader the document as it was before they corrected it.
     */
    private fun republish(corrected: DocumentModel, index: Int) {
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

    /** One document, picked or opened or shared. */
    fun onPicked(uri: Uri) = onPickedAll(listOf(uri))

    /**
     * Several pictures, shared together, which are between them one
     * document: a reader photographs the four pages of a form and shares
     * all four at once.
     *
     * The first names the result and decides what kind of thing this is.
     * The rest are pages of it, in the order they were handed over, which
     * is the order the sharing app showed them in.
     */
    fun onPickedAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // Synchronously, before the IO hop: a new document supersedes
        // whatever was in flight. The epoch bump makes a running conversion
        // discard its result rather than publish it over the new pick, and
        // cancelling OCR stops minutes of work nobody is waiting for.
        pickEpoch++
        cancelled.set(true)
        lastReport = null
        lastModel = null
        lastPreviewHtml = ""
        lastPreviewPdf = ByteArray(0)
        lastWriter = null
        pdfPassword = ""
        pdfPages = null
        wantsMarkdown = false
        editedBlocks.clear()
        _review.value = null

        val epoch = pickEpoch
        val uri = uris.first()
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
            val picture = DocumentFormats.isImage(name, mime)
            // Several files are several pages only where they are pictures.
            // Two Word documents are two documents, and converting the
            // first while silently dropping the second is the sort of thing
            // a reader finds out about much later.
            val theseOnes = if (picture) uris else listOf(uri)
            val state = ConvertUiState.Picked(
                fileName = name,
                mime = mime,
                isWordDocument = DocumentFormats.isWord(name, mime),
                isPdf = DocumentFormats.isPdf(name, mime),
                isImage = picture,
                pictures = theseOnes.size,
            )
            // A newer pick while this query was in flight wins outright.
            if (epoch != pickEpoch) return@launch
            pickedFile = PickedFile(theseOnes, state)
            _state.value = state
        }
    }

    fun convert() {
        startConversion(asMarkdown = false, again = ::convert)
    }

    /**
     * A PDF to Markdown rather than to Word: the same reading, written for
     * a notebook, a repository or a static site instead of a word
     * processor. Going by way of Word to get there is two conversions and
     * two files for what the reader already holds.
     */
    fun convertToMarkdown() {
        startConversion(asMarkdown = true, again = ::convertToMarkdown)
    }

    /**
     * Which of the two a picked PDF was asked for. A scan is found out
     * only after a conversion has been tried, and the reader who asked
     * for Markdown asked for Markdown either way.
     */
    private var wantsMarkdown = false

    private fun startConversion(asMarkdown: Boolean, again: () -> Unit) {
        wantsMarkdown = asMarkdown
        // A conversion stopped earlier must not stop this one before it
        // has read a page.
        cancelled.set(false)
        // Two taps inside one frame would otherwise start two conversions
        // at once, for no benefit and to the user's confusion.
        if (_state.value is ConvertUiState.Converting) return

        val picked = pickedFile ?: return
        val uri = picked.uri
        val source = picked.meta
        lastOperation = again
        _state.value = ConvertUiState.Converting()
        val epoch = pickEpoch
        viewModelScope.launch(Dispatchers.IO) {
            when {
                source.isPdf -> convertPicked(
                    epoch, uri, source,
                    if (asMarkdown) "md" else "docx",
                    if (asMarkdown) MARKDOWN_MIME else DocxWriter.MIME_TYPE,
                    read = { bytes ->
                        val model =
                            AndroidPdfReader(getApplication())
                                .extract(bytes, pdfPassword, pdfPages, watching(epoch))
                        val hasText = model.blocks.filterIsInstance<Paragraph>()
                            .any { it.text.isNotBlank() }
                        if (!hasText) throw UnconvertibleContent(FailReason.SCANNED_PDF)
                        model
                    },
                    write = { model ->
                        if (asMarkdown) MarkdownWriter.write(model).toByteArray(Charsets.UTF_8)
                        else DocxWriter.toByteArray(model)
                    },
                )
                // A picture holds no text to find, so there is no reading
                // to try before recognition and nothing to be gained by
                // offering one: it goes where a scanned PDF goes after the
                // reader has been told it is a scan.
                source.isImage -> convertPicked(
                    epoch, uri, source,
                    if (asMarkdown) "md" else "docx",
                    if (asMarkdown) MARKDOWN_MIME else DocxWriter.MIME_TYPE,
                    read = { bytes -> recognizedPictures(epoch, bytes, picked.uris.drop(1)) },
                    write = { model ->
                        if (asMarkdown) MarkdownWriter.write(model).toByteArray(Charsets.UTF_8)
                        else DocxWriter.toByteArray(model)
                    },
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
    private fun looksTextual(source: ConvertUiState.Picked): Boolean =
        DocumentFormats.isPlainText(source.fileName, source.mime)

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
        } catch (e: Reading.Cancelled) {
            // Stopped by the reader, not by the document: back to the file
            // they picked, with nothing said about a failure.
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

        outputName = DocumentFormats.outputName(source.fileName, extension)
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
     * Scanned PDF → on-device OCR (Tesseract, Arabic+English) → Word, or
     * to Markdown where that is what was asked for before the file turned
     * out to be a scan. Slow by nature — pages render to bitmaps and get
     * recognized one by one — but never leaves the device.
     */
    fun convertWithOcr() {
        // Two taps inside one frame would otherwise start two conversions
        // at once, for no benefit and to the user's confusion.
        if (_state.value is ConvertUiState.Converting) return

        val picked = pickedFile ?: return
        val uri = picked.uri
        val source = picked.meta
        lastOperation = ::convertWithOcr
        _state.value = ConvertUiState.Converting()
        cancelled.set(false)
        val epoch = pickEpoch
        viewModelScope.launch(Dispatchers.IO) {
            convertPicked(
                epoch, uri, source,
                if (wantsMarkdown) "md" else "docx",
                if (wantsMarkdown) MARKDOWN_MIME else DocxWriter.MIME_TYPE,
                read = { bytes ->
                    val model = AndroidOcrReader(getApplication()).recognize(
                        bytes = bytes,
                        languages = ocrLanguages(),
                        password = pdfPassword,
                        pages = pdfPages,
                        onPage = { page, pageCount ->
                            publish(epoch, ConvertUiState.Converting(page, pageCount))
                        },
                        shouldContinue = { !cancelled.get() },
                    )
                    // Recognizing nothing is a real outcome — a blank scan, or
                    // a script with no model. Saying so beats saving an empty
                    // document that looks like a successful conversion.
                    val recognized = model.blocks.filterIsInstance<Paragraph>()
                        .any { it.text.isNotBlank() }
                    if (!recognized) throw UnconvertibleContent(FailReason.OCR_EMPTY)
                    model
                },
                write = { model ->
                    if (wantsMarkdown) MarkdownWriter.write(model).toByteArray(Charsets.UTF_8)
                    else DocxWriter.toByteArray(model)
                },
            )
        }
    }

    /**
     * One picture, recognised — the same reading a scanned PDF gets, since
     * a rendered page and a photographed one are the same thing to
     * recognition once they are pixels.
     */
    private fun recognizedPictures(epoch: Int, first: ByteArray, rest: List<Uri>): DocumentModel {
        // The first is already in hand; the rest are opened as the reader
        // reaches them and let go before the next, so forty photographs
        // are forty pages of work and never forty pictures held together.
        val pages = listOf<() -> ByteArray>({ first }) + rest.map { uri ->
            { bytesOf(uri) ?: throw UnconvertibleContent(FailReason.READ_ERROR) }
        }
        val model = try {
            AndroidOcrReader(getApplication()).recognizeImages(
                pictures = pages,
                languages = ocrLanguages(),
                onPage = { page, pageCount ->
                    publish(epoch, ConvertUiState.Converting(page, pageCount))
                },
                shouldContinue = { !cancelled.get() },
            )
        } catch (e: AndroidOcrReader.UnreadablePicture) {
            // Named rather than lumped in with a read error: a reader whose
            // TIFF will not open needs to know it is the kind of picture
            // and not the picture.
            throw UnconvertibleContent(FailReason.UNREADABLE_PICTURE)
        }
        // Recognising nothing is a real outcome — a photograph of a wall,
        // a page too dark to read. Saying so beats saving an empty
        // document that looks like a conversion that worked.
        val recognized = model.blocks.filterIsInstance<Paragraph>().any { it.text.isNotBlank() }
        if (!recognized) throw UnconvertibleContent(FailReason.OCR_EMPTY)
        return model
    }

    /** [uri] read whole, or null where the provider would not open it. */
    private fun bytesOf(uri: Uri): ByteArray? = runCatching {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /**
     * The OCR language set follows the app language. Which set that is
     * belongs beside the packs it names, where a test can hold the two to
     * each other: a set naming a pack the app does not ship fails nowhere
     * but on the phone, and takes recognition down with it.
     */
    private fun ocrLanguages(): String =
        AndroidOcrReader.languagesFor(Locale.getDefault().language)

    /** Text, Markdown or Word input → a real .pdf file via the save dialog. */
    fun exportPdf() {
        // Two taps inside one frame would otherwise start two conversions
        // at once, for no benefit and to the user's confusion.
        if (_state.value is ConvertUiState.Converting) return

        val picked = pickedFile ?: return
        val uri = picked.uri
        val source = picked.meta
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

        val picked = pickedFile ?: return
        val uri = picked.uri
        val source = picked.meta
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
            val jobName = DocumentFormats.baseName(source.fileName, whenEmpty = "document")
            try {
                // The page this becomes is a PDF, not a preview: it
                // carries no notes, so that printing a commented
                // document here gives the same document as writing it
                // out with the app's own layout, which draws none.
                val html = HtmlWriter.write(modelOf(input, source), jobName, comments = false)
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
        const val MARKDOWN_MIME = DocumentFormats.MARKDOWN_MIME
        const val PDF_MIME = DocumentFormats.PDF_MIME
    }
}
