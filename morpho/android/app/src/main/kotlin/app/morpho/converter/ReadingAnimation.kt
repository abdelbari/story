package app.morpho.converter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * A page being read, drawn while the reader waits.
 *
 * The app is named for what it does: a document goes in as one thing and
 * comes out as another, and the waiting is where that happens. So the
 * waiting shows it. A page sits on the screen with its lines set the way a
 * PDF sets them — ragged, each ending where its words happened to end —
 * and a band travels down it. Behind the band the same lines are ranged to
 * the measure, which is what a Word document does with them, and lit in
 * the accent colour. Ahead of it they are still the page's own.
 *
 * That is not decoration standing in for progress. It is the one sentence
 * this app has to say about itself, said while there is nothing else to
 * look at: your page is being read here, on this phone, and set again.
 *
 * The lines are ranged from the right where the reader's language runs
 * that way, because a converter for Arabic that animates a left-ranged
 * page is showing somebody else's document.
 *
 * Driven off the frame clock rather than an animation library: the clock
 * stops when nothing is drawing, so a conversion left in the background
 * costs nothing, and the app takes no dependency it would otherwise have
 * no use for.
 */
@Composable
fun ReadingPage(modifier: Modifier = Modifier) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var opened = 0L
        while (true) {
            withFrameMillis { now ->
                if (opened == 0L) opened = now
                phase = ((now - opened) % SWEEP_MILLIS) / SWEEP_MILLIS.toFloat()
            }
        }
    }
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The colours are read here and handed down: what draws the page is
    // not composable and cannot ask the theme anything.
    val scheme = MaterialTheme.colorScheme
    val ink = remember(scheme) {
        PageInk(
            paper = scheme.surface,
            edge = scheme.outlineVariant,
            set = scheme.primary,
            unset = scheme.onSurfaceVariant.copy(alpha = 0.35f),
            sweep = scheme.primary,
        )
    }
    Canvas(modifier.fillMaxWidth().height(148.dp)) {
        drawReadingPage(phase, rightToLeft, ink)
    }
}

/**
 * What the drawn page is drawn in. Taken off the theme where the theme can
 * be asked, since the drawing itself cannot ask it.
 */
private data class PageInk(
    val paper: Color,
    val edge: Color,
    /** A line that has been read and set again. */
    val set: Color,
    /** A line the band has not reached. */
    val unset: Color,
    val sweep: Color,
)

/** How long the band takes to travel the page once, in milliseconds. */
private const val SWEEP_MILLIS = 2600L

/** How many lines of type the drawn page holds. */
private const val LINES = 9

/**
 * The widths the page's own lines happen to have, as a share of the
 * measure: a paragraph's last line is short, and no two others end alike.
 * Fixed rather than random so the page does not flicker between frames.
 */
private val RAGGED = floatArrayOf(0.94f, 0.88f, 0.97f, 0.62f, 0.91f, 0.95f, 0.86f, 0.99f, 0.48f)

/** Which of those lines are a heading, and so are set shorter and heavier. */
private val HEADINGS = setOf(0, 4)

private fun DrawScope.drawReadingPage(phase: Float, rightToLeft: Boolean, ink: PageInk) {
    // The page: as tall as there is room for, and as wide as A4 is against
    // that height, so what is drawn is a page and not a rectangle.
    val high = size.height
    val wide = (high * A4_WIDE_TO_HIGH).coerceAtMost(size.width)
    val left = (size.width - wide) / 2f
    val corner = CornerRadius(wide * 0.03f, wide * 0.03f)
    drawRoundRect(
        color = ink.paper,
        topLeft = Offset(left, 0f),
        size = Size(wide, high),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = ink.edge,
        topLeft = Offset(left, 0f),
        size = Size(wide, high),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )

    val margin = wide * 0.12f
    val measure = wide - margin * 2f
    val top = high * 0.14f
    val step = (high * 0.76f) / LINES
    val band = high * phase

    for (line in 0 until LINES) {
        val y = top + step * line
        val heading = line in HEADINGS
        val thickness = if (heading) step * 0.34f else step * 0.2f
        // Behind the band the line has been read and set again: ranged to
        // the measure. Ahead of it, it is still as the page had it.
        val read = y < band
        val ragged = RAGGED[line]
        val share = when {
            heading -> if (read) 0.55f else ragged * 0.6f
            read -> if (line == LINES - 1) 0.62f else 1f
            else -> ragged
        }
        val length = measure * share
        val x = if (rightToLeft) left + margin + (measure - length) else left + margin
        drawRoundRect(
            color = if (read) ink.set else ink.unset,
            topLeft = Offset(x, y),
            size = Size(length, thickness),
            cornerRadius = CornerRadius(thickness / 2f, thickness / 2f),
        )
    }

    // The band itself: a soft edge rather than a rule, so it reads as
    // something passing over the page rather than something drawn on it.
    val depth = high * 0.16f
    val glow = (0.35f + 0.65f * abs(sin(phase * Math.PI.toFloat()))).coerceIn(0f, 1f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, ink.sweep.copy(alpha = 0.5f * glow), Color.Transparent),
            startY = band - depth,
            endY = band + depth,
        ),
        topLeft = Offset(left, (band - depth).coerceAtLeast(0f)),
        size = Size(wide, (depth * 2f).coerceAtMost(high)),
    )
}

/** A4's width against its height, which is what makes the drawn page a page. */
private const val A4_WIDE_TO_HIGH = 595.3f / 841.9f
