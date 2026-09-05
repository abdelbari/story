package com.kinetic.editor.core.mvi

import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.ClipMotion
import com.kinetic.editor.core.model.MaskSpec
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TransformSpec
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.snapToFrame
import com.kinetic.editor.core.model.sourceToTimelineMs
import com.kinetic.editor.core.model.timelineToSourceMs
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.roundToLong

/**
 * Pure, synchronous, allocation-light. Persistent collections give structural
 * sharing, so an edit copies O(changed clips), not the whole document — which is
 * also what makes the undo stack nearly free.
 */
fun reduce(state: TimelineState, intent: EditorIntent): TimelineState = when (intent) {
    is EditorIntent.AddClip -> reduceAdd(state, intent)
    is EditorIntent.RemoveClip -> mapTracks(state) { t ->
        if (t.clips.none { it.id == intent.clipId }) t
        else t.copy(clips = t.clips.filterNot { it.id == intent.clipId }.toPersistentList())
    }
    is EditorIntent.MoveClip -> reduceMove(state, intent)
    is EditorIntent.TrimClip -> replaceClip(state, intent.clipId) { c ->
        // A freeze is one frame; its length is its hold, not a trim.
        if (c.freezeMs > 0L) return@replaceClip c
        // fps <= 0 (audio-only media, voiceovers): no frame grid to snap to —
        // snapToFrame passes through and a small fixed minimum span applies.
        val fps = c.media.fps
        val minSpan = if (fps > 0f) (1000f / fps).roundToLong().coerceAtLeast(1L) else 33L
        val tin = intent.trimInMs.snapToFrame(fps)
            .coerceIn(0L, (c.media.durationMs - minSpan).coerceAtLeast(0L))
        val tout = intent.trimOutMs.snapToFrame(fps)
            .coerceIn(tin + minSpan, maxOf(c.media.durationMs, tin + minSpan))
        c.copy(
            trimInMs = tin,
            trimOutMs = tout,
            startMs = intent.startMs ?: c.startMs,
            // Envelope times are clip-relative; clamp any keyframe the trim orphaned.
            volumeKeyframes = c.volumeKeyframes
                .map { kf ->
                    kf.copy(atMs = kf.atMs.coerceAtMost(c.copy(trimInMs = tin, trimOutMs = tout).durationMs))
                }
                .toPersistentList(),
        )
    }
    is EditorIntent.SplitClip -> reduceSplit(state, intent)
    is EditorIntent.SetSpeed -> replaceClip(state, intent.clipId) {
        if (it.freezeMs > 0L) it else it.copy(speed = intent.speed.coerceIn(0.1f, 8f))
    }
    is EditorIntent.SetSpeedCurve -> replaceClip(state, intent.clipId) {
        if (it.freezeMs > 0L) it else it.copy(curve = intent.curve)
    }
    is EditorIntent.SetFreezeHold -> replaceClip(state, intent.clipId) {
        if (it.freezeMs <= 0L) it else it.copy(freezeMs = intent.holdMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS))
    }
    is EditorIntent.FreezeFrame -> reduceFreeze(state, intent)
    is EditorIntent.SetGrade -> replaceClip(state, intent.clipId) { it.copy(grade = intent.grade) }
    is EditorIntent.SetLut -> replaceClip(state, intent.clipId) { it.copy(lut = intent.lut) }
    is EditorIntent.ApplyFilter -> replaceClip(state, intent.clipId) {
        it.copy(grade = intent.grade, lut = intent.lut)
    }
    is EditorIntent.SetChroma -> replaceClip(state, intent.clipId) { clip ->
        clip.copy(
            chroma = intent.chroma?.copy(
                // A zero tolerance keys nothing, which is the same as no key at
                // all; a softness wider than the tolerance would key the frame.
                tolerance = intent.chroma.tolerance.coerceIn(0.01f, 1f),
                softness = intent.chroma.softness.coerceIn(0f, 0.5f),
            ),
        )
    }
    is EditorIntent.SetMask -> replaceClip(state, intent.clipId) {
        it.copy(mask = intent.mask?.let(::clampMask))
    }
    is EditorIntent.SetFlip -> replaceClip(state, intent.clipId) {
        it.copy(flipX = intent.flipX, flipY = intent.flipY)
    }
    is EditorIntent.SetEffect -> replaceClip(state, intent.clipId) {
        it.copy(effect = intent.effect, effectAmount = intent.amount.coerceIn(0f, 1f))
    }
    is EditorIntent.SetMotion -> replaceClip(state, intent.clipId) { it.copy(motion = intent.motion) }
    is EditorIntent.SetTransformEnd -> replaceClip(state, intent.clipId) {
        it.copy(transformEnd = intent.transform?.let(::clampTransform))
    }
    is EditorIntent.SetTransform -> replaceClip(state, intent.clipId) {
        it.copy(transform = clampTransform(intent.transform))
    }
    is EditorIntent.SetTransition -> replaceClip(state, intent.clipId) {
        it.copy(transitionOut = intent.transition)
    }
    is EditorIntent.SetVolume -> replaceClip(state, intent.clipId) {
        it.copy(volume = intent.volume.coerceIn(0f, 2f))
    }
    is EditorIntent.SetVolumeKeyframes -> replaceClip(state, intent.clipId) {
        it.copy(volumeKeyframes = intent.keyframes.sortedBy { kf -> kf.atMs }.toPersistentList())
    }
    is EditorIntent.SetText -> replaceClip(state, intent.clipId) {
        // Only meaningful on a clip that already is a text overlay.
        if (it.text == null) it else it.copy(text = intent.text)
    }
    is EditorIntent.SetPip -> replaceClip(state, intent.clipId) {
        it.copy(
            pip = intent.pip.copy(
                scale = intent.pip.scale.coerceIn(0.05f, 1f),
                anchorX = intent.pip.anchorX.coerceIn(-1f, 1f),
                anchorY = intent.pip.anchorY.coerceIn(-1f, 1f),
                opacity = intent.pip.opacity.coerceIn(0f, 1f),
            ),
        )
    }
    is EditorIntent.SetSticker -> replaceClip(state, intent.clipId) {
        // Only meaningful on a clip that already is a sticker.
        if (it.sticker == null) {
            it
        } else {
            it.copy(
                sticker = intent.sticker.copy(
                    scale = intent.sticker.scale.coerceIn(0.05f, 1f),
                    anchorX = intent.sticker.anchorX.coerceIn(-1f, 1f),
                    anchorY = intent.sticker.anchorY.coerceIn(-1f, 1f),
                ),
            )
        }
    }
    is EditorIntent.DuplicateClip -> reduceDuplicate(state, intent.clipId)
    is EditorIntent.DetachAudio -> reduceDetachAudio(state, intent.clipId)
    is EditorIntent.SetTrackMuted -> mapTracks(state) { t ->
        if (t.id == intent.trackId) t.copy(muted = intent.muted) else t
    }
    is EditorIntent.SetCanvasFit -> state.copy(canvasFit = intent.fit)
    is EditorIntent.SetCanvasBackground -> state.copy(canvasBackground = intent.background)
    is EditorIntent.SetCanvas -> state.copy(
        // Hardware encoders want even dimensions; keep both within sane bounds.
        outputWidth = (intent.width.coerceIn(16, 4096) / 2) * 2,
        outputHeight = (intent.height.coerceIn(16, 4096) / 2) * 2,
    )
    // A restored project keeps its own revision counter continuity via the store.
    is EditorIntent.Replace -> intent.state
    // Undo/Redo are handled by the store's history, never by the reducer.
    EditorIntent.Undo, EditorIntent.Redo -> state
}

private fun reduceAdd(state: TimelineState, intent: EditorIntent.AddClip): TimelineState {
    val tin = intent.trimInMs.coerceAtLeast(0L)
    val tout = intent.trimOutMs.coerceAtMost(
        if (intent.media.durationMs > 0) intent.media.durationMs else intent.trimOutMs,
    )
    // Zero/unknown-duration media (failed probe, some ADTS/webm streams) must
    // never become a clip: ClipModel's invariants would throw inside dispatch.
    if (tout <= tin) return state
    val clip = ClipModel(
        id = ClipId.random(),
        media = intent.media,
        trimInMs = tin,
        trimOutMs = tout,
        startMs = intent.startMs,
        text = intent.text,
        sticker = intent.sticker,
        pip = intent.pip,
    )
    return mapTracks(state) { t ->
        if (t.id != intent.trackId) t
        else t.copy(
            clips = t.clips.mutate { list ->
                if (t.type == TrackType.VIDEO_MAIN) {
                    list.add((intent.index ?: list.size).coerceIn(0, list.size), clip)
                } else {
                    list.add(clip)
                    list.sortBy { it.startMs }
                }
            },
        )
    }
}

private fun reduceMove(state: TimelineState, intent: EditorIntent.MoveClip): TimelineState {
    val (fromTrack, clip) = state.findClip(intent.clipId) ?: return state
    val stripped = mapTracks(state) { t ->
        if (t.id != fromTrack.id) t
        else t.copy(clips = t.clips.filterNot { it.id == clip.id }.toPersistentList())
    }
    return mapTracks(stripped) { t ->
        if (t.id != intent.toTrackId) t
        else t.copy(
            clips = t.clips.mutate { list ->
                if (t.type == TrackType.VIDEO_MAIN) {
                    val moved = clip.copy(startMs = 0L)
                    list.add((intent.toIndex ?: list.size).coerceIn(0, list.size), moved)
                } else {
                    list.add(clip.copy(startMs = (intent.toStartMs ?: clip.startMs).coerceAtLeast(0L)))
                    list.sortBy { it.startMs }
                }
            },
        )
    }
}

private fun reduceSplit(state: TimelineState, intent: EditorIntent.SplitClip): TimelineState {
    val (track, placed) = state.findPlaced(intent.clipId) ?: return state
    val clip = placed.clip
    // One frame has no inside to cut.
    if (clip.freezeMs > 0L) return state
    val fps = clip.media.fps.takeIf { it > 0f } ?: 30f
    val frameMs = (1000f / fps).roundToLong().coerceAtLeast(1L)

    // Both guards are required: the timeline guard keeps the split point away
    // from the clip's visual edges, the SOURCE guard guarantees the clamp below
    // has room for one frame on each side (a 0.5x clip can pass the first
    // while violating the second).
    if (clip.sourceSpanMs < 2 * frameMs) return state
    val offsetTimelineMs = intent.atTimelineMs - placed.startMs
    if (offsetTimelineMs < frameMs || offsetTimelineMs > clip.durationMs - frameMs) return state

    // Map the timeline split point back into the source domain, on the frame grid.
    val splitSourceMs = (clip.trimInMs + clip.timelineToSourceMs(offsetTimelineMs))
        .snapToFrame(fps)
        .coerceIn(clip.trimInMs + frameMs, clip.trimOutMs - frameMs)
    val (first, second) = splitAtSource(clip, splitSourceMs, track.type, placed.startMs)

    return mapTracks(state) { t ->
        if (t.id != track.id) t
        else t.copy(
            clips = t.clips.mutate { list ->
                val idx = list.indexOfFirst { it.id == clip.id }
                if (idx >= 0) {
                    list[idx] = first
                    list.add(idx + 1, second)
                }
            },
        )
    }
}

/**
 * The two clips a cut at [splitSourceMs] leaves, each playing exactly as its
 * part of the original did: the envelope is rebased, and a speed curve is
 * sliced so the rate on either side of the cut is the rate that was there.
 */
private fun splitAtSource(
    clip: ClipModel,
    splitSourceMs: Long,
    trackType: TrackType,
    startMs: Long,
): Pair<ClipModel, ClipModel> {
    val splitAtTimeline = clip.sourceToTimelineMs(splitSourceMs - clip.trimInMs)
    val t = (splitSourceMs - clip.trimInMs).toFloat() / clip.sourceSpanMs
    val first = clip.copy(
        trimOutMs = splitSourceMs,
        transitionOut = null,
        volumeKeyframes = clip.volumeKeyframes.filter { it.atMs < splitAtTimeline }.toPersistentList(),
        curve = clip.curve?.slice(0f, t),
    )
    val second = clip.copy(
        id = ClipId.random(),
        trimInMs = splitSourceMs,
        startMs = if (trackType == TrackType.VIDEO_MAIN) 0L else startMs + splitAtTimeline,
        volumeKeyframes = clip.volumeKeyframes
            .filter { it.atMs >= splitAtTimeline }
            .map { it.copy(atMs = it.atMs - splitAtTimeline) }
            .toPersistentList(),
        curve = clip.curve?.slice(t, 1f),
    )
    return first to second
}

private const val MIN_HOLD_MS = 100L
private const val MAX_HOLD_MS = 15_000L

/**
 * A freeze frame: the frame under the playhead becomes a clip of its own, one
 * source frame long and [EditorIntent.FreezeFrame.holdMs] on the timeline,
 * between the two halves of the clip it came from. The frame belongs to the
 * second half, so the picture resumes from the very frame it held on.
 *
 * Main track only: the export renders a freeze as an image item, and only the
 * main sequence builds those.
 */
private fun reduceFreeze(state: TimelineState, intent: EditorIntent.FreezeFrame): TimelineState {
    val (track, placed) = state.findPlaced(intent.clipId) ?: return state
    val clip = placed.clip
    if (track.type != TrackType.VIDEO_MAIN || !clip.media.hasVideo || clip.freezeMs > 0L) return state
    val fps = clip.media.fps.takeIf { it > 0f } ?: 30f
    val frameMs = (1000f / fps).roundToLong().coerceAtLeast(1L)
    if (clip.sourceSpanMs < frameMs) return state

    val offsetMs = (intent.atTimelineMs - placed.startMs).coerceIn(0L, clip.durationMs)
    val frameSourceMs = (clip.trimInMs + clip.timelineToSourceMs(offsetMs))
        .snapToFrame(fps)
        .coerceIn(clip.trimInMs, (clip.trimOutMs - frameMs).coerceAtLeast(clip.trimInMs))

    val hold = clip.copy(
        id = ClipId.random(),
        trimInMs = frameSourceMs,
        trimOutMs = frameSourceMs + frameMs,
        freezeMs = intent.holdMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS),
        speed = 1f,
        curve = null,
        transitionOut = null,
        volumeKeyframes = persistentListOf(),
        // A held frame has no span for a move to run across.
        motion = ClipMotion.NONE,
        transformEnd = null,
    )
    val pieces = if (frameSourceMs - clip.trimInMs < frameMs) {
        // At the clip's first frame: the hold goes in front of the whole clip.
        listOf(hold, clip)
    } else {
        val (before, after) = splitAtSource(clip, frameSourceMs, track.type, placed.startMs)
        listOf(before, hold, after)
    }
    return mapTracks(state) { t ->
        if (t.id != track.id) t
        else t.copy(
            clips = t.clips.mutate { list ->
                val idx = list.indexOfFirst { it.id == clip.id }
                if (idx >= 0) {
                    list.removeAt(idx)
                    list.addAll(idx, pieces)
                }
            },
        )
    }
}

/**
 * Moves a clip's sound onto the audio track, at the same moment in the
 * timeline, and silences the video it came from. Both halves matter: leaving
 * the original audible would double it, and placing the copy anywhere but the
 * clip's own start would put the sound out of sync with the picture.
 *
 * The copy keeps the source's trims and speed, so it plays exactly the samples
 * the clip was playing — until the user moves it, which is the point.
 */
private fun reduceDetachAudio(state: TimelineState, id: ClipId): TimelineState {
    val (track, clip) = state.findClip(id) ?: return state
    if (track.type == TrackType.AUDIO || !clip.media.hasAudio) return state
    val audioTrack = state.tracks.firstOrNull { it.type == TrackType.AUDIO } ?: return state
    val startMs = state.placements(track).firstOrNull { it.clip.id == id }?.startMs ?: return state
    val lifted = clip.copy(
        id = ClipId.random(),
        startMs = startMs,
        // Sound only: the visual specs would be meaningless on an audio lane.
        text = null,
        sticker = null,
        pip = null,
    )
    return mapTracks(state) { t ->
        when (t.id) {
            audioTrack.id -> t.copy(clips = t.clips.add(lifted))
            track.id -> t.copy(
                clips = t.clips.map { if (it.id == id) it.copy(volume = 0f) else it }
                    .toPersistentList(),
            )
            else -> t
        }
    }
}

/**
 * A copy of the clip, right where the user is looking: next in line on the
 * sequential main track, and immediately after itself in time on a freely
 * placed one, where dropping it at the same start would hide it under the
 * original.
 */
private fun reduceDuplicate(state: TimelineState, id: ClipId): TimelineState {
    val (track, clip) = state.findClip(id) ?: return state
    val copy = clip.copy(id = ClipId.random())
    return mapTracks(state) { t ->
        if (t.id != track.id) {
            t
        } else if (t.type == TrackType.VIDEO_MAIN) {
            val idx = t.clips.indexOfFirst { it.id == id }
            t.copy(clips = t.clips.add(idx + 1, copy))
        } else {
            t.copy(clips = t.clips.add(copy.copy(startMs = clip.startMs + clip.durationMs)))
        }
    }
}

/**
 * Bounds a framing to what the shader can sample: a zero or negative scale
 * divides the sampling coordinate by nothing, and the offsets bound how far
 * off-frame a clip can be pushed before it is simply gone.
 */
private fun clampTransform(t: TransformSpec) = t.copy(
    scale = t.scale.coerceIn(0.1f, 8f),
    offsetX = t.offsetX.coerceIn(-2f, 2f),
    offsetY = t.offsetY.coerceIn(-2f, 2f),
    rotationDeg = t.rotationDeg.coerceIn(-180f, 180f),
)

/**
 * Bounds a mask to shapes that mean something: a zero size is no mask at all,
 * a feather wider than half the frame is a fade, and the centre may sit a
 * little off-frame so a shape can enter from an edge.
 */
private fun clampMask(m: MaskSpec) = m.copy(
    centerX = m.centerX.coerceIn(-1.5f, 1.5f),
    centerY = m.centerY.coerceIn(-1.5f, 1.5f),
    size = m.size.coerceIn(0.02f, 3f),
    aspect = m.aspect.coerceIn(0.2f, 5f),
    roundness = m.roundness.coerceIn(0f, 1f),
    rotationDeg = m.rotationDeg.coerceIn(-180f, 180f),
    feather = m.feather.coerceIn(0f, 0.5f),
)

private inline fun mapTracks(state: TimelineState, transform: (Track) -> Track): TimelineState {
    var changed = false
    val newTracks = state.tracks.map { t ->
        val nt = transform(t)
        if (nt !== t) changed = true
        nt
    }
    return if (changed) state.copy(tracks = newTracks.toPersistentList()) else state
}

private inline fun replaceClip(
    state: TimelineState,
    id: ClipId,
    transform: (ClipModel) -> ClipModel,
): TimelineState = mapTracks(state) { t ->
    val idx = t.clips.indexOfFirst { it.id == id }
    if (idx < 0) return@mapTracks t
    val replacement = transform(t.clips[idx])
    // Identity-preserving no-op: a gesture or slider that lands on the values
    // the clip already has must not manufacture an undo entry.
    if (replacement == t.clips[idx]) t
    else t.copy(clips = t.clips.mutate { it[idx] = replacement })
}
