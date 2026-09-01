package com.kinetic.editor.core.mvi

import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.LutSpec
import com.kinetic.editor.core.model.MediaRef
import com.kinetic.editor.core.model.PipSpec
import com.kinetic.editor.core.model.StickerSpec
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.TrackId
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

    /** Replaces the whole document (project restore). Clears undo history. */
    data class Replace(val state: TimelineState) : EditorIntent

    data object Undo : EditorIntent
    data object Redo : EditorIntent
}
