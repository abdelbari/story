package com.kinetic.editor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import com.kinetic.editor.core.model.StickerSpec
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType

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

    private const val FADE_US = 150_000L

    fun build(context: Context, state: TimelineState, canvasWidth: Int): OverlayEffect? {
        val overlays = ImmutableList.builder<TextureOverlay>()
        var any = false
        for (track in state.tracks) {
            when (track.type) {
                TrackType.TEXT -> for (p in state.placements(track)) {
                    val spec = p.clip.text ?: continue
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

    internal fun windowAlpha(timeUs: Long, startUs: Long, endUs: Long): Float {
        if (timeUs < startUs || timeUs >= endUs) return 0f
        val inEdge = ((timeUs - startUs).toFloat() / FADE_US).coerceIn(0f, 1f)
        val outEdge = ((endUs - timeUs).toFloat() / FADE_US).coerceIn(0f, 1f)
        return minOf(inEdge, outEdge)
    }
}

private class TimedTextOverlay(
    spec: TextSpec,
    private val startUs: Long,
    private val endUs: Long,
) : TextOverlay() {

    private val anchorX = spec.anchorX
    private val anchorY = spec.anchorY

    // Built once; getText must not allocate per frame.
    private val styled = SpannableString(spec.text).apply {
        setSpan(
            ForegroundColorSpan(spec.argb.toInt()),
            0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        setSpan(
            AbsoluteSizeSpan(spec.textSizePx.toInt()),
            0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    override fun getText(presentationTimeUs: Long): SpannableString = styled

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(anchorX, anchorY)
            .setAlphaScale(OverlayFactory.windowAlpha(presentationTimeUs, startUs, endUs))
            .build()
}

private class TimedStickerOverlay(
    private val bitmap: Bitmap,
    private val spec: StickerSpec,
    canvasWidth: Int,
    private val startUs: Long,
    private val endUs: Long,
) : BitmapOverlay() {

    private val scale = spec.scale * canvasWidth / bitmap.width

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(spec.anchorX, spec.anchorY)
            .setScale(scale, scale)
            .setRotationDegrees(spec.rotationDeg)
            .setAlphaScale(OverlayFactory.windowAlpha(presentationTimeUs, startUs, endUs))
            .build()
}
