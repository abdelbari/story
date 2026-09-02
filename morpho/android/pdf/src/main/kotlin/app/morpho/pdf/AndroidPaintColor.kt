package app.morpho.pdf

import com.tom_roush.pdfbox.pdmodel.graphics.state.PDGraphicsState

/**
 * Android twin of the engine's PaintColor (:engine:pdf-read), on the
 * tom-roush PDFBox port. Keep the two in step.
 *
 * The colour a page is painting text in.
 *
 * A PDF says nothing about colour glyph by glyph: it sets a fill colour in
 * the graphics state and every glyph after that is painted in it, in
 * whichever colour space the producer chose — grey, RGB, CMYK, a separation
 * ink, an indexed palette. The one thing every space can do is answer what
 * a colour is in RGB, which is what a word processor and a screen want.
 *
 * Black is the colour a page paints with unless it says otherwise, so black
 * is reported as nothing at all: a writer then leaves the colour to the
 * document's own default instead of stamping every run of an ordinary
 * black-on-white document with a colour it never chose.
 */
internal object AndroidPaintColor {

    /** Below this, a channel is close enough to black that no producer meant a colour. */
    private const val BLACK = 0x0A

    /** The non-stroking colour of [state] as packed 0xRRGGBB, or null when it is black or unreadable. */
    fun of(state: PDGraphicsState?): Int? {
        val color = state?.nonStrokingColor ?: return null
        val rgb = runCatching { color.toRGB() }.getOrNull() ?: return null
        val packed = rgb and 0xFFFFFF
        val red = packed shr 16 and 0xFF
        val green = packed shr 8 and 0xFF
        val blue = packed and 0xFF
        if (red <= BLACK && green <= BLACK && blue <= BLACK) return null
        return packed
    }
}
