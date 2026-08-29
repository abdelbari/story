package com.kinetic.editor.ui.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.PlacedClip
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import kotlin.math.abs

/**
 * Pure time<->pixel math and manual hit-testing for the single-node timeline.
 *
 * All methods read [viewport] AT CALL TIME. Calls happen inside the draw phase
 * and inside pointer handlers — never during composition — so scroll/zoom
 * changes invalidate only the draw pass, not the composable tree.
 */
class TimelineGeometry(
    private val viewport: TimelineViewportState,
    density: Density,
) {
    private val dp: Float = density.density

    val rulerHeightPx = 28f * dp
    val laneGapPx = 3f * dp
    val handleWidthPx = 14f * dp
    val handleTouchSlopPx = 12f * dp
    val clipCornerPx = 6f * dp
    val snapThresholdPx = 10f * dp
    val edgeAutoScrollZonePx = 56f * dp
    val thumbSlotWidthPx = 40f * dp

    private val laneHeights = floatArrayOf(
        56f * dp, // VIDEO_MAIN
        44f * dp, // VIDEO_OVERLAY
        26f * dp, // TEXT
        26f * dp, // STICKER
        40f * dp, // AUDIO
    )

    fun laneHeightPx(type: TrackType): Float = laneHeights[type.ordinal]

    fun laneTop(state: TimelineState, trackIndex: Int): Float {
        var y = rulerHeightPx + laneGapPx
        for (i in 0 until trackIndex) {
            y += laneHeightPx(state.tracks[i].type) + laneGapPx
        }
        return y
    }

    fun totalHeightPx(state: TimelineState): Float =
        laneTop(state, state.tracks.size) // one-past-last == bottom edge

    fun timeToX(ms: Long, canvasWidthPx: Float): Float =
        canvasWidthPx * 0.5f + ms * viewport.pxPerMs - viewport.scrollXPx

    fun xToTime(x: Float, canvasWidthPx: Float): Long =
        ((x - canvasWidthPx * 0.5f + viewport.scrollXPx) / viewport.pxPerMs).toLong()

    fun pxToMs(px: Float): Long = (px / viewport.pxPerMs).toLong()

    fun msToPx(ms: Long): Float = ms * viewport.pxPerMs

    fun clipRect(
        state: TimelineState,
        trackIndex: Int,
        placed: PlacedClip,
        canvasWidthPx: Float,
    ): Rect {
        val top = laneTop(state, trackIndex)
        val left = timeToX(placed.startMs, canvasWidthPx)
        val right = timeToX(placed.endMs, canvasWidthPx)
        return Rect(left, top, right, top + laneHeightPx(state.tracks[trackIndex].type))
    }

    sealed interface Hit {
        data class TrimHandle(val clipId: ClipId, val edge: TrimEdge, val trackIndex: Int) : Hit
        data class Body(val clipId: ClipId, val trackIndex: Int) : Hit
        data object None : Hit
    }

    /**
     * Priority: selected clip's trim handles (with generous touch slop), then any
     * clip body, then empty timeline (scrub). Iterates straight over the immutable
     * state — no spatial index needed at editor scale (tens of clips).
     */
    fun hitTest(
        pos: Offset,
        canvasWidthPx: Float,
        state: TimelineState,
        selection: ClipId?,
    ): Hit {
        if (selection != null) {
            val found = state.findPlaced(selection)
            if (found != null) {
                val (track, placed) = found
                val trackIndex = state.tracks.indexOfFirst { it.id == track.id }
                val rect = clipRect(state, trackIndex, placed, canvasWidthPx)
                if (pos.y in (rect.top - handleTouchSlopPx)..(rect.bottom + handleTouchSlopPx)) {
                    val dLeft = abs(pos.x - rect.left)
                    val dRight = abs(pos.x - rect.right)
                    if (dLeft <= handleWidthPx + handleTouchSlopPx && dLeft <= dRight) {
                        return Hit.TrimHandle(selection, TrimEdge.START, trackIndex)
                    }
                    if (dRight <= handleWidthPx + handleTouchSlopPx) {
                        return Hit.TrimHandle(selection, TrimEdge.END, trackIndex)
                    }
                }
            }
        }
        for (trackIndex in state.tracks.indices) {
            val track = state.tracks[trackIndex]
            val top = laneTop(state, trackIndex)
            val bottom = top + laneHeightPx(track.type)
            if (pos.y < top || pos.y > bottom) continue
            for (placed in state.placements(track)) {
                val left = timeToX(placed.startMs, canvasWidthPx)
                val right = timeToX(placed.endMs, canvasWidthPx)
                if (pos.x in left..right) return Hit.Body(placed.clip.id, trackIndex)
            }
        }
        return Hit.None
    }

    /** Which lane a drag ghost hovers; -1 between lanes. */
    fun trackIndexAtY(state: TimelineState, y: Float): Int {
        for (i in state.tracks.indices) {
            val top = laneTop(state, i)
            if (y >= top && y <= top + laneHeightPx(state.tracks[i].type)) return i
        }
        return -1
    }
}
