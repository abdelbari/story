package app.morpho.converter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BorderAll
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.morpho.engine.layout.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The editor: the document itself, laid out and editable, with a bar of
 * the tools every word processor has above the keyboard — the looks,
 * the type, the paragraph, the lists, what can be put in — and a
 * second row of table tools when the caret stands in a cell. The page
 * inside is the engine's ([app.morpho.engine.layout.HtmlWriter.writeEditor]);
 * every tap on a tool is a call into the page's script, which sends
 * the engine an operation and paints the reply, so there is one path
 * for every edit whether a key or a button made it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    session: ConvertViewModel.EditorSession,
    onDone: () -> Unit,
    onSave: () -> Unit,
) {
    BackHandler(onBack = onDone)
    val status by session.bridge.status.collectAsState()
    val tapped by session.bridge.tapped.collectAsState()
    var web by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    /** A call on the page's own API, which is what a toolbar calls. */
    fun call(expression: String) {
        web?.evaluateJavascript("window.morphoEditor.$expression", null)
    }

    var finding by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var commenting by remember { mutableStateOf(false) }
    var tabling by remember { mutableStateOf(false) }
    var counting by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) session.pendingImage = uri
    }
    // A picture picked is read and put in off the main thread, at a
    // document's size rather than a camera's.
    val pending = session.pendingImage
    if (pending != null) {
        LaunchedEffect(pending) {
            val picture = withContext(Dispatchers.IO) { documentPicture(context, pending) }
            session.pendingImage = null
            if (picture != null) call("insertImage(${Json.write(picture.base64)},${Json.write(picture.mimeType)},${picture.width},${picture.height},null)")
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(session.fileName, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_done)) }
            },
            actions = {
                IconButton(onClick = { call("undo()") }, enabled = status.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.editor_undo)) }
                IconButton(onClick = { call("redo()") }, enabled = status.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.editor_redo)) }
                IconButton(onClick = { finding = !finding }) { Icon(Icons.Filled.Search, stringResource(R.string.editor_find)) }
                if (session.doubtful > 0) {
                    IconButton(onClick = { call("nextDoubtful()") }) { Icon(Icons.Filled.Warning, stringResource(R.string.editor_doubtful)) }
                }
                IconButton(onClick = onSave) { Icon(Icons.Filled.Done, stringResource(R.string.editor_save)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.editor_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_word_count)) },
                            onClick = {
                                menu = false
                                val count = session.bridge.ask("""{"op":"count"}""")?.get("count") as? Map<*, *>
                                if (count != null) {
                                    counting = context.getString(
                                        R.string.editor_count_body,
                                        (count["words"] as? Double)?.toInt() ?: 0,
                                        (count["characters"] as? Double)?.toInt() ?: 0,
                                        (count["paragraphs"] as? Double)?.toInt() ?: 0,
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_superscript)) }, onClick = { menu = false; call("format({superscript:${!status.superscript}})") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_subscript)) }, onClick = { menu = false; call("format({subscript:${!status.subscript}})") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_direction_rtl)) }, onClick = { menu = false; call("restyle({direction:'RTL'})") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_direction_ltr)) }, onClick = { menu = false; call("restyle({direction:'LTR'})") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_link_remove)) }, onClick = { menu = false; call("link(null)") })
                    }
                }
            },
        )
        if (finding) {
            FindBar(
                onFind = { query -> (session.bridge.ask("""{"op":"find","query":${Json.write(query)},"ignoreCase":true}""")?.get("matches") as? List<*>).orEmpty() },
                onSelect = { anchor, focus -> call("select(${Json.write(anchor)},${Json.write(focus)})") },
                onReplaceAll = { query, replacement -> call("replaceAll(${Json.write(query)},${Json.write(replacement)},true)") },
                onClose = { finding = false },
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx -> editorWebView(ctx, session).also { web = it } },
        )
        for (note in status.comments) {
            CommentStrip(note, onDelete = { call("uncomment(${note.id})") })
        }
        HorizontalDivider()
        if (status.table != null) {
            TableTools(status, ::call)
            HorizontalDivider()
        }
        Toolbar(
            status = status,
            call = ::call,
            onLink = { linking = true },
            onComment = { commenting = true },
            onTable = { tabling = true },
            onImage = { pickImage.launch(arrayOf("image/*")) },
        )
    }

    if (linking) {
        LinkDialog(
            needsText = status.collapsed,
            current = status.link,
            onLink = { url, text ->
                linking = false
                call(if (text == null) "link(${Json.write(url)})" else "link(${Json.write(url)},${Json.write(text)})")
            },
            onDismiss = { linking = false },
        )
    }
    if (commenting) {
        TextDialog(
            title = stringResource(R.string.editor_comment),
            label = stringResource(R.string.editor_comment_text),
            confirm = stringResource(R.string.editor_comment),
            onConfirm = { text -> commenting = false; if (text.isNotBlank()) call("comment(${Json.write(text)})") },
            onDismiss = { commenting = false },
        )
    }
    if (tabling) {
        TableDialog(onInsert = { rows, columns -> tabling = false; call("insertTable($rows,$columns)") }, onDismiss = { tabling = false })
    }
    val tap = tapped
    if (tap != null) {
        ImageDialog(
            tap = tap,
            onDescribe = { alt -> session.bridge.dismissTap(); call("describeImage(${tap.block},${Json.write(alt)})") },
            onRemove = { session.bridge.dismissTap(); call("removeBlock(${tap.block})") },
            onDismiss = { session.bridge.dismissTap() },
        )
    }
    val count = counting
    if (count != null) {
        AlertDialog(
            onDismissRequest = { counting = null },
            title = { Text(stringResource(R.string.editor_word_count)) },
            text = { Text(count) },
            confirmButton = { TextButton(onClick = { counting = null }) { Text(stringResource(R.string.review_words_keep)) } },
        )
    }
}

/**
 * The WebView the editor is: script on, since the page is one, and
 * every other door shut — no file, no content, no window, no address
 * followed, no resource fetched — behind a page whose own policy allows
 * no source at all, in an app with no network permission to reach one.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun editorWebView(context: Context, session: ConvertViewModel.EditorSession): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.domStorageEnabled = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.setGeolocationEnabled(false)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
    addJavascriptInterface(session.bridge, "Morpho")
    loadDataWithBaseURL(null, session.html, "text/html", "utf-8", null)
}

@Composable
private fun Toolbar(
    status: EditorStatus,
    call: (String) -> Unit,
    onLink: () -> Unit,
    onComment: () -> Unit,
    onTable: () -> Unit,
    onImage: () -> Unit,
) {
    var sizes by remember { mutableStateOf(false) }
    var fonts by remember { mutableStateOf(false) }
    var styles by remember { mutableStateOf(false) }
    var colours by remember { mutableStateOf(false) }
    var highlights by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tool(Icons.Filled.FormatBold, R.string.editor_bold, active = status.bold) { call("format({bold:${!status.bold}})") }
        Tool(Icons.Filled.FormatItalic, R.string.editor_italic, active = status.italic) { call("format({italic:${!status.italic}})") }
        Tool(Icons.Filled.FormatUnderlined, R.string.editor_underline, active = status.underline) { call("format({underline:${!status.underline}})") }
        Tool(Icons.Filled.FormatStrikethrough, R.string.editor_strike, active = status.strikethrough) { call("format({strikethrough:${!status.strikethrough}})") }
        Box {
            Tool(Icons.Filled.FormatColorText, R.string.editor_text_color, active = status.colorRgb != null) { colours = true }
            Palette(expanded = colours, onDismiss = { colours = false }) { rgb -> call("format({colorRgb:${rgb ?: "null"}})") }
        }
        Box {
            Tool(Icons.Filled.FormatColorFill, R.string.editor_highlight, active = status.highlightRgb != null) { highlights = true }
            Palette(expanded = highlights, onDismiss = { highlights = false }) { rgb -> call("format({highlightRgb:${rgb ?: "null"}})") }
        }
        Box {
            Tool(Icons.Filled.FormatSize, R.string.editor_font_size) { sizes = true }
            DropdownMenu(expanded = sizes, onDismissRequest = { sizes = false }) {
                for (size in SIZES) {
                    DropdownMenuItem(
                        text = { Text(if (status.fontSizePt == size.toFloat()) "$size ✓" else "$size") },
                        onClick = { sizes = false; call("format({fontSizePt:$size})") },
                    )
                }
            }
        }
        Box {
            Tool(Icons.Filled.TextFields, R.string.editor_font) { fonts = true }
            DropdownMenu(expanded = fonts, onDismissRequest = { fonts = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.editor_font_default)) }, onClick = { fonts = false; call("format({fontFamily:null})") })
                for (family in FAMILIES) {
                    DropdownMenuItem(
                        text = { Text(if (status.fontFamily == family) "$family ✓" else family) },
                        onClick = { fonts = false; call("format({fontFamily:${Json.write(family)}})") },
                    )
                }
            }
        }
        Box {
            Tool(Icons.Filled.Title, R.string.editor_style, active = status.kind != "BODY") { styles = true }
            DropdownMenu(expanded = styles, onDismissRequest = { styles = false }) {
                for ((kind, label) in KINDS) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label) + if (status.kind == kind) " ✓" else "") },
                        onClick = { styles = false; call("restyle({kind:'$kind'})") },
                    )
                }
            }
        }
        Tool(Icons.AutoMirrored.Filled.FormatAlignLeft, R.string.editor_align_start, active = status.alignment == "START") { call("restyle({alignment:'START'})") }
        Tool(Icons.Filled.FormatAlignCenter, R.string.editor_align_center, active = status.alignment == "CENTER") { call("restyle({alignment:${if (status.alignment == "CENTER") "null" else "'CENTER'"}})") }
        Tool(Icons.AutoMirrored.Filled.FormatAlignRight, R.string.editor_align_end, active = status.alignment == "END") { call("restyle({alignment:'END'})") }
        Tool(Icons.Filled.FormatAlignJustify, R.string.editor_justify, active = status.alignment == "JUSTIFY") { call("restyle({alignment:${if (status.alignment == "JUSTIFY") "null" else "'JUSTIFY'"}})") }
        Tool(Icons.AutoMirrored.Filled.FormatListBulleted, R.string.editor_bullets, active = status.listMarker == "BULLET") { call("restyle({listMarker:${if (status.listMarker == "BULLET") "null" else "'BULLET'"}})") }
        Tool(Icons.Filled.FormatListNumbered, R.string.editor_numbers, active = status.listMarker == "NUMBERED") { call("restyle({listMarker:${if (status.listMarker == "NUMBERED") "null" else "'NUMBERED'"}})") }
        Tool(Icons.AutoMirrored.Filled.FormatIndentIncrease, R.string.editor_indent, enabled = status.listMarker != null) { call("restyle({listLevel:${(status.listLevel + 1).coerceAtMost(8)}})") }
        Tool(Icons.AutoMirrored.Filled.FormatIndentDecrease, R.string.editor_outdent, enabled = status.listMarker != null && status.listLevel > 0) { call("restyle({listLevel:${status.listLevel - 1}})") }
        Tool(Icons.Filled.Link, R.string.editor_link, active = status.link != null, onClick = onLink)
        Tool(Icons.Filled.Comment, R.string.editor_comment, enabled = !status.collapsed, onClick = onComment)
        Tool(Icons.Filled.TableChart, R.string.editor_table, onClick = onTable)
        Tool(Icons.Filled.Image, R.string.editor_image, onClick = onImage)
    }
}

/** The tools for the table the caret stands in. */
@Composable
private fun TableTools(status: EditorStatus, call: (String) -> Unit) {
    var shading by remember { mutableStateOf(false) }
    val table = status.table ?: return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { call("insertRow(false)") }) { Text(stringResource(R.string.editor_row_above)) }
        TextButton(onClick = { call("insertRow(true)") }) { Text(stringResource(R.string.editor_row_below)) }
        TextButton(onClick = { call("deleteRow()") }) { Text(stringResource(R.string.editor_row_delete)) }
        TextButton(onClick = { call("insertColumn(false)") }) { Text(stringResource(R.string.editor_col_before)) }
        TextButton(onClick = { call("insertColumn(true)") }) { Text(stringResource(R.string.editor_col_after)) }
        TextButton(onClick = { call("deleteColumn()") }) { Text(stringResource(R.string.editor_col_delete)) }
        Tool(Icons.Filled.CallMerge, R.string.editor_merge, enabled = status.canMerge) { call("mergeCells()") }
        Tool(Icons.Filled.CallSplit, R.string.editor_split, enabled = status.canSplit) { call("splitCell()") }
        Box {
            Tool(Icons.Filled.FormatColorFill, R.string.editor_shade, active = table.shadingRgb != null) { shading = true }
            Palette(expanded = shading, onDismiss = { shading = false }) { rgb -> call("shadeCells(${rgb ?: "null"})") }
        }
        Tool(Icons.Filled.BorderAll, R.string.editor_rules, active = table.ruled) { call("ruleTable(${!table.ruled})") }
        TextButton(onClick = { call("headRow(${!table.headRow})") }) {
            Text(stringResource(R.string.editor_head_row) + if (table.headRow) " ✓" else "")
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: Int, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = stringResource(label), tint = tint)
    }
}

/** A dozen colours and none, as every editor's palette has. */
@Composable
private fun Palette(expanded: Boolean, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in COLOURS.chunked(6)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (rgb in row) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF000000.toInt() or rgb), CircleShape)
                                .clickable { onDismiss(); onPick(rgb) },
                        )
                    }
                }
            }
            TextButton(onClick = { onDismiss(); onPick(null) }) { Text(stringResource(R.string.editor_color_none)) }
        }
    }
}

@Composable
private fun CommentStrip(note: EditorComment, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = listOfNotNull(note.author, note.text).joinToString(": "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.editor_comment_remove)) }
        }
    }
}

@Composable
private fun FindBar(
    onFind: (String) -> List<*>,
    onSelect: (List<Int>, List<Int>) -> Unit,
    onReplaceAll: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Pair<List<Int>, List<Int>>>>(emptyList()) }
    var at by remember { mutableIntStateOf(-1) }
    fun go(step: Int) {
        if (query.isEmpty()) return
        matches = onFind(query).mapNotNull { match ->
            val ends = match as? List<*> ?: return@mapNotNull null
            val anchor = (ends.getOrNull(0) as? List<*>)?.map { (it as? Double)?.toInt() ?: return@mapNotNull null } ?: return@mapNotNull null
            val focus = (ends.getOrNull(1) as? List<*>)?.map { (it as? Double)?.toInt() ?: return@mapNotNull null } ?: return@mapNotNull null
            anchor to focus
        }
        if (matches.isEmpty()) { at = -1; return }
        at = ((at + step) % matches.size + matches.size) % matches.size
        onSelect(matches[at].first, matches[at].second)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = query, onValueChange = { query = it; at = -1 }, label = { Text(stringResource(R.string.editor_find_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { go(-1) }) { Text(stringResource(R.string.editor_previous)) }
                TextButton(onClick = { go(1) }) { Text(stringResource(R.string.editor_next)) }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Done, stringResource(R.string.review_close)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = replacement, onValueChange = { replacement = it }, label = { Text(stringResource(R.string.editor_replace_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (query.isNotEmpty()) { onReplaceAll(query, replacement); matches = emptyList(); at = -1 } }) { Text(stringResource(R.string.editor_replace_all)) }
            }
            Text(
                text = if (query.isEmpty()) "" else if (matches.isEmpty() && at == -1) "" else if (matches.isEmpty()) stringResource(R.string.editor_no_matches) else stringResource(R.string.editor_matches, matches.size),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun LinkDialog(needsText: Boolean, current: String?, onLink: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(current.orEmpty()) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.editor_link_url)) }, singleLine = true)
                if (needsText) OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.editor_link_text)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (url.isNotBlank()) onLink(url.trim(), if (needsText) text.ifBlank { null } else null) }) { Text(stringResource(R.string.editor_link)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TextDialog(title: String, label: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TableDialog(onInsert: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf("3") }
    var columns by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_table)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = rows, onValueChange = { rows = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.editor_table_rows)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = columns, onValueChange = { columns = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.editor_table_columns)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
        },
        confirmButton = {
            TextButton(onClick = { onInsert((rows.toIntOrNull() ?: 3).coerceIn(1, 64), (columns.toIntOrNull() ?: 3).coerceIn(1, 64)) }) { Text(stringResource(R.string.editor_insert)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ImageDialog(tap: EditorTap, onDescribe: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var alt by remember(tap) { mutableStateOf(tap.alt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_image)) },
        text = { OutlinedTextField(value = alt, onValueChange = { alt = it }, label = { Text(stringResource(R.string.editor_image_alt)) }) },
        confirmButton = { TextButton(onClick = { onDescribe(alt) }) { Text(stringResource(R.string.review_words_keep)) } },
        dismissButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.editor_image_remove)) } },
    )
}

/** A picture read from the device at a document's size, encoded for the page. */
private class DocumentPicture(val base64: String, val mimeType: String, val width: Int, val height: Int)

/**
 * [uri] decoded and, where it is a camera's, brought down to a size a
 * document holds — the longest side at most [LONGEST_SIDE] — since a
 * photograph of forty megapixels in a Word file is a file nobody can
 * send. Null where nothing on the device decodes it.
 */
private fun documentPicture(context: Context, uri: Uri): DocumentPicture? {
    val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
    val mime = bounds.outMimeType?.lowercase().orEmpty()
    val small = bounds.outWidth <= LONGEST_SIDE && bounds.outHeight <= LONGEST_SIDE && bytes.size <= MOST_BYTES
    if (small && (mime == "image/png" || mime == "image/jpeg" || mime == "image/gif" || mime == "image/webp")) {
        return DocumentPicture(Base64.getEncoder().encodeToString(bytes), mime, bounds.outWidth, bounds.outHeight)
    }
    var sample = 1
    while (bounds.outWidth / sample > LONGEST_SIDE * 2 || bounds.outHeight / sample > LONGEST_SIDE * 2) sample *= 2
    val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull() ?: return null
    val scale = minOf(1f, LONGEST_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
    val fitted = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
    val out = ByteArrayOutputStream()
    val png = mime == "image/png" && fitted.hasAlpha()
    fitted.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, out)
    val picture = DocumentPicture(Base64.getEncoder().encodeToString(out.toByteArray()), if (png) "image/png" else "image/jpeg", fitted.width, fitted.height)
    if (fitted !== decoded) fitted.recycle()
    decoded.recycle()
    return picture
}

private const val LONGEST_SIDE = 1600
private const val MOST_BYTES = 1_500_000

private val SIZES = listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48)
private val FAMILIES = listOf("Noto Naskh Arabic", "Noto Sans Arabic", "Roboto", "serif", "sans-serif", "monospace")
private val KINDS = listOf(
    "BODY" to R.string.editor_style_body,
    "TITLE" to R.string.editor_style_title,
    "HEADING_1" to R.string.editor_h1,
    "HEADING_2" to R.string.editor_h2,
    "HEADING_3" to R.string.editor_h3,
)
private val COLOURS = listOf(0x000000, 0x5F6368, 0x9AA0A6, 0xFFFFFF, 0xC5221F, 0xE8710A, 0xF9AB00, 0x188038, 0x1A73E8, 0x8430CE, 0x795548, 0xF28B82)
