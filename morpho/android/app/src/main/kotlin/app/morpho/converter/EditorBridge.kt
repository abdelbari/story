package app.morpho.converter

import android.webkit.JavascriptInterface
import app.morpho.engine.layout.DocumentModel
import app.morpho.engine.layout.EditorProtocol
import app.morpho.engine.layout.EditorState
import app.morpho.engine.layout.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one object the editor's page is given, and everything it may ask
 * of the app: an operation sent and its reply returned on the same
 * call, the state at the caret told after every reply, and a picture
 * tapped. The plan's price for script in a WebView was one bridge with
 * a few narrowly typed methods; this is the bridge, and the page's
 * script is held by a test to ask for these three and nothing else.
 *
 * The document is [EditorState]'s, in the engine, where every edit is
 * a pure function held by its tests; nothing here decides what an edit
 * does. The page's script calls in on the WebView's own thread, so the
 * state is kept under a lock, and what the screen reads of it comes out
 * as flows.
 */
class EditorBridge(
    document: DocumentModel,
    /** Told the document as it now stands after every edit, on the page's thread. */
    private val onChanged: (DocumentModel) -> Unit,
) {
    private val lock = Any()
    private var state: EditorState = EditorState.open(document)

    private val _status = MutableStateFlow(EditorStatus())

    /** What the toolbar shows: the look at the caret, the paragraph, what can be undone. */
    val status: StateFlow<EditorStatus> = _status.asStateFlow()

    private val _tapped = MutableStateFlow<EditorTap?>(null)

    /** A picture the reader tapped, until the screen has dealt with it. */
    val tapped: StateFlow<EditorTap?> = _tapped.asStateFlow()

    /** The document as it now stands. */
    val document: DocumentModel get() = synchronized(lock) { state.document }

    /** Whether any block is not as it was opened. */
    val changed: Boolean get() = synchronized(lock) { state.modified.isNotEmpty() }

    /** An operation done from the app itself, for what needs its answer here: a search, a count. */
    fun ask(json: String): Map<*, *>? = runCatching { Json.parse(send(json)) as? Map<*, *> }.getOrNull()

    fun dismissTap() {
        _tapped.value = null
    }

    @JavascriptInterface
    fun send(json: String): String {
        val reply: String
        var changedTo: DocumentModel? = null
        synchronized(lock) {
            val before = state.document
            val step = EditorProtocol.step(state, json)
            state = step.state
            reply = step.reply
            if (state.document !== before) changedTo = state.document
        }
        changedTo?.let(onChanged)
        return reply
    }

    @JavascriptInterface
    fun status(json: String) {
        _status.value = EditorStatus.parse(json)
    }

    @JavascriptInterface
    fun tapped(json: String) {
        val map = runCatching { Json.parse(json) as? Map<*, *> }.getOrNull() ?: return
        if (map["kind"] != "image") return
        val block = (map["block"] as? Double)?.toInt() ?: return
        _tapped.value = EditorTap(block, (map["alt"] as? String).orEmpty())
    }
}

/** A picture the reader tapped: which block, and what it says it shows. */
data class EditorTap(val block: Int, val alt: String)

/** A note about the character left of the caret. */
data class EditorComment(val id: Int, val text: String, val author: String?)

/** What the caret's table is, for the table tools. */
data class EditorTable(val ruled: Boolean, val headRow: Boolean, val shadingRgb: Int?)

/**
 * The state at the caret as the page last told it, for the toolbar's
 * buttons: which looks are down, what the paragraph is, what can be
 * undone, and what the caret stands in.
 */
data class EditorStatus(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val fontFamily: String? = null,
    val fontSizePt: Float? = null,
    val colorRgb: Int? = null,
    val highlightRgb: Int? = null,
    val link: String? = null,
    val kind: String = "BODY",
    val alignment: String? = null,
    val direction: String? = null,
    val listMarker: String? = null,
    val listLevel: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val modified: Int = 0,
    val cellsSelected: Int = 0,
    val canMerge: Boolean = false,
    val canSplit: Boolean = false,
    val table: EditorTable? = null,
    val comments: List<EditorComment> = emptyList(),
    val collapsed: Boolean = true,
    val block: Int = 0,
) {
    companion object {
        /** [json] as the page's script writes it, read as if a hostile page wrote it. */
        fun parse(json: String): EditorStatus {
            val map = runCatching { Json.parse(json) as? Map<*, *> }.getOrNull() ?: return EditorStatus()
            val look = map["look"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val paragraph = map["paragraph"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val table = (map["table"] as? Map<*, *>)?.let {
                EditorTable(
                    ruled = it["ruled"] as? Boolean ?: true,
                    headRow = it["headRow"] as? Boolean ?: false,
                    shadingRgb = (it["shadingRgb"] as? Double)?.toInt(),
                )
            }
            val comments = (map["comments"] as? List<*>).orEmpty().mapNotNull { note ->
                val m = note as? Map<*, *> ?: return@mapNotNull null
                EditorComment((m["id"] as? Double)?.toInt() ?: return@mapNotNull null, m["text"] as? String ?: "", m["author"] as? String)
            }
            return EditorStatus(
                bold = look["bold"] as? Boolean ?: false,
                italic = look["italic"] as? Boolean ?: false,
                underline = look["underline"] as? Boolean ?: false,
                strikethrough = look["strikethrough"] as? Boolean ?: false,
                superscript = look["superscript"] as? Boolean ?: false,
                subscript = look["subscript"] as? Boolean ?: false,
                fontFamily = look["fontFamily"] as? String,
                fontSizePt = (look["fontSizePt"] as? Double)?.toFloat(),
                colorRgb = (look["colorRgb"] as? Double)?.toInt(),
                highlightRgb = (look["highlightRgb"] as? Double)?.toInt(),
                link = look["link"] as? String,
                kind = paragraph["kind"] as? String ?: "BODY",
                alignment = paragraph["alignment"] as? String,
                direction = paragraph["direction"] as? String,
                listMarker = paragraph["listMarker"] as? String,
                listLevel = (paragraph["listLevel"] as? Double)?.toInt() ?: 0,
                canUndo = map["canUndo"] as? Boolean ?: false,
                canRedo = map["canRedo"] as? Boolean ?: false,
                modified = (map["modified"] as? Double)?.toInt() ?: 0,
                cellsSelected = (map["cells"] as? List<*>)?.size ?: 0,
                canMerge = map["canMerge"] as? Boolean ?: false,
                canSplit = map["canSplit"] as? Boolean ?: false,
                table = table,
                comments = comments,
                collapsed = map["collapsed"] as? Boolean ?: true,
                block = (map["block"] as? Double)?.toInt() ?: 0,
            )
        }
    }
}
