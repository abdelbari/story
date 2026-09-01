package app.morpho.converter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * About and open-source attribution. The Apache License requires that a copy
 * travel with the software, and an app with no network permission cannot very
 * well link to one — so the full text ships as a raw resource and is shown
 * here, along with what each dependency is and the privacy guarantee stated
 * plainly.
 */
@Composable
fun AboutScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val license = remember {
        runCatching {
            context.resources.openRawResource(R.raw.apache_license_2_0)
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The activity is edge-to-edge, and this screen has no Scaffold
            // to inset it: without this the header sits under the status bar.
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.review_close)) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_privacy_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.about_privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                text = stringResource(R.string.about_components_title),
                style = MaterialTheme.typography.titleMedium,
            )
            for (component in COMPONENTS) {
                Text(text = component, style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = stringResource(R.string.about_license_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = license,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Attribution is a legal requirement, not a courtesy, so the list is written
 * out rather than generated: everything here is Apache-2.0, whose text
 * follows in full.
 */
private val COMPONENTS = listOf(
    "Tesseract4Android — Adaptech s.r.o. (Apache License 2.0)",
    "Tesseract OCR and its trained language data — Google Inc. and contributors (Apache License 2.0)",
    "PDFBox for Android — Tom Roush, from Apache PDFBox (Apache License 2.0)",
    "Jetpack Compose, AndroidX and Material Components — The Android Open Source Project (Apache License 2.0)",
    "Kotlin and kotlinx.coroutines — JetBrains s.r.o. (Apache License 2.0)",
)
