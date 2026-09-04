package com.kinetic.editor.core.model

import kotlin.math.roundToLong

/*
 * Pure planning math shared by the PREVIEW engine and the EXPORT mapper.
 *
 * Both pipelines must derive identical geometry from the same document — a
 * transition that starts 40ms earlier in export than in preview is exactly the
 * class of bug that only shows up in a rendered file. Keeping one
 * implementation (and testing it) is what makes "what you see is what renders"
 * a property of the code rather than a claim in a comment.
 */

/**
 * A clip's transition half-windows in clip-local SOURCE microseconds
 * (pre-speed timestamps, the domain the GL shader sees).
 *
 * Transitions are single-stream: the outgoing clip animates through shader
 * phase [0, 0.5] over its last [outHalfUs], the incoming clip through
 * [0.5, 1] over its first [inHalfUs]. Each half is clamped to half the clip so
 * a long transition on a short clip cannot invert the window.
 */
data class TransitionWindowsUs(
    val durationUs: Long,
    val inTypeOrdinal: Int,
    val inHalfUs: Long,
    val outTypeOrdinal: Int,
    val outHalfUs: Long,
) {
    /** Absolute end of the incoming half, offset by the clip's base timestamp. */
    fun inEndUs(baseUs: Long = 0L): Long = baseUs + inHalfUs

    /** Absolute start of the outgoing half. */
    fun outStartUs(baseUs: Long = 0L): Long = baseUs + durationUs - outHalfUs

    /** Absolute end of the outgoing half (== the clip's end). */
    fun outEndUs(baseUs: Long = 0L): Long = baseUs + durationUs
}

/**
 * @param previousTransition the transitionOut of the clip BEFORE this one on
 *   the same track — that is what supplies this clip's incoming half.
 */
fun transitionWindowsUs(clip: ClipModel, previousTransition: TransitionSpec?): TransitionWindowsUs {
    val durationUs = clip.sourceSpanMs * 1_000L
    val halfCap = durationUs / 2

    // A transition's duration is authored in TIMELINE ms; this clip's own
    // timestamps run at source rate, so the conversion is * speed.
    fun halfUs(spec: TransitionSpec?): Long {
        if (spec == null || spec.type == TransitionType.NONE) return 0L
        return (spec.durationMs * 500L * clip.speed).roundToLong().coerceIn(0L, halfCap)
    }

    val inHalf = halfUs(previousTransition)
    val outHalf = halfUs(clip.transitionOut)
    return TransitionWindowsUs(
        durationUs = durationUs,
        inTypeOrdinal = if (inHalf > 0) previousTransition!!.type.ordinal else 0,
        inHalfUs = inHalf,
        outTypeOrdinal = if (outHalf > 0) clip.transitionOut!!.type.ordinal else 0,
        outHalfUs = outHalf,
    )
}

/**
 * One item in a serial sequence: what to play, from where, and how much blank
 * time precedes it. Used for both audio tracks and video-overlay (PiP) tracks —
 * a Media3 sequence is serial regardless of what it carries.
 *
 * Because a sequence is strictly serial, overlapping clips on one track cannot
 * be mixed within it. Rather than let a later clip slide right (which desyncs
 * the whole tail from the preview), the overlap is removed from the HEAD of the
 * later clip: what does play stays exactly where the timeline says it should be.
 */
data class SequenceItemPlan(
    val clip: ClipModel,
    val gapBeforeMs: Long,
    val trimInMs: Long,
    val trimOutMs: Long,
    val startMs: Long,
) {
    /** Timeline duration this item occupies after its (possibly trimmed) head. */
    val timelineDurationMs: Long
        get() = ((trimOutMs - trimInMs) / clip.speed.toDouble()).roundToLong()
}

fun planSequence(placements: List<PlacedClip>): List<SequenceItemPlan> {
    val out = ArrayList<SequenceItemPlan>(placements.size)
    var cursorMs = 0L
    for (placed in placements.sortedBy { it.startMs }) {
        val clip = placed.clip
        var trimInMs = clip.trimInMs
        var startMs = placed.startMs

        val overlapMs = cursorMs - startMs
        if (overlapMs > 0) {
            trimInMs += (overlapMs * clip.speed.toDouble()).roundToLong()
            startMs = cursorMs
            if (trimInMs >= clip.trimOutMs) continue // fully covered by the previous clip
        }

        val plan = SequenceItemPlan(
            clip = clip,
            gapBeforeMs = startMs - cursorMs,
            trimInMs = trimInMs,
            trimOutMs = clip.trimOutMs,
            startMs = startMs,
        )
        out.add(plan)
        cursorMs = startMs + plan.timelineDurationMs
    }
    return out
}

/**
 * Fade in/out expressed the way a user thinks about it, over the top of the
 * general keyframe envelope.
 *
 * A full keyframe editor is the wrong first tool: in practice almost every
 * volume edit is "ease it in at the start, ease it out at the end". These two
 * durations generate the four keyframes that do that, and can be read back out
 * of an arbitrary envelope so the sliders show what the clip actually has.
 */
data class FadeSpec(val inMs: Long = 0L, val outMs: Long = 0L)

fun fadeKeyframes(clipDurationMs: Long, fade: FadeSpec): List<VolumeKeyframe> {
    if (clipDurationMs <= 0) return emptyList()
    // Fades may not overlap; if the user asks for more than the clip holds,
    // scale both down proportionally rather than letting one win.
    var inMs = fade.inMs.coerceAtLeast(0L)
    var outMs = fade.outMs.coerceAtLeast(0L)
    val total = inMs + outMs
    if (total > clipDurationMs && total > 0) {
        inMs = inMs * clipDurationMs / total
        outMs = clipDurationMs - inMs
    }
    if (inMs == 0L && outMs == 0L) return emptyList()

    val out = ArrayList<VolumeKeyframe>(4)
    if (inMs > 0) {
        out.add(VolumeKeyframe(0L, 0f))
        out.add(VolumeKeyframe(inMs, 1f))
    } else {
        out.add(VolumeKeyframe(0L, 1f))
    }
    if (outMs > 0) {
        out.add(VolumeKeyframe(clipDurationMs - outMs, 1f))
        out.add(VolumeKeyframe(clipDurationMs, 0f))
    } else {
        out.add(VolumeKeyframe(clipDurationMs, 1f))
    }
    return out
}

/** Best-effort inverse of [fadeKeyframes]; zero for an envelope it did not author. */
fun readFades(keyframes: List<VolumeKeyframe>, clipDurationMs: Long): FadeSpec {
    if (keyframes.size < 2 || clipDurationMs <= 0) return FadeSpec()
    val first = keyframes.first()
    val last = keyframes.last()
    val inMs = if (first.atMs == 0L && first.gain == 0f) {
        keyframes.getOrNull(1)?.atMs ?: 0L
    } else 0L
    val outMs = if (last.gain == 0f) {
        (clipDurationMs - (keyframes.getOrNull(keyframes.size - 2)?.atMs ?: clipDurationMs))
            .coerceAtLeast(0L)
    } else 0L
    return FadeSpec(inMs, outMs)
}

/**
 * One PiP clip's placement over its span of composition (== timeline) time.
 * [aspect] is the source's display width / height (0 when unknown) so preview
 * and export can both size the box by width while keeping the picture's own
 * proportions.
 */
data class PipWindow(val startUs: Long, val endUs: Long, val pip: PipSpec, val aspect: Float = 0f)

/**
 * Windows follow the sequence plan, not the raw placements: an overlap between
 * two PiP clips is resolved the way the export sequence resolves it (head-trim
 * of the later clip), so the windows never overlap and a time maps to at most
 * one framing.
 */
fun pipWindows(placements: List<PlacedClip>): List<PipWindow> =
    planSequence(placements).map { plan ->
        val m = plan.clip.media
        PipWindow(
            startUs = plan.startMs * 1_000L,
            endUs = (plan.startMs + plan.timelineDurationMs) * 1_000L,
            pip = plan.clip.pip ?: PipSpec(),
            aspect = if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else 0f,
        )
    }

/** The window in force at a composition timestamp, or null between and outside clips. */
fun pipWindowAt(windows: List<PipWindow>, timeUs: Long): PipWindow? {
    var lo = 0
    var hi = windows.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val w = windows[mid]
        when {
            timeUs < w.startUs -> hi = mid - 1
            timeUs >= w.endUs -> lo = mid + 1
            else -> return w
        }
    }
    return null
}

/* ---------------------------- overlay animation ---------------------------- */

/** How an overlay is drawn at one moment: the whole animation, as data. */
data class OverlayAnimState(
    val alpha: Float,
    val scale: Float,
    /** NDC offset added to the anchor's y; the frame is 2 units tall. */
    val dy: Float,
    /** Characters to reveal, or -1 for all of them. */
    val visibleChars: Int,
)

private const val OVERLAY_ANIM_US = 350_000L
private const val RISE_NDC = 0.15f

/**
 * The state of an overlay animation at [timeUs], in composition (== timeline) time.
 *
 * One implementation for both pipelines: the preview draws from it and the
 * export's overlay reads it per frame, so an animation cannot look one way on
 * screen and another in the file. Pure, and therefore actually testable —
 * animation timing is otherwise the kind of thing only a rendered video reveals.
 */
fun overlayAnimAt(
    anim: OverlayAnim,
    timeUs: Long,
    startUs: Long,
    endUs: Long,
    charCount: Int,
): OverlayAnimState {
    if (timeUs < startUs || timeUs >= endUs) return OverlayAnimState(0f, 1f, 0f, 0)
    // A short clip gets a short animation rather than a truncated one: neither
    // end may eat more than half of it, or the two would overlap.
    val windowUs = minOf(OVERLAY_ANIM_US, (endUs - startUs) / 2).coerceAtLeast(1L)
    val enter = ((timeUs - startUs).toFloat() / windowUs).coerceIn(0f, 1f)
    val exit = ((endUs - timeUs).toFloat() / windowUs).coerceIn(0f, 1f)
    val edge = minOf(enter, exit)
    return when (anim) {
        OverlayAnim.NONE -> OverlayAnimState(1f, 1f, 0f, -1)
        OverlayAnim.FADE -> OverlayAnimState(edge, 1f, 0f, -1)
        OverlayAnim.POP -> OverlayAnimState(edge, 0.6f + 0.4f * easeOutBack(enter), 0f, -1)
        OverlayAnim.RISE -> OverlayAnimState(edge, 1f, -RISE_NDC * (1f - easeOutCubic(enter)), -1)
        // Typing IS the entrance, so it does not also fade in; it still fades out.
        // At least one character once the clip has started: media3 rasterises
        // text through a StaticLayout, and a zero-width layout makes
        // Bitmap.createBitmap throw — an empty first frame would not look
        // empty, it would end the export.
        OverlayAnim.TYPE -> OverlayAnimState(
            alpha = exit,
            scale = 1f,
            dy = 0f,
            visibleChars = kotlin.math.ceil(charCount * enter).toInt()
                .coerceIn(if (charCount > 0) 1 else 0, charCount),
        )
    }
}

/** 0 -> 1 overshooting slightly past 1, which is what makes a pop read as a pop. */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

private fun easeOutCubic(t: Float): Float {
    val u = 1f - t
    return 1f - u * u * u
}

/* ------------------------------- clip motion ------------------------------- */

/** How far a push travels, and how far a pan slides. */
private const val MOTION_ZOOM = 1.18f
private const val MOTION_PAN = 0.12f

/**
 * A pan has to be zoomed in far enough that sliding never reveals the edge of
 * the source. Sampling stays inside the frame while `scale >= 1 + |offset|`,
 * so this is [MOTION_PAN] plus a margin.
 */
private const val MOTION_PAN_ZOOM = 1.15f

/**
 * The clip's transform partway through it, [progress] running 0..1 across the
 * clip's own span.
 *
 * Linear, not eased: a push that accelerates and brakes over a whole shot reads
 * as sluggish, where a constant drift reads as a camera move. Motion composes
 * with the manual transform rather than replacing it, so a clip that has been
 * reframed by hand can still be given a push.
 */
/**
 * The clip's transform partway through it, from whichever source supplies a
 * move: a hand-set [end] frame if there is one, otherwise a [motion] preset.
 *
 * One function so the two cannot both apply and fight. A move set by hand wins,
 * because the user was being more specific than a preset.
 */
fun transformAt(
    base: TransformSpec,
    end: TransformSpec?,
    motion: ClipMotion,
    progress: Float,
): TransformSpec =
    if (end != null) lerpTransform(base, end, progress.coerceIn(0f, 1f))
    else motionAt(base, motion, progress)

/** Straight-line travel between two framings; rotation takes the direct path. */
private fun lerpTransform(a: TransformSpec, b: TransformSpec, t: Float) = TransformSpec(
    scale = a.scale + (b.scale - a.scale) * t,
    offsetX = a.offsetX + (b.offsetX - a.offsetX) * t,
    offsetY = a.offsetY + (b.offsetY - a.offsetY) * t,
    rotationDeg = a.rotationDeg + (b.rotationDeg - a.rotationDeg) * t,
)

fun motionAt(base: TransformSpec, motion: ClipMotion, progress: Float): TransformSpec {
    if (motion == ClipMotion.NONE) return base
    val t = progress.coerceIn(0f, 1f)
    fun lerp(from: Float, to: Float) = from + (to - from) * t
    return when (motion) {
        ClipMotion.NONE -> base
        ClipMotion.ZOOM_IN -> base.copy(scale = base.scale * lerp(1f, MOTION_ZOOM))
        ClipMotion.ZOOM_OUT -> base.copy(scale = base.scale * lerp(MOTION_ZOOM, 1f))
        ClipMotion.PAN_LEFT -> base.copy(
            scale = base.scale * MOTION_PAN_ZOOM,
            offsetX = base.offsetX + lerp(MOTION_PAN, -MOTION_PAN),
        )
        ClipMotion.PAN_RIGHT -> base.copy(
            scale = base.scale * MOTION_PAN_ZOOM,
            offsetX = base.offsetX + lerp(-MOTION_PAN, MOTION_PAN),
        )
        ClipMotion.DRIFT_UP -> base.copy(
            scale = base.scale * MOTION_PAN_ZOOM,
            offsetY = base.offsetY + lerp(-MOTION_PAN, MOTION_PAN),
        )
    }
}

/* ------------------------------ overlay sizing ----------------------------- */

/**
 * The multiplier media3 needs so an overlay covers [fractionOfWidth] of the
 * frame it is drawn into.
 *
 * The compositor and the overlay effect both draw at the source's NATIVE pixel
 * size times a scale, so the same sticker would come out a different size on
 * every canvas and the same picture-in-picture a different size for every
 * source resolution. The preview, meanwhile, lays its boxes out as a plain
 * fraction of the frame. This is the conversion between the two, in one place,
 * so "scale means fraction of the frame's width" holds on both sides — and can
 * be tested rather than reasoned about.
 *
 * Height needs no conversion: media3 scales both axes by this, which preserves
 * the source's own proportions, exactly as the preview does.
 */
fun overlayScaleFor(fractionOfWidth: Float, frameWidthPx: Int, assetWidthPx: Int): Float =
    if (assetWidthPx > 0 && frameWidthPx > 0) {
        fractionOfWidth * frameWidthPx / assetWidthPx
    } else {
        // Unknown geometry: fall back to the raw fraction rather than dividing
        // by nothing. Wrong size beats no frame.
        fractionOfWidth
    }
