package com.kinetic.editor.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.util.UUID
import kotlin.math.roundToLong

/*
 * Unit conventions, enforced project-wide:
 *  - All model/state time is Long MILLISECONDS ("Ms" suffix).
 *  - Microseconds exist only at the Media3 boundary (CompositionMapper, shaders).
 *  - All keyframe times are relative to the clip's TIMELINE span (post-speed).
 */

@JvmInline
value class ClipId(val value: String) {
    companion object { fun random() = ClipId(UUID.randomUUID().toString()) }
}

@JvmInline
value class TrackId(val value: String) {
    companion object { fun random() = TrackId(UUID.randomUUID().toString()) }
}

enum class TrackType { VIDEO_MAIN, VIDEO_OVERLAY, TEXT, STICKER, AUDIO }

/** Immutable description of a source media file. Probed once at import. */
@Immutable
data class MediaRef(
    val uri: String,
    val durationMs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val fps: Float = 30f,
    val width: Int = 0,
    val height: Int = 0,
)

@Immutable
data class ColorGradeSpec(
    val brightness: Float = 0f,   // [-0.5, 0.5] additive
    val contrast: Float = 1f,     // [0.25, 2.0]
    val saturation: Float = 1f,   // [0.0, 2.0]
    val temperature: Float = 0f,  // [-1.0, 1.0] warm(+)/cool(-)
) {
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 1f && saturation == 1f && temperature == 0f

    companion object { val NEUTRAL = ColorGradeSpec() }
}

/** A 64x64x64 cube LUT packed into a 512x512 PNG (8x8 grid of 64x64 B-slices). */
@Immutable
data class LutSpec(
    val assetPath: String,       // e.g. "luts/teal_orange.png"
    val intensity: Float = 1f,   // [0, 1]
)

enum class TransitionType { NONE, DIP_TO_BLACK, WIPE_LEFT, ZOOM_PUNCH }

@Immutable
data class TransitionSpec(
    val type: TransitionType,
    val durationMs: Long = 500L,
)

@Immutable
data class VolumeKeyframe(
    val atMs: Long,   // relative to clip timeline start
    val gain: Float,  // [0, 2] linear
)

@Immutable
data class TextSpec(
    val text: String,
    val textSizePx: Float = 64f,
    val argb: Long = 0xFFFFFFFF,
    // Normalized device coords, [-1, 1]; (0, 0) is frame center.
    val anchorX: Float = 0f,
    val anchorY: Float = -0.6f,
)

@Immutable
data class StickerSpec(
    val assetPath: String,
    val anchorX: Float = 0.6f,
    val anchorY: Float = 0.6f,
    val scale: Float = 0.35f,
    val rotationDeg: Float = 0f,
)

@Immutable
data class ClipModel(
    val id: ClipId,
    val media: MediaRef,
    /** Trim window into the SOURCE file, [trimInMs, trimOutMs). */
    val trimInMs: Long,
    val trimOutMs: Long,
    /**
     * Timeline start for freely-placed tracks (overlay/text/sticker/audio).
     * Ignored on VIDEO_MAIN, where position is derived from clip order (ripple).
     */
    val startMs: Long = 0L,
    /** Constant playback rate. Speed ramps are modeled by splitting into stepped segments. */
    val speed: Float = 1f,
    val grade: ColorGradeSpec = ColorGradeSpec.NEUTRAL,
    val lut: LutSpec? = null,
    /** Transition played across this clip's END boundary into the next clip. */
    val transitionOut: TransitionSpec? = null,
    val volume: Float = 1f,
    val volumeKeyframes: ImmutableList<VolumeKeyframe> = persistentListOf(),
    val text: TextSpec? = null,
    val sticker: StickerSpec? = null,
) {
    /** Source-domain span (what the decoder actually reads). */
    val sourceSpanMs: Long get() = trimOutMs - trimInMs

    /** Timeline-domain duration after speed. */
    val durationMs: Long get() = (sourceSpanMs / speed.toDouble()).roundToLong()

    init {
        require(trimOutMs > trimInMs) { "Empty clip: trimOut <= trimIn" }
        require(speed in 0.1f..8f) { "Speed out of supported range: $speed" }
    }
}

@Immutable
data class Track(
    val id: TrackId,
    val type: TrackType,
    val clips: ImmutableList<ClipModel>,
    val muted: Boolean = false,
    val volume: Float = 1f,
)

/** A clip with its resolved absolute timeline position. */
@Immutable
data class PlacedClip(val clip: ClipModel, val startMs: Long) {
    val endMs: Long get() = startMs + clip.durationMs
    operator fun contains(timelineMs: Long) = timelineMs in startMs until endMs
}

@Immutable
data class TimelineState(
    val tracks: ImmutableList<Track>,
    val outputWidth: Int = 1080,
    val outputHeight: Int = 1920,
    val projectFps: Float = 30f,
    /** Monotonic; bumped by the store on each committed reduction. */
    val revision: Long = 0L,
) {
    val mainTrack: Track get() = tracks.first { it.type == TrackType.VIDEO_MAIN }

    /** Resolve absolute positions. Main track = prefix sums (ripple layout is free). */
    fun placements(track: Track): List<PlacedClip> =
        if (track.type == TrackType.VIDEO_MAIN) {
            var cursor = 0L
            track.clips.map { c -> PlacedClip(c, cursor).also { cursor += c.durationMs } }
        } else {
            track.clips.map { PlacedClip(it, it.startMs) }
        }

    val durationMs: Long
        get() = tracks.maxOfOrNull { t -> placements(t).maxOfOrNull { it.endMs } ?: 0L } ?: 0L

    fun findClip(id: ClipId): Pair<Track, ClipModel>? {
        for (t in tracks) for (c in t.clips) if (c.id == id) return t to c
        return null
    }

    fun findPlaced(id: ClipId): Pair<Track, PlacedClip>? {
        for (t in tracks) for (p in placements(t)) if (p.clip.id == id) return t to p
        return null
    }

    companion object {
        fun empty(): TimelineState = TimelineState(
            tracks = listOf(
                Track(TrackId("main"), TrackType.VIDEO_MAIN, persistentListOf()),
                Track(TrackId("text"), TrackType.TEXT, persistentListOf()),
                Track(TrackId("sticker"), TrackType.STICKER, persistentListOf()),
                Track(TrackId("audio"), TrackType.AUDIO, persistentListOf()),
            ).toPersistentList(),
        )
    }
}

/** Snap a timeline/source time onto the source's frame grid (nearest frame). */
fun Long.snapToFrame(fps: Float): Long {
    if (fps <= 0f) return this
    val frameMs = 1000.0 / fps
    return (Math.round(this / frameMs) * frameMs).roundToLong()
}

/**
 * Structural fingerprints decide how much of the preview pipeline must be rebuilt.
 * Everything not covered here (grades, LUTs, transitions, volume, text) is
 * "cosmetic" and reaches the render threads through lock-free snapshot swaps.
 */
fun TimelineState.videoStructureHash(): Int {
    var h = 17
    for (c in mainTrack.clips) {
        h = 31 * h + c.media.uri.hashCode()
        h = 31 * h + c.trimInMs.toInt()
        h = 31 * h + c.trimOutMs.toInt()
    }
    return h
}

fun TimelineState.audioStructureHash(): Int {
    var h = 17
    for (t in tracks) {
        if (t.type != TrackType.AUDIO) continue
        h = 31 * h + t.id.value.hashCode() + if (t.muted) 1 else 0
        for (c in t.clips) {
            h = 31 * h + c.media.uri.hashCode()
            h = 31 * h + c.trimInMs.toInt()
            h = 31 * h + c.trimOutMs.toInt()
            h = 31 * h + c.startMs.toInt()
        }
    }
    return h
}

/** Interpolated envelope gain at a clip-relative timeline position. */
fun ClipModel.gainAt(clipRelativeMs: Long): Float {
    val kfs = volumeKeyframes
    if (kfs.isEmpty()) return volume
    if (clipRelativeMs <= kfs.first().atMs) return volume * kfs.first().gain
    if (clipRelativeMs >= kfs.last().atMs) return volume * kfs.last().gain
    for (i in 0 until kfs.size - 1) {
        val a = kfs[i]; val b = kfs[i + 1]
        if (clipRelativeMs in a.atMs..b.atMs) {
            val f = (clipRelativeMs - a.atMs).toFloat() / (b.atMs - a.atMs).coerceAtLeast(1)
            return volume * (a.gain + (b.gain - a.gain) * f)
        }
    }
    return volume
}
