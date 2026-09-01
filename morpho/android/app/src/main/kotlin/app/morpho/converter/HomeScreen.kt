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
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.morpho.engine.ooxml.DocxWriter

private val inputMimeTypes = arrayOf(
    "text/plain",
    "text/markdown",
    "text/*",
    DocxWriter.MIME_TYPE,
    "application/pdf",
)

@Composable
fun HomeScreen(viewModel: ConvertViewModel) {
    val state by viewModel.state.collectAsState()
    val review by viewModel.review.collectAsState()

    // Local state: which screen is showing is not worth surviving process
    // death, unlike a conversion.
    var showAbout by remember { mutableStateOf(false) }

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
                launcher.launch(current.suggestedName)
                // Leave ReadyToSave immediately: recreation (rotation, process
                // restore) must not launch a second save dialog over the open one.
                viewModel.onSaveDialogLaunched()
            }
            is ConvertUiState.ReadyToPrint -> {
                printLauncher.print(current.html, current.jobName)
                viewModel.onPrintHandedOff()
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
                        onExportPdf = viewModel::exportPdf,
                        onPrint = viewModel::printPdf,
                        onRetry = viewModel::retry,
                        onOcr = viewModel::convertWithOcr,
                        onReview = viewModel::showReview,
                        onCancel = viewModel::cancelOcr,
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
    onExportPdf: () -> Unit,
    onPrint: () -> Unit,
    onRetry: () -> Unit,
    onOcr: () -> Unit,
    onReview: () -> Unit,
    onCancel: () -> Unit,
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
            if (!state.isPdf) {
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

private fun FailReason.messageRes(): Int = when (this) {
    FailReason.UNSUPPORTED_TYPE -> R.string.unsupported_type
    FailReason.SCANNED_PDF -> R.string.scanned_pdf
    FailReason.ENCRYPTED_PDF -> R.string.encrypted_pdf
    FailReason.OCR_EMPTY -> R.string.ocr_empty
    FailReason.TOO_LARGE -> R.string.too_large
    FailReason.READ_ERROR -> R.string.read_error
    FailReason.WRITE_ERROR -> R.string.write_error
}
