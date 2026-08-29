package app.morpho.converter

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
    }
}
