package com.kinetic.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.kinetic.editor.audio.VolumeEnvelopeAudioProcessor
import com.kinetic.editor.core.model.CanvasFit
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.SpeedRunLookup
import com.kinetic.editor.core.model.speedRuns
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
import com.kinetic.editor.effects.canvasFillEffect
import com.kinetic.editor.effects.PipCompositorSettings
import kotlin.math.roundToInt

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
 *    ClippingConfiguration, per-clip GLSL grade/LUT/transition BEFORE the speed
 *    change (so shader windows stay in clip-local SOURCE time) and the volume
 *    envelope AFTER it (clip TIMELINE time, the domain keyframes are authored
 *    in). Speed itself is media3's interlinked audio/video pair; see
 *    [speedEffects].
 *  - AUDIO tracks      -> one audio-only sequence each; placement gaps become
 *    addGap() silences. Composition mixes all sequences sample-accurately.
 *  - VIDEO_OVERLAY     -> one extra video sequence each, placed in time by gaps
 *    (blank frames) and in space by PipCompositorSettings (input id 0 is the
 *    main track), padded to the main track's end so the compositor never runs
 *    out of overlay frames and freezes on the last one.
 *  - Sequences opening with a gap, or with an item lacking a track that later
 *    items carry, need the force-audio/video flags or Transformer fails.
 *  - TEXT/STICKER      -> composition-level OverlayEffect windows (timeline time).
 *  - Canvas size/fit   -> Presentation on every MAIN item, so the compositor
 *    and the overlays all work in canvas coordinates, plus once more at
 *    composition level as a guarantee of the encoded size.
 */
object CompositionMapper {

    /**
     * @param freezeFrames the held frame of each freeze on the main track, as
     *   [FreezeFrames] extracted it; a freeze without one plays its video item
     *   very slowly instead (see `speedRuns`), which holds the frame but gives
     *   the overlays nothing to animate over.
     */
    fun build(
        context: Context,
        state: TimelineState,
        spec: ExportSpec,
        freezeFrames: Map<ClipId, Uri> = emptyMap(),
    ): Composition {
        val mainPlacements = state.placements(state.mainTrack)
        require(mainPlacements.isNotEmpty()) { "Export requires at least one clip on the main track" }
        val mainDurationMs = mainPlacements.last().endMs

        val lutCache = HashMap<String, Bitmap?>()
        val sequences = ArrayList<EditedMediaItemSequence>()

        val mainBuilder = EditedMediaItemSequence.Builder()
        var previousTransition: TransitionSpec? = null
        for (placed in mainPlacements) {
            val frame = if (placed.clip.freezeMs > 0L) freezeFrames[placed.clip.id] else null
            mainBuilder.addItem(
                if (frame != null) freezeItem(context, state, spec, placed.clip, frame, lutCache)
                else mainTrackItem(context, state, spec, placed, previousTransition, lutCache),
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
            // The main items are already canvas-sized, so this is a no-op in the
            // ordinary case; it is here as a guarantee that whatever reaches the
            // encoder is the size the user asked for.
            add(presentation(spec, state.canvasFit))
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
        state: TimelineState,
        spec: ExportSpec,
        placed: PlacedClip,
        previousTransition: TransitionSpec?,
        lutCache: MutableMap<String, Bitmap?>,
    ): EditedMediaItem {
        val track = state.mainTrack
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

        // A freeze without an extracted frame plays here, very slowly and
        // silently: a frame of sound stretched over seconds is not audio.
        val removeAudio = track.muted || !clip.media.hasAudio || clip.freezeMs > 0L
        val speed = speedEffects(clip, headOffsetMs = 0L, keepsAudio = !removeAudio)

        val videoEffects = buildList<Effect> {
            add(
                GradeGlEffect(
                    ClipGradeProvider(
                        grade = clip.grade,
                        chroma = clip.chroma,
                        transform = clip.transform,
                        transformEnd = clip.transformEnd,
                        motion = clip.motion,
                        spanUs = clip.sourceSpanMs * 1_000L,
                        flipX = clip.flipX,
                        flipY = clip.flipY,
                        mask = clip.mask,
                        effect = clip.effect,
                        effectAmount = clip.effectAmount,
                        // The encoder's input surface ignores alpha, exactly as
                        // the preview's SurfaceView does.
                        opaque = true,
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
            // After the grade, so the shader still sees clip-local source time.
            speed.videoEffect?.let(::add)
            // The letterbox fill, when the project has one; it outputs the
            // canvas size, which makes the Presentation after it a no-op.
            canvasFillEffect(spec.width, spec.height, state.canvasFit, state.canvasBackground)
                ?.let(::add)
            // Last, and per item rather than once for the whole composition: it
            // makes every main frame canvas-sized BEFORE the compositor sees it,
            // which is what puts picture-in-picture and overlays in the same
            // coordinate space the preview lays them out in. See build().
            add(presentation(spec, state.canvasFit))
        }

        val audioProcessors = buildList<AudioProcessor> {
            speed.audioProcessor?.let(::add)
            // After the speed change, so the envelope runs in clip timeline time.
            add(VolumeEnvelopeAudioProcessor(clip.volume * track.volume, clip.volumeKeyframes))
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .setRemoveAudio(removeAudio)
            .build()
    }

    /**
     * A freeze frame as media3 renders it best: an image item, which produces
     * real frames at the project's rate for the whole hold — so captions
     * animate across it and picture-in-picture keeps moving, exactly as they
     * do in the preview. Grade, mask and effects apply to those frames as to
     * any other; there is no speed change and no audio.
     */
    private fun freezeItem(
        context: Context,
        state: TimelineState,
        spec: ExportSpec,
        clip: ClipModel,
        frame: Uri,
        lutCache: MutableMap<String, Bitmap?>,
    ): EditedMediaItem {
        val holdUs = clip.freezeMs * 1_000L
        val mediaItem = MediaItem.Builder()
            .setUri(frame)
            .setMimeType(MimeTypes.IMAGE_PNG)
            // The asset loader treats an item as an image only with this set.
            .setImageDurationMs(clip.freezeMs)
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
                        chroma = clip.chroma,
                        transform = clip.transform,
                        transformEnd = clip.transformEnd,
                        motion = clip.motion,
                        // Image frames arrive in timeline time, over the hold.
                        spanUs = holdUs,
                        flipX = clip.flipX,
                        flipY = clip.flipY,
                        mask = clip.mask,
                        effect = clip.effect,
                        effectAmount = clip.effectAmount,
                        opaque = true,
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
            canvasFillEffect(spec.width, spec.height, state.canvasFit, state.canvasBackground)
                ?.let(::add)
            add(presentation(spec, state.canvasFit))
        }

        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(holdUs)
            .setFrameRate(state.projectFps.roundToInt().coerceAtLeast(1))
            .setEffects(Effects(/* audioProcessors= */ emptyList(), videoEffects))
            .build()
    }

    /** The canvas, and how a differently-shaped clip is fitted into it. */
    private fun presentation(spec: ExportSpec, fit: CanvasFit): Presentation =
        Presentation.createForWidthAndHeight(
            spec.width,
            spec.height,
            when (fit) {
                CanvasFit.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
                CanvasFit.FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                CanvasFit.STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
            },
        )

    /**
     * The speed change for one item, as media3 wants it applied.
     *
     * `SpeedChangeEffect` plus a Sonic processor set to the same factor are two
     * independent timestamp mappings with independent rounding, and that is what
     * drifts audio against video over a long clip. media3 ships an interlinked
     * pair for exactly this, and documents the plain video effect as the choice
     * "when input has no audio" — which is the split here.
     */
    private fun speedEffects(clip: ClipModel, headOffsetMs: Long, keepsAudio: Boolean): SpeedEffects {
        val lookup = SpeedRunLookup(clip.speedRuns(), headOffsetMs)
        if (lookup.isConstant && lookup.constantSpeed == 1f) return SpeedEffects(null, null)
        val provider = RunSpeedProvider(lookup)
        return when {
            keepsAudio -> {
                val pair = Effects.createExperimentalSpeedChangingEffect(provider)
                SpeedEffects(pair.first, pair.second)
            }
            lookup.isConstant -> SpeedEffects(null, SpeedChangeEffect(lookup.constantSpeed))
            else -> SpeedEffects(null, SpeedChangeEffect(provider))
        }
    }

    private class SpeedEffects(val audioProcessor: AudioProcessor?, val videoEffect: Effect?)

    /**
     * media3's view of a clip's runs. The rule about the next change being
     * strictly after the time asked lives in [SpeedRunLookup], where it is
     * tested; this only translates its "none" into media3's TIME_UNSET.
     */
    private class RunSpeedProvider(private val lookup: SpeedRunLookup) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = lookup.speedAtUs(timeUs)
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
            lookup.nextChangeUs(timeUs).let { if (it < 0L) C.TIME_UNSET else it }
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

            val removeAudio = track.muted || !clip.media.hasAudio
            // Item-local time starts at the plan's head, which an overlap may
            // have trimmed into the clip.
            val speed = speedEffects(
                clip,
                headOffsetMs = plan.trimInMs - clip.trimInMs,
                keepsAudio = !removeAudio,
            )

            val videoEffects = buildList<Effect> {
                add(
                    GradeGlEffect(
                        ClipGradeProvider(
                            grade = clip.grade,
                            chroma = clip.chroma,
                            transform = clip.transform,
                            transformEnd = clip.transformEnd,
                            motion = clip.motion,
                            spanUs = clip.sourceSpanMs * 1_000L,
                            flipX = clip.flipX,
                            flipY = clip.flipY,
                            mask = clip.mask,
                            effect = clip.effect,
                            effectAmount = clip.effectAmount,
                            // Straight alpha: the compositor blends this over
                            // the main picture, and a mask or key must let it
                            // through.
                            opaque = false,
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
                speed.videoEffect?.let(::add)
            }

            val processors = buildList<AudioProcessor> {
                speed.audioProcessor?.let(::add)
                add(VolumeEnvelopeAudioProcessor(clip.volume * track.volume, clip.volumeKeyframes))
            }

            builder.addItem(
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(processors, videoEffects))
                    .setRemoveAudio(removeAudio)
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
                // No video on this sequence, so the audio half of the pair stands
                // alone — the same processor media3's own audio graph uses.
                val lookup = SpeedRunLookup(clip.speedRuns(), plan.trimInMs - clip.trimInMs)
                if (!(lookup.isConstant && lookup.constantSpeed == 1f)) {
                    add(SpeedChangingAudioProcessor(RunSpeedProvider(lookup)))
                }
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
