package com.kinetic.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.kinetic.editor.audio.VolumeEnvelopeAudioProcessor
import com.kinetic.editor.core.model.PlacedClip
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.TransitionSpec
import com.kinetic.editor.core.model.TransitionType
import com.kinetic.editor.effects.ClipGradeProvider
import com.kinetic.editor.effects.GradeGlEffect
import kotlin.math.roundToLong

data class ExportSpec(
    val width: Int = 1080,
    val height: Int = 1920,
    val videoMimeType: String = MimeTypes.VIDEO_H264, // VIDEO_H265 for 4K exports
    val videoBitrate: Int = 12_000_000,
)

/**
 * The single translation point from the editor document to Media3's Composition.
 * Pure and synchronous (LUT/sticker assets are small, decoded inline); called on
 * the export thread right before Transformer.start.
 *
 * Mapping rules:
 *  - Main video track  -> primary EditedMediaItemSequence. Trims via
 *    ClippingConfiguration, per-clip GLSL grade/LUT/transition BEFORE
 *    SpeedChangeEffect (so shader windows are in clip-local SOURCE time),
 *    audio speed via Sonic + envelope AFTER it (clip TIMELINE time).
 *  - AUDIO tracks      -> one audio-only sequence each; placement gaps become
 *    addGap() silences. Composition mixes all sequences sample-accurately.
 *  - TEXT/STICKER      -> composition-level OverlayEffect windows (timeline time).
 *  - Canvas size       -> Presentation.createForWidthAndHeight at composition level.
 */
object CompositionMapper {

    fun build(context: Context, state: TimelineState, spec: ExportSpec): Composition {
        val mainPlacements = state.placements(state.mainTrack)
        require(mainPlacements.isNotEmpty()) { "Export requires at least one clip on the main track" }

        val lutCache = HashMap<String, Bitmap?>()
        val sequences = ArrayList<EditedMediaItemSequence>()

        val mainBuilder = EditedMediaItemSequence.Builder()
        var previousTransition: TransitionSpec? = null
        for (placed in mainPlacements) {
            mainBuilder.addItem(
                mainTrackItem(context, state.mainTrack, placed, previousTransition, lutCache),
            )
            previousTransition = placed.clip.transitionOut
        }
        sequences += mainBuilder.build()

        for (track in state.tracks) {
            if (track.type != TrackType.AUDIO || track.muted || track.clips.isEmpty()) continue
            sequences += audioSequence(state, track)
        }

        val compositionVideoEffects = buildList<Effect> {
            add(Presentation.createForWidthAndHeight(spec.width, spec.height, Presentation.LAYOUT_SCALE_TO_FIT))
            com.kinetic.editor.effects.OverlayFactory.build(context, state)?.let(::add)
        }

        return Composition.Builder(sequences)
            .setEffects(Effects(/* audioProcessors= */ emptyList(), compositionVideoEffects))
            // Audio sequences may lead with gaps and main clips may be muted or
            // video-only; force a (possibly silent) audio track so mixing always
            // has a primary stream to align to.
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    private fun mainTrackItem(
        context: Context,
        track: Track,
        placed: PlacedClip,
        previousTransition: TransitionSpec?,
        lutCache: MutableMap<String, Bitmap?>,
    ): EditedMediaItem {
        val clip = placed.clip

        val mediaItem = MediaItem.Builder()
            .setUri(clip.media.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimInMs)
                    .setEndPositionMs(clip.trimOutMs)
                    .build(),
            )
            .build()

        // Transition halves in clip-local SOURCE µs (pre-speed timestamps):
        // a timeline-ms duration converts by *speed.
        val durUs = clip.sourceSpanMs * 1_000L
        val outHalfUs = clip.transitionOut
            ?.let { (it.durationMs * 500L * clip.speed).roundToLong() }
            ?.coerceAtMost(durUs / 2) ?: 0L
        val inHalfUs = previousTransition
            ?.takeIf { it.type != TransitionType.NONE }
            ?.let { (it.durationMs * 500L * clip.speed).roundToLong() }
            ?.coerceAtMost(durUs / 2) ?: 0L

        val lutBitmap = clip.lut?.let { lut ->
            lutCache.getOrPut(lut.assetPath) {
                runCatching {
                    context.assets.open(lut.assetPath).use(BitmapFactory::decodeStream)
                }.getOrNull()
            }
        }

        val videoEffects = buildList<Effect> {
            add(
                GradeGlEffect(
                    ClipGradeProvider(
                        grade = clip.grade,
                        lutBitmap = lutBitmap,
                        lutIntensity = clip.lut?.intensity ?: 0f,
                        transOutType = clip.transitionOut?.type ?: TransitionType.NONE,
                        transOutStartUs = durUs - outHalfUs,
                        transOutEndUs = durUs,
                        transInType = previousTransition?.type ?: TransitionType.NONE,
                        transInEndUs = inHalfUs,
                    ),
                ),
            )
            if (clip.speed != 1f) add(SpeedChangeEffect(clip.speed))
        }

        val audioProcessors = buildList<AudioProcessor> {
            if (clip.speed != 1f) {
                add(SonicAudioProcessor().apply { setSpeed(clip.speed) })
            }
            add(VolumeEnvelopeAudioProcessor(clip.volume * track.volume, clip.volumeKeyframes))
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .setRemoveAudio(track.muted || !clip.media.hasAudio)
            .build()
    }

    private fun audioSequence(state: TimelineState, track: Track): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder()
        var cursorMs = 0L
        for (placed in state.placements(track).sortedBy { it.startMs }) {
            val clip = placed.clip

            // A sequence is strictly serial: an overlap with the previous clip
            // cannot be mixed here (that needs a second track). Cut the head of
            // the later clip instead, so the audio that DOES play stays exactly
            // time-aligned with the preview rather than silently shifting right.
            var trimInMs = clip.trimInMs
            var startMs = placed.startMs
            val overlapMs = cursorMs - startMs
            if (overlapMs > 0) {
                trimInMs += (overlapMs * clip.speed.toDouble()).roundToLong()
                startMs = cursorMs
                if (trimInMs >= clip.trimOutMs) continue // fully covered
            }

            val gapMs = startMs - cursorMs
            if (gapMs > 0) builder.addGap(gapMs * 1_000L)

            val mediaItem = MediaItem.Builder()
                .setUri(clip.media.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trimInMs)
                        .setEndPositionMs(clip.trimOutMs)
                        .build(),
                )
                .build()

            val processors = buildList<AudioProcessor> {
                if (clip.speed != 1f) add(SonicAudioProcessor().apply { setSpeed(clip.speed) })
                add(VolumeEnvelopeAudioProcessor(clip.volume * track.volume, clip.volumeKeyframes))
            }

            builder.addItem(
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(processors, /* videoEffects= */ emptyList()))
                    .setRemoveVideo(true) // music sourced from mp4 stays audio-only
                    .build(),
            )
            cursorMs = startMs +
                ((clip.trimOutMs - trimInMs) / clip.speed.toDouble()).roundToLong()
        }
        return builder.build()
    }
}
