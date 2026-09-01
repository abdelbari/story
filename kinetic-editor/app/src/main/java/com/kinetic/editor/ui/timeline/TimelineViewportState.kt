package com.kinetic.editor.ui.timeline

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kinetic.editor.core.model.ClipId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The only two viewport values the geometry layer reads. Depending on this
 * instead of the whole mutable viewport keeps time<->pixel math a pure function
 * of two floats — which is what makes it testable without a Compose runtime.
 */
interface ViewportReader {
    val pxPerMs: Float
    val scrollXPx: Float
}

/**
 * HOT interaction state — the second tier of the two-tier state model.
 *
 * The MVI store holds the cold, committed document. Everything that changes at
 * input/animation frequency (scroll, zoom, in-progress trims and drags) lives
 * here as Compose snapshot state and is read ONLY from the draw phase and
 * gesture handlers. Result: scrubbing at 120Hz never recomposes the tree and
 * never touches the store; a gesture commits exactly one intent on release.
 *
 * Invariant: the playhead is fixed at the viewport's horizontal center and
 * content scrolls beneath it (CapCut model), so `scrollXPx == playheadMs * pxPerMs`.
 * Zooming therefore anchors on the playhead for free.
 */
@Stable
class TimelineViewportState(
    initialPxPerMs: Float = 0.06f, // 60 px per second
) : ViewportReader {
    override var pxPerMs by mutableFloatStateOf(initialPxPerMs)
        private set

    override var scrollXPx by mutableFloatStateOf(0f)
        private set

    val playheadMs: Long
        get() = (scrollXPx / pxPerMs).toLong().coerceAtLeast(0L)

    /** True while a finger is down on the timeline OR a scrub fling is decaying. */
    var isUserInteracting by mutableStateOf(false)

    /** Set by the host so scroll can be clamped to the document. */
    var durationMsProvider: () -> Long = { 0L }

    /** In-progress gesture ghosts; rendered by the canvas, absent from the document. */
    var trimming: TrimGhost? by mutableStateOf(null)
    var dragging: DragGhost? by mutableStateOf(null)

    private var flingJob: Job? = null

    private val maxScrollPx: Float
        get() = durationMsProvider() * pxPerMs

    fun scrollBy(deltaPx: Float) {
        scrollXPx = (scrollXPx + deltaPx).coerceIn(0f, maxScrollPx)
    }

    fun scrollToMs(ms: Long) {
        scrollXPx = (ms * pxPerMs).coerceIn(0f, maxScrollPx)
    }

    /** Player → viewport sync path; ignored while the user owns the position. */
    fun syncFromPlayer(positionMs: Long) {
        if (!isUserInteracting) scrollToMs(positionMs)
    }

    /** Playhead-anchored zoom: 4 px/s (overview) … 600 px/s (~20 px per 30fps frame). */
    fun zoomBy(factor: Float) {
        if (factor == 1f || factor.isNaN()) return
        val anchorMs = playheadMs
        pxPerMs = (pxPerMs * factor).coerceIn(0.004f, 0.6f)
        scrollXPx = (anchorMs * pxPerMs).coerceIn(0f, maxScrollPx)
    }

    fun stopFling() {
        flingJob?.cancel()
        flingJob = null
    }

    /**
     * Momentum scrubbing. The viewport stays "user owned" until the decay
     * settles, so the player keeps following the flung playhead.
     */
    fun fling(scope: CoroutineScope, velocityPxPerSec: Float, onSettled: () -> Unit) {
        stopFling()
        flingJob = scope.launch {
            var interrupted = false
            try {
                AnimationState(initialValue = scrollXPx, initialVelocity = velocityPxPerSec)
                    .animateDecay(exponentialDecay(frictionMultiplier = 1.4f)) {
                        val clamped = value.coerceIn(0f, maxScrollPx)
                        scrollXPx = clamped
                        if (clamped != value) cancelAnimation() // hit an edge
                    }
            } catch (e: CancellationException) {
                interrupted = true // a new touch took over; it now owns interaction state
                throw e
            } finally {
                flingJob = null
                if (!interrupted) onSettled()
            }
        }
    }
}

/** Live trim preview. Times already snapped/clamped; committed as one TrimClip intent. */
data class TrimGhost(
    val clipId: ClipId,
    val edge: TrimEdge,
    val trimInMs: Long,
    val trimOutMs: Long,
    /** Resolved timeline start of the ghost (overlay left-trims shift placement). */
    val startMs: Long,
)

enum class TrimEdge { START, END }

/** Live drag-reorder preview. */
data class DragGhost(
    val clipId: ClipId,
    val ghostStartMs: Long,
    val ghostTrackIndex: Int,
    /** VIDEO_MAIN insertion slot; -1 on freely-placed tracks. */
    val insertIndex: Int,
    val widthMs: Long,
)
