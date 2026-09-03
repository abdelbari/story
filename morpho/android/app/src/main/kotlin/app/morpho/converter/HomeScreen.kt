package app.morpho.converter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.morpho.engine.layout.DocumentFormats
import app.morpho.engine.ooxml.DocxWriter

// The types the picker offers, off the same list the converter decides by:
// a type offered here and refused there wastes the reader's pick, and one
// read there and not offered here is a file they are never shown.
private val inputMimeTypes = DocumentFormats.PICKABLE_MIME_TYPES.toTypedArray()

@Composable
fun HomeScreen(viewModel: ConvertViewModel) {
    val state by viewModel.state.collectAsState()
    val review by viewModel.review.collectAsState()

    // Local state: which screen is showing is not worth surviving process
    // death, unlike a conversion.
    var showAbout by remember { mutableStateOf(false) }
    // Opens on its own when a conversion finishes. Keyed on being in the
    // Converted state, so closing it for this document does not keep it
    // closed for the next one.
    var showPreview by remember(state is ConvertUiState.Converted) { mutableStateOf(true) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onPicked(uri) }

    val saveDocxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DocxWriter.MIME_TYPE)
    ) { uri -> viewModel.onSaveTarget(uri) }

    val saveMarkdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConvertViewModel.MARKDOWN_MIME)
    ) { uri -> viewModel.onSaveTarget(uri) }

    val savePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConvertViewModel.PDF_MIME)
    ) { uri -> viewModel.onSaveTarget(uri) }

    val context = LocalContext.current
    val printLauncher = remember { PdfPrintLauncher(context) }

    LaunchedEffect(state) {
        when (val current = state) {
            is ConvertUiState.ReadyToSave -> {
                val launcher = when (current.mimeType) {
                    ConvertViewModel.MARKDOWN_MIME -> saveMarkdownLauncher
                    ConvertViewModel.PDF_MIME -> savePdfLauncher
                    else -> saveDocxLauncher
                }
                // A device with no document picker, or a disabled WebView
                // package, throws here — and an exception escaping a
                // LaunchedEffect takes the whole composition down with it.
                val launched = runCatching { launcher.launch(current.suggestedName) }.isSuccess
                if (launched) {
                    // Leave ReadyToSave immediately: recreation (rotation,
                    // process restore) must not launch a second dialog over
                    // the open one.
                    viewModel.onSaveDialogLaunched()
                } else {
                    viewModel.onSystemUiUnavailable()
                }
            }
            is ConvertUiState.ReadyToPrint -> {
                val printed = runCatching {
                    printLauncher.print(current.html, current.jobName)
                }.isSuccess
                if (printed) viewModel.onPrintHandedOff() else viewModel.onSystemUiUnavailable()
            }
            else -> {}
        }
    }

    // Below the launchers and the save effect on purpose: a conversion that
    // finishes while one of these screens is open still gets its save dialog.
    if (showAbout) {
        AboutScreen(onClose = { showAbout = false })
        return
    }

    val openReview = review
    if (openReview != null) {
        ReviewScreen(
            state = openReview,
            onReclassify = viewModel::reclassify,
            onSaveCorrected = viewModel::saveCorrected,
            onClose = viewModel::hideReview,
        )
        return
    }

    // After Review on purpose: Review Mode opened from the preview shows on
    // top of it, and closing Review lands back on the preview.
    val converted = state as? ConvertUiState.Converted
    if (converted != null && showPreview) {
        PreviewScreen(
            pdf = converted.previewPdf,
            html = converted.previewHtml,
            fileName = converted.suggestedName,
            onSave = viewModel::requestSave,
            onReview = viewModel::showReview,
            onClose = { showPreview = false },
        )
        return
    }

    // Local state again: which pages to convert is a question asked of one
    // document, not something worth surviving process death.
    var askingPages by remember(state) { mutableStateOf(false) }
    if (askingPages) {
        PagesDialog(
            onConvert = { pages ->
                askingPages = false
                viewModel.convertPages(pages)
            },
            onDismiss = { askingPages = false },
        )
    }

    val locked = state as? ConvertUiState.NeedsPassword
    if (locked != null) {
        PasswordDialog(
            fileName = locked.fileName,
            wrongPassword = locked.wrongPassword,
            onUnlock = viewModel::unlock,
            onDismiss = viewModel::cancelUnlock,
        )
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(24.dp))

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StateContent(state)
                    StateActions(
                        state = state,
                        onPick = { openLauncher.launch(inputMimeTypes) },
                        onConvert = viewModel::convert,
                        onConvertToMarkdown = viewModel::convertToMarkdown,
                        onExportPdf = viewModel::exportPdf,
                        onPrint = viewModel::printPdf,
                        onRetry = viewModel::retry,
                        onOcr = viewModel::convertWithOcr,
                        onReview = viewModel::showReview,
                        onCancel = viewModel::cancelConversion,
                        onSave = viewModel::requestSave,
                        onPreview = { showPreview = true },
                        onChoosePages = { askingPages = true },
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = stringResource(R.string.privacy_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showAbout = true }) {
                Text(stringResource(R.string.about_open))
            }
        }
    }
}

@Composable
private fun StateContent(state: ConvertUiState) {
    when (state) {
        is ConvertUiState.Idle ->
            Text(stringResource(R.string.empty_hint))

        is ConvertUiState.Picked ->
            Text(state.fileName, style = MaterialTheme.typography.titleMedium)

        is ConvertUiState.NeedsPassword -> {
            Text(state.fileName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.password_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ConvertUiState.Converting -> {
            if (state.pageCount > 0) {
                // page is the one being read, so pages *finished* is one
                // less: the bar should not sit at 100% during the last page.
                PageProgress((state.page - 1).coerceAtLeast(0).toFloat() / state.pageCount)
                Text(stringResource(R.string.converting_pages, state.page, state.pageCount))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.converting))
            }
        }

        is ConvertUiState.ReadyToSave, is ConvertUiState.AwaitingSave,
        is ConvertUiState.ReadyToPrint -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.converting))
        }

        is ConvertUiState.Converted -> {
            Text(
                text = stringResource(R.string.converted_ready),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(state.suggestedName)
            if (state.needsReview) {
                Text(
                    text = stringResource(R.string.fidelity_review),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ConvertUiState.Saved -> {
            Text(
                text = stringResource(R.string.saved_ok),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(state.fileName)
            if (state.needsReview) {
                // The Fidelity Report's honesty, surfaced: reconstructed or
                // OCR-read blocks are guesses, and the user should know.
                Text(
                    text = stringResource(R.string.fidelity_review),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ConvertUiState.Failed ->
            Text(
                text = stringResource(state.reason.messageRes()),
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/** How far through a page-by-page job we are, drawn without a determinate
 *  indicator so the API stays stable across Material 3 versions. */
@Composable
private fun PageProgress(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun StateActions(
    state: ConvertUiState,
    onPick: () -> Unit,
    onConvert: () -> Unit,
    onConvertToMarkdown: () -> Unit,
    onExportPdf: () -> Unit,
    onPrint: () -> Unit,
    onRetry: () -> Unit,
    onOcr: () -> Unit,
    onReview: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onPreview: () -> Unit,
    onChoosePages: () -> Unit,
) {
    when (state) {
        is ConvertUiState.Idle ->
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_document))
            }

        // Only OCR reports pages, and only OCR can be stopped between them —
        // so the button appears exactly when it would do something.
        is ConvertUiState.Converting ->
            if (state.pageCount > 0) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel))
                }
            }

        is ConvertUiState.Picked -> {
            Button(onClick = onConvert, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (state.isWordDocument) R.string.convert_to_markdown
                        else R.string.convert_to_docx
                    )
                )
            }
            if (state.isPdf) {
                // A PDF is wanted as Markdown as often as as Word — for a
                // notebook, a repository, a site — and going by way of
                // Word to get there is two conversions and two files.
                TextButton(onClick = onConvertToMarkdown, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.convert_to_markdown))
                }
                TextButton(onClick = onChoosePages, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pages_choose))
                }
            } else {
                TextButton(onClick = onExportPdf, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.convert_to_pdf))
                }
                TextButton(onClick = onPrint, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.print_document))
                }
            }
            TextButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_other))
            }
        }

        is ConvertUiState.Converted -> {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save_file))
            }
            TextButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.preview_open))
            }
            TextButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.review_open))
            }
            TextButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_other))
            }
        }

        is ConvertUiState.Saved -> {
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.convert_another))
            }
            TextButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.review_open))
            }
        }

        is ConvertUiState.Failed -> {
            if (state.reason == FailReason.SCANNED_PDF) {
                Button(onClick = onOcr, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.convert_with_ocr))
                }
            }
            // A document too large for this phone is not a document that
            // cannot be converted: part of it can.
            if (state.reason == FailReason.TOO_LARGE) {
                Button(onClick = onChoosePages, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pages_choose))
                }
            }
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_document))
            }
            if (state.reason == FailReason.READ_ERROR || state.reason == FailReason.WRITE_ERROR) {
                // Repeats whichever conversion failed — a failed PDF export
                // must not retry as a Word conversion.
                TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        else -> Unit
    }
}

/**
 * Asks for the password of a locked PDF. What is typed opens the document
 * on this phone for this conversion and is kept nowhere — the app has no
 * network permission to send it anywhere even if it wanted to.
 */
@Composable
private fun PasswordDialog(
    fileName: String,
    wrongPassword: Boolean,
    onUnlock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.password_body, fileName))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    isError = wrongPassword,
                    visualTransformation =
                        if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (password.isNotEmpty()) onUnlock(password) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (wrongPassword) {
                    Text(
                        text = stringResource(R.string.password_wrong),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { shown = !shown }) {
                    Text(
                        stringResource(
                            if (shown) R.string.password_hide else R.string.password_show
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUnlock(password) },
                enabled = password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.password_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Asks which pages to convert. A reader who wants one chapter of a book,
 * or one part of a document too large for the phone to hold whole, says
 * so here; an empty box is the whole document, which is what converting a
 * file means.
 */
@Composable
private fun PagesDialog(
    onConvert: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pages by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pages_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pages_body))
                OutlinedTextField(
                    value = pages,
                    onValueChange = { pages = it },
                    label = { Text(stringResource(R.string.pages_label)) },
                    singleLine = true,
                    // Text rather than a number pad: a range is two numbers
                    // with a dash between them, and a keyboard of digits
                    // alone has no dash on it.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConvert(pages) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConvert(pages) }) {
                Text(stringResource(R.string.pages_convert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun FailReason.messageRes(): Int = when (this) {
    FailReason.UNSUPPORTED_TYPE -> R.string.unsupported_type
    FailReason.SCANNED_PDF -> R.string.scanned_pdf
    FailReason.OCR_EMPTY -> R.string.ocr_empty
    FailReason.TOO_LARGE -> R.string.too_large
    FailReason.READ_ERROR -> R.string.read_error
    FailReason.WRITE_ERROR -> R.string.write_error
}
