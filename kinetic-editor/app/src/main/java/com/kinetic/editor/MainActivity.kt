package com.kinetic.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.kinetic.editor.ui.EditorScreen
import com.kinetic.editor.ui.EditorViewModel

class MainActivity : ComponentActivity() {

    // Held here as well as passed to the screen so onStop below can reach it
    // after the composition is gone. Same ViewModelStore, same instance.
    private val editor: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF35C4B5),
                    surface = Color(0xFF0E0F13),
                    background = Color(0xFF0E0F13),
                ),
            ) {
                EditorScreen(editor)
            }
        }
    }

    /**
     * onStop, not onPause: a video editor should keep playing through a
     * transient overlay (a permission dialog, the volume panel), and stop when
     * it actually leaves the screen. See EditorViewModel.onEnterBackground.
     */
    override fun onStop() {
        super.onStop()
        editor.onEnterBackground()
    }
}
