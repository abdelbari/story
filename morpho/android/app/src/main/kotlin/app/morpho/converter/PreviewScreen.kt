package app.morpho.converter

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The converted document, shown the moment a conversion finishes and before
 * anything is written to disk.
 *
 * Until now the app handed over a file blind: the only way to know whether
 * Arabic had come out backwards was to save, open Word, and look. Three
 * rounds of that on one document is what this screen exists to end. What is
 * shown is the same HTML the print path renders — one rendering of the
 * document model, so what the reader sees here is what the file holds.
 *
 * Nothing is enabled on the WebView that a static page does not need: no
 * script, no file or content access. The page is a string handed in
 * directly, and the app has no network permission to reach anything anyway.
 */
@Composable
fun PreviewScreen(
    html: String,
    fileName: String,
    onSave: () -> Unit,
    onReview: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.preview_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.review_close))
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = WebViewClient()
                    tag = html
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            update = { view ->
                // Reload only when the document changed — a correction from
                // Review Mode — not on every recomposition.
                if (view.tag != html) {
                    view.tag = html
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
        )

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save_file))
        }
        TextButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.review_open))
        }
    }
}
