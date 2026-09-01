package com.kinetic.editor

import androidx.compose.ui.geometry.Offset
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.MediaRef
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.ui.timeline.TimelineGeometry
import com.kinetic.editor.ui.timeline.TrimEdge
import com.kinetic.editor.ui.timeline.ViewportReader
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hit-testing and time<->pixel math run on every touch and every drawn frame, so
 * they are worth pinning down. TimelineGeometry depends only on [ViewportReader]
 * (two floats) and a density scalar, which is what lets these run as plain JVM
 * tests with no Compose runtime.
 */
class TimelineGeometryTest {

    private class FakeViewport(
        override val pxPerMs: Float = 0.06f,
        override val scrollXPx: Float = 0f,
    ) : ViewportReader

    private fun clip(id: String, durMs: Long) = ClipModel(
        ClipId(id),
        MediaRef("uri://$id", durMs, true, true, 30f, 1920, 1080),
        0, durMs,
    )

    private fun stateWith(main: List<ClipModel>): TimelineState {
        val e = TimelineState.empty()
        return e.copy(
            tracks = e.tracks.map { t ->
                if (t.type == TrackType.VIDEO_MAIN) t.copy(clips = main.toPersistentList()) else t
            }.toPersistentList(),
        )
    }

    @Test
    fun timeAndPixelsRoundTripAroundTheCenteredPlayhead() {
        val g = TimelineGeometry(FakeViewport(pxPerMs = 0.1f, scrollXPx = 500f), dp = 2f)
        val width = 1000f
        assertEquals(500f, g.timeToX(5_000, width), 0.01f)   // playhead sits at center
        assertEquals(600f, g.timeToX(6_000, width), 0.01f)
        assertEquals(5_000L, g.xToTime(500f, width))
        assertEquals(2_500L, g.pxToMs(250f))
        assertEquals(250f, g.msToPx(2_500), 0.01f)
        assertEquals(1_234L, g.xToTime(g.timeToX(1_234, width), width))
    }

    @Test
    fun lanesStackInTrackOrderAndResolveByY() {
        val g = TimelineGeometry(FakeViewport(), dp = 1f)
        val state = stateWith(listOf(clip("a", 4_000)))
        assertEquals(31f, g.laneTop(state, 0), 0.01f)           // ruler 28 + gap 3
        assertEquals(90f, g.laneTop(state, 1), 0.01f)           // + main 56 + gap 3
        assertEquals(0, g.trackIndexAtY(state, 40f))
        assertEquals(-1, g.trackIndexAtY(state, 5f))            // in the ruler
        assertEquals(-1, g.trackIndexAtY(state, 88f))           // in the inter-lane gap
    }

    @Test
    fun hitTestPrefersSelectedHandlesThenBodies() {
        val g = TimelineGeometry(FakeViewport(pxPerMs = 0.1f, scrollXPx = 0f), dp = 1f)
        val width = 1000f
        val a = clip("a", 4_000)                                 // drawn x 500..900
        val state = stateWith(listOf(a))
        val laneY = g.laneTop(state, 0) + 10f

        val body = g.hitTest(Offset(700f, laneY), width, state, null)
        assertTrue(body is TimelineGeometry.Hit.Body)
        assertEquals(a.id, (body as TimelineGeometry.Hit.Body).clipId)

        val start = g.hitTest(Offset(502f, laneY), width, state, a.id)
        assertEquals(TrimEdge.START, (start as TimelineGeometry.Hit.TrimHandle).edge)

        val end = g.hitTest(Offset(898f, laneY), width, state, a.id)
        assertEquals(TrimEdge.END, (end as TimelineGeometry.Hit.TrimHandle).edge)

        assertTrue(g.hitTest(Offset(700f, laneY), width, state, a.id) is TimelineGeometry.Hit.Body)
        assertEquals(TimelineGeometry.Hit.None, g.hitTest(Offset(950f, laneY), width, state, a.id))
        assertEquals(TimelineGeometry.Hit.None, g.hitTest(Offset(700f, 2f), width, state, a.id))
    }

    /** A clip zoomed down to a few pixels must still be trimmable. */
    @Test
    fun handlesStayGrabbableOnAClipNarrowerThanTheHandles() {
        val g = TimelineGeometry(FakeViewport(pxPerMs = 0.01f, scrollXPx = 0f), dp = 1f)
        val tiny = clip("t", 400)                                // 4px wide
        val state = stateWith(listOf(tiny))
        val hit = g.hitTest(Offset(500f, g.laneTop(state, 0) + 10f), 1000f, state, tiny.id)
        assertTrue(hit is TimelineGeometry.Hit.TrimHandle)
        assertEquals(TrimEdge.START, (hit as TimelineGeometry.Hit.TrimHandle).edge)
    }
}
