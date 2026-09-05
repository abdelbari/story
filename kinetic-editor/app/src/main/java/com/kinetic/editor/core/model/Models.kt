package com.kinetic.editor.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.serialization.Serializable
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
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
    /** Film grain on the print: [0, 1]. Screen space, so it never zooms. */
    val grain: Float = 0f,
    /** Corner falloff: [0, 1]. */
    val vignette: Float = 0f,
) {
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 1f && saturation == 1f &&
            temperature == 0f && grain == 0f && vignette == 0f

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
    /**
     * Outline thickness as a fraction of the text size, so it stays right when
     * the caption is resized. Legibility over footage is what captions are for,
     * and an outline is how they get it.
     */
    val outline: Float = 0f,
    val outlineArgb: Long = 0xFF000000,
    /** Drop shadow strength, [0, 1]; scaled by the text size like the outline. */
    val shadow: Float = 0f,
    /** Backing box behind the text. Alpha 0 — the default — means no box. */
    val boxArgb: Long = 0x00000000,
) {
    /** Outline half-width in canvas pixels. */
    val outlinePx: Float get() = outline * textSizePx

    /** Shadow blur and offset in canvas pixels. */
    val shadowPx: Float get() = shadow * textSizePx * 0.18f

    /** How much room the outline and shadow need around the text block. */
    val decorationPadPx: Float get() = outlinePx + shadowPx * 2f
}

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
 * What shows behind a clip that does not fill the canvas.
 *
 * BLUR is the clip itself, blown up to cover the canvas and softened — the
 * treatment nearly every vertical edit of horizontal footage uses. The colour
 * components are what the fill program paints for the flat choices.
 */
@Serializable
enum class CanvasBackground(val label: String, val red: Float, val green: Float, val blue: Float) {
    BLACK("Black", 0f, 0f, 0f),
    BLUR("Blur", 0f, 0f, 0f),
    WHITE("White", 1f, 1f, 1f),
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

/**
 * Chroma key: makes one colour transparent so what is behind shows through.
 *
 * Null on a clip means no key, which is why it is nullable rather than a
 * disabled default — an untouched clip costs nothing on disk and nothing in the
 * shader. Meant for picture-in-picture, where there is something behind to
 * reveal; on the main track a keyed pixel simply reads as black.
 */
@Serializable
@Immutable
data class ChromaKeySpec(
    /** The colour to remove. Green by default, because that is what people shoot. */
    val argb: Long = 0xFF00D000,
    /** How far from the key a colour may be and still be removed: [0, 1]. */
    val tolerance: Float = 0.32f,
    /** Feather either side of that threshold, so the edge is not a staircase. */
    val softness: Float = 0.10f,
)

/** The shapes a mask can take; labels are what the inspector shows. */
@Serializable
enum class MaskShape(val label: String) {
    CIRCLE("Circle"),
    RECTANGLE("Rectangle"),
    LINEAR("Split"),
    BAND("Band"),
}

/**
 * A mask: the part of the frame a clip is allowed to show through.
 *
 * It sits on the FRAME rather than on the picture, so it holds still while the
 * clip pans or zooms behind it — which is what a circle reveal or a split
 * screen wants. Null on a clip means no mask, for the same reason the chroma
 * key is nullable: an unmasked clip costs nothing on disk and one compare in
 * the shader. Sizes are fractions of the frame's HEIGHT, so a circle is round
 * on every canvas and the same mask reads the same on a 9:16 and a 16:9.
 */
@Serializable
@Immutable
data class MaskSpec(
    val shape: MaskShape = MaskShape.CIRCLE,
    // NDC, [-1, 1]; (0, 0) is frame centre, y up, like every other anchor.
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    /** Diameter (circle), height (rectangle) or width (band), as a fraction of the frame height. */
    val size: Float = 0.6f,
    /** Rectangle width over its height. */
    val aspect: Float = 1f,
    /** Rectangle corner rounding, 0 (square) to 1 (fully round). */
    val roundness: Float = 0.1f,
    val rotationDeg: Float = 0f,
    /** Edge softness, as a fraction of the frame height. */
    val feather: Float = 0.04f,
    /** Show what is OUTSIDE the shape instead. */
    val invert: Boolean = false,
)

/**
 * A stylised treatment of the whole frame, animated per frame by the shader.
 *
 * These are the procedural looks short-form editors reach for. None needs an
 * asset, each is the same code in preview and export, and
 * [ClipModel.effectAmount] scales every one so a light touch is possible.
 * Ids in the shader are the ordinals here.
 */
@Serializable
enum class ClipEffect(val label: String) {
    NONE("None"),
    CHROMATIC("Chromatic"),
    GLITCH("Glitch"),
    VHS("VHS"),
    LIGHT_LEAK("Light leak"),
    FLICKER("Flicker"),
    SHAKE("Shake"),
    GLOW("Glow"),
    MIRROR("Mirror"),
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

/** One point of a speed curve: at fraction [t] of the clip's source span, play at [speed]. */
@Serializable
@Immutable
data class SpeedPoint(val t: Float, val speed: Float)

/**
 * A speed that changes across the clip — CapCut's curve speed.
 *
 * Points are over the clip's SOURCE span, so a preset keeps its shape whatever
 * the clip's length, and the speed between points is interpolated in log
 * space, which is what makes a ramp from 0.5x to 4x feel even rather than
 * lurch through 2x. Both pipelines play it as [speedRuns]: media3's speed API
 * is piecewise-constant, and the preview sets the player's rate the same way,
 * so the two cannot disagree about where a ramp lands.
 */
@Serializable
@Immutable
data class SpeedCurve(val points: List<SpeedPoint>) {

    /** The rate at fraction [t] of the source span; flat beyond the end points. */
    fun speedAt(t: Float): Float {
        if (points.isEmpty()) return 1f
        if (t <= points.first().t) return points.first().speed
        if (t >= points.last().t) return points.last().speed
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (t >= a.t && t <= b.t) {
                // Two points at one t are a step; the later one wins from there on.
                if (b.t <= a.t) return b.speed
                val f = (t - a.t) / (b.t - a.t)
                return exp(ln(a.speed) + (ln(b.speed) - ln(a.speed)) * f)
            }
        }
        return points.last().speed
    }

    /**
     * The curve between two fractions, re-parametrised to 0..1: what a split
     * leaves each half, so a cut clip keeps playing exactly as it did.
     */
    fun slice(t0: Float, t1: Float): SpeedCurve {
        val span = t1 - t0
        if (span <= 0f) return this
        val inner = points
            .filter { it.t > t0 && it.t < t1 }
            .map { SpeedPoint((it.t - t0) / span, it.speed) }
        return SpeedCurve(
            listOf(SpeedPoint(0f, speedAt(t0))) + inner + listOf(SpeedPoint(1f, speedAt(t1))),
        )
    }

    companion object {
        /** Steps a curve is played as; see [speedRuns]. */
        const val RUNS = 24
    }
}

/**
 * The curve shapes offered as one tap. The clip stores the points rather than
 * the name, so a shape can be adjusted later without the document knowing
 * what it started as; the inspector recognises a preset by its points.
 */
enum class SpeedPreset(val label: String, val curve: SpeedCurve) {
    MONTAGE(
        "Montage",
        SpeedCurve(
            listOf(
                SpeedPoint(0f, 1f), SpeedPoint(0.25f, 2.2f), SpeedPoint(0.5f, 0.6f),
                SpeedPoint(0.75f, 2.2f), SpeedPoint(1f, 1f),
            ),
        ),
    ),
    HERO(
        "Hero",
        SpeedCurve(
            listOf(
                SpeedPoint(0f, 1f), SpeedPoint(0.3f, 4f), SpeedPoint(0.5f, 0.4f),
                SpeedPoint(0.7f, 4f), SpeedPoint(1f, 1f),
            ),
        ),
    ),
    BULLET(
        "Bullet",
        SpeedCurve(
            listOf(
                SpeedPoint(0f, 1.5f), SpeedPoint(0.35f, 6f), SpeedPoint(0.5f, 0.3f),
                SpeedPoint(0.65f, 6f), SpeedPoint(1f, 1.5f),
            ),
        ),
    ),
    JUMP_CUT(
        "Jump cut",
        SpeedCurve(
            listOf(
                SpeedPoint(0f, 1f), SpeedPoint(0.45f, 1f), SpeedPoint(0.5f, 8f),
                SpeedPoint(0.55f, 1f), SpeedPoint(1f, 1f),
            ),
        ),
    ),
    FLASH_IN(
        "Flash in",
        SpeedCurve(
            listOf(SpeedPoint(0f, 4f), SpeedPoint(0.3f, 4f), SpeedPoint(0.6f, 1f), SpeedPoint(1f, 1f)),
        ),
    ),
    FLASH_OUT(
        "Flash out",
        SpeedCurve(
            listOf(SpeedPoint(0f, 1f), SpeedPoint(0.4f, 1f), SpeedPoint(0.7f, 4f), SpeedPoint(1f, 4f)),
        ),
    ),
}

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
    val chroma: ChromaKeySpec? = null,
    /**
     * Where the picture ends up, when the user wants a move of their own.
     * Null means the clip does not travel: [motion] supplies the move, or
     * nothing does. Non-null takes precedence — a move set by hand beats a
     * preset, because the user was more specific.
     */
    val transformEnd: TransformSpec? = null,
    /** Mirrors the source picture left-to-right / top-to-bottom, before anything else. */
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val mask: MaskSpec? = null,
    val effect: ClipEffect = ClipEffect.NONE,
    /** How strongly [effect] is applied, [0, 1]. Kept when the effect is switched, like a mix knob. */
    val effectAmount: Float = 0.6f,
    /** A speed that changes across the clip, on top of [speed]. Null plays at [speed] throughout. */
    val curve: SpeedCurve? = null,
    /**
     * Above zero, the clip is a freeze frame: its first frame, held for this
     * long. The trim window is then a single frame and [durationMs] is this.
     */
    val freezeMs: Long = 0L,
) {
    /** Source-domain span (what the decoder actually reads). */
    val sourceSpanMs: Long get() = trimOutMs - trimInMs

    /** Timeline-domain duration after speed; see Speed.kt for the mapping. */
    val durationMs: Long
        get() = when {
            freezeMs > 0L -> freezeMs
            curve == null -> (sourceSpanMs / speed.toDouble()).roundToLong()
            else -> sourceToTimelineMs(sourceSpanMs)
        }

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
    /** Only visible with [CanvasFit.FIT]; the other fits leave no bars to fill. */
    val canvasBackground: CanvasBackground = CanvasBackground.BLACK,
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
