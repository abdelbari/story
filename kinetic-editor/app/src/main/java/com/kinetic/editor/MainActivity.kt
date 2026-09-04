package com.kinetic.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.kinetic.editor.ui.EditorScreen
import com.kinetic.editor.ui.theme.Ink
import com.kinetic.editor.ui.EditorViewModel

class MainActivity : ComponentActivity() {

    // Held here as well as passed to the screen so onStop below can reach it
    // after the composition is gone. Same ViewModelStore, same instance.
    private val editor: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Most of the interface is painted from Ink directly; this scheme
            // exists for the few Material components still in use (the text
            // field, its cursor and label) so they belong to the same palette.
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Ink.accent,
                    onPrimary = Ink.window,
                    secondary = Ink.accent,
                    background = Ink.window,
                    onBackground = Ink.text,
                    surface = Ink.surface,
                    onSurface = Ink.text,
                    surfaceVariant = Ink.raised,
                    onSurfaceVariant = Ink.textMuted,
                    outline = Ink.hairline,
                    error = Ink.danger,
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
