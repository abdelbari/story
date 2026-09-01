package com.kinetic.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.kinetic.editor.audio.VolumeEnvelopeAudioProcessor
import com.kinetic.editor.core.model.PipWindow
import com.kinetic.editor.core.model.pipWindows
import com.kinetic.editor.core.model.PlacedClip
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.TransitionSpec
import com.kinetic.editor.core.model.TransitionType
import com.kinetic.editor.core.model.planSequence
import com.kinetic.editor.core.model.transitionWindowsUs
import com.kinetic.editor.effects.ClipGradeProvider
import com.kinetic.editor.effects.GradeGlEffect
import com.kinetic.editor.effects.PipCompositorSettings

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
 *  - VIDEO_OVERLAY     -> one extra video sequence each, placed in time by gaps
 *    (blank frames) and in space by PipCompositorSettings (input id 0 is the
 *    main track), padded to the main track's end so the compositor never runs
 *    out of overlay frames and freezes on the last one.
 *  - Sequences opening with a gap, or with an item lacking a track that later
 *    items carry, need the force-audio/video flags or Transformer fails.
 *  - TEXT/STICKER      -> composition-level OverlayEffect windows (timeline time).
 *  - Canvas size       -> Presentation.createForWidthAndHeight at composition level.
 */
object CompositionMapper {

    fun build(context: Context, state: TimelineState, spec: ExportSpec): Composition {
        val mainPlacements = state.placements(state.mainTrack)
        require(mainPlacements.isNotEmpty()) { "Export requires at least one clip on the main track" }
        val mainDurationMs = mainPlacements.last().endMs

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
        sequences += mainBuilder
            // A video-only first clip must not leave the later clips' audio unmixed.
            .experimentalSetForceAudioTrack(true)
            .build()

        // Secondary VIDEO sequences come first so their input ids (1..n) line up
        // with the placement list handed to the compositor; audio sequences carry
        // no video and are never composited, so they can follow.
        val overlayWindows = ArrayList<List<PipWindow>>()
        for (track in state.tracks) {
            if (track.type != TrackType.VIDEO_OVERLAY || track.clips.isEmpty()) continue
            sequences += overlaySequence(context, state, track, mainDurationMs, lutCache)
            // Placement is per clip and resolved by time, so two PiP clips on one
            // track can sit in different corners.
            overlayWindows += pipWindows(state.placements(track))
        }

        for (track in state.tracks) {
            if (track.type != TrackType.AUDIO || track.muted || track.clips.isEmpty()) continue
            sequences += audioSequence(state, track)
        }

        val compositionVideoEffects = buildList<Effect> {
            add(Presentation.createForWidthAndHeight(spec.width, spec.height, Presentation.LAYOUT_SCALE_TO_FIT))
            com.kinetic.editor.effects.OverlayFactory.build(context, state, spec.width)?.let(::add)
        }

        return Composition.Builder(sequences)
            .setEffects(Effects(/* audioProcessors= */ emptyList(), compositionVideoEffects))
            .setVideoCompositorSettings(
                if (overlayWindows.isEmpty()) {
                    VideoCompositorSettings.DEFAULT
                } else {
                    PipCompositorSettings(overlayWindows)
                },
            )
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

        // Clip-local SOURCE µs windows — the exact same computation the preview
        // engine feeds its shader, so export matches playback frame for frame.
        val windows = transitionWindowsUs(clip, previousTransition)

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
                        transOutStartUs = windows.outStartUs(),
                        transOutEndUs = windows.outEndUs(),
                        transInType = previousTransition?.type ?: TransitionType.NONE,
                        transInEndUs = windows.inEndUs(),
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

    /**
     * A VIDEO_OVERLAY track becomes its own video sequence, positioned in time by
     * gaps and in space by the compositor. Grade/LUT apply per clip just as on
     * the main track; transitions do not, because a transition is a cut between
     * neighbours on one track and a PiP has no cut to sit on.
     */
    private fun overlaySequence(
        context: Context,
        state: TimelineState,
        track: Track,
        padToMs: Long,
        lutCache: MutableMap<String, Bitmap?>,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder()
        var coveredMs = 0L
        for (plan in planSequence(state.placements(track))) {
            val clip = plan.clip
            if (plan.gapBeforeMs > 0) builder.addGap(plan.gapBeforeMs * 1_000L)
            coveredMs = plan.startMs + plan.timelineDurationMs

            val mediaItem = MediaItem.Builder()
                .setUri(clip.media.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(plan.trimInMs)
                        .setEndPositionMs(plan.trimOutMs)
                        .build(),
                )
                .build()

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
                            transOutType = TransitionType.NONE,
                            transOutStartUs = 0L,
                            transOutEndUs = 0L,
                            transInType = TransitionType.NONE,
                            transInEndUs = 0L,
                        ),
                    ),
                )
                if (clip.speed != 1f) add(SpeedChangeEffect(clip.speed))
            }

            val processors = buildList<AudioProcessor> {
                if (clip.speed != 1f) add(SonicAudioProcessor().apply { setSpeed(clip.speed) })
                add(VolumeEnvelopeAudioProcessor(clip.volume * track.volume, clip.volumeKeyframes))
            }

            builder.addItem(
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(processors, videoEffects))
                    .setRemoveAudio(track.muted || !clip.media.hasAudio)
                    .build(),
            )
        }
        // Once a secondary sequence runs dry the compositor keeps re-drawing its
        // final frame, so keep supplying (hidden, see PipCompositorSettings)
        // frames until the main picture is over.
        if (coveredMs < padToMs) builder.addGap((padToMs - coveredMs) * 1_000L)
        return builder
            // Gaps are blank video and silence; these let a sequence open with one.
            .experimentalSetForceAudioTrack(true)
            .experimentalSetForceVideoTrack(true)
            .build()
    }

    private fun audioSequence(state: TimelineState, track: Track): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder()
        for (plan in planSequence(state.placements(track))) {
            val clip = plan.clip
            if (plan.gapBeforeMs > 0) builder.addGap(plan.gapBeforeMs * 1_000L)

            val mediaItem = MediaItem.Builder()
                .setUri(clip.media.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(plan.trimInMs)
                        .setEndPositionMs(plan.trimOutMs)
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
        }
        return builder
            // A track whose first clip starts late opens with a gap of silence.
            .experimentalSetForceAudioTrack(true)
            .build()
    }
}
