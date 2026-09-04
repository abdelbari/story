package com.kinetic.editor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import com.kinetic.editor.core.model.StickerSpec
import com.kinetic.editor.core.model.OverlayAnim
import com.kinetic.editor.core.model.OverlayAnimState
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.overlayAnimAt
import com.kinetic.editor.core.model.overlayScaleFor
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.effect.CanvasOverlay
import kotlin.math.ceil

/**
 * EXPORT-side text & sticker rendering: one OverlayEffect at the Composition
 * level, whose presentation time is COMPOSITION time == editor timeline time,
 * so overlay windows are the clip placements verbatim.
 *
 * Time-gating is done through alphaScale in getOverlaySettings (0 outside the
 * clip window, with a 150ms ease at both edges).
 *
 * Sizes are canvas-relative. Media3 draws an overlay at its native pixel size
 * times `scale`, so a 256px sticker asset would come out a different size on
 * every canvas; [canvasWidth] folds that into the scale so `StickerSpec.scale`
 * means "fraction of the frame's width" here and in the preview alike. Text
 * needs no such correction: its size is authored in canvas pixels.
 *
 * (The PREVIEW draws the same specs as a Compose layer over the SurfaceView —
 * zero GL cost while editing; see PreviewOverlayLayer.)
 */
object OverlayFactory {

    fun build(context: Context, state: TimelineState, canvasWidth: Int): OverlayEffect? {
        val overlays = ImmutableList.builder<TextureOverlay>()
        var any = false
        for (track in state.tracks) {
            when (track.type) {
                TrackType.TEXT -> for (p in state.placements(track)) {
                    val spec = p.clip.text ?: continue
                    // Blank text has no layout to rasterise, and TextOverlay
                    // would throw building a zero-width bitmap from it. A clip
                    // whose field the user emptied simply draws nothing.
                    if (spec.text.isEmpty()) continue
                    overlays.add(TimedTextOverlay(spec, p.startMs * 1000L, p.endMs * 1000L))
                    any = true
                }
                TrackType.STICKER -> for (p in state.placements(track)) {
                    val spec = p.clip.sticker ?: continue
                    val bitmap = runCatching {
                        context.assets.open(spec.assetPath).use(BitmapFactory::decodeStream)
                    }.getOrNull() ?: continue
                    overlays.add(
                        TimedStickerOverlay(bitmap, spec, canvasWidth, p.startMs * 1000L, p.endMs * 1000L),
                    )
                    any = true
                }
                else -> Unit
            }
        }
        return if (any) OverlayEffect(overlays.build()) else null
    }

}

/**
 * EXPORT-side text, drawn onto a canvas rather than handed to media3 as a
 * SpannableString.
 *
 * `TextOverlay` builds its own `TextPaint`, so an outline, a drop shadow or a
 * backing box — the three things that make a caption legible over footage —
 * are simply not reachable through it. `CanvasOverlay` hands over a `Canvas`
 * instead, which also means type-on draws a substring rather than needing a
 * pre-built string per character count, and an empty frame is a canvas nobody
 * drew on rather than a zero-width bitmap that throws.
 *
 * The canvas is sized to the text block once, at construction, and never
 * resized: a caption that grew its own bitmap as characters appeared would
 * shift on screen while it typed.
 */
private class TimedTextOverlay(
    private val spec: TextSpec,
    private val startUs: Long,
    private val endUs: Long,
) : CanvasOverlay(/* useInputFrameSize= */ false) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spec.textSizePx
        typeface = Typeface.create(
            Typeface.create(spec.font.androidFamily, Typeface.NORMAL),
            when {
                spec.bold && spec.italic -> Typeface.BOLD_ITALIC
                spec.bold -> Typeface.BOLD
                spec.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            },
        )
    }

    private val pad = spec.decorationPadPx

    // Measured once, from the FULL text: the block keeps its size while type-on
    // fills it, so a caption does not shift on screen as characters appear.
    private val blockWidth = maxOf(1, ceil(measureWidest(spec.text, paint)).toInt())
    private val full = layoutFor(spec.text)

    init {
        setCanvasSize(blockWidth + (pad * 2).toInt(), full.height + (pad * 2).toInt())
    }

    /** One layout per distinct visible length; type-on only ever asks for a few. */
    private var cachedChars = -1
    private var cached: StaticLayout = full

    private fun layoutFor(text: String): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, blockWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

    private fun visibleLayout(chars: Int): StaticLayout {
        if (chars < 0) return full
        if (chars == cachedChars) return cached
        cachedChars = chars
        cached = layoutFor(spec.text.take(chars))
        return cached
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val anim = overlayAnimAt(spec.anim, presentationTimeUs, startUs, endUs, spec.text.length)
        if (anim.alpha <= 0f) return
        val layout = visibleLayout(anim.visibleChars)

        canvas.save()
        canvas.translate(pad, pad)

        val box = spec.boxArgb.toInt()
        if (box ushr 24 != 0) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = box }
            val inset = spec.textSizePx * 0.18f
            canvas.drawRoundRect(
                -inset, -inset,
                blockWidth + inset, layout.height + inset,
                inset, inset, fill,
            )
        }

        // Stroke first, fill over it, so the outline sits behind the letterform
        // rather than eating into it.
        if (spec.outlinePx > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = spec.outlinePx * 2f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = spec.outlineArgb.toInt()
            paint.clearShadowLayer()
            layout.draw(canvas)
        }

        paint.style = Paint.Style.FILL
        paint.color = spec.argb.toInt()
        if (spec.shadowPx > 0f) {
            paint.setShadowLayer(spec.shadowPx, 0f, spec.shadowPx, 0xC0000000.toInt())
        } else {
            paint.clearShadowLayer()
        }
        layout.draw(canvas)
        canvas.restore()
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val anim = overlayAnimAt(spec.anim, presentationTimeUs, startUs, endUs, spec.text.length)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(spec.anchorX, spec.anchorY + anim.dy)
            .setScale(anim.scale, anim.scale)
            .setAlphaScale(anim.alpha)
            .build()
    }
}

/** Widest line, so a multi-line caption is not clipped to its first line. */
private fun measureWidest(text: String, paint: TextPaint): Float =
    text.split('\n').maxOf { paint.measureText(it) }

private class TimedStickerOverlay(
    private val bitmap: Bitmap,
    private val spec: StickerSpec,
    canvasWidth: Int,
    private val startUs: Long,
    private val endUs: Long,
) : BitmapOverlay() {

    private val scale = overlayScaleFor(spec.scale, canvasWidth, bitmap.width)

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        // The same timing text uses; a sticker has no characters to reveal, so
        // TYPE degenerates to a plain cut-in, which is the sensible reading.
        val anim = overlayAnimAt(spec.anim, presentationTimeUs, startUs, endUs, 0)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(spec.anchorX, spec.anchorY + anim.dy)
            .setScale(scale * anim.scale, scale * anim.scale)
            .setRotationDegrees(spec.rotationDeg)
            .setAlphaScale(anim.alpha)
            .build()
    }
}
