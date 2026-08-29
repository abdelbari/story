package com.kinetic.editor

import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.MediaRef
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.TransitionType
import com.kinetic.editor.core.model.VolumeKeyframe
import com.kinetic.editor.core.model.audioStructureHash
import com.kinetic.editor.core.model.gainAt
import com.kinetic.editor.core.model.snapToFrame
import com.kinetic.editor.core.model.videoStructureHash
import com.kinetic.editor.core.mvi.EditorIntent
import com.kinetic.editor.core.mvi.EditorStore
import com.kinetic.editor.core.mvi.reduce
import com.kinetic.editor.effects.ClipGradeProvider
import com.kinetic.editor.effects.FxSegment
import com.kinetic.editor.effects.GradeUniformsBuffer
import com.kinetic.editor.effects.PreviewFxProvider
import com.kinetic.editor.effects.PreviewFxTimeline
import com.kinetic.editor.engine.PreviewSegments
import com.kinetic.editor.engine.Segment
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the document/engine math. Runs without Robolectric:
 * android.os.SystemClock is the only framework call on these paths, covered by
 * testOptions.unitTests.isReturnDefaultValues in app/build.gradle.kts.
 */
class CoreLogicTest {

    private fun media(dur: Long, fps: Float = 30f) =
        MediaRef("uri://m$dur", dur, true, true, fps, 1920, 1080)

    private fun clip(
        id: String, dur: Long, tin: Long = 0, tout: Long = dur,
        speed: Float = 1f, start: Long = 0,
    ) = ClipModel(ClipId(id), media(dur), tin, tout, start, speed)

    private fun stateWith(
        main: List<ClipModel>,
        audio: List<ClipModel> = emptyList(),
    ): TimelineState {
        val e = TimelineState.empty()
        return e.copy(
            tracks = e.tracks.map { tr ->
                when (tr.type) {
                    TrackType.VIDEO_MAIN -> tr.copy(clips = main.toPersistentList())
                    TrackType.AUDIO -> tr.copy(clips = audio.toPersistentList())
                    else -> tr
                }
            }.toPersistentList(),
        )
    }

    @Test
    fun durationAppliesSpeed() {
        assertEquals(3_000L, clip("a", 10_000, 2_000, 8_000, speed = 2f).durationMs)
        assertEquals(12_000L, clip("a", 10_000, 2_000, 8_000, speed = 0.5f).durationMs)
    }

    @Test
    fun snapToFrameIsIdempotentOnTheGrid() {
        assertEquals(33L, 33L.snapToFrame(30f))
        assertEquals(67L, 50L.snapToFrame(30f))
        assertEquals(67L, 67L.snapToFrame(30f))
        assertEquals(1_001L, 1_000L.snapToFrame(29.97f))
        assertEquals(500L, 500L.snapToFrame(0f))
    }

    @Test
    fun placementsDeriveMainPrefixSums() {
        val s = stateWith(
            main = listOf(clip("a", 3_000), clip("b", 2_000)),
            audio = listOf(clip("x", 1_000, start = 4_500)),
        )
        val main = s.placements(s.mainTrack)
        assertEquals(0L, main[0].startMs)
        assertEquals(3_000L, main[1].startMs)
        assertEquals(5_500L, s.durationMs)
    }

    @Test
    fun trimClampsToMediaMinSpanAndKeyframes() {
        val c = clip("a", 10_000).copy(
            volumeKeyframes = persistentListOf(VolumeKeyframe(9_000, 0.5f)),
        )
        val s1 = reduce(stateWith(listOf(c)), EditorIntent.TrimClip(c.id, 1_000, 15_000))
        val c1 = s1.mainTrack.clips[0]
        assertEquals(1_000L, c1.trimInMs)
        assertEquals(10_000L, c1.trimOutMs)
        val s2 = reduce(stateWith(listOf(c)), EditorIntent.TrimClip(c.id, 5_000, 5_010))
        val c2 = s2.mainTrack.clips[0]
        assertTrue(c2.trimOutMs - c2.trimInMs >= 33L)
    }

    @Test
    fun splitConservesDurationAndRebasesKeyframes() {
        val c = clip("a", 10_000).copy(
            volumeKeyframes = persistentListOf(VolumeKeyframe(1_000, 0.2f), VolumeKeyframe(7_000, 0.8f)),
        )
        val clips = reduce(stateWith(listOf(c)), EditorIntent.SplitClip(c.id, 4_000)).mainTrack.clips
        assertEquals(2, clips.size)
        assertEquals(clips[0].trimOutMs, clips[1].trimInMs)
        assertEquals(10_000L, clips[0].durationMs + clips[1].durationMs)
        assertEquals(listOf(3_000L), clips[1].volumeKeyframes.map { it.atMs })
    }

    @Test
    fun splitMapsTimelineOffsetThroughSpeed() {
        val c = clip("a", 10_000, speed = 2f)
        val clips = reduce(stateWith(listOf(c)), EditorIntent.SplitClip(c.id, 2_000)).mainTrack.clips
        assertEquals(4_000L, clips[0].trimOutMs)
        assertEquals(2_000L, clips[0].durationMs)
        assertEquals(3_000L, clips[1].durationMs)
    }

    @Test
    fun splitRejectsSubFrameRemainders() {
        val c = clip("a", 10_000)
        val s0 = stateWith(listOf(c))
        assertTrue(reduce(s0, EditorIntent.SplitClip(c.id, 10)) === s0)
        assertTrue(reduce(s0, EditorIntent.SplitClip(c.id, 9_995)) === s0)
    }

    @Test
    fun addRejectsZeroDurationMedia() {
        val s0 = stateWith(emptyList())
        val bad = MediaRef("uri://bad", 0, true, true, 30f, 0, 0)
        assertTrue(reduce(s0, EditorIntent.AddClip(s0.mainTrack.id, bad)) === s0)
    }

    @Test
    fun splitRejectsSourceSpanUnderTwoFrames() {
        val c = clip("s", 1_000, 0, 50, speed = 0.5f) // timeline 100ms, source 50ms
        val s0 = stateWith(listOf(c))
        assertTrue(reduce(s0, EditorIntent.SplitClip(c.id, 50)) === s0)
    }

    @Test
    fun audioTrimIsNotSnappedToSeconds() {
        val audioRef = MediaRef("uri://voice", 10_000, false, true, 0f, 0, 0)
        val c = ClipModel(ClipId("v"), audioRef, 0, 10_000)
        val s0 = stateWith(emptyList(), audio = listOf(c))
        val s1 = reduce(s0, EditorIntent.TrimClip(c.id, 0, 1_234))
        assertEquals(1_234L, s1.tracks.first { it.type == TrackType.AUDIO }.clips[0].trimOutMs)
    }

    @Test
    fun moveReordersMainAndResortsFreeTracks() {
        val a = clip("a", 1_000); val b = clip("b", 1_000); val c = clip("c", 1_000)
        val s0 = stateWith(listOf(a, b, c))
        val s1 = reduce(s0, EditorIntent.MoveClip(c.id, s0.mainTrack.id, toIndex = 0))
        assertEquals(listOf("c", "a", "b"), s1.mainTrack.clips.map { it.id.value })
    }

    @Test
    fun gainInterpolatesEnvelope() {
        val c = clip("a", 10_000).copy(
            volume = 2f,
            volumeKeyframes = persistentListOf(VolumeKeyframe(0, 1f), VolumeKeyframe(1_000, 0f)),
        )
        assertEquals(1f, c.gainAt(500), 1e-3f)
        assertEquals(0f, c.gainAt(5_000), 1e-3f)
    }

    @Test
    fun structureHashesSeparateCosmeticFromStructural() {
        val c = clip("a", 10_000)
        val s0 = stateWith(listOf(c))
        assertTrue(s0.videoStructureHash() != reduce(s0, EditorIntent.TrimClip(c.id, 1_000, 9_000)).videoStructureHash())
        assertEquals(
            s0.videoStructureHash(),
            reduce(s0, EditorIntent.SetGrade(c.id, ColorGradeSpec(brightness = 0.3f))).videoStructureHash(),
        )
        assertTrue(
            s0.audioStructureHash() != reduce(
                s0,
                EditorIntent.AddClip(s0.tracks.first { it.type == TrackType.AUDIO }.id, media(500), startMs = 100),
            ).audioStructureHash(),
        )
    }

    @Test
    fun storeCoalescesUndoAndInvalidatesSelection() {
        Dispatchers.setMain(StandardTestDispatcher())
        runTest {
            val storeScope = CoroutineScope(coroutineContext + Job())
            val store = EditorStore(storeScope) { _, _ -> }
            val mainId = store.timeline.value.mainTrack.id
            store.dispatch(EditorIntent.AddClip(mainId, media(5_000)))
            advanceUntilIdle()
            val id = store.timeline.value.mainTrack.clips[0].id

            store.select(id)
            store.dispatch(EditorIntent.SetGrade(id, ColorGradeSpec(brightness = 0.1f)))
            store.dispatch(EditorIntent.SetGrade(id, ColorGradeSpec(brightness = 0.3f)))
            advanceUntilIdle()
            assertEquals(0.3f, store.timeline.value.mainTrack.clips[0].grade.brightness, 1e-3f)

            store.dispatch(EditorIntent.Undo)
            advanceUntilIdle()
            assertEquals(0f, store.timeline.value.mainTrack.clips[0].grade.brightness, 1e-3f)

            store.dispatch(EditorIntent.Undo)
            advanceUntilIdle()
            assertEquals(0, store.timeline.value.mainTrack.clips.size)
            assertNull(store.selection.value)

            store.dispatch(EditorIntent.Redo)
            advanceUntilIdle()
            assertEquals(1, store.timeline.value.mainTrack.clips.size)
            storeScope.cancel()
        }
    }

    @Test
    fun previewSegmentsMapBothDirectionsAcrossSpeeds() {
        val a = clip("a", 4_000, speed = 2f)
        val b = clip("b", 3_000)
        val segs = PreviewSegments(
            listOf(
                Segment(0, 2_000, 0, 4_000, 2f, a),
                Segment(2_000, 5_000, 4_000, 7_000, 1f, b),
            ),
        )
        assertEquals(2_000L, segs.timelineToPreviewMs(1_000))
        assertEquals(4_000L, segs.timelineToPreviewMs(2_000))
        assertEquals(7_000L, segs.timelineToPreviewMs(99_000))
        assertEquals(1_000L, segs.previewToTimelineMs(2_000))
        assertEquals(2_000L, segs.previewToTimelineMs(4_000))
        assertEquals(5_000L, segs.previewToTimelineMs(99_000))
        assertEquals(0L, PreviewSegments(emptyList()).previewToTimelineMs(500))
    }

    @Test
    fun transitionPhasesMatchShaderContract() {
        assertEquals(1, TransitionType.DIP_TO_BLACK.ordinal)
        assertEquals(2, TransitionType.WIPE_LEFT.ordinal)
        assertEquals(3, TransitionType.ZOOM_PUNCH.ordinal)

        val buf = GradeUniformsBuffer()
        val p = ClipGradeProvider(
            grade = ColorGradeSpec(brightness = 0.2f),
            lutBitmap = null, lutIntensity = 0f,
            transOutType = TransitionType.DIP_TO_BLACK,
            transOutStartUs = 5_750_000, transOutEndUs = 6_000_000,
            transInType = TransitionType.ZOOM_PUNCH, transInEndUs = 250_000,
        )
        p.fill(0, buf)
        assertEquals(0.5f, buf.transProgress, 1e-3f)
        p.fill(3_000_000, buf)
        assertEquals(0f, buf.transType, 1e-3f)
        assertEquals(0.2f, buf.brightness, 1e-3f)
        p.fill(5_750_000, buf)
        assertEquals(1f, buf.transType, 1e-3f)
        assertEquals(0f, buf.transProgress, 1e-3f)
    }

    @Test
    fun fxTimelineBinarySearchGatesSegments() {
        fun seg(s: Long, e: Long) = FxSegment(s, e, 0.1f, 1f, 1f, 0f, null, 0f, 0f, e, 0f, s)
        val tl = PreviewFxTimeline(listOf(seg(0, 1_000_000), seg(1_000_000, 3_000_000)))
        assertEquals(1_000_000L, tl.segmentAt(999_999)!!.endUs)
        assertEquals(3_000_000L, tl.segmentAt(1_000_000)!!.endUs)
        assertNull(tl.segmentAt(3_000_000))

        val provider = PreviewFxProvider()
        val buf = GradeUniformsBuffer()
        provider.fill(500, buf)
        assertEquals(0f, buf.brightness, 1e-3f)
    }
}
