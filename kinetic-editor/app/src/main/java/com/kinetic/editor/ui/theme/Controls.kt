package com.kinetic.editor.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The controls the editor is built from.
 *
 * They exist so the screen files describe *what* is on screen rather than how
 * every button is painted, and so a change of feel is one edit rather than
 * forty. Everything here obeys [Dim.touch] regardless of how small it looks.
 */

/** A tool: icon over a caption, the shape of every button in the bottom rail. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colour = when {
        !enabled -> Ink.textFaint
        tint != null -> tint
        active -> Ink.accent
        else -> Ink.textMuted
    }
    // Colour is the only thing that animates: a tool rail that moves is a tool
    // rail you cannot aim at.
    val animated by animateColorAsState(colour, label = "tool")
    Column(
        modifier
            .clip(RoundedCornerShape(Dim.radiusSm))
            .background(if (active) Ink.accentFill else Color.Transparent)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    // Fire on press, not on release: an editor's tools should
                    // feel like keys, and the gesture layer owns the timeline.
                    onClick()
                }
            }
            .width(56.dp)
            .padding(vertical = Dim.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dim.xs),
    ) {
        Icon(icon, contentDescription = label, tint = animated, modifier = Modifier.size(Dim.icon))
        Text(label, style = Type.control.copy(color = animated, fontSize = Type.label.fontSize))
    }
}

/** A choice among several: filters, faces, motions, canvas presets. */
@Composable
fun Chip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colour by animateColorAsState(if (active) Ink.accent else Ink.textMuted, label = "chip")
    Box(
        modifier
            .clip(RoundedCornerShape(Dim.radiusSm))
            .background(if (active) Ink.accentFill else Ink.raised)
            .border(
                Dim.hair,
                if (active) Ink.accent.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(Dim.radiusSm),
            )
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onClick() } }
            .padding(horizontal = Dim.md, vertical = Dim.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.control.copy(color = colour))
    }
}

/**
 * A chip that draws its own contents: a face shown in its own family, a bold
 * "B", a colour swatch. Same shell as [Chip] so a row of them lines up.
 */
@Composable
fun ChipBox(
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(Dim.radiusSm))
            .background(if (active) Ink.accentFill else Ink.raised)
            .border(
                Dim.hair,
                if (active) Ink.accent.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(Dim.radiusSm),
            )
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onClick() } }
            .padding(horizontal = Dim.md, vertical = Dim.sm),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A heading over a group of controls. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.label, modifier = modifier.padding(vertical = Dim.xs))
}

/**
 * A value between two bounds.
 *
 * Hand-drawn rather than Material's Slider: that one is built for a settings
 * screen, and its thumb and ripple are larger than some of this app's rows.
 * A 3dp track and a small thumb read as an instrument. The touch target is
 * still full height — thin to look at, not to hit.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    val latest by rememberUpdatedState(onChange)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)

    Row(
        modifier.height(Dim.touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.sm),
    ) {
        Text(label, style = Type.label, modifier = Modifier.width(54.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(Dim.touch)
                .pointerInput(range) {
                    fun emit(x: Float) {
                        val f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        latest(range.start + f * span)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        emit(down.position.x)
                        // One gesture handles press and drag, so a tap anywhere
                        // on the track jumps to that value and keeps tracking.
                        drag(down.id) { change ->
                            emit(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            val y = size.height / 2f
            val trackW = 3.dp.toPx()
            val x = fraction * size.width
            drawLine(Ink.raised, Offset(0f, y), Offset(size.width, y), trackW, StrokeCap.Round)
            if (x > 0f) {
                drawLine(Ink.accent, Offset(0f, y), Offset(x, y), trackW, StrokeCap.Round)
            }
            drawCircle(Ink.accent, radius = 6.dp.toPx(), center = Offset(x, y))
            drawCircle(Ink.window, radius = 2.5.dp.toPx(), center = Offset(x, y))
        }
        Text(
            formatValue(value),
            style = Type.control.copy(color = Ink.textMuted, textAlign = TextAlign.End),
            modifier = Modifier.width(38.dp),
        )
    }
}

/**
 * Numbers a person reads at a glance while dragging: no more precision than the
 * eye can use, and no trailing zeros to make the column jitter.
 */
private fun formatValue(v: Float): String = when {
    abs(v) >= 100f -> v.roundToInt().toString()
    abs(v) >= 10f -> ((v * 10f).roundToInt() / 10f).toString()
    else -> ((v * 100f).roundToInt() / 100f).toString()
}
