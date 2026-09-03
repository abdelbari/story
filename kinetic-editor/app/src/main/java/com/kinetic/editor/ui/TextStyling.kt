package com.kinetic.editor.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import com.kinetic.editor.core.model.TextFont
import com.kinetic.editor.core.model.TextSpec

/**
 * The preview half of the text contract; the export half is the TypefaceSpan in
 * `effects/Overlays.kt`.
 *
 * Both sides resolve the SAME Android family name — Compose's built-in
 * families are defined as those names — so a text clip is measured and drawn in
 * the preview with the face the render will use, not one that merely resembles
 * it. Every property here has an export counterpart; a property with no
 * counterpart would be a preview that lies.
 */
fun TextFont.composeFamily(): FontFamily = when (this) {
    TextFont.SANS -> FontFamily.SansSerif
    TextFont.SERIF -> FontFamily.Serif
    TextFont.MONO -> FontFamily.Monospace
    TextFont.CURSIVE -> FontFamily.Cursive
}

/** Colour is deliberately absent: it is applied at draw time, off the layout path. */
fun TextSpec.previewStyle(fontSize: TextUnit): TextStyle = TextStyle(
    fontSize = fontSize,
    fontFamily = font.composeFamily(),
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
)
