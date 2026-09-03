package com.kinetic.editor.core.mvi

import com.kinetic.editor.core.model.CanvasFit
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipMotion
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.LutSpec
import com.kinetic.editor.core.model.MediaRef
import com.kinetic.editor.core.model.PipSpec
import com.kinetic.editor.core.model.StickerSpec
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.TrackId
import com.kinetic.editor.core.model.TransformSpec
import com.kinetic.editor.core.model.TransitionSpec
import com.kinetic.editor.core.model.VolumeKeyframe
import kotlinx.collections.immutable.ImmutableList

/**
 * Every document mutation flows through exactly one of these. Gestures preview
 * their effect locally (hot state in TimelineViewportState) and dispatch a single
 * intent on release — the store is never hammered at input frequency, except for
 * intents that opt into undo-coalescing via [coalesceKey] (effect sliders).
 */
sealed interface EditorIntent {

    /** Same non-null key across consecutive intents = one undo entry for the burst. */
    val coalesceKey: String? get() = null

    data class AddClip(
        val trackId: TrackId,
        val media: MediaRef,
        val index: Int? = null,      // VIDEO_MAIN insertion position (null = append)
        val startMs: Long = 0L,      // freely-placed tracks
        val trimInMs: Long = 0L,
        val trimOutMs: Long = media.durationMs,
        val text: TextSpec? = null,
        val sticker: StickerSpec? = null,
        val pip: PipSpec? = null,
    ) : EditorIntent

    data class RemoveClip(val clipId: ClipId) : EditorIntent

    /** Copies a clip in place: after it on the main track, beside it elsewhere. */
    data class DuplicateClip(val clipId: ClipId) : EditorIntent

    /**
     * Lifts a clip's sound onto the audio track and silences the source, so the
     * two can be trimmed, faded and moved apart from each other.
     */
    data class DetachAudio(val clipId: ClipId) : EditorIntent

    data class MoveClip(
        val clipId: ClipId,
        val toTrackId: TrackId,
        val toIndex: Int? = null,    // VIDEO_MAIN
        val toStartMs: Long? = null, // freely-placed tracks
    ) : EditorIntent

    /** Committed once per trim gesture; values pre-snapped to the source frame grid. */
    data class TrimClip(
        val clipId: ClipId,
        val trimInMs: Long,
        val trimOutMs: Long,
        val startMs: Long? = null,   // overlay left-edge trim also shifts placement
    ) : EditorIntent

    data class SplitClip(val clipId: ClipId, val atTimelineMs: Long) : EditorIntent

    data class SetSpeed(val clipId: ClipId, val speed: Float) : EditorIntent {
        override val coalesceKey get() = "speed:${clipId.value}"
    }

    data class SetGrade(val clipId: ClipId, val grade: ColorGradeSpec) : EditorIntent {
        override val coalesceKey get() = "grade:${clipId.value}"
    }

    data class SetLut(val clipId: ClipId, val lut: LutSpec?) : EditorIntent {
        override val coalesceKey get() = "lut:${clipId.value}"
    }

    /**
     * A look, applied as one edit. Grade and LUT are separate intents so each
     * can coalesce a slider drag on its own; a filter sets both at once and
     * should cost the user a single undo, not two.
     */
    data class ApplyFilter(
        val clipId: ClipId,
        val grade: ColorGradeSpec,
        val lut: LutSpec?,
    ) : EditorIntent

    /** A move that runs across the whole clip, on top of its transform. */
    data class SetMotion(val clipId: ClipId, val motion: ClipMotion) : EditorIntent

    /** Pan, zoom and rotate the picture inside its frame. */
    data class SetTransform(val clipId: ClipId, val transform: TransformSpec) : EditorIntent {
        override val coalesceKey get() = "xform:${clipId.value}"
    }

    data class SetTransition(val clipId: ClipId, val transition: TransitionSpec?) : EditorIntent

    data class SetVolume(val clipId: ClipId, val volume: Float) : EditorIntent {
        override val coalesceKey get() = "volume:${clipId.value}"
    }

    data class SetVolumeKeyframes(
        val clipId: ClipId,
        val keyframes: ImmutableList<VolumeKeyframe>,
    ) : EditorIntent {
        override val coalesceKey get() = "env:${clipId.value}"
    }

    data class SetTrackMuted(val trackId: TrackId, val muted: Boolean) : EditorIntent

    /** Edits an existing text overlay's content and styling. */
    data class SetText(val clipId: ClipId, val text: TextSpec) : EditorIntent {
        override val coalesceKey get() = "text:${clipId.value}"
    }

    /** Moves/scales a picture-in-picture overlay. */
    data class SetPip(val clipId: ClipId, val pip: PipSpec) : EditorIntent {
        override val coalesceKey get() = "pip:${clipId.value}"
    }

    /** Moves/scales/rotates a sticker overlay. */
    data class SetSticker(val clipId: ClipId, val sticker: StickerSpec) : EditorIntent {
        override val coalesceKey get() = "sticker:${clipId.value}"
    }

    /**
     * Output canvas in pixels. Preview letterboxes the picture into it and the
     * export renders at exactly this size, so a landscape project is a choice,
     * not a crop.
     */
    data class SetCanvas(val width: Int, val height: Int) : EditorIntent

    /** Letterbox, crop or stretch a clip whose shape differs from the canvas. */
    data class SetCanvasFit(val fit: CanvasFit) : EditorIntent

    /** Replaces the whole document (project restore). Clears undo history. */
    data class Replace(val state: TimelineState) : EditorIntent

    data object Undo : EditorIntent
    data object Redo : EditorIntent
}
