package com.kinetic.editor.ui.timeline

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.snapToFrame
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One pointerInput node arbitrates every timeline gesture:
 *
 *   down on empty/clip  + move      -> scrub-scroll (with decay fling)
 *   down                + 2nd finger-> pinch zoom (playhead-anchored) + pan
 *   down on trim handle + move      -> frame-snapped trim (ghost preview)
 *   long-press on clip  + move      -> drag-reorder / retiming (ghost preview)
 *   down + up within slop           -> tap select / deselect
 *
 * The handler mutates ONLY TimelineViewportState (hot tier) during a gesture and
 * emits exactly one commit callback on release. pointerInput(Unit) + provider
 * lambdas keep this node alive across recompositions — no gesture restarts.
 */
interface TimelineGestureCallbacks {
    /** Finger owns the playhead: pause playback, enter player scrubbing mode. */
    fun onScrubStart()

    /** Finger/fling released the playhead: exact seek, leave scrubbing mode. */
    fun onScrubEnd()

    /** A structural gesture (trim/drag) begins: pause playback, haptic tick. */
    fun onEditStart()
    fun onEditEnd()

    fun onTap(hit: TimelineGeometry.Hit)
    fun onTrimCommit(ghost: TrimGhost)
    fun onMoveCommit(ghost: DragGhost)
}

fun Modifier.timelineGestures(
    viewport: TimelineViewportState,
    geometry: TimelineGeometry,
    flingScope: CoroutineScope,
    stateProvider: () -> TimelineState,
    selectionProvider: () -> com.kinetic.editor.core.model.ClipId?,
    callbacks: TimelineGestureCallbacks,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // If a scrub fling is mid-decay, this touch catches the moving timeline:
        // interaction ownership carries over instead of being re-negotiated, so
        // the "settled" callback of the cancelled fling never fires and every
        // path below is responsible for eventually releasing ownership.
        val caughtMomentum = viewport.isUserInteracting
        viewport.stopFling()

        val canvasWidth = size.width.toFloat()
        val state = stateProvider()
        val hit = geometry.hitTest(down.position, canvasWidth, state, selectionProvider())

        when (hit) {
            is TimelineGeometry.Hit.TrimHandle -> {
                if (caughtMomentum) {
                    viewport.isUserInteracting = false
                    callbacks.onScrubEnd()
                }
                trimGesture(down, hit, state, viewport, geometry, callbacks)
            }

            is TimelineGeometry.Hit.Body -> {
                if (caughtMomentum) {
                    scrubGesture(down, hit, viewport, callbacks, flingScope, startAsScrubbing = true)
                } else when (awaitLongPressOrSlop(down)) {
                    // Held still past the timeout: pick the clip up.
                    PressOutcome.LONG_PRESS ->
                        clipDragGesture(down, hit, state, viewport, geometry, callbacks, canvasWidth)
                    // Moved first: scrub, exactly as if the touch had begun on
                    // empty timeline. scrubGesture re-checks slop against `down`,
                    // which we have already exceeded, so it engages immediately.
                    PressOutcome.MOVED ->
                        scrubGesture(down, hit, viewport, callbacks, flingScope)
                    PressOutcome.LIFTED -> callbacks.onTap(hit)
                }
            }

            TimelineGeometry.Hit.None ->
                scrubGesture(down, hit, viewport, callbacks, flingScope, startAsScrubbing = caughtMomentum)
        }
    }
}

private enum class PressOutcome { LONG_PRESS, MOVED, LIFTED }

/**
 * Races the long-press timer against the touch slop.
 *
 * awaitLongPressOrCancellation alone is NOT enough here: it resolves only on
 * pointer-up or a consumed change, never on movement, so a horizontal drag
 * starting on a clip would block for the whole timeout and then be handled as a
 * reorder. Scrubbing must win as soon as the finger travels past slop.
 */
private suspend fun AwaitPointerEventScope.awaitLongPressOrSlop(
    down: PointerInputChange,
): PressOutcome = try {
    withTimeout(viewConfiguration.longPressTimeoutMillis) {
        var outcome = PressOutcome.LIFTED
        while (true) {
            val event = awaitPointerEvent()
            // A second finger means pinch-zoom: hand off to the scrub/zoom path.
            if (event.changes.count { it.pressed } > 1) {
                outcome = PressOutcome.MOVED
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                outcome = PressOutcome.LIFTED
                break
            }
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                outcome = PressOutcome.MOVED
                break
            }
        }
        outcome
    }
} catch (_: PointerEventTimeoutCancellationException) {
    PressOutcome.LONG_PRESS
}

/* ------------------------------- scrubbing ------------------------------- */

private suspend fun AwaitPointerEventScope.scrubGesture(
    down: PointerInputChange,
    hit: TimelineGeometry.Hit,
    viewport: TimelineViewportState,
    callbacks: TimelineGestureCallbacks,
    flingScope: CoroutineScope,
    startAsScrubbing: Boolean = false,
) {
    val velocity = VelocityTracker()
    velocity.addPosition(down.uptimeMillis, down.position)
    var scrubbing = startAsScrubbing

    fun beginScrub() {
        if (!scrubbing) {
            scrubbing = true
            viewport.isUserInteracting = true
            callbacks.onScrubStart()
        }
    }

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }

        if (pressed.isEmpty()) {
            if (!scrubbing) {
                callbacks.onTap(hit)
            } else {
                val v = velocity.calculateVelocity().x
                if (abs(v) > 250f) {
                    // Momentum scrub: viewport stays user-owned until decay settles.
                    viewport.fling(flingScope, -v) {
                        viewport.isUserInteracting = false
                        callbacks.onScrubEnd()
                    }
                } else {
                    viewport.isUserInteracting = false
                    callbacks.onScrubEnd()
                }
            }
            return
        }

        if (pressed.size >= 2) {
            beginScrub()
            viewport.zoomBy(event.calculateZoom())
            viewport.scrollBy(-event.calculatePan().x)
            event.changes.forEach { it.consume() }
            velocity.resetTracking()
        } else {
            val change = pressed.first()
            velocity.addPosition(change.uptimeMillis, change.position)
            if (!scrubbing &&
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            ) {
                beginScrub()
            }
            if (scrubbing) {
                viewport.scrollBy(-change.positionChange().x)
                change.consume()
            }
        }
    }
}

/* ------------------------------- trimming -------------------------------- */

private suspend fun AwaitPointerEventScope.trimGesture(
    down: PointerInputChange,
    hit: TimelineGeometry.Hit.TrimHandle,
    state: TimelineState,
    viewport: TimelineViewportState,
    geometry: TimelineGeometry,
    callbacks: TimelineGestureCallbacks,
) {
    val (track, placed) = state.findPlaced(hit.clipId) ?: return
    val clip = placed.clip
    // fps <= 0 = audio-only media: no frame grid (snapToFrame passes through),
    // just a small minimum span. Clamping fps up to 1 would snap audio trims
    // onto a 1000ms grid.
    val fps = clip.media.fps
    val frameMs = if (fps > 0f) (1000f / fps).roundToLong().coerceAtLeast(1L) else 33L

    callbacks.onEditStart()
    viewport.trimming = TrimGhost(clip.id, hit.edge, clip.trimInMs, clip.trimOutMs, placed.startMs)
    var accumPx = 0f

    drag(down.id) { change ->
        accumPx += change.positionChange().x
        change.consume()
        // Handle moves in TIMELINE pixels; the source-domain delta scales by speed.
        val sourceDeltaMs = (geometry.pxToMs(accumPx) * clip.speed.toDouble()).roundToLong()
        viewport.trimming = when (hit.edge) {
            TrimEdge.START -> {
                val tin = (clip.trimInMs + sourceDeltaMs)
                    .snapToFrame(fps)
                    .coerceIn(0L, clip.trimOutMs - frameMs)
                val startShiftMs = ((tin - clip.trimInMs) / clip.speed.toDouble()).roundToLong()
                TrimGhost(
                    clipId = clip.id,
                    edge = hit.edge,
                    trimInMs = tin,
                    trimOutMs = clip.trimOutMs,
                    // Main track ripples automatically; free tracks keep content anchored.
                    startMs = if (track.type == TrackType.VIDEO_MAIN) placed.startMs
                    else placed.startMs + startShiftMs,
                )
            }
            TrimEdge.END -> {
                val tout = (clip.trimOutMs + sourceDeltaMs)
                    .snapToFrame(fps)
                    .coerceIn(clip.trimInMs + frameMs, clip.media.durationMs)
                TrimGhost(clip.id, hit.edge, clip.trimInMs, tout, placed.startMs)
            }
        }
    }

    // A tap that merely lands on a handle must not enter the undo history.
    viewport.trimming
        ?.takeIf {
            it.trimInMs != clip.trimInMs ||
                it.trimOutMs != clip.trimOutMs ||
                it.startMs != placed.startMs
        }
        ?.let(callbacks::onTrimCommit)
    viewport.trimming = null
    callbacks.onEditEnd()
}

/* ------------------------------ drag-reorder ------------------------------ */

private suspend fun AwaitPointerEventScope.clipDragGesture(
    grab: PointerInputChange,
    hit: TimelineGeometry.Hit.Body,
    state: TimelineState,
    viewport: TimelineViewportState,
    geometry: TimelineGeometry,
    callbacks: TimelineGestureCallbacks,
    canvasWidth: Float,
) {
    val (homeTrack, placed) = state.findPlaced(hit.clipId) ?: return
    val clip = placed.clip
    val homeTrackIndex = state.tracks.indexOfFirst { it.id == homeTrack.id }
    val grabOffsetMs = geometry.xToTime(grab.position.x, canvasWidth) - placed.startMs

    val homeIndex = if (homeTrack.type == TrackType.VIDEO_MAIN) {
        homeTrack.clips.indexOfFirst { it.id == clip.id }
    } else -1

    callbacks.onEditStart()
    viewport.dragging = DragGhost(
        clipId = clip.id,
        ghostStartMs = placed.startMs,
        ghostTrackIndex = homeTrackIndex,
        insertIndex = homeIndex,
        widthMs = clip.durationMs,
    )

    drag(grab.id) { change ->
        change.consume()
        val p = change.position

        // Auto-scroll when hovering the viewport edges so long moves don't stall.
        when {
            p.x < geometry.edgeAutoScrollZonePx -> viewport.scrollBy(-14f)
            p.x > canvasWidth - geometry.edgeAutoScrollZonePx -> viewport.scrollBy(14f)
        }

        // Only lanes of the same type accept the clip; otherwise stay home.
        val hoverIndex = geometry.trackIndexAtY(state, p.y)
        val targetIndex = if (
            hoverIndex >= 0 && state.tracks[hoverIndex].type == homeTrack.type
        ) hoverIndex else homeTrackIndex
        val targetTrack = state.tracks[targetIndex]

        val rawStartMs = (geometry.xToTime(p.x, canvasWidth) - grabOffsetMs).coerceAtLeast(0L)

        viewport.dragging = if (targetTrack.type == TrackType.VIDEO_MAIN) {
            val (index, slotStartMs) = mainTrackSlot(state, targetTrack.clips, clip, rawStartMs)
            DragGhost(clip.id, slotStartMs, targetIndex, index, clip.durationMs)
        } else {
            val snapped = snapToNeighbors(rawStartMs, clip, state, targetTrack, viewport, geometry)
            DragGhost(clip.id, snapped, targetIndex, -1, clip.durationMs)
        }
    }

    // Same for a long-press released without moving the clip anywhere.
    viewport.dragging
        ?.takeIf {
            it.ghostStartMs != placed.startMs ||
                it.ghostTrackIndex != homeTrackIndex ||
                it.insertIndex != homeIndex
        }
        ?.let(callbacks::onMoveCommit)
    viewport.dragging = null
    callbacks.onEditEnd()
}

/** Insertion slot on the sequential main track: index + the slot's start time. */
private fun mainTrackSlot(
    state: TimelineState,
    clips: List<ClipModel>,
    dragged: ClipModel,
    rawStartMs: Long,
): Pair<Int, Long> {
    val others = clips.filter { it.id != dragged.id }
    val pointerMidMs = rawStartMs + dragged.durationMs / 2
    var cursor = 0L
    var index = others.size
    var slotStart = others.sumOf { it.durationMs }
    for ((i, c) in others.withIndex()) {
        if (pointerMidMs < cursor + c.durationMs / 2) {
            index = i
            slotStart = cursor
            break
        }
        cursor += c.durationMs
    }
    return index to slotStart
}

/** Magnetic snapping to the playhead and neighboring clip edges on free tracks. */
private fun snapToNeighbors(
    rawStartMs: Long,
    dragged: ClipModel,
    state: TimelineState,
    targetTrack: com.kinetic.editor.core.model.Track,
    viewport: TimelineViewportState,
    geometry: TimelineGeometry,
): Long {
    val thresholdMs = geometry.pxToMs(geometry.snapThresholdPx)
    var best = rawStartMs
    var bestDist = Long.MAX_VALUE

    fun consider(candidateStart: Long) {
        val d = abs(candidateStart - rawStartMs)
        if (d <= thresholdMs && d < bestDist) {
            best = candidateStart; bestDist = d
        }
    }

    consider(viewport.playheadMs)                       // start snaps to playhead
    consider(viewport.playheadMs - dragged.durationMs)  // end snaps to playhead
    for (p in state.placements(targetTrack)) {
        if (p.clip.id == dragged.id) continue
        consider(p.endMs)                      // butt against previous clip
        consider(p.startMs - dragged.durationMs) // butt against next clip
    }
    return best.coerceAtLeast(0L)
}
