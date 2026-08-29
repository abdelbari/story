package com.kinetic.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import com.kinetic.editor.core.model.PlacedClip
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.gainAt
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
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
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
            // Applies to all subsequent media; set once before the first prepare().
            setVideoEffects(listOf(GradeGlEffect(fxProvider)))
            playWhenReady = false
            addListener(PlayerEvents())
        }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _timelineDurationMs = MutableStateFlow(0L)
    val timelineDurationMs: StateFlow<Long> = _timelineDurationMs.asStateFlow()

    private var latestState: TimelineState = TimelineState.empty()
    private var segments: PreviewSegments = PreviewSegments(emptyList())
    private val slaves = LinkedHashMap<String, SlaveAudio>()
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
        rebuildFx(state)
        for (slave in slaves.values) slave.refresh(state)
    }

    /** Audio-structural change: recreate slave playlists, keep the master alone. */
    fun rescheduleAudio(state: TimelineState) {
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
        val neededLuts = state.mainTrack.clips.mapNotNull { it.lut?.assetPath }.distinct()
        val missing = neededLuts.filter { it !in lutCache }
        if (missing.isEmpty()) {
            fxProvider.timeline = buildFxTimeline(state)
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
        }
    }

    private fun buildFxTimeline(state: TimelineState): PreviewFxTimeline {
        val placements = state.placements(state.mainTrack)
        val out = ArrayList<FxSegment>(placements.size)
        var previewUs = 0L
        var prevTransition = com.kinetic.editor.core.model.TransitionSpec(
            com.kinetic.editor.core.model.TransitionType.NONE, 0L,
        )
        for (p in placements) {
            val clip = p.clip
            val durUs = clip.sourceSpanMs * 1000L
            // Transition halves live in each clip's own preview time, which runs
            // at source rate: timeline-ms * speed.
            val outHalfUs = clip.transitionOut
                ?.let { (it.durationMs * 500L * clip.speed).roundToLong() }
                ?.coerceAtMost(durUs / 2) ?: 0L
            val inHalfUs = (prevTransition.durationMs * 500L * clip.speed).roundToLong()
                .coerceAtMost(durUs / 2)
            out.add(
                FxSegment(
                    startUs = previewUs,
                    endUs = previewUs + durUs,
                    brightness = clip.grade.brightness,
                    contrast = clip.grade.contrast,
                    saturation = clip.grade.saturation,
                    temperature = clip.grade.temperature,
                    lutBitmap = clip.lut?.let { lutCache[it.assetPath] },
                    lutIntensity = clip.lut?.intensity ?: 0f,
                    transOutType = (clip.transitionOut?.type?.ordinal ?: 0).toFloat(),
                    transOutStartUs = previewUs + durUs - outHalfUs,
                    transInType = if (inHalfUs > 0) prevTransition.type.ordinal.toFloat() else 0f,
                    transInEndUs = previewUs + inHalfUs,
                ),
            )
            prevTransition = clip.transitionOut
                ?: com.kinetic.editor.core.model.TransitionSpec(
                    com.kinetic.editor.core.model.TransitionType.NONE, 0L,
                )
            previewUs += durUs
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
        val audioTracks = state.tracks.filter { it.type == TrackType.AUDIO }
        val wantedIds = audioTracks.map { it.id.value }.toSet()
        slaves.keys.retainAll { id ->
            (id in wantedIds).also { keep -> if (!keep) slaves[id]?.player?.release() }
        }
        for (track in audioTracks) {
            val slave = slaves.getOrPut(track.id.value) { SlaveAudio(ExoPlayer.Builder(context).build()) }
            slave.rebuild(state, track)
        }
    }

    /**
     * One lightweight audio-only player per AUDIO track, slaved to the master
     * clock: re-seeked on item boundaries and whenever phase drift exceeds 80ms.
     * (Export replaces this with sample-accurate Composition mixing.)
     */
    private inner class SlaveAudio(val player: ExoPlayer) {
        private var track: Track? = null
        private var placements: List<PlacedClip> = emptyList()

        fun rebuild(state: TimelineState, newTrack: Track) {
            track = newTrack
            placements = state.placements(newTrack)
            player.pause()
            player.setMediaItems(placements.map { clippedItem(it) })
            player.prepare()
        }

        /** Cosmetic refresh: new envelopes/mute, same playlist. */
        fun refresh(state: TimelineState) {
            val id = track?.id ?: return
            val t = state.tracks.firstOrNull { it.id == id } ?: return
            track = t
            placements = state.placements(t)
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
