package com.kinetic.editor.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.PlacedClip
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.timelineToSourceMs
import com.kinetic.editor.engine.ThumbnailEngine
import com.kinetic.editor.engine.WaveformEngine
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.ceil
import kotlin.math.max
import androidx.compose.ui.graphics.Path
import com.kinetic.editor.ui.theme.Ink
import com.kinetic.editor.ui.theme.Lane

/**
 * The timeline's colours, borrowed from the app's palette rather than restated.
 * A Canvas cannot reach Compose's theme machinery, so this object is the seam:
 * one place, every value from [Ink] and [Lane].
 */
private object Palette {
    val background = Ink.window
    val lane = Lane.bed
    val clipPlaceholder = Lane.videoClip
    val audioFill = Lane.audioFill
    val audioWave = Lane.audioWave
    val textChip = Lane.textChip
    val stickerChip = Lane.stickerChip

    /** Selection is the accent; white would compete with the playhead. */
    val selection = Ink.accent
    val handleGlyph = Ink.window
    val playhead = Lane.playhead
    val rulerText = Lane.ruler
    val tick = Lane.tick
    val ghost = Lane.ghost
    val transitionBadge = Lane.transitionBadge
    val label = Ink.text
    val laneLabel = Ink.textFaint
}

/** What an empty lane is for. Drawn only while it is empty, so it never hides a clip. */
private fun laneLabel(type: TrackType): String = when (type) {
    TrackType.VIDEO_MAIN -> "Video"
    TrackType.VIDEO_OVERLAY -> "Picture-in-picture"
    TrackType.TEXT -> "Text"
    TrackType.STICKER -> "Stickers"
    TrackType.AUDIO -> "Audio"
}

/**
 * The entire multi-track timeline is ONE Canvas node.
 *
 * Why not LazyRow-per-track: rows recompose while scrolling, item entry/exit
 * churns the composition, and cross-track alignment during pinch-zoom requires
 * synchronizing N scroll states per frame. Here, scroll/zoom mutate two floats;
 * only this node's draw phase re-executes (composition and layout are skipped),
 * which is what keeps 120Hz scrubbing flat even with dozens of clips.
 *
 * Recomposition happens ONLY when the committed document ([state]) or selection
 * changes — i.e. at gesture-commit frequency, not input frequency.
 */
@Composable
fun Timeline(
    state: TimelineState,
    selection: ClipId?,
    viewport: TimelineViewportState,
    thumbnails: ThumbnailEngine,
    waveforms: WaveformEngine,
    callbacks: TimelineGestureCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val geometry = remember(viewport, density) { TimelineGeometry(viewport, density.density) }
    val flingScope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val labelCache = remember { HashMap<String, TextLayoutResult>() }
    val latestState by rememberUpdatedState(state)
    val latestSelection by rememberUpdatedState(selection)

    SideEffect { viewport.durationMsProvider = { latestState.durationMs } }

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()

        // Visible-window prefetch: decoupled from drawing, throttled by 64px scroll
        // buckets so a fling doesn't spam the decoder queues.
        LaunchedEffect(thumbnails, waveforms, geometry, widthPx) {
            snapshotFlow {
                Triple(
                    (viewport.scrollXPx / 64f).toInt(),
                    viewport.pxPerMs,
                    latestState.revision,
                )
            }.distinctUntilChanged().collect {
                prefetchVisible(latestState, geometry, widthPx, thumbnails, waveforms)
            }
        }

        val heightDp = with(density) { geometry.totalHeightPx(state).toDp() }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .timelineGestures(
                    viewport = viewport,
                    geometry = geometry,
                    flingScope = flingScope,
                    stateProvider = { latestState },
                    selectionProvider = { latestSelection },
                    callbacks = callbacks,
                ),
        ) {
            // Draw-phase reads: scroll/zoom/ghost/thumbnail-revision changes land
            // here directly, invalidating draw only.
            thumbnails.revision.value
            waveforms.revision.value
            val trimGhost = viewport.trimming
            val dragGhost = viewport.dragging

            drawRect(Palette.background, size = size)
            drawRuler(geometry, widthPx, textMeasurer, labelCache)

            state.tracks.forEachIndexed { trackIndex, track ->
                val laneTop = geometry.laneTop(state, trackIndex)
                val laneH = geometry.laneHeightPx(track.type)
                drawRoundRect(
                    Palette.lane,
                    topLeft = Offset(0f, laneTop),
                    size = Size(size.width, laneH),
                    cornerRadius = CornerRadius(geometry.clipCornerPx),
                )

                val effective = effectiveTrack(track, trimGhost, dragGhost)
                if (effective.clips.isEmpty()) {
                    // Fixed in screen space, not timeline space: it names the lane,
                    // so it should not scroll away from it.
                    val layout = cachedLabel(labelCache, textMeasurer, laneLabel(track.type))
                    drawText(
                        layout,
                        Palette.laneLabel,
                        topLeft = Offset(
                            geometry.laneLabelInsetPx,
                            laneTop + (laneH - layout.size.height) / 2f,
                        ),
                    )
                }
                for (placed in state.placements(effective)) {
                    val left = geometry.timeToX(placed.startMs, widthPx)
                    val right = geometry.timeToX(placed.endMs, widthPx)
                    if (right < 0f || left > size.width) continue
                    val rect = Rect(left, laneTop, right, laneTop + laneH)
                    val isDragged = dragGhost?.clipId == placed.clip.id

                    drawClip(
                        track, placed, rect, geometry,
                        thumbnails, waveforms, textMeasurer, labelCache,
                        selected = placed.clip.id == selection,
                        ghosted = isDragged,
                    )
                }
            }

            drawPlayhead(geometry)
        }
    }
}

/**
 * Live-ripple preview: gesture ghosts are merged into the track before layout,
 * so the user sees the exact post-commit arrangement while the document itself
 * stays untouched until release. Allocation happens only while a ghost exists.
 */
private fun effectiveTrack(track: Track, trim: TrimGhost?, drag: DragGhost?): Track {
    var t = track
    if (trim != null && track.clips.any { it.id == trim.clipId }) {
        t = t.copy(
            clips = t.clips.map { c ->
                if (c.id == trim.clipId) {
                    c.copy(trimInMs = trim.trimInMs, trimOutMs = trim.trimOutMs, startMs = trim.startMs)
                } else c
            }.toPersistentList(),
        )
    }
    if (drag != null) {
        val idx = t.clips.indexOfFirst { it.id == drag.clipId }
        if (idx >= 0) {
            t = if (t.type == TrackType.VIDEO_MAIN && drag.insertIndex >= 0) {
                val clips = t.clips.toMutableList()
                val c = clips.removeAt(idx)
                clips.add(drag.insertIndex.coerceIn(0, clips.size), c)
                t.copy(clips = clips.toPersistentList())
            } else {
                t.copy(
                    clips = t.clips.map { c ->
                        if (c.id == drag.clipId) c.copy(startMs = drag.ghostStartMs) else c
                    }.sortedBy { it.startMs }.toPersistentList(),
                )
            }
        }
    }
    return t
}

/* --------------------------------- pieces --------------------------------- */

private fun DrawScope.drawRuler(
    geometry: TimelineGeometry,
    widthPx: Float,
    measurer: TextMeasurer,
    cache: HashMap<String, TextLayoutResult>,
) {
    val pxPerMs = geometry.msToPx(1L)
    val stepMs = TICK_STEPS_MS.firstOrNull { it * pxPerMs >= 80f } ?: 60_000L
    val minorMs = stepMs / 5
    val t0 = geometry.xToTime(0f, widthPx).coerceAtLeast(0L)
    val t1 = geometry.xToTime(widthPx, widthPx)

    var t = (t0 / minorMs) * minorMs
    while (t <= t1) {
        val x = geometry.timeToX(t, widthPx)
        val major = t % stepMs == 0L
        drawLine(
            Palette.tick,
            start = Offset(x, if (major) 8f else 16f),
            end = Offset(x, geometry.rulerHeightPx - 4f),
            strokeWidth = 1f,
        )
        if (major) {
            val layout = cachedLabel(cache, measurer, formatRulerMs(t))
            drawText(layout, Palette.rulerText, topLeft = Offset(x + 4f, 2f))
        }
        t += minorMs
    }
}

private fun DrawScope.drawClip(
    track: Track,
    placed: PlacedClip,
    rect: Rect,
    geometry: TimelineGeometry,
    thumbnails: ThumbnailEngine,
    waveforms: WaveformEngine,
    measurer: TextMeasurer,
    cache: HashMap<String, TextLayoutResult>,
    selected: Boolean,
    ghosted: Boolean,
) {
    val clip = placed.clip
    val alpha = if (ghosted) 0.55f else 1f

    when (track.type) {
        TrackType.VIDEO_MAIN, TrackType.VIDEO_OVERLAY -> {
            drawRoundRect(
                Palette.clipPlaceholder.copy(alpha = alpha),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(geometry.clipCornerPx),
            )
            clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                val slotW = geometry.thumbSlotWidthPx
                val slots = ceil(rect.width / slotW).toInt()
                // Each slot is a moment on the TIMELINE; the clip's own mapping
                // says which source frame plays there, curve or freeze included.
                val slotTimelineMs = geometry.pxToMs(slotW).toLong()
                for (i in 0 until slots) {
                    val x = rect.left + i * slotW
                    if (x + slotW < 0f || x > size.width) continue
                    val sourceMs = clip.trimInMs + clip.timelineToSourceMs(i * slotTimelineMs)
                    val bmp = thumbnails.peek(clip.media.uri, sourceMs)
                    if (bmp != null) {
                        drawImage(
                            bmp,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset(x.toInt(), rect.top.toInt()),
                            dstSize = IntSize(slotW.toInt() + 1, rect.height.toInt()),
                            alpha = alpha,
                        )
                    }
                }
                val badge = when {
                    clip.freezeMs > 0L -> "hold"
                    clip.curve != null -> "curve"
                    clip.speed != 1f -> "${clip.speed}x"
                    else -> null
                }
                if (badge != null) {
                    val layout = cachedLabel(cache, measurer, badge)
                    drawText(layout, Palette.label, topLeft = Offset(rect.left + 6f, rect.top + 4f))
                }
            }
        }

        TrackType.AUDIO -> {
            drawRoundRect(
                Palette.audioFill.copy(alpha = alpha),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(geometry.clipCornerPx),
            )
            val peaks = waveforms.peek(clip.media.uri)
            if (peaks != null && clip.media.durationMs > 0) {
                clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                    val midY = rect.top + rect.height / 2f
                    val colStep = 3f
                    var x = max(rect.left, 0f)
                    val end = kotlin.math.min(rect.right, size.width)
                    while (x < end) {
                        val fSource = (clip.trimInMs +
                            ((x - rect.left) / rect.width) * clip.sourceSpanMs) /
                            clip.media.durationMs.toFloat()
                        val idx = (fSource * (peaks.size - 1)).toInt().coerceIn(0, peaks.size - 1)
                        val h = (peaks[idx] * (rect.height * 0.85f)).coerceAtLeast(2f)
                        drawLine(
                            Palette.audioWave.copy(alpha = alpha),
                            start = Offset(x, midY - h / 2f),
                            end = Offset(x, midY + h / 2f),
                            strokeWidth = 2f,
                        )
                        x += colStep
                    }
                }
            }
        }

        TrackType.TEXT, TrackType.STICKER -> {
            val fill = if (track.type == TrackType.TEXT) Palette.textChip else Palette.stickerChip
            drawRoundRect(
                fill.copy(alpha = alpha),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(geometry.clipCornerPx),
            )
            val labelText = clip.text?.text ?: clip.sticker?.assetPath?.substringAfterLast('/') ?: "clip"
            clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                val layout = cachedLabel(cache, measurer, labelText)
                drawText(
                    layout, Palette.label,
                    topLeft = Offset(rect.left + 8f, rect.top + (rect.height - layout.size.height) / 2f),
                )
            }
        }
    }

    if (clip.transitionOut != null) {
        drawCircle(Palette.transitionBadge, radius = 7f, center = Offset(rect.right, rect.bottom - 8f))
    }

    if (selected) {
        drawRoundRect(
            Palette.selection,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(geometry.clipCornerPx),
            style = Stroke(width = 2f * geometry.dpScale),
        )
        drawTrimHandle(rect.left, rect, geometry, leading = true)
        drawTrimHandle(rect.right, rect, geometry, leading = false)
    }
}

private fun DrawScope.drawTrimHandle(x: Float, rect: Rect, geometry: TimelineGeometry, leading: Boolean) {
    val w = geometry.handleWidthPx
    val left = if (leading) x - w else x
    drawRoundRect(
        Palette.selection,
        topLeft = Offset(left, rect.top),
        size = Size(w, rect.height),
        cornerRadius = CornerRadius(geometry.clipCornerPx),
    )
    val gx = left + w / 2f
    drawLine(
        Palette.handleGlyph,
        start = Offset(gx, rect.top + rect.height * 0.3f),
        end = Offset(gx, rect.top + rect.height * 0.7f),
        strokeWidth = 3f,
    )
}

private fun DrawScope.drawPlayhead(geometry: TimelineGeometry) {
    val x = size.width / 2f
    val dp = geometry.dpScale
    drawLine(Palette.playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f * dp)
    // A diamond rather than a dot: it points at the frame it is sitting on.
    val r = 5f * dp
    val head = Path().apply {
        moveTo(x, 0f); lineTo(x + r, r); lineTo(x, 2f * r); lineTo(x - r, r); close()
    }
    drawPath(head, Palette.playhead)
}

/* -------------------------------- helpers -------------------------------- */

private val TICK_STEPS_MS = longArrayOf(100, 250, 500, 1_000, 2_000, 5_000, 10_000, 30_000).toList()

private fun formatRulerMs(ms: Long): String {
    val totalSec = ms / 1000
    val frac = (ms % 1000) / 100
    return if (ms < 1_000 || frac != 0L) "${totalSec}.${frac}s"
    else "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun cachedLabel(
    cache: HashMap<String, TextLayoutResult>,
    measurer: TextMeasurer,
    text: String,
): TextLayoutResult = cache.getOrPut(text) {
    if (cache.size > 512) cache.clear()
    measurer.measure(
        AnnotatedString(text),
        TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
    )
}

private fun prefetchVisible(
    state: TimelineState,
    geometry: TimelineGeometry,
    widthPx: Float,
    thumbnails: ThumbnailEngine,
    waveforms: WaveformEngine,
) {
    val t0 = geometry.xToTime(0f, widthPx).coerceAtLeast(0L)
    val t1 = geometry.xToTime(widthPx, widthPx)
    for (track in state.tracks) {
        for (placed in state.placements(track)) {
            if (placed.endMs < t0 || placed.startMs > t1) continue
            val clip = placed.clip
            when (track.type) {
                TrackType.VIDEO_MAIN, TrackType.VIDEO_OVERLAY -> {
                    val slotTimelineMs =
                        geometry.pxToMs(geometry.thumbSlotWidthPx).toLong().coerceAtLeast(50L)
                    var timelineMs = 0L
                    var guard = 0
                    while (timelineMs < clip.durationMs && guard++ < 64) {
                        thumbnails.request(
                            clip.media.uri,
                            clip.trimInMs + clip.timelineToSourceMs(timelineMs),
                        )
                        timelineMs += slotTimelineMs
                    }
                }
                TrackType.AUDIO -> waveforms.request(clip.media.uri)
                else -> Unit
            }
        }
    }
}
