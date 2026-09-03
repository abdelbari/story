package com.kinetic.editor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import com.kinetic.editor.core.model.StickerSpec
import com.kinetic.editor.core.model.OverlayAnim
import com.kinetic.editor.core.model.OverlayAnimState
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.overlayAnimAt
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

}

private class TimedTextOverlay(
    private val spec: TextSpec,
    private val startUs: Long,
    private val endUs: Long,
) : TextOverlay() {

    private val anchorX = spec.anchorX
    private val anchorY = spec.anchorY

    // Built once; getText must not allocate per frame.
    private val styled = SpannableString(spec.text).apply {
        fun span(what: Any) = setSpan(what, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        span(ForegroundColorSpan(spec.argb.toInt()))
        span(AbsoluteSizeSpan(spec.textSizePx.toInt()))
        // The family name Compose resolves its own FontFamily from, so preview
        // and render land on the same face rather than two similar ones.
        span(TypefaceSpan(spec.font.androidFamily))
        span(
            StyleSpan(
                when {
                    spec.bold && spec.italic -> Typeface.BOLD_ITALIC
                    spec.bold -> Typeface.BOLD
                    spec.italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            ),
        )
    }

    /**
     * Every prefix of the styled text, built up front. Typing has to hand back a
     * different string each frame, and getText runs on the GL thread: better a
     * few dozen small objects at export start than an allocation per frame.
     */
    private val prefixes: List<SpannableString>? =
        if (spec.anim == OverlayAnim.TYPE && spec.text.length <= MAX_TYPED) {
            (0..spec.text.length).map { n -> SpannableString(styled.subSequence(0, n)) }
        } else {
            null
        }

    private fun stateAt(timeUs: Long): OverlayAnimState =
        overlayAnimAt(spec.anim, timeUs, startUs, endUs, spec.text.length)

    override fun getText(presentationTimeUs: Long): SpannableString {
        val chars = stateAt(presentationTimeUs).visibleChars
        if (chars < 0 || prefixes == null) return styled
        return prefixes[chars.coerceIn(0, prefixes.size - 1)]
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val anim = stateAt(presentationTimeUs)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(anchorX, anchorY + anim.dy)
            .setScale(anim.scale, anim.scale)
            .setAlphaScale(anim.alpha)
            .build()
    }

    private companion object {
        /** Beyond this, typing shows everything rather than pre-building a novel. */
        const val MAX_TYPED = 240
    }
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
