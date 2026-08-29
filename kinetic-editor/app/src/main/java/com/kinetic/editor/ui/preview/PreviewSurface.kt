package com.kinetic.editor.ui.preview

import android.graphics.BitmapFactory
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.engine.PreviewEngine
import com.kinetic.editor.ui.timeline.TimelineViewportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Video out on a SurfaceView (zero-copy decoder path — TextureView would force
 * an extra GPU composite per frame), with text/sticker previews drawn as a
 * Compose Canvas ABOVE the surface. Overlays in preview cost no GL work and
 * update live while typing; the export renders the same specs via OverlayEffect.
 */
@Composable
fun PreviewSurface(
    engine: PreviewEngine,
    state: TimelineState,
    viewport: TimelineViewportState,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(state.outputWidth.toFloat() / state.outputHeight.toFloat())
                .align(Alignment.Center),
        ) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also(engine::attachSurface) },
                onRelease = { engine.detachSurface() },
                modifier = Modifier.fillMaxSize(),
            )
            PreviewOverlayLayer(state, viewport, Modifier.fillMaxSize())
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
                    val scale = size.width / state.outputWidth
                    val layout = textCache.getOrPut("${spec.text}:${(spec.textSizePx * scale).toInt()}") {
                        if (textCache.size > 128) textCache.clear()
                        measurer.measure(
                            AnnotatedString(spec.text),
                            TextStyle(
                                fontSize = (spec.textSizePx * scale / density).sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    // NDC anchors: x right-positive, y up-positive, (0,0) center.
                    val cx = (spec.anchorX * 0.5f + 0.5f) * size.width
                    val cy = (-spec.anchorY * 0.5f + 0.5f) * size.height
                    drawText(
                        layout,
                        Color(spec.argb),
                        topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                    )
                }

                TrackType.STICKER -> for (p in state.placements(track)) {
                    if (timeMs !in p) continue
                    val spec = p.clip.sticker ?: continue
                    val bmp = stickerCache[spec.assetPath] ?: continue
                    val w = size.width * spec.scale
                    val h = w * bmp.height / bmp.width
                    val cx = (spec.anchorX * 0.5f + 0.5f) * size.width
                    val cy = (-spec.anchorY * 0.5f + 0.5f) * size.height
                    drawImage(
                        bmp,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()),
                        dstSize = IntSize(w.toInt(), h.toInt()),
                    )
                }

                else -> Unit
            }
        }
    }
}
