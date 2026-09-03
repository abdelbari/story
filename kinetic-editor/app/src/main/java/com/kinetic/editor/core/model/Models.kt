package com.kinetic.editor.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.serialization.Serializable
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

@Serializable
@JvmInline
value class ClipId(val value: String) {
    companion object { fun random() = ClipId(UUID.randomUUID().toString()) }
}

@Serializable
@JvmInline
value class TrackId(val value: String) {
    companion object { fun random() = TrackId(UUID.randomUUID().toString()) }
}

enum class TrackType { VIDEO_MAIN, VIDEO_OVERLAY, TEXT, STICKER, AUDIO }

/** Immutable description of a source media file. Probed once at import. */
@Serializable
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

@Serializable
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
@Serializable
@Immutable
data class LutSpec(
    val assetPath: String,       // e.g. "luts/teal_orange.png"
    val intensity: Float = 1f,   // [0, 1]
)

enum class TransitionType { NONE, DIP_TO_BLACK, WIPE_LEFT, ZOOM_PUNCH }

@Serializable
@Immutable
data class TransitionSpec(
    val type: TransitionType,
    val durationMs: Long = 500L,
)

@Serializable
@Immutable
data class VolumeKeyframe(
    val atMs: Long,   // relative to clip timeline start
    val gain: Float,  // [0, 2] linear
)

@Serializable
@Immutable
data class TextSpec(
    val text: String,
    val textSizePx: Float = 64f,
    val argb: Long = 0xFFFFFFFF,
    // Normalized device coords, [-1, 1]; (0, 0) is frame center.
    val anchorX: Float = 0f,
    val anchorY: Float = -0.6f,
    val font: TextFont = TextFont.SANS,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val anim: OverlayAnim = OverlayAnim.FADE,
)

/**
 * How an overlay — text or sticker — enters and leaves.
 *
 * The timing is [overlayAnimAt], shared by the preview and the export, so an
 * animation is one implementation seen twice rather than two that drift.
 */
@Serializable
enum class OverlayAnim(val label: String) {
    NONE("Cut"),
    FADE("Fade"),
    POP("Pop"),
    RISE("Rise"),
    TYPE("Type"),
}

/**
 * The type faces a text clip can use.
 *
 * These are the families Android itself ships, not bundled font files, and that
 * is deliberate: they exist on every device, cost nothing to install, and
 * [androidFamily] is the *same string* the export's TypefaceSpan resolves that
 * Compose resolves its own [FontFamily] from — so the preview and the render
 * pick the identical face rather than two faces that merely look similar.
 * Bundled faces can be added later without changing anything but this enum.
 */
@Serializable
enum class TextFont(val androidFamily: String, val label: String) {
    SANS("sans-serif", "Sans"),
    SERIF("serif", "Serif"),
    MONO("monospace", "Mono"),
    CURSIVE("cursive", "Script"),
}

/**
 * Everything about a text clip that changes its measured layout, and nothing
 * that does not: colour is applied when the layout is drawn, so two clips
 * differing only in colour share one measurement.
 */
fun TextSpec.layoutKey(sizePx: Int): String = "$font|$bold|$italic|$sizePx|$text"

/**
 * How a clip whose shape differs from the canvas is fitted into it.
 *
 * FIT letterboxes, FILL crops to cover, STRETCH distorts. The names map to
 * media3's Presentation layouts, which is where the fitting actually happens —
 * in the preview as well as the export, so the two cannot disagree.
 */
@Serializable
enum class CanvasFit(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    STRETCH("Stretch"),
}

/**
 * Pan, zoom and rotation of the picture inside its own frame — CapCut's
 * "transform", and the thing a reframe or a slow push-in is made of.
 *
 * Applied by the shared shader, so it costs no extra pass and the preview and
 * the render are the same code. Offsets are NDC over a 2-unit frame; positive
 * [rotationDeg] turns the picture counter-clockwise, as media3 specifies
 * rotation everywhere else.
 */
@Serializable
@Immutable
data class TransformSpec(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDeg: Float = 0f,
) {
    val isIdentity: Boolean
        get() = scale == 1f && offsetX == 0f && offsetY == 0f && rotationDeg == 0f

    companion object { val NONE = TransformSpec() }
}

/**
 * A move that runs across the whole clip, on top of its [TransformSpec].
 *
 * The 90% of camera motion an editor actually reaches for, as one tap rather
 * than a keyframe editor — the same trade the volume fades make over the
 * general envelope underneath them. [motionAt] is the whole implementation.
 */
@Serializable
enum class ClipMotion(val label: String) {
    NONE("None"),
    ZOOM_IN("Push in"),
    ZOOM_OUT("Pull out"),
    PAN_LEFT("Pan left"),
    PAN_RIGHT("Pan right"),
    DRIFT_UP("Drift up"),
}

/** Placement of a picture-in-picture video overlay. Null on a clip = full frame. */
@Serializable
@Immutable
data class PipSpec(
    // Normalized device coords, [-1, 1]; (0, 0) is frame center.
    val anchorX: Float = 0.55f,
    val anchorY: Float = 0.55f,
    val scale: Float = 0.35f,
    val rotationDeg: Float = 0f,
    /** 0 = invisible, 1 = opaque. Blended over the main picture. */
    val opacity: Float = 1f,
)

@Serializable
@Immutable
data class StickerSpec(
    val assetPath: String,
    val anchorX: Float = 0.6f,
    val anchorY: Float = 0.6f,
    val scale: Float = 0.35f,
    val rotationDeg: Float = 0f,
    val anim: OverlayAnim = OverlayAnim.FADE,
)

@Serializable
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
    @Serializable(with = PersistentListSerializer::class)
    val volumeKeyframes: PersistentList<VolumeKeyframe> = persistentListOf(),
    val text: TextSpec? = null,
    val sticker: StickerSpec? = null,
    /** Set on VIDEO_OVERLAY clips; drives both the preview surface and the compositor. */
    val pip: PipSpec? = null,
    val transform: TransformSpec = TransformSpec.NONE,
    val motion: ClipMotion = ClipMotion.NONE,
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

@Serializable
@Immutable
data class Track(
    val id: TrackId,
    val type: TrackType,
    // PersistentList (not just ImmutableList) so the reducer can use mutate {}.
    @Serializable(with = PersistentListSerializer::class)
    val clips: PersistentList<ClipModel>,
    val muted: Boolean = false,
    val volume: Float = 1f,
)

/** A clip with its resolved absolute timeline position. */
@Immutable
data class PlacedClip(val clip: ClipModel, val startMs: Long) {
    val endMs: Long get() = startMs + clip.durationMs
    operator fun contains(timelineMs: Long) = timelineMs in startMs until endMs
}

@Serializable
@Immutable
data class TimelineState(
    @Serializable(with = PersistentListSerializer::class)
    val tracks: PersistentList<Track>,
    val outputWidth: Int = 1080,
    val outputHeight: Int = 1920,
    val canvasFit: CanvasFit = CanvasFit.FIT,
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
                Track(TrackId("overlay"), TrackType.VIDEO_OVERLAY, persistentListOf()),
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

fun TimelineState.overlayStructureHash(): Int {
    var h = 17
    for (t in tracks) {
        if (t.type != TrackType.VIDEO_OVERLAY) continue
        h = 31 * h + t.id.value.hashCode()
        for (c in t.clips) {
            h = 31 * h + c.media.uri.hashCode()
            h = 31 * h + c.trimInMs.toInt()
            h = 31 * h + c.trimOutMs.toInt()
            h = 31 * h + c.startMs.toInt()
        }
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
