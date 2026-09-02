package app.morpho.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The converted document, shown the moment a conversion finishes and before
 * anything is written to disk.
 *
 * Until now the app handed over a file blind: the only way to know whether
 * Arabic had come out backwards was to save, open Word, and look. Three
 * rounds of that on one document is what this screen exists to end.
 *
 * What is shown is pages — the document laid out on its own sheet by
 * [PdfFileExporter], the same layout the app writes when it makes a PDF,
 * drawn one page at a time as the reader scrolls. A web page in a box is
 * not what a document looks like, and the reader who compares this with
 * Word or the original PDF is comparing pages. The platform's own text
 * stack draws them, so Arabic is shaped and ordered by the same code that
 * draws every other app on the phone.
 *
 * Should that rendering fail for a document, the print path's HTML stands
 * in, on a WebView with nothing enabled that a static page does not need:
 * no script, no file or content access. The app has no network permission
 * to reach anything anyway.
 */
@Composable
fun PreviewScreen(
    pdf: ByteArray,
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

        val pageModifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        if (pdf.isNotEmpty()) {
            PagedPreview(pdf, pageModifier)
        } else {
            HtmlPreview(html, pageModifier)
        }

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save_file))
        }
        TextButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.review_open))
        }
    }
}

/** The document as a column of pages, each drawn when it scrolls into view. */
@Composable
private fun PagedPreview(pdf: ByteArray, modifier: Modifier) {
    val context = LocalContext.current
    val document = remember(pdf) { PreviewDocument.open(context, pdf) }
    DisposableEffect(document) {
        onDispose { document?.close() }
    }
    if (document == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.preview_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val widthPx = with(LocalDensity.current) { (maxWidth - 24.dp).roundToPx() }.coerceAtLeast(1)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items((0 until document.pageCount).toList(), key = { it }) { index ->
                PreviewPage(document, index, widthPx)
            }
        }
    }
}

@Composable
private fun PreviewPage(document: PreviewDocument, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, document, index, widthPx) {
        // Drawn off the main thread; assigned here, where lint can see the
        // producer set its value.
        val drawn = withContext(Dispatchers.IO) { document.render(index, widthPx) }
        value = drawn
    }
    val label = stringResource(R.string.preview_page, index + 1, document.pageCount)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(document.aspectRatio)
            .shadow(2.dp)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val page = bitmap
        if (page != null) {
            Image(
                bitmap = page.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The print path's page, for a document the page renderer could not draw. */
@Composable
private fun HtmlPreview(html: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
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
}

/**
 * A PDF open for drawing. The platform renderer wants a seekable file, so
 * the bytes go to the app's cache for as long as the screen is open; it
 * draws one page at a time and is not thread-safe, so pages are drawn
 * under a lock.
 */
private class PreviewDocument private constructor(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount: Int = renderer.pageCount

    /** Width over height of the first page — the placeholder shape for every page. */
    val aspectRatio: Float = renderer.openPage(0).use { page ->
        if (page.height > 0) page.width.toFloat() / page.height else A4_ASPECT
    }

    private val lock = Mutex()

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        runCatching {
            renderer.openPage(index).use { page ->
                val heightPx = (widthPx.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }.getOrNull()
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        file.delete()
    }

    companion object {
        private const val A4_ASPECT = 595f / 842f

        fun open(context: Context, pdf: ByteArray): PreviewDocument? = runCatching {
            val file = File.createTempFile("preview", ".pdf", context.cacheDir)
            file.writeBytes(pdf)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: Exception) {
                descriptor.close()
                file.delete()
                throw e
            }
            if (renderer.pageCount == 0) {
                renderer.close()
                descriptor.close()
                file.delete()
                return null
            }
            PreviewDocument(file, descriptor, renderer)
        }.getOrNull()
    }
}
