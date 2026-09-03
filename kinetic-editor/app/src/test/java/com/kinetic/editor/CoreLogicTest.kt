package com.kinetic.editor

import com.kinetic.editor.core.model.CanvasFit
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.ClipMotion
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
import com.kinetic.editor.core.model.OverlayAnim
import com.kinetic.editor.core.model.TextFont
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.TransformSpec
import com.kinetic.editor.core.model.TransitionSpec
import com.kinetic.editor.core.model.planSequence
import com.kinetic.editor.core.model.transitionWindowsUs
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.TransitionType
import com.kinetic.editor.core.model.VolumeKeyframe
import com.kinetic.editor.core.model.audioStructureHash
import com.kinetic.editor.core.model.overlayStructureHash
import com.kinetic.editor.core.model.motionAt
import com.kinetic.editor.core.model.gainAt
import com.kinetic.editor.core.model.layoutKey
import com.kinetic.editor.core.model.overlayAnimAt
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
import com.kinetic.editor.effects.progressOf
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

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

        val faded = reduce(
            s0,
            EditorIntent.SetPip(plain.id, PipSpec(opacity = -2f)),
        ).mainTrack.clips[0].pip!!
        assertEquals(0f, faded.opacity, 1e-3f)
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
    fun everyTextAnimationIsAtRestInTheMiddleAndGoneOutside() {
        val start = 1_000_000L
        val end = 5_000_000L
        for (anim in OverlayAnim.entries) {
            assertEquals(0f, overlayAnimAt(anim, start - 1, start, end, 5).alpha, 1e-4f)
            assertEquals(0f, overlayAnimAt(anim, end, start, end, 5).alpha, 1e-4f)
            // Whatever it does on the way in, by the middle it must be showing
            // the text plainly: full alpha, unit scale, on its anchor.
            val mid = overlayAnimAt(anim, 3_000_000L, start, end, 5)
            assertEquals("$anim alpha", 1f, mid.alpha, 1e-4f)
            assertEquals("$anim scale", 1f, mid.scale, 1e-4f)
            assertEquals("$anim dy", 0f, mid.dy, 1e-4f)
        }
    }

    @Test
    fun popOvershootsRiseLandsAndTypeReveals() {
        val end = 4_000_000L
        // A pop that does not overshoot is just a zoom.
        assertEquals(0.6f, overlayAnimAt(OverlayAnim.POP, 0, 0, end, 0).scale, 1e-3f)
        val peak = (0..100).map { overlayAnimAt(OverlayAnim.POP, it * 3_500L, 0, end, 0).scale }.maxOrNull()!!
        assertTrue("pop peaked at $peak, expected an overshoot", peak > 1.02f)
        assertEquals(1f, overlayAnimAt(OverlayAnim.POP, 2_000_000L, 0, end, 0).scale, 1e-3f)

        // Rise enters from below the anchor and settles exactly on it.
        assertTrue(overlayAnimAt(OverlayAnim.RISE, 0, 0, end, 0).dy < -0.1f)
        assertEquals(0f, overlayAnimAt(OverlayAnim.RISE, 2_000_000L, 0, end, 0).dy, 1e-3f)

        val n = 10
        assertEquals(0, overlayAnimAt(OverlayAnim.TYPE, 0, 0, end, n).visibleChars)
        assertEquals(n, overlayAnimAt(OverlayAnim.TYPE, 350_000L, 0, end, n).visibleChars)
        assertEquals(n, overlayAnimAt(OverlayAnim.TYPE, 2_000_000L, 0, end, n).visibleChars)
        // Typing is the entrance, so it must not also fade in — the others do.
        assertEquals(1f, overlayAnimAt(OverlayAnim.TYPE, 175_000L, 0, end, n).alpha, 1e-3f)
        assertTrue(overlayAnimAt(OverlayAnim.FADE, 175_000L, 0, end, n).alpha < 1f)
        // -1 means "all of it", so a clip that is not typing shows everything.
        assertEquals(-1, overlayAnimAt(OverlayAnim.FADE, 175_000L, 0, end, n).visibleChars)
    }

    @Test
    fun aShortTextClipGetsAShortAnimationNotATruncatedOne() {
        // 200ms is shorter than the 350ms window; if each end still took 350ms
        // they would overlap and the text would never reach full opacity.
        val mid = overlayAnimAt(OverlayAnim.FADE, 100_000L, 0, 200_000L, 3)
        assertEquals(1f, mid.alpha, 1e-3f)
    }

    @Test
    fun textLayoutKeyTracksTheFaceButNotTheColour() {
        val base = TextSpec("hello")
        // Colour is applied when the layout is drawn, so it must not split the cache.
        assertEquals(base.layoutKey(64), base.copy(argb = 0xFF00FF00).layoutKey(64))
        // Everything that changes the measurement must.
        assertNotEquals(base.layoutKey(64), base.copy(font = TextFont.SERIF).layoutKey(64))
        assertNotEquals(base.layoutKey(64), base.copy(bold = false).layoutKey(64))
        assertNotEquals(base.layoutKey(64), base.copy(italic = true).layoutKey(64))
        assertNotEquals(base.layoutKey(64), base.copy(text = "hi").layoutKey(64))
        assertNotEquals(base.layoutKey(64), base.layoutKey(65))
        // The export resolves these exact strings through TypefaceSpan, and
        // Compose resolves its own built-in families from the same names.
        assertEquals("sans-serif", TextFont.SANS.androidFamily)
        assertEquals("serif", TextFont.SERIF.androidFamily)
        assertEquals("monospace", TextFont.MONO.androidFamily)
        assertEquals("cursive", TextFont.CURSIVE.androidFamily)
    }

    @Test
    fun textDefaultsAreOmittedFromDiskAndRestoredOnLoad() {
        val textClip = ClipModel(
            ClipId("t"), MediaRef("kinetic://text", 3_000, false, false, 0f), 0, 3_000,
            text = TextSpec("hi"),
        )
        val base = TimelineState.empty()
        val doc = base.copy(
            tracks = base.tracks.map {
                if (it.type == TrackType.TEXT) it.copy(clips = persistentListOf(textClip)) else it
            }.toPersistentList(),
        )
        val json = ProjectCodec.encode(doc)
        // encodeDefaults = false, so this is byte-for-byte the shape a project
        // saved before TextSpec grew these fields has. Loading it must fill them
        // in, not fail: an update that ate the user's project would be the worst
        // possible bug in a persistence layer.
        assertFalse(json.contains("\"font\""))
        val spec = ProjectCodec.decode(json)!!
            .tracks.first { it.type == TrackType.TEXT }.clips[0].text!!
        assertEquals(TextFont.SANS, spec.font)
        assertTrue(spec.bold)
        assertFalse(spec.italic)
    }

    @Test
    fun applyFilterSetsGradeAndLutAsOneEdit() {
        val c = clip("a", 5_000)
        val s0 = stateWith(listOf(c))
        val film = LutSpec("luts/teal_orange.png", 0.85f)
        val graded = reduce(s0, EditorIntent.ApplyFilter(c.id, ColorGradeSpec(saturation = 0f), film))
        assertEquals(0f, graded.mainTrack.clips[0].grade.saturation, 1e-3f)
        assertEquals("luts/teal_orange.png", graded.mainTrack.clips[0].lut?.assetPath)
        // Going back to "None" has to drop the LUT too, or the look sticks.
        val cleared = reduce(graded, EditorIntent.ApplyFilter(c.id, ColorGradeSpec.NEUTRAL, null))
        assertNull(cleared.mainTrack.clips[0].lut)
        assertTrue(cleared.mainTrack.clips[0].grade.isNeutral)
    }

    @Test
    fun everyUniformTheProgramSetsIsAlsoReadByTheShader() {
        // GLSL compilers strip uniforms nothing reads, and GlProgram throws when
        // asked to set one that is not in the linked program. So a uniform the
        // Kotlin sets but the shader never reads is not a silent no-op: it is a
        // crash on the first frame, on every device.
        val src = EditorShaders.FRAGMENT
        for (name in SHADER_UNIFORMS) {
            val uses = src.split(name).size - 1
            assertTrue("$name is not declared in the shader", src.contains("uniform") && uses > 0)
            assertTrue("$name is declared but never read", uses > 1)
        }
    }

    @Test
    fun noMotionEverSlidesThePictureOffItsOwnEdge() {
        // The shader samples inside the source while scale >= 1 + |offset|.
        // Below that a pan reveals the frame's edge as black, which is exactly
        // the bug a hand-tuned pair of constants invites.
        for (motion in ClipMotion.entries) {
            for (step in 0..20) {
                val xf = motionAt(TransformSpec.NONE, motion, step / 20f)
                val worst = maxOf(abs(xf.offsetX), abs(xf.offsetY))
                assertTrue(
                    "$motion at ${step / 20f}: scale ${xf.scale} cannot cover offset $worst",
                    xf.scale >= 1f + worst - 1e-4f,
                )
            }
        }
    }

    @Test
    fun motionRunsAcrossTheClipAndComposesWithAManualReframe() {
        val none = TransformSpec.NONE
        assertEquals(1f, motionAt(none, ClipMotion.ZOOM_IN, 0f).scale, 1e-3f)
        assertTrue(motionAt(none, ClipMotion.ZOOM_IN, 1f).scale > 1.15f)
        // Pull out is the same move, reversed.
        assertEquals(
            motionAt(none, ClipMotion.ZOOM_IN, 0.25f).scale,
            motionAt(none, ClipMotion.ZOOM_OUT, 0.75f).scale,
            1e-4f,
        )
        // A pan crosses the centre halfway through, and the two pans mirror.
        assertEquals(0f, motionAt(none, ClipMotion.PAN_LEFT, 0.5f).offsetX, 1e-4f)
        assertEquals(
            motionAt(none, ClipMotion.PAN_LEFT, 0f).offsetX,
            -motionAt(none, ClipMotion.PAN_RIGHT, 0f).offsetX,
            1e-4f,
        )
        // Motion composes with a hand reframe rather than discarding it.
        val reframed = TransformSpec(scale = 2f, offsetX = 0.3f)
        assertEquals(2f, motionAt(reframed, ClipMotion.ZOOM_IN, 0f).scale, 1e-3f)
        assertEquals(0.3f, motionAt(reframed, ClipMotion.ZOOM_IN, 1f).offsetX, 1e-3f)
        assertSame(reframed, motionAt(reframed, ClipMotion.NONE, 0.5f))
        // Progress is clamped: a stray timestamp cannot fling the picture away.
        assertEquals(
            motionAt(none, ClipMotion.ZOOM_IN, 1f).scale,
            motionAt(none, ClipMotion.ZOOM_IN, 9f).scale,
            1e-4f,
        )
        // A clip with no span sits at the start of its move rather than dividing by it.
        assertEquals(0f, progressOf(5_000L, 0L), 1e-4f)
        assertEquals(1f, progressOf(9_000L, 3_000L), 1e-4f)
        assertEquals(0.5f, progressOf(1_500L, 3_000L), 1e-4f)
    }

    @Test
    fun transformClampsToWhatTheShaderCanActuallySample() {
        val c = clip("a", 4_000)
        val s0 = stateWith(listOf(c))
        val wild = reduce(
            s0,
            EditorIntent.SetTransform(
                c.id,
                TransformSpec(scale = 0f, offsetX = 9f, offsetY = -9f, rotationDeg = 900f),
            ),
        ).mainTrack.clips[0].transform
        // Zero scale divides the sampling coordinate by nothing.
        assertEquals(0.1f, wild.scale, 1e-4f)
        assertEquals(2f, wild.offsetX, 1e-4f)
        assertEquals(-2f, wild.offsetY, 1e-4f)
        assertEquals(180f, wild.rotationDeg, 1e-4f)

        assertTrue(TransformSpec.NONE.isIdentity)
        assertFalse(TransformSpec(scale = 1.2f).isIdentity)
        // An untouched clip stays byte-identical on disk.
        assertFalse(ProjectCodec.encode(s0).contains("transform"))
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
    fun detachAudioLiftsTheSoundAndSilencesTheSource() {
        val a = clip("a", 4_000)
        val b = clip("b", 3_000)
        val s0 = stateWith(listOf(a, b))
        val out = reduce(s0, EditorIntent.DetachAudio(b.id))

        // Silenced, not removed: the picture stays.
        assertEquals(2, out.mainTrack.clips.size)
        assertEquals(0f, out.mainTrack.clips[1].volume, 1e-4f)

        val lifted = out.tracks.first { it.type == TrackType.AUDIO }.clips.single()
        // At the same moment in the timeline, or the sound drifts off the picture.
        assertEquals(a.durationMs, lifted.startMs)
        assertEquals(b.trimInMs, lifted.trimInMs)
        assertEquals(b.trimOutMs, lifted.trimOutMs)
        assertNotEquals(b.id, lifted.id)

        // Nothing to detach from silent media, or from the audio lane itself.
        val silent = ClipModel(
            ClipId("s"), MediaRef("uri://silent", 2_000, true, false, 30f), 0, 2_000,
        )
        val quiet = stateWith(listOf(silent))
        assertTrue(reduce(quiet, EditorIntent.DetachAudio(silent.id)) === quiet)
        assertTrue(reduce(out, EditorIntent.DetachAudio(lifted.id)) === out)
    }

    @Test
    fun duplicateLandsBesideTheOriginalWithItsOwnIdentity() {
        val a = clip("a", 4_000)
        val b = clip("b", 3_000)
        val s0 = stateWith(listOf(a, b))
        val out = reduce(s0, EditorIntent.DuplicateClip(a.id))
        val clips = out.mainTrack.clips
        assertEquals(3, clips.size)
        // Next in line, not appended at the end.
        assertEquals(a.id, clips[0].id)
        assertEquals(b.id, clips[2].id)
        // A copy, not an alias: a later edit to one must not move the other.
        assertNotEquals(a.id, clips[1].id)
        assertEquals(a.trimInMs, clips[1].trimInMs)
        assertEquals(a.trimOutMs, clips[1].trimOutMs)

        // On a freely placed track the copy has to move, or it hides underneath.
        val pip = ClipModel(
            ClipId("p"), MediaRef("uri://pip", 4_000, true, false, 30f), 0, 4_000,
            startMs = 1_000, pip = PipSpec(),
        )
        val base = TimelineState.empty()
        val withPip = base.copy(
            tracks = base.tracks.map {
                if (it.type == TrackType.VIDEO_OVERLAY) it.copy(clips = persistentListOf(pip)) else it
            }.toPersistentList(),
        )
        val dup = reduce(withPip, EditorIntent.DuplicateClip(pip.id))
            .tracks.first { it.type == TrackType.VIDEO_OVERLAY }.clips
        assertEquals(2, dup.size)
        assertEquals(1_000L + pip.durationMs, dup[1].startMs)

        assertTrue(reduce(s0, EditorIntent.DuplicateClip(ClipId("nope"))) === s0)
    }

    @Test
    fun canvasFitSurvivesARoundTripAndDefaultsToFit() {
        val s0 = stateWith(listOf(clip("a", 4_000)))
        assertEquals(CanvasFit.FIT, s0.canvasFit)
        val filled = reduce(s0, EditorIntent.SetCanvasFit(CanvasFit.FILL))
        assertEquals(CanvasFit.FILL, filled.canvasFit)
        assertEquals(CanvasFit.FILL, ProjectCodec.decode(ProjectCodec.encode(filled))!!.canvasFit)
        // The default is omitted on disk, so older projects still open.
        assertFalse(ProjectCodec.encode(s0).contains("canvasFit"))
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

/** Every uniform GradeShaderProgram sets; see the test above for why it matters. */
private val SHADER_UNIFORMS = listOf(
    "uTexSampler", "uLutSampler", "uLutEnabled", "uLutIntensity",
    "uBrightness", "uContrast", "uSaturation", "uTemperature",
    "uTransType", "uTransProgress",
    "uXfScale", "uXfOffset", "uXfRot", "uAspect",
)
