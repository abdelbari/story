package com.kinetic.editor.ui.preview

import android.graphics.BitmapFactory
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetic.editor.core.model.CanvasFit
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.PipSpec
import com.kinetic.editor.core.model.canvasScales
import com.kinetic.editor.core.mvi.EditorIntent
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.layoutKey
import com.kinetic.editor.core.model.overlayAnimAt
import com.kinetic.editor.core.model.pipWindowAt
import com.kinetic.editor.core.model.overlayAnimAt
import com.kinetic.editor.ui.previewStyle
import com.kinetic.editor.engine.PreviewEngine
import com.kinetic.editor.ui.timeline.TimelineViewportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Video out on a SurfaceView (zero-copy decoder path — TextureView would force
 * an extra GPU composite per frame), with text/sticker previews drawn as a
 * Compose Canvas ABOVE the surface. Overlays in preview cost no GL work and
 * update live while typing; the export renders the same specs via OverlayEffect.
 *
 * One frame, because the engine letterboxes with the same `Presentation` the
 * export applies: what reaches this surface is already canvas-sized and fitted.
 * So the surface is simply the canvas, and everything drawn over it — PiP
 * boxes, text, stickers — is placed in canvas coordinates, exactly as the
 * export's compositor and overlays place them.
 */
@Composable
fun PreviewSurface(
    engine: PreviewEngine,
    state: TimelineState,
    viewport: TimelineViewportState,
    selection: ClipId?,
    dispatch: (EditorIntent) -> Unit,
    onSelect: (ClipId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canvasAspect = state.outputWidth.toFloat() / state.outputHeight.toFloat()
    // Read inside the gesture handlers, which must not restart on every commit
    // a drag makes: a restart mid-drag would end the drag.
    val latestState by rememberUpdatedState(state)
    val latestSelection by rememberUpdatedState(selection)

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(canvasAspect)
                .align(Alignment.Center)
                // Direct manipulation: drag, pinch and twist the selected
                // thing on the picture itself. Each event is a small edit,
                // coalesced by the store into one undo step per gesture.
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val id = latestSelection ?: return@detectTransformGestures
                        manipulate(latestState, id, pan, zoom, rotation, size, dispatch)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        onSelect(overlayAt(latestState, viewport.playheadMs, tap, size))
                    }
                },
        ) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also(engine::attachSurface) },
                onRelease = { engine.detachSurface() },
                modifier = Modifier.fillMaxSize(),
            )
            PipLayer(engine, viewport)
            PreviewOverlayLayer(state, viewport, Modifier.fillMaxSize())
        }
    }
}

/**
 * Picture-in-picture preview: one TextureView per PiP track, laid out from the
 * same PipWindow the export compositor uses, so the box the user drags is the
 * box that renders. Each sits above the main video and below text/stickers,
 * which matches the export layer order (compositor first, OverlayEffect last).
 */
@Composable
private fun PipLayer(engine: PreviewEngine, viewport: TimelineViewportState) {
    val overlays by engine.overlays.collectAsState()
    for (overlay in overlays) {
        key(overlay.trackId) {
            // derivedStateOf: the playhead changes every frame, but the window in
            // force only changes at clip boundaries — so this box re-lays out
            // then, not sixty times a second.
            val window by remember(overlay) {
                derivedStateOf { pipWindowAt(overlay.windows, viewport.playheadMs * 1_000L) }
            }
            val pip = window?.pip ?: PipSpec()
            val aspect = window?.aspect ?: 0f
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        // Width is the requested fraction of the picture; height keeps
                        // the source's own proportions, as the export compositor does.
                        val w = (constraints.maxWidth * pip.scale).toInt().coerceAtLeast(1)
                        val h = (if (aspect > 0f) w / aspect else constraints.maxHeight * pip.scale)
                            .toInt()
                            .coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            // NDC anchor -> pixel centre, y up-positive.
                            val cx = (pip.anchorX * 0.5f + 0.5f) * constraints.maxWidth
                            val cy = (-pip.anchorY * 0.5f + 0.5f) * constraints.maxHeight
                            placeable.place((cx - w / 2f).toInt(), (cy - h / 2f).toInt())
                        }
                    },
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { engine.attachOverlayTexture(overlay.trackId, it) }
                    },
                    onRelease = { engine.detachOverlayTexture(overlay.trackId) },
                    modifier = Modifier
                        // On the box itself, so the rotation pivots on the box's centre.
                        // Negated: media3 specifies overlay rotation counter-clockwise,
                        // Compose rotates clockwise, and a preview that turns the
                        // opposite way from the render is worse than none.
                        .fillMaxSize()
                        .rotate(-pip.rotationDeg)
                        // Between clips the slave player sits paused on its last frame: hide it.
                        .alpha(if (window != null) pip.opacity else 0f),
                )
            }
        }
    }
}

@Composable
private fun PreviewOverlayLayer(
    state: TimelineState,
    viewport: TimelineViewportState,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val textCache = remember { HashMap<String, TextLayoutResult>() }
    val stickerCache = remember { mutableStateMapOf<String, ImageBitmap>() }

    // Decode sticker assets once, off the main thread.
    LaunchedEffect(state.revision) {
        val paths = state.tracks
            .filter { it.type == TrackType.STICKER }
            .flatMap { t -> t.clips.mapNotNull { it.sticker?.assetPath } }
            .filter { it !in stickerCache }
        if (paths.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (path in paths) {
                    runCatching {
                        context.assets.open(path).use(BitmapFactory::decodeStream)
                    }.getOrNull()?.let { stickerCache[path] = it.asImageBitmap() }
                }
            }
        }
    }

    Canvas(modifier) {
        // Draw-phase read: overlays follow the playhead during play AND scrub.
        val timeMs = viewport.playheadMs
        for (track in state.tracks) {
            when (track.type) {
                TrackType.TEXT -> for (p in state.placements(track)) {
                    if (timeMs !in p) continue
                    val spec = p.clip.text ?: continue
                    // The export's overlay reads this same function per frame.
                    val anim = overlayAnimAt(
                        spec.anim,
                        timeMs * 1_000L,
                        p.startMs * 1_000L,
                        p.endMs * 1_000L,
                        spec.text.length,
                    )
                    if (anim.alpha <= 0f) continue
                    val shown =
                        if (anim.visibleChars < 0) spec.text else spec.text.take(anim.visibleChars)
                    if (shown.isEmpty()) continue

                    val scale = size.width / state.outputWidth
                    val key = spec.layoutKey((spec.textSizePx * scale).toInt()) + "#" + shown.length
                    val layout = textCache.getOrPut(key) {
                        if (textCache.size > 256) textCache.clear()
                        measurer.measure(
                            AnnotatedString(shown),
                            spec.previewStyle((spec.textSizePx * scale / density).sp),
                        )
                    }
                    // NDC anchors: x right-positive, y up-positive, (0,0) center.
                    val cx = (spec.anchorX * 0.5f + 0.5f) * size.width
                    val cy = (-(spec.anchorY + anim.dy) * 0.5f + 0.5f) * size.height
                    val base = Color(spec.argb)
                    val w = layout.size.width.toFloat()
                    val h = layout.size.height.toFloat()
                    val topLeft = Offset(cx - w / 2f, cy - h / 2f)
                    scale(anim.scale, pivot = Offset(cx, cy)) {
                        // Box, then outline, then fill: the same order the
                        // export's canvas draws them in, so the layers stack
                        // identically on screen and in the file.
                        val box = Color(spec.boxArgb)
                        if (box.alpha > 0f) {
                            val inset = spec.textSizePx * scale * 0.18f
                            drawRoundRect(
                                box.copy(alpha = box.alpha * anim.alpha),
                                topLeft = Offset(topLeft.x - inset, topLeft.y - inset),
                                size = Size(w + inset * 2f, h + inset * 2f),
                                cornerRadius = CornerRadius(inset),
                            )
                        }
                        if (spec.outlinePx > 0f) {
                            val edge = Color(spec.outlineArgb)
                            drawText(
                                layout,
                                edge.copy(alpha = edge.alpha * anim.alpha),
                                topLeft = topLeft,
                                drawStyle = Stroke(
                                    width = spec.outlinePx * scale * 2f,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                        drawText(
                            layout,
                            base.copy(alpha = base.alpha * anim.alpha),
                            topLeft = topLeft,
                            shadow = if (spec.shadowPx > 0f) {
                                Shadow(
                                    color = Color(0xC0000000),
                                    offset = Offset(0f, spec.shadowPx * scale),
                                    blurRadius = spec.shadowPx * scale,
                                )
                            } else {
                                null
                            },
                        )
                    }
                }

                TrackType.STICKER -> for (p in state.placements(track)) {
                    if (timeMs !in p) continue
                    val spec = p.clip.sticker ?: continue
                    val bmp = stickerCache[spec.assetPath] ?: continue
                    val anim = overlayAnimAt(
                        spec.anim,
                        timeMs * 1_000L,
                        p.startMs * 1_000L,
                        p.endMs * 1_000L,
                        0,
                    )
                    if (anim.alpha <= 0f) continue
                    // Width is the requested fraction of the frame, height follows
                    // the asset's proportions — the same size the export's
                    // canvas-relative scale produces. See OverlayFactory.
                    val w = size.width * spec.scale * anim.scale
                    val h = w * bmp.height / bmp.width
                    val cx = (spec.anchorX * 0.5f + 0.5f) * size.width
                    val cy = (-(spec.anchorY + anim.dy) * 0.5f + 0.5f) * size.height
                    // Negated for the same reason as the PiP box above: media3
                    // rotates overlays counter-clockwise, Compose clockwise.
                    rotate(-spec.rotationDeg, pivot = Offset(cx, cy)) {
                        drawImage(
                            bmp,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()),
                            dstSize = IntSize(w.toInt(), h.toInt()),
                            alpha = anim.alpha,
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}

/* ---------------------------- direct manipulation --------------------------- */

/**
 * Applies one gesture increment to the selected clip: overlays move, scale and
 * turn about their anchor; a main-track clip's transform pans, zooms and
 * rotates the picture. Pan is converted from canvas pixels to the NDC the
 * specs use, y up, and for a transform on into the source frame's own NDC, so
 * the picture follows the finger exactly however it is fitted.
 */
private fun manipulate(
    state: TimelineState,
    id: ClipId,
    pan: Offset,
    zoom: Float,
    rotation: Float,
    size: IntSize,
    dispatch: (EditorIntent) -> Unit,
) {
    val (track, clip) = state.findClip(id) ?: return
    if (size.width == 0 || size.height == 0) return
    val dx = pan.x / size.width * 2f
    val dy = -pan.y / size.height * 2f
    // Compose reports rotation clockwise-positive; the specs are counter-clockwise.
    val turn = -rotation
    clip.text?.let { t ->
        dispatch(
            EditorIntent.SetText(
                id,
                t.copy(
                    anchorX = t.anchorX + dx,
                    anchorY = t.anchorY + dy,
                    textSizePx = (t.textSizePx * zoom).coerceIn(8f, 400f),
                ),
            ),
        )
        return
    }
    clip.sticker?.let { st ->
        dispatch(
            EditorIntent.SetSticker(
                id,
                st.copy(
                    anchorX = st.anchorX + dx,
                    anchorY = st.anchorY + dy,
                    scale = st.scale * zoom,
                    rotationDeg = st.rotationDeg + turn,
                ),
            ),
        )
        return
    }
    clip.pip?.let { pip ->
        dispatch(
            EditorIntent.SetPip(
                id,
                pip.copy(
                    anchorX = pip.anchorX + dx,
                    anchorY = pip.anchorY + dy,
                    scale = pip.scale * zoom,
                    rotationDeg = pip.rotationDeg + turn,
                ),
            ),
        )
        return
    }
    if (track.type == TrackType.VIDEO_MAIN && clip.media.hasVideo) {
        // Canvas NDC -> source-frame NDC: a picture letterboxed to a third of
        // the canvas's height must move three times as far in its own frame
        // to keep up with the finger.
        val scales = canvasScales(clip.media.width, clip.media.height, state.outputWidth, state.outputHeight)
        val (sx, sy) = when (state.canvasFit) {
            CanvasFit.FIT -> scales.fitX to scales.fitY
            CanvasFit.FILL -> scales.fillX to scales.fillY
            CanvasFit.STRETCH -> 1f to 1f
        }
        val t = clip.transform
        dispatch(
            EditorIntent.SetTransform(
                id,
                t.copy(
                    offsetX = t.offsetX + dx * sx,
                    offsetY = t.offsetY + dy * sy,
                    scale = t.scale * zoom,
                    rotationDeg = wrapDegrees(t.rotationDeg + turn),
                ),
            ),
        )
    }
}

/** Keeps a twist that passes 180 going, where the reducer's clamp would pin it. */
private fun wrapDegrees(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/**
 * The overlay under a tap, topmost first, or null for the picture. Boxes are
 * the ones the layer above draws, with a caption's measured by a rule of
 * thumb: hit-testing wants a generous target, not a typographer's.
 */
private fun overlayAt(state: TimelineState, timeMs: Long, tap: Offset, size: IntSize): ClipId? {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    if (w <= 0f || h <= 0f) return null
    val scale = w / state.outputWidth
    fun hit(anchorX: Float, anchorY: Float, boxW: Float, boxH: Float): Boolean {
        val cx = (anchorX * 0.5f + 0.5f) * w
        val cy = (-anchorY * 0.5f + 0.5f) * h
        return kotlin.math.abs(tap.x - cx) <= boxW / 2f && kotlin.math.abs(tap.y - cy) <= boxH / 2f
    }
    // Text and stickers sit above picture-in-picture, so they are asked first.
    for (type in listOf(TrackType.TEXT, TrackType.STICKER, TrackType.VIDEO_OVERLAY)) {
        for (track in state.tracks) {
            if (track.type != type) continue
            for (p in state.placements(track).asReversed()) {
                if (timeMs !in p) continue
                val clip = p.clip
                val found = when {
                    clip.text != null -> {
                        val lines = clip.text.text.split('\n')
                        val em = clip.text.textSizePx * scale
                        hit(
                            clip.text.anchorX, clip.text.anchorY,
                            em * 0.6f * (lines.maxOfOrNull { it.length } ?: 1).coerceAtLeast(1),
                            em * 1.3f * lines.size,
                        )
                    }
                    clip.sticker != null ->
                        hit(clip.sticker.anchorX, clip.sticker.anchorY, w * clip.sticker.scale, w * clip.sticker.scale)
                    clip.pip != null -> {
                        val boxW = w * clip.pip.scale
                        val aspect = clip.media.width.toFloat() / clip.media.height.coerceAtLeast(1)
                        hit(clip.pip.anchorX, clip.pip.anchorY, boxW, if (aspect > 0f) boxW / aspect else h * clip.pip.scale)
                    }
                    else -> false
                }
                if (found) return clip.id
            }
        }
    }
    return null
}
