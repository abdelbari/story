package app.morpho.converter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morpho.engine.ooxml.DocxWriter

private val inputMimeTypes = arrayOf(
    "text/plain",
    "text/markdown",
    "text/*",
    "application/pdf",
)

@Composable
fun HomeScreen(viewModel: ConvertViewModel) {
    val state by viewModel.state.collectAsState()

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onPicked(uri) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DocxWriter.MIME_TYPE)
    ) { uri -> viewModel.onSaveTarget(uri) }

    LaunchedEffect(state) {
        val ready = state as? ConvertUiState.ReadyToSave ?: return@LaunchedEffect
        saveLauncher.launch(ready.suggestedName)
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
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = stringResource(R.string.privacy_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

        is ConvertUiState.Converting, is ConvertUiState.ReadyToSave -> {
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
        }

        is ConvertUiState.Failed ->
            Text(
                text = stringResource(state.reason.messageRes()),
                color = MaterialTheme.colorScheme.error,
            )
    }
}

@Composable
private fun StateActions(
    state: ConvertUiState,
    onPick: () -> Unit,
    onConvert: () -> Unit,
) {
    when (state) {
        is ConvertUiState.Idle ->
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_document))
            }

        is ConvertUiState.Picked -> {
            Button(onClick = onConvert, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.convert_to_docx))
            }
            TextButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_other))
            }
        }

        is ConvertUiState.Saved ->
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.convert_another))
            }

        is ConvertUiState.Failed -> {
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pick_document))
            }
            if (state.reason == FailReason.READ_ERROR || state.reason == FailReason.WRITE_ERROR) {
                TextButton(onClick = onConvert, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        else -> Unit
    }
}

private fun FailReason.messageRes(): Int = when (this) {
    FailReason.UNSUPPORTED_TYPE -> R.string.unsupported_type
    FailReason.PDF_NOT_YET -> R.string.pdf_not_yet
    FailReason.READ_ERROR -> R.string.read_error
    FailReason.WRITE_ERROR -> R.string.write_error
}
