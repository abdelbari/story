package com.kinetic.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.effect.Presentation
import com.kinetic.editor.core.model.CanvasFit
import com.kinetic.editor.core.model.PipWindow
import com.kinetic.editor.core.model.pipWindows
import com.kinetic.editor.core.model.PlacedClip
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.TransitionSpec
import com.kinetic.editor.core.model.transitionWindowsUs
import com.kinetic.editor.core.model.gainAt
import com.kinetic.editor.core.model.transformAt
import com.kinetic.editor.effects.ClipFx
import com.kinetic.editor.effects.canvasFillEffect
import com.kinetic.editor.effects.ClipSnapshotFxProvider
import com.kinetic.editor.effects.FxSegment
import com.kinetic.editor.effects.GradeGlEffect
import com.kinetic.editor.effects.PreviewFxProvider
import com.kinetic.editor.effects.PreviewFxTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Owns every media object the preview needs. The UI talks to it in TIMELINE
 * milliseconds; MVI state flows in through [setTimeline]/[updateCosmetics].
 * Compose never sees an ExoPlayer, ExoPlayer never sees Compose state.
 *
 * ## The two time domains
 * The main track plays through ONE ConcatenatingMediaSource2 (a single seekable
 * window — ideal for scrubbing). That window advances in SOURCE time (clip trim
 * spans), while the editor thinks in TIMELINE time (post-speed). [PreviewSegments]
 * is the bidirectional mapping; it is rebuilt on every commit and is the only
 * place the conversion lives.
 *
 * ## Frame-accurate scrubbing without queue flooding
 * SeekParameters.EXACT + scrubbing mode + a one-deep conflated seek queue:
 * a new scrub target while a seek is in flight overwrites [pendingScrubMs];
 * completion (first rendered frame) issues the latest target. The decoder is
 * never more than one seek behind the finger, whatever the input rate.
 *
 * Threading: every method must be called on the main thread ([scope] must be a
 * main-dispatcher scope). The GL thread reads only volatile FX snapshots.
 */
class PreviewEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val fxProvider = PreviewFxProvider()

    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        // Effects must see window positions, whatever seeks came before: see PreviewRenderers.kt.
        PreviewRenderersFactory(context).setEnableDecoderFallback(true),
    )
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Editors don't need long lookahead; keep decoded history around
                // the playhead instead so short back-scrubs replay from memory.
                .setBufferDurationsMs(2_000, 10_000, 250, 500)
                .setBackBuffer(/* backBufferDurationMs= */ 3_000, /* retainBackBufferFromKeyframe= */ true)
                .build(),
        )
        .build()
        .apply {
            setSeekParameters(SeekParameters.EXACT)
            // Replaced by applyCanvas() before the first prepare(); a player with
            // no media yet has nothing to letterbox into.
            setVideoEffects(listOf(GradeGlEffect(fxProvider)))
            playWhenReady = false
            addListener(PlayerEvents())
        }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _timelineDurationMs = MutableStateFlow(0L)
    val timelineDurationMs: StateFlow<Long> = _timelineDurationMs.asStateFlow()

    /** PiP tracks currently loaded, so the UI can host a surface for each. */
    private val _overlays = MutableStateFlow<List<OverlayHandle>>(emptyList())
    val overlays: StateFlow<List<OverlayHandle>> = _overlays.asStateFlow()

    /**
     * Identifies one PiP preview surface. Placement is a time-ordered list, not
     * a single spec: the UI resolves the box for the current playhead, exactly
     * as the export compositor resolves it per frame.
     */
    data class OverlayHandle(val trackId: String, val windows: List<PipWindow>)

    /**
     * Last playback failure, or null. A source that vanished after being added
     * (a persisted project whose file was deleted, a revoked URI) otherwise shows
     * as a silently black preview. Cleared when the pipeline is next rebuilt,
     * which is what removing the bad clip does.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var latestState: TimelineState = TimelineState.empty()
    private var segments: PreviewSegments = PreviewSegments(emptyList())
    private val slaves = LinkedHashMap<String, SlavePlayer>()
    private val lutCache = HashMap<String, Bitmap>()

    // Scrub conflation state (main thread only).
    private var scrubbing = false
    private var seekInFlight = false
    private var lastIssuedMs = -1L
    private var pendingScrubMs = -1L
    private var seekWatchdog: Job? = null
    private var ticker: Job? = null

    /* ------------------------------ lifecycle ------------------------------ */

    fun attachSurface(view: SurfaceView) = player.setVideoSurfaceView(view)

    fun detachSurface() = player.clearVideoSurface()

    /**
     * PiP preview is a second player drawn into its own TextureView, laid out by
     * Compose from the same PipSpec the export compositor uses. Compositing two
     * decoded streams into one GL surface just to preview them would cost a full
     * render pass per frame for no visual difference. A TextureView rather than
     * a SurfaceView because it is drawn as part of the view tree: Compose can
     * rotate it, fade it out between clips and clip it like any other box.
     */
    fun attachOverlayTexture(trackId: String, view: TextureView) {
        // Not opaque: a keyed or zoomed-out picture-in-picture has transparent
        // pixels, and an opaque TextureView would paint them black over the
        // main picture instead of letting it through.
        view.isOpaque = false
        slaves[trackId]?.player?.setVideoTextureView(view)
    }

    fun detachOverlayTexture(trackId: String) {
        slaves[trackId]?.player?.clearVideoSurface()
    }

    fun release() {
        ticker?.cancel()
        seekWatchdog?.cancel()
        player.release()
        slaves.values.forEach { it.player.release() }
        slaves.clear()
    }

    /* --------------------------- state ingestion --------------------------- */

    /** Structural change: rebuild the concatenated source, preserving position. */
    fun setTimeline(state: TimelineState, keepTimelineMs: Long) {
        latestState = state
        _error.value = null
        applyCanvas(state)
        rebuildSegments(state)
        val placements = state.placements(state.mainTrack)
        if (placements.isEmpty()) {
            player.clearMediaItems()
        } else {
            val builder = ConcatenatingMediaSource2.Builder()
                .useDefaultMediaSourceFactory(context)
            for (p in placements) {
                // Known clipped durations -> the concatenated window is exact and
                // window-position == accumulated source time, which PreviewSegments
                // relies on.
                builder.add(clippedItem(p), /* initialPlaceholderDurationMs= */ p.clip.sourceSpanMs)
            }
            player.setMediaSource(
                builder.build(),
                /* startPositionMs= */ segments.timelineToPreviewMs(keepTimelineMs),
            )
            player.prepare()
        }
        lastIssuedMs = -1
        rebuildFx(state)
        rebuildSlaves(state)
    }

    /**
     * Cosmetic change (grade/LUT/transition/volume/speed value/text): NO pipeline
     * rebuild. Segments and the FX snapshot are recomputed and swapped in;
     * a slider dragged at 60Hz costs two small array builds per commit.
     */
    fun updateCosmetics(state: TimelineState) {
        latestState = state
        rebuildSegments(state)
        publishOverlays(state)
        // Slaves first: rebuildFx pushes each PiP's uniforms from the placements
        // they hold, so those must already be the new ones.
        for (slave in slaves.values) slave.refresh(state)
        rebuildFx(state)
    }

    /**
     * Canvas size or fit changed: the preview letterboxes with the SAME
     * Presentation the export applies, rather than approximating it in the view
     * tree, so "what you see is what renders" holds for the frame's shape too.
     * The main picture reaching the surface is therefore already canvas-sized,
     * which is what lets the UI place overlays in canvas coordinates.
     */
    fun applyCanvas(state: TimelineState) {
        player.setVideoEffects(
            listOfNotNull(
                GradeGlEffect(fxProvider),
                // Same factory as the export, so the two agree on when the
                // bars are filled; a no-op Presentation follows it.
                canvasFillEffect(
                    state.outputWidth,
                    state.outputHeight,
                    state.canvasFit,
                    state.canvasBackground,
                ),
                Presentation.createForWidthAndHeight(
                    state.outputWidth,
                    state.outputHeight,
                    when (state.canvasFit) {
                        CanvasFit.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
                        CanvasFit.FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                        CanvasFit.STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
                    },
                ),
            ),
        )
    }

    /** Slave-structural change (audio or PiP): rebuild those playlists only. */
    fun rescheduleSlaves(state: TimelineState) {
        latestState = state
        rebuildSlaves(state)
    }

    private fun clippedItem(p: PlacedClip): MediaItem =
        MediaItem.Builder()
            .setUri(p.clip.media.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(p.clip.trimInMs)
                    .setEndPositionMs(p.clip.trimOutMs)
                    .build(),
            )
            .build()

    /* ------------------------------ transport ------------------------------ */

    fun togglePlay() = if (player.isPlaying) pause() else play()

    fun play() {
        if (segments.timelineDurationMs == 0L) return
        if (timelinePositionMs() >= segments.timelineDurationMs - 10) scrubTo(0L)
        player.play()
    }

    fun pause() {
        player.pause()
        slaves.values.forEach { it.player.pause() }
    }

    fun timelinePositionMs(): Long = segments.previewToTimelineMs(player.currentPosition)

    /* ------------------------------ scrubbing ------------------------------ */

    fun setScrubbing(active: Boolean) {
        if (scrubbing == active) return
        scrubbing = active
        if (active) {
            pause()
            // media3 >= 1.8: parallel-seek optimizations while dragging.
            player.setScrubbingModeEnabled(true)
        } else {
            player.setScrubbingModeEnabled(false)
            // Land exactly on the final frame and bring overlay audio to it.
            if (pendingScrubMs >= 0) {
                val target = pendingScrubMs
                pendingScrubMs = -1
                issueSeek(target)
            }
            val tl = if (lastIssuedMs >= 0) lastIssuedMs else timelinePositionMs()
            slaves.values.forEach { it.sync(tl, playing = false) }
        }
    }

    fun scrubTo(timelineMs: Long) {
        if (segments.timelineDurationMs == 0L) return
        val target = timelineMs.coerceIn(0L, segments.timelineDurationMs)
        if (target == lastIssuedMs) return
        if (seekInFlight) {
            pendingScrubMs = target
            return
        }
        issueSeek(target)
    }

    private fun issueSeek(timelineMs: Long) {
        seekInFlight = true
        lastIssuedMs = timelineMs
        player.seekTo(segments.timelineToPreviewMs(timelineMs))
        // Audio-only or codec-stall fallback: never let the conflation gate wedge.
        seekWatchdog?.cancel()
        seekWatchdog = scope.launch {
            delay(250)
            if (seekInFlight) {
                seekInFlight = false
                drainPendingSeek()
            }
        }
    }

    private fun drainPendingSeek() {
        if (pendingScrubMs >= 0) {
            val next = pendingScrubMs
            pendingScrubMs = -1
            if (next != lastIssuedMs) issueSeek(next)
        }
    }

    /* ----------------------------- play ticker ----------------------------- */

    private inner class PlayerEvents : Player.Listener {
        override fun onRenderedFirstFrame() {
            // Fires after each seek's frame actually hits the surface: the
            // completion signal for the conflated scrub queue.
            seekWatchdog?.cancel()
            seekInFlight = false
            drainPendingSeek()
        }

        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            _isPlaying.value = isPlayingNow
            ticker?.cancel()
            if (isPlayingNow) {
                ticker = scope.launch { while (isActive) { tick(); delay(100) } }
            } else {
                slaves.values.forEach { it.player.pause() }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) pause()
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = describe("Main track", error)
            seekInFlight = false
            pendingScrubMs = -1
        }
    }

    private fun describe(where: String, error: PlaybackException): String =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            -> "$where: a media file is missing or no longer readable"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> "$where: this device cannot decode one of the clips"
            else -> "$where: ${error.errorCodeName}"
        }

    /**
     * 10Hz control loop while playing: applies per-clip preview speed, samples
     * volume envelopes, and keeps overlay-audio players phase-locked. (Export
     * does all of this sample-exactly; the tick is a preview approximation.)
     */
    private fun tick() {
        val tl = timelinePositionMs()
        val seg = segments.segmentAtTimeline(tl)
        if (seg != null) {
            if (player.playbackParameters.speed != seg.speed) {
                player.playbackParameters = PlaybackParameters(seg.speed)
            }
            val clipRelMs = tl - seg.timelineStartMs
            val gain = if (latestState.mainTrack.muted) 0f
            else seg.clip.gainAt(clipRelMs) * latestState.mainTrack.volume
            player.volume = gain.coerceIn(0f, 1f)
        }
        slaves.values.forEach { it.sync(tl, playing = true) }
    }

    /* ----------------------------- fx snapshot ----------------------------- */

    private fun rebuildFx(state: TimelineState) {
        // Overlay tracks included: a PiP is graded by the same shader, from the
        // same LUT cache, as the main track.
        val neededLuts = state.tracks
            .filter { it.type == TrackType.VIDEO_MAIN || it.type == TrackType.VIDEO_OVERLAY }
            .flatMap { t -> t.clips.mapNotNull { c -> c.lut?.assetPath } }
            .distinct()
        val missing = neededLuts.filter { it !in lutCache }
        if (missing.isEmpty()) {
            fxProvider.timeline = buildFxTimeline(state)
            applySlaveFx()
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                for (path in missing) {
                    runCatching {
                        context.assets.open(path).use { lutCache[path] = BitmapFactory.decodeStream(it) }
                    }
                }
            }
            fxProvider.timeline = buildFxTimeline(latestState)
            applySlaveFx()
        }
    }

    /** Pushes each PiP player's current-clip uniforms; safe to call at any time. */
    private fun applySlaveFx() {
        for (slave in slaves.values) slave.applyFx()
    }

    private fun buildFxTimeline(state: TimelineState): PreviewFxTimeline {
        val placements = state.placements(state.mainTrack)
        val out = ArrayList<FxSegment>(placements.size)
        var previewUs = 0L
        var prevTransition: TransitionSpec? = null
        for (p in placements) {
            val clip = p.clip
            // Same shared windows the export mapper uses; here they are offset
            // into the concatenated preview timeline instead of clip-local time.
            val w = transitionWindowsUs(clip, prevTransition)
            out.add(
                FxSegment(
                    startUs = previewUs,
                    endUs = previewUs + w.durationUs,
                    transform = clip.transform,
                    transformEnd = clip.transformEnd,
                    motion = clip.motion,
                    chroma = clip.chroma,
                    flipX = clip.flipX,
                    flipY = clip.flipY,
                    mask = clip.mask,
                    effect = clip.effect,
                    effectAmount = clip.effectAmount,
                    grain = clip.grade.grain,
                    vignette = clip.grade.vignette,
                    brightness = clip.grade.brightness,
                    contrast = clip.grade.contrast,
                    saturation = clip.grade.saturation,
                    temperature = clip.grade.temperature,
                    lutBitmap = clip.lut?.let { lutCache[it.assetPath] },
                    lutIntensity = clip.lut?.intensity ?: 0f,
                    transOutType = w.outTypeOrdinal.toFloat(),
                    transOutStartUs = w.outStartUs(previewUs),
                    transInType = w.inTypeOrdinal.toFloat(),
                    transInEndUs = w.inEndUs(previewUs),
                ),
            )
            prevTransition = clip.transitionOut
            previewUs += w.durationUs
        }
        return PreviewFxTimeline(out)
    }

    /* ---------------------------- segment mapping --------------------------- */

    private fun rebuildSegments(state: TimelineState) {
        val placements = state.placements(state.mainTrack)
        val list = ArrayList<Segment>(placements.size)
        var previewMs = 0L
        for (p in placements) {
            list.add(
                Segment(
                    timelineStartMs = p.startMs,
                    timelineEndMs = p.endMs,
                    previewStartMs = previewMs,
                    previewEndMs = previewMs + p.clip.sourceSpanMs,
                    speed = p.clip.speed,
                    clip = p.clip,
                ),
            )
            previewMs += p.clip.sourceSpanMs
        }
        segments = PreviewSegments(list)
        _timelineDurationMs.value = state.durationMs
    }

    /* ----------------------------- slave audio ----------------------------- */

    private fun rebuildSlaves(state: TimelineState) {
        val slaveTracks = state.tracks.filter {
            (it.type == TrackType.AUDIO || it.type == TrackType.VIDEO_OVERLAY) && it.clips.isNotEmpty()
        }
        val wantedIds = slaveTracks.map { it.id.value }.toSet()
        slaves.keys.retainAll { id ->
            (id in wantedIds).also { keep -> if (!keep) slaves[id]?.player?.release() }
        }
        for (track in slaveTracks) {
            val slave = slaves.getOrPut(track.id.value) { SlavePlayer(ExoPlayer.Builder(context).build()) }
            slave.rebuild(state, track)
        }
        publishOverlays(state)
        applySlaveFx()
    }

    /**
     * Republishes the PiP placement windows the UI lays its boxes out from.
     *
     * Called on the COSMETIC path as well as the structural one, and that is the
     * point: dragging the PiP size/position sliders changes no playlist, so it
     * routes as cosmetic and never reaches rebuildSlaves — the box on screen
     * would sit still while the exported one moved. Putting placement in the
     * structural hash instead would tear down and re-prepare the PiP player on
     * every frame of the drag. Handles are values, so a commit that does not
     * touch placement produces an equal list and the StateFlow stays silent.
     */
    private fun publishOverlays(state: TimelineState) {
        _overlays.value = state.tracks
            .filter { it.type == TrackType.VIDEO_OVERLAY && it.clips.isNotEmpty() }
            .map { OverlayHandle(it.id.value, pipWindows(state.placements(it))) }
    }

    /**
     * One lightweight player per AUDIO or VIDEO_OVERLAY track, slaved to the
     * master clock: re-seeked on item boundaries and whenever phase drift exceeds
     * 80ms. (Export replaces this with sample-accurate Composition mixing and a
     * real video compositor.)
     */
    private inner class SlavePlayer(val player: ExoPlayer) {
        private var track: Track? = null
        private var placements: List<PlacedClip> = emptyList()

        /** Non-null once this slave carries video, i.e. it is a PiP track. */
        private var fx: ClipSnapshotFxProvider? = null

        init {
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val label = when (track?.type) {
                        TrackType.VIDEO_OVERLAY -> "Picture-in-picture"
                        else -> "Audio track"
                    }
                    _error.value = describe(label, error)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // Immediate at a clip boundary, rather than waiting for the
                    // 10Hz tick to notice the grade should have changed.
                    applyFx()
                }
            })
        }

        fun rebuild(state: TimelineState, newTrack: Track) {
            track = newTrack
            placements = state.placements(newTrack)
            // Same shader as the main track and the export, so a PiP's grade and
            // LUT are previewed rather than only rendered. Attached once, before
            // the first prepare, as setVideoEffects requires.
            if (newTrack.type == TrackType.VIDEO_OVERLAY && fx == null) {
                val provider = ClipSnapshotFxProvider()
                fx = provider
                player.setVideoEffects(listOf(GradeGlEffect(provider)))
            }
            player.pause()
            player.setMediaItems(placements.map { clippedItem(it) })
            player.prepare()
            applyFx()
        }

        /** Cosmetic refresh: new envelopes/mute/grade, same playlist. */
        fun refresh(state: TimelineState) {
            val id = track?.id ?: return
            val t = state.tracks.firstOrNull { it.id == id } ?: return
            track = t
            placements = state.placements(t)
        }

        /**
         * Swaps in the uniforms of the clip whose frames are being decoded. The
         * player's own item index is the authority: it is what the decoder is
         * reading, whatever the playhead has since been dragged to.
         */
        fun applyFx() {
            val provider = fx ?: return
            val clip = placements.getOrNull(player.currentMediaItemIndex)?.clip ?: return
            provider.snapshot = ClipFx(
                grade = clip.grade,
                chroma = clip.chroma,
                // A PiP's snapshot carries no clock, so a move of any kind
                // shows its starting frame; the manual transform still applies.
                transform = transformAt(clip.transform, clip.transformEnd, clip.motion, 0f),
                flipX = clip.flipX,
                flipY = clip.flipY,
                mask = clip.mask,
                effect = clip.effect,
                effectAmount = clip.effectAmount,
                lutBitmap = clip.lut?.let { lutCache[it.assetPath] },
                lutIntensity = clip.lut?.intensity ?: 0f,
            )
        }

        fun sync(timelineMs: Long, playing: Boolean) {
            val t = track ?: return
            var idx = -1
            for (i in placements.indices) {
                if (timelineMs in placements[i]) { idx = i; break }
            }
            if (idx < 0) {
                player.pause()
                return
            }
            val p = placements[idx]
            val clipRelMs = timelineMs - p.startMs
            val sourcePosMs = (clipRelMs * p.clip.speed).roundToLong()
            if (player.currentMediaItemIndex != idx) {
                player.seekTo(idx, sourcePosMs)
            } else if (abs(player.currentPosition - sourcePosMs) > 80) {
                player.seekTo(idx, sourcePosMs)
            }
            if (p.clip.speed != player.playbackParameters.speed) {
                player.playbackParameters = PlaybackParameters(p.clip.speed)
            }
            player.volume =
                if (t.muted) 0f else (p.clip.gainAt(clipRelMs) * t.volume).coerceIn(0f, 1f)
            if (playing && !player.playWhenReady) player.play()
        }
    }
}

/* --------------------------- timeline <-> preview --------------------------- */

internal class Segment(
    val timelineStartMs: Long,
    val timelineEndMs: Long,
    val previewStartMs: Long,
    val previewEndMs: Long,
    val speed: Float,
    val clip: com.kinetic.editor.core.model.ClipModel,
)

internal class PreviewSegments(private val list: List<Segment>) {

    val timelineDurationMs: Long get() = list.lastOrNull()?.timelineEndMs ?: 0L

    fun segmentAtTimeline(timelineMs: Long): Segment? {
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = list[mid]
            when {
                timelineMs < s.timelineStartMs -> hi = mid - 1
                timelineMs >= s.timelineEndMs -> lo = mid + 1
                else -> return s
            }
        }
        return list.lastOrNull()?.takeIf { timelineMs >= it.timelineEndMs }
    }

    fun timelineToPreviewMs(timelineMs: Long): Long {
        val s = segmentAtTimeline(timelineMs) ?: return 0L
        val rel = (timelineMs - s.timelineStartMs).coerceAtLeast(0L)
        return (s.previewStartMs + rel * s.speed.toDouble())
            .roundToLong()
            .coerceIn(s.previewStartMs, s.previewEndMs)
    }

    fun previewToTimelineMs(previewMs: Long): Long {
        if (list.isEmpty()) return 0L
        var lo = 0
        var hi = list.size - 1
        var found: Segment? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = list[mid]
            when {
                previewMs < s.previewStartMs -> hi = mid - 1
                previewMs >= s.previewEndMs -> lo = mid + 1
                else -> { found = s; break }
            }
        }
        val s = found ?: list.last()
        val rel = (previewMs - s.previewStartMs).coerceAtLeast(0L)
        return (s.timelineStartMs + rel / s.speed.toDouble())
            .roundToLong()
            .coerceIn(s.timelineStartMs, s.timelineEndMs)
    }
}
