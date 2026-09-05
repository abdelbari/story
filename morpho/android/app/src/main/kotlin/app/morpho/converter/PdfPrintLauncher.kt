package app.morpho.converter

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Renders print-ready HTML in an offscreen WebView and hands it to the
 * Android print framework: the system sheet's "Save as PDF" writes the file
 * with Blink-quality BiDi, shaping and line breaking — the plan's §5.2 v1
 * route to Word→PDF without a native layout engine. The WebView reference is
 * held until the next print so the framework can finish laying out the
 * current job.
 */
class PdfPrintLauncher(private val context: Context) {

    private var active: WebView? = null

    fun print(html: String, jobName: String) {
        val webView = WebView(context)
        active = webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printable = view ?: return
                val printManager =
                    context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val attributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
                printManager.print(
                    jobName,
                    printable.createPrintDocumentAdapter(jobName),
                    attributes,
                )
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
