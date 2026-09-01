package com.kinetic.editor.core.mvi

import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.snapToFrame
import kotlinx.collections.immutable.mutate
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
                .map { kf -> kf.copy(atMs = kf.atMs.coerceAtMost(((tout - tin) / c.speed).toLong())) }
                .toPersistentList(),
        )
    }
    is EditorIntent.SplitClip -> reduceSplit(state, intent)
    is EditorIntent.SetSpeed -> replaceClip(state, intent.clipId) {
        it.copy(speed = intent.speed.coerceIn(0.1f, 8f))
    }
    is EditorIntent.SetGrade -> replaceClip(state, intent.clipId) { it.copy(grade = intent.grade) }
    is EditorIntent.SetLut -> replaceClip(state, intent.clipId) { it.copy(lut = intent.lut) }
    is EditorIntent.SetTransition -> replaceClip(state, intent.clipId) {
        it.copy(transitionOut = intent.transition)
    }
    is EditorIntent.SetVolume -> replaceClip(state, intent.clipId) {
        it.copy(volume = intent.volume.coerceIn(0f, 2f))
    }
    is EditorIntent.SetVolumeKeyframes -> replaceClip(state, intent.clipId) {
        it.copy(volumeKeyframes = intent.keyframes.sortedBy { kf -> kf.atMs }.toPersistentList())
    }
    is EditorIntent.SetTrackMuted -> mapTracks(state) { t ->
        if (t.id == intent.trackId) t.copy(muted = intent.muted) else t
    }
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
    val splitSourceMs = (clip.trimInMs + offsetTimelineMs * clip.speed.toDouble())
        .roundToLong()
        .snapToFrame(fps)
        .coerceIn(clip.trimInMs + frameMs, clip.trimOutMs - frameMs)
    val splitAtTimeline = ((splitSourceMs - clip.trimInMs) / clip.speed.toDouble()).roundToLong()

    val first = clip.copy(
        trimOutMs = splitSourceMs,
        transitionOut = null,
        volumeKeyframes = clip.volumeKeyframes.filter { it.atMs < splitAtTimeline }.toPersistentList(),
    )
    val second = clip.copy(
        id = ClipId.random(),
        trimInMs = splitSourceMs,
        startMs = if (track.type == TrackType.VIDEO_MAIN) 0L else placed.startMs + splitAtTimeline,
        volumeKeyframes = clip.volumeKeyframes
            .filter { it.atMs >= splitAtTimeline }
            .map { it.copy(atMs = it.atMs - splitAtTimeline) }
            .toPersistentList(),
    )

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
