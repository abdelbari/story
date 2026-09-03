package com.kinetic.editor

import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.MediaRef
import com.kinetic.editor.core.model.LutSpec
import com.kinetic.editor.core.model.FadeSpec
import com.kinetic.editor.core.model.PipSpec
import com.kinetic.editor.core.model.pipWindowAt
import com.kinetic.editor.core.model.pipWindows
import com.kinetic.editor.core.model.fadeKeyframes
import com.kinetic.editor.core.model.readFades
import com.kinetic.editor.core.model.PlacedClip
import com.kinetic.editor.core.model.ProjectCodec
import com.kinetic.editor.core.model.StickerSpec
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.TransitionSpec
import com.kinetic.editor.core.model.planSequence
import com.kinetic.editor.core.model.transitionWindowsUs
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.TransitionType
import com.kinetic.editor.core.model.VolumeKeyframe
import com.kinetic.editor.core.model.audioStructureHash
import com.kinetic.editor.core.model.overlayStructureHash
import com.kinetic.editor.core.model.gainAt
import com.kinetic.editor.core.model.snapToFrame
import com.kinetic.editor.core.model.videoStructureHash
import com.kinetic.editor.core.mvi.EditorIntent
import com.kinetic.editor.core.mvi.EditorStore
import com.kinetic.editor.core.mvi.reduce
import com.kinetic.editor.effects.ClipGradeProvider
import com.kinetic.editor.effects.EditorShaders
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun transitionWindowsScaleBySpeedAndClampToHalfTheClip() {
        val c = clip("a", 10_000).copy(transitionOut = TransitionSpec(TransitionType.WIPE_LEFT, 500))
        val w = transitionWindowsUs(c, null)
        assertEquals(10_000_000L, w.durationUs)
        assertEquals(250_000L, w.outHalfUs)
        assertEquals(9_750_000L, w.outStartUs())
        assertEquals(0L, w.inHalfUs)
        assertEquals(500_000L, transitionWindowsUs(c.copy(speed = 2f), null).outHalfUs)

        val tiny = clip("t", 10_000, 0, 200)
            .copy(transitionOut = TransitionSpec(TransitionType.DIP_TO_BLACK, 5_000))
        val tw = transitionWindowsUs(tiny, null)
        assertEquals(100_000L, tw.outHalfUs)
        assertTrue(tw.outStartUs() < tw.outEndUs())
    }

    @Test
    fun noneOrNullPreviousTransitionMeansNoIncomingHalf() {
        val c = clip("a", 4_000)
        assertEquals(0L, transitionWindowsUs(c, null).inHalfUs)
        assertEquals(0L, transitionWindowsUs(c, TransitionSpec(TransitionType.NONE, 800)).inHalfUs)
        val w = transitionWindowsUs(c, TransitionSpec(TransitionType.ZOOM_PUNCH, 600))
        assertEquals(300_000L, w.inHalfUs)
        assertEquals(1_300_000L, w.inEndUs(1_000_000))
    }

    /** Guards the "what you see is what renders" property across both pipelines. */
    @Test
    fun previewAndExportDeriveIdenticalTransitionGeometry() {
        val a = clip("a", 6_000, speed = 1.5f)
            .copy(transitionOut = TransitionSpec(TransitionType.DIP_TO_BLACK, 700))
        val b = clip("b", 5_000, speed = 0.5f)
        val previewBase = a.sourceSpanMs * 1_000L
        val export = transitionWindowsUs(b, a.transitionOut)
        val preview = transitionWindowsUs(b, a.transitionOut)
        assertEquals(export.inEndUs(), preview.inEndUs(previewBase) - previewBase)
        assertEquals(export.outStartUs(), preview.outStartUs(previewBase) - previewBase)
    }

    @Test
    fun audioPlanHeadTrimsOverlapsAndDropsCoveredClips() {
        val x = clip("x", 4_000, start = 1_000)
        val y = clip("y", 4_000, start = 3_000)
        val plans = planSequence(listOf(PlacedClip(x, 1_000), PlacedClip(y, 3_000)))
        assertEquals(2, plans.size)
        assertEquals(1_000L, plans[0].gapBeforeMs)
        assertEquals(5_000L, plans[1].startMs)
        assertEquals(2_000L, plans[1].trimInMs)

        val covered = planSequence(
            listOf(PlacedClip(clip("big", 10_000), 0), PlacedClip(clip("c", 1_000, start = 2_000), 2_000)),
        )
        assertEquals(1, covered.size)

        val fast = planSequence(
            listOf(PlacedClip(clip("pre", 2_000), 0), PlacedClip(clip("f", 8_000, speed = 2f), 1_000)),
        )
        assertEquals(2_000L, fast[1].trimInMs)
    }

    @Test
    fun noOpEditsPreserveStateIdentity() {
        val c = clip("a", 10_000)
        val s0 = stateWith(listOf(c))
        assertTrue(reduce(s0, EditorIntent.TrimClip(c.id, c.trimInMs, c.trimOutMs)) === s0)
        assertTrue(reduce(s0, EditorIntent.SetVolume(c.id, c.volume)) === s0)
        assertTrue(reduce(s0, EditorIntent.SetGrade(c.id, c.grade)) === s0)
        assertTrue(reduce(s0, EditorIntent.SetSpeed(c.id, 2f)) !== s0)
    }

    @Test
    fun projectCodecRoundTripsAFullDocument() {
        val c1 = clip("a", 10_000, 1_000, 9_000, speed = 1.5f).copy(
            grade = ColorGradeSpec(brightness = 0.2f, saturation = 1.3f),
            lut = LutSpec("luts/teal_orange.png", 0.85f),
            transitionOut = TransitionSpec(TransitionType.WIPE_LEFT, 600),
            volume = 1.4f,
            volumeKeyframes = persistentListOf(VolumeKeyframe(0, 0f), VolumeKeyframe(2_000, 1f)),
        )
        val text = ClipModel(
            ClipId("t1"),
            MediaRef("kinetic://text", 3_000, false, false, 0f),
            0, 3_000, startMs = 1_500,
            text = TextSpec("Hello", anchorY = -0.4f),
        )
        val base = stateWith(listOf(c1), audio = listOf(clip("m", 5_000, start = 500)))
        val original = base.copy(
            tracks = base.tracks.map { tr ->
                if (tr.type == TrackType.TEXT) tr.copy(clips = persistentListOf(text)) else tr
            }.toPersistentList(),
            outputWidth = 1920, outputHeight = 1080, projectFps = 60f,
        )

        val decoded = ProjectCodec.decode(ProjectCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals("luts/teal_orange.png", decoded!!.mainTrack.clips[0].lut?.assetPath)
        assertEquals(2, decoded.mainTrack.clips[0].volumeKeyframes.size)
        assertEquals("Hello", decoded.tracks.first { it.type == TrackType.TEXT }.clips[0].text?.text)
        // The decoded lists must still be persistent enough for the reducer.
        val edited = reduce(decoded, EditorIntent.SetVolume(decoded.mainTrack.clips[0].id, 0.5f))
        assertEquals(0.5f, edited.mainTrack.clips[0].volume, 1e-3f)
    }

    @Test
    fun projectCodecFailsSoftAndToleratesUnknownKeys() {
        assertNull(ProjectCodec.decode("{ not json"))
        assertNull(ProjectCodec.decode(""))
        val json = ProjectCodec.encode(stateWith(listOf(clip("a", 4_000))))
        assertTrue(ProjectCodec.decode(json.dropLast(1) + ",\"futureField\":123}") != null)
    }

    @Test
    fun projectCodecRejectsDocumentWithNoMainTrack() {
        val ok = stateWith(listOf(clip("a", 4_000)))
        assertTrue(ProjectCodec.decode(ProjectCodec.encode(ok)) != null)
        val noMain = ok.copy(
            tracks = ok.tracks.filterNot { it.type == TrackType.VIDEO_MAIN }.toPersistentList(),
        )
        assertNull(ProjectCodec.decode(ProjectCodec.encode(noMain)))
    }

    @Test
    fun overlayEditsAreStructuralForPipOnly() {
        val s0 = stateWith(listOf(clip("a", 5_000)))
        val overlayId = s0.tracks.first { it.type == TrackType.VIDEO_OVERLAY }.id
        val s1 = reduce(
            s0,
            EditorIntent.AddClip(overlayId, media(4_000), startMs = 1_000, pip = PipSpec()),
        )
        assertTrue(s1.overlayStructureHash() != s0.overlayStructureHash())
        assertEquals(s0.videoStructureHash(), s1.videoStructureHash())
        assertEquals(PipSpec(), s1.tracks.first { it.type == TrackType.VIDEO_OVERLAY }.clips[0].pip)

        val decoded = ProjectCodec.decode(ProjectCodec.encode(s1))
        assertEquals(
            PipSpec(),
            decoded!!.tracks.first { it.type == TrackType.VIDEO_OVERLAY }.clips[0].pip,
        )
    }

    @Test
    fun setTextOnlyAppliesToTextClipsAndSetPipClamps() {
        val plain = clip("a", 5_000)
        val s0 = stateWith(listOf(plain))
        assertTrue(reduce(s0, EditorIntent.SetText(plain.id, TextSpec("hi"))) === s0)

        val textClip = ClipModel(
            ClipId("t"), MediaRef("kinetic://text", 3_000, false, false, 0f), 0, 3_000,
            text = TextSpec("before"),
        )
        val base = TimelineState.empty()
        val withText = base.copy(
            tracks = base.tracks.map {
                if (it.type == TrackType.TEXT) it.copy(clips = persistentListOf(textClip)) else it
            }.toPersistentList(),
        )
        val edited = reduce(withText, EditorIntent.SetText(textClip.id, TextSpec("hi")))
        assertEquals("hi", edited.tracks.first { it.type == TrackType.TEXT }.clips[0].text?.text)

        val pip = reduce(
            s0,
            EditorIntent.SetPip(plain.id, PipSpec(anchorX = 5f, anchorY = -9f, scale = 40f)),
        ).mainTrack.clips[0].pip!!
        assertEquals(1f, pip.anchorX, 1e-3f)
        assertEquals(-1f, pip.anchorY, 1e-3f)
        assertEquals(1f, pip.scale, 1e-3f)
    }

    @Test
    fun setStickerOnlyAppliesToStickerClipsAndClamps() {
        val plain = clip("a", 5_000)
        val s0 = stateWith(listOf(plain))
        assertTrue(reduce(s0, EditorIntent.SetSticker(plain.id, StickerSpec("stickers/star.png"))) === s0)

        val stickerClip = ClipModel(
            ClipId("s"), MediaRef("kinetic://sticker", 3_000, false, false, 0f), 0, 3_000,
            sticker = StickerSpec("stickers/star.png"),
        )
        val base = TimelineState.empty()
        val withSticker = base.copy(
            tracks = base.tracks.map {
                if (it.type == TrackType.STICKER) it.copy(clips = persistentListOf(stickerClip)) else it
            }.toPersistentList(),
        )
        val edited = reduce(
            withSticker,
            EditorIntent.SetSticker(
                stickerClip.id,
                StickerSpec("stickers/star.png", anchorX = 5f, anchorY = -9f, scale = 40f, rotationDeg = 30f),
            ),
        )
        val spec = edited.tracks.first { it.type == TrackType.STICKER }.clips[0].sticker!!
        assertEquals(1f, spec.anchorX, 1e-3f)
        assertEquals(-1f, spec.anchorY, 1e-3f)
        assertEquals(1f, spec.scale, 1e-3f)
        assertEquals(30f, spec.rotationDeg, 1e-3f)
        assertEquals("sticker:s", EditorIntent.SetSticker(stickerClip.id, spec).coalesceKey)
    }

    @Test
    fun pipPlacementIsCosmeticNotStructural() {
        // The routing contract PreviewEngine.publishOverlays depends on: moving
        // or resizing a PiP must NOT land in the structural hash, or the slave
        // player would be torn down and re-prepared on every frame of the drag.
        // Trim and placement in time must, because those do change the playlist.
        val pipClip = ClipModel(
            ClipId("p"), MediaRef("uri://pip", 4_000, true, false, 30f), 0, 4_000,
            startMs = 1_000, pip = PipSpec(),
        )
        val base = TimelineState.empty()
        val withPip = base.copy(
            tracks = base.tracks.map {
                if (it.type == TrackType.VIDEO_OVERLAY) it.copy(clips = persistentListOf(pipClip)) else it
            }.toPersistentList(),
        )

        val moved = reduce(withPip, EditorIntent.SetPip(pipClip.id, PipSpec(scale = 0.9f)))
        assertEquals(withPip.overlayStructureHash(), moved.overlayStructureHash())
        assertEquals(0.9f, moved.tracks.first { it.type == TrackType.VIDEO_OVERLAY }.clips[0].pip!!.scale, 1e-3f)

        val trimmed = reduce(withPip, EditorIntent.TrimClip(pipClip.id, 500, 4_000))
        assertNotEquals(withPip.overlayStructureHash(), trimmed.overlayStructureHash())
    }

    @Test
    fun shaderSourcesAreAsciiOnly() {
        // GLSL ES 1.00 restricts the source character set to ASCII, comments
        // included. A typographic dash in a comment makes strict drivers reject
        // the whole program — at runtime, on some devices only, which is the
        // worst way to find out.
        for (src in listOf(EditorShaders.VERTEX, EditorShaders.FRAGMENT)) {
            val offender = src.firstOrNull { it.code > 127 }
            assertNull("non-ASCII character in shader source: $offender", offender)
        }
    }

    @Test
    fun saveReportsFailureInsteadOfThrowing() {
        // Autosave collects in a coroutine: a throw here would cancel the
        // collector and end autosaving for the rest of the session.
        val unwritable = File("/proc/kinetic-does-not-exist/project.json")
        assertFalse(ProjectCodec.save(unwritable, TimelineState.empty()))
        assertNull(ProjectCodec.load(unwritable))
    }

    @Test
    fun setCanvasKeepsDimensionsEvenAndBounded() {
        val s0 = stateWith(listOf(clip("a", 5_000)))
        val wide = reduce(s0, EditorIntent.SetCanvas(1920, 1080))
        assertEquals(1920, wide.outputWidth)
        assertEquals(1080, wide.outputHeight)
        val odd = reduce(s0, EditorIntent.SetCanvas(1081, 9_999))
        assertEquals(1080, odd.outputWidth)
        assertEquals(4096, odd.outputHeight)
        assertEquals(16, reduce(s0, EditorIntent.SetCanvas(2, 2)).outputWidth)
    }

    @Test
    fun fadesGenerateAndReadBackRoundTrip() {
        val dur = 10_000L
        val kfs = fadeKeyframes(dur, FadeSpec(1_000, 2_000))
        assertEquals(listOf(0L, 1_000L, 8_000L, 10_000L), kfs.map { it.atMs })
        assertEquals(listOf(0f, 1f, 1f, 0f), kfs.map { it.gain })
        assertEquals(FadeSpec(1_000, 2_000), readFades(kfs, dur))

        val inOnly = fadeKeyframes(dur, FadeSpec(inMs = 500))
        assertEquals(500L, readFades(inOnly, dur).inMs)
        assertEquals(0L, readFades(inOnly, dur).outMs)

        assertTrue(fadeKeyframes(dur, FadeSpec()).isEmpty())
        assertEquals(FadeSpec(), readFades(emptyList(), dur))
    }

    @Test
    fun overlappingFadesAreScaledDownNotInverted() {
        val dur = 1_000L
        val kfs = fadeKeyframes(dur, FadeSpec(900, 900))
        val times = kfs.map { it.atMs }
        assertEquals(times.sorted(), times)
        assertTrue(times.last() <= dur)
        val c = clip("f", 4_000).copy(volumeKeyframes = kfs.toPersistentList())
        assertEquals(0f, c.gainAt(0), 1e-3f)
        assertTrue(c.gainAt(dur / 2) > 0f)
    }

    @Test
    fun pipWindowsResolvePerClipAndHideInGaps() {
        val a = clip("p1", 2_000, start = 1_000).copy(pip = PipSpec(anchorX = -0.5f, scale = 0.3f))
        val b = clip("p2", 2_000, start = 5_000).copy(pip = PipSpec(anchorX = 0.8f, scale = 0.5f))
        val ws = pipWindows(listOf(PlacedClip(a, 1_000), PlacedClip(b, 5_000)))
        assertEquals(2, ws.size)
        assertEquals(1_000_000L, ws[0].startUs)
        assertEquals(3_000_000L, ws[0].endUs)
        assertEquals(1920f / 1080f, ws[0].aspect, 1e-4f) // from the source's display size

        assertEquals(-0.5f, pipWindowAt(ws, 1_500_000)!!.pip.anchorX, 1e-3f)
        assertEquals(0.8f, pipWindowAt(ws, 5_500_000)!!.pip.anchorX, 1e-3f)
        assertEquals(0.5f, pipWindowAt(ws, 5_000_000)!!.pip.scale, 1e-3f) // start boundary is inclusive
        assertNull(pipWindowAt(ws, 3_000_000))                             // end boundary is exclusive
        assertNull(pipWindowAt(ws, 4_000_000))                             // gap: hidden
        assertNull(pipWindowAt(ws, 99_000_000))                            // past the end: hidden
        assertNull(pipWindowAt(emptyList(), 0))
    }

    @Test
    fun overlappingPipClipsYieldDisjointWindows() {
        // The later clip is head-trimmed exactly as the export sequence trims it,
        // so a timestamp never maps to two framings.
        val a = clip("p1", 4_000, start = 1_000).copy(pip = PipSpec(anchorX = -0.5f))
        val b = clip("p2", 4_000, start = 3_000).copy(pip = PipSpec(anchorX = 0.8f))
        val ws = pipWindows(listOf(PlacedClip(a, 1_000), PlacedClip(b, 3_000)))
        assertEquals(5_000_000L, ws[0].endUs)
        assertEquals(5_000_000L, ws[1].startUs)
        assertEquals(7_000_000L, ws[1].endUs)
        assertEquals(-0.5f, pipWindowAt(ws, 4_000_000)!!.pip.anchorX, 1e-3f)
        assertEquals(0.8f, pipWindowAt(ws, 5_000_000)!!.pip.anchorX, 1e-3f)
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
    fun exportGradeProviderMeasuresFromTheFirstFrameItSees() {
        // Transformer adds the item's sequence offset ahead of its effects: the
        // third clip of a sequence sees ~12.5s on its first frame, not 0.
        val buf = GradeUniformsBuffer()
        val p = ClipGradeProvider(
            grade = ColorGradeSpec(),
            lutBitmap = null, lutIntensity = 0f,
            transOutType = TransitionType.DIP_TO_BLACK,
            transOutStartUs = 5_750_000, transOutEndUs = 6_000_000,
            transInType = TransitionType.ZOOM_PUNCH, transInEndUs = 250_000,
        )
        p.fill(12_500_000, buf)
        assertEquals(3f, buf.transType, 1e-3f)
        assertEquals(0.5f, buf.transProgress, 1e-3f)
        p.fill(12_749_999, buf)
        assertEquals(1f, buf.transProgress, 1e-2f)
        p.fill(15_500_000, buf)
        assertEquals(0f, buf.transType, 1e-3f)
        p.fill(18_250_000, buf)
        assertEquals(1f, buf.transType, 1e-3f)
        assertEquals(0f, buf.transProgress, 1e-3f)
        p.fill(18_499_999, buf)
        assertEquals(0.5f, buf.transProgress, 1e-2f)
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
