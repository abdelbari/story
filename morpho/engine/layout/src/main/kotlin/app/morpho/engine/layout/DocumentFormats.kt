package app.morpho.engine.layout

/**
 * What kind of document a picked file is, by the name and type its
 * provider gave for it.
 *
 * The app asks this of every file a reader picks, and got it wrong in a
 * way nothing could catch: it tested for `.docx` and for the one MIME
 * type its own writer writes, so a macro-enabled Word document — `.docm`,
 * the format an institution's forms and templates are nearly always saved
 * in — was refused as an unsupported type. The reader reads one perfectly:
 * a `.docm` is the same package with a macro part beside the document and
 * a different content type on it, and the macro part is one this converter
 * never opens, so what comes out is the document without it. The same goes
 * for `.dotx` and `.dotm`, which are Word's templates.
 *
 * This lives in the engine rather than in the app because it is the app's
 * only decision that is pure enough to test, and the version that lived in
 * the app was tested by nothing at all.
 */
object DocumentFormats {

    /** What the Word writer writes, and what a `.docx` picked by a reader is. */
    const val WORD_MIME: String =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    const val PDF_MIME: String = "application/pdf"

    const val MARKDOWN_MIME: String = "text/markdown"

    /**
     * Every wordprocessing package the reader reads: the document, the
     * macro-enabled document, and the two templates. All four are the same
     * OOXML package with the same `word/document.xml` inside.
     */
    private val WORD_SUFFIXES = listOf(".docx", ".docm", ".dotx", ".dotm")

    /**
     * Deliberately not here: `application/msword`. It is the type of a
     * binary `.doc`, which nothing in this converter can read, and some
     * providers hand it back for a `.docx` as well. Accepting it would
     * turn "this file type isn't supported" — which is true of a `.doc`
     * and says what to do about it — into "couldn't read that file",
     * which sends the reader back to pick the same file again. A `.docx`
     * mislabelled that way is caught by its name.
     */
    private val WORD_MIMES = setOf(
        WORD_MIME,
        "application/vnd.ms-word.document.macroEnabled.12",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "application/vnd.ms-word.template.macroEnabled.12",
    )

    private val PDF_SUFFIXES = listOf(".pdf")

    /**
     * `application/x-pdf` is a type older tools still write. A file that
     * carries it and is not a PDF is refused by the reader a moment later
     * either way.
     */
    private val PDF_MIMES = setOf(PDF_MIME, "application/x-pdf")

    /** Text and Markdown, which the plain-text importer reads as one thing. */
    private val TEXT_SUFFIXES = listOf(".txt", ".md", ".markdown")

    /**
     * The types the app offers the system file picker.
     *
     * This is the other half of the same decision, and the half that was
     * quietly wrong on its own: a picker that does not offer a type never
     * shows a reader the file, so widening what the converter accepts
     * without widening this changes nothing a reader can see. Both come
     * off one list now, and a test holds them to each other.
     *
     * The wildcard text type is here beside the two named ones because a
     * provider that understands wildcards should show every text file, and
     * one that does not still shows the two.
     */
    val PICKABLE_MIME_TYPES: List<String> =
        listOf("text/plain", MARKDOWN_MIME, "text/*") + WORD_MIMES + PDF_MIME

    /** Whether [fileName] with [mimeType] is a Word document this can read. */
    fun isWord(fileName: String, mimeType: String? = null): Boolean =
        mimeType in WORD_MIMES || named(fileName, WORD_SUFFIXES)

    /** Whether [fileName] with [mimeType] is a PDF. */
    fun isPdf(fileName: String, mimeType: String? = null): Boolean =
        mimeType in PDF_MIMES || named(fileName, PDF_SUFFIXES)

    /**
     * Whether [fileName] with [mimeType] is text this can import.
     *
     * A null or blank type alone is no evidence of text — an unknown
     * binary would import as a document full of replacement characters —
     * so a file with no type is judged by its name.
     */
    fun isPlainText(fileName: String, mimeType: String? = null): Boolean =
        mimeType.orEmpty().startsWith("text/") || named(fileName, TEXT_SUFFIXES)

    private fun named(fileName: String, suffixes: List<String>): Boolean {
        val lower = fileName.lowercase()
        return suffixes.any { lower.endsWith(it) }
    }

    /**
     * The name to offer for [fileName] converted to a file ending
     * [extension] — the name it had, with what it was before taken off.
     *
     * A file whose name is all extension ("`.pdf`", picked from a folder
     * of hidden files) would leave nothing to call the result, so it gets
     * a name rather than a file called "`.docx`".
     */
    fun outputName(fileName: String, extension: String): String =
        "${baseName(fileName)}.$extension"

    /** [fileName] with its extension off, or "converted" where that leaves nothing. */
    fun baseName(fileName: String, whenEmpty: String = "converted"): String =
        fileName.substringBeforeLast('.').trim().ifEmpty { whenEmpty }
}
