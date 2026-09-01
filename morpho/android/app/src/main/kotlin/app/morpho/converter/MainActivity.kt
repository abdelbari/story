package app.morpho.converter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import app.morpho.design.MorphoTheme

// AppCompatActivity (not ComponentActivity) so the per-app language backport
// works below Android 13.
class MainActivity : AppCompatActivity() {

    private val viewModel: ConvertViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MorphoTheme {
                HomeScreen(viewModel)
            }
        }
        // Only a fresh launch consumes the launching intent — recreation
        // (rotation) must not re-pick a file over newer state.
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** A document arriving via the share sheet or an "Open with" tap. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) viewModel.onPicked(uri)
    }
}
