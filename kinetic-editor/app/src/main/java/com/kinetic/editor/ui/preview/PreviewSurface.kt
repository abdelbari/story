package com.kinetic.editor.ui.preview

import android.graphics.BitmapFactory
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
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
import com.kinetic.editor.core.model.PipSpec
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.layoutKey
import com.kinetic.editor.core.model.pipWindowAt
import com.kinetic.editor.core.model.textAnimAt
import com.kinetic.editor.ui.previewStyle
import com.kinetic.editor.engine.PreviewEngine
import com.kinetic.editor.ui.timeline.TimelineViewportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier,
) {
    val canvasAspect = state.outputWidth.toFloat() / state.outputHeight.toFloat()

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(canvasAspect)
                .align(Alignment.Center),
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
                    val anim = textAnimAt(
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
                    scale(anim.scale, pivot = Offset(cx, cy)) {
                        drawText(
                            layout,
                            base.copy(alpha = base.alpha * anim.alpha),
                            topLeft = Offset(
                                cx - layout.size.width / 2f,
                                cy - layout.size.height / 2f,
                            ),
                        )
                    }
                }

                TrackType.STICKER -> for (p in state.placements(track)) {
                    if (timeMs !in p) continue
                    val spec = p.clip.sticker ?: continue
                    val bmp = stickerCache[spec.assetPath] ?: continue
                    // Width is the requested fraction of the frame, height follows
                    // the asset's proportions — the same size the export's
                    // canvas-relative scale produces. See OverlayFactory.
                    val w = size.width * spec.scale
                    val h = w * bmp.height / bmp.width
                    val cx = (spec.anchorX * 0.5f + 0.5f) * size.width
                    val cy = (-spec.anchorY * 0.5f + 0.5f) * size.height
                    // Negated for the same reason as the PiP box above: media3
                    // rotates overlays counter-clockwise, Compose clockwise.
                    rotate(-spec.rotationDeg, pivot = Offset(cx, cy)) {
                        drawImage(
                            bmp,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()),
                            dstSize = IntSize(w.toInt(), h.toInt()),
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}
