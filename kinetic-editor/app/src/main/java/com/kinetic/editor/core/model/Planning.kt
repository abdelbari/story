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

/** One PiP clip's placement over its span of composition (== timeline) time. */
data class PipWindow(val startUs: Long, val endUs: Long, val pip: PipSpec)

fun pipWindows(placements: List<PlacedClip>): List<PipWindow> =
    placements.map { PipWindow(it.startMs * 1_000L, it.endMs * 1_000L, it.clip.pip ?: PipSpec()) }

/**
 * Placement in force at a composition timestamp. Between or outside windows —
 * a gap, or a rounding straggler at a boundary — the nearest known framing is
 * held rather than returning null, so a stray frame is never placed full-screen.
 */
fun pipSpecAt(windows: List<PipWindow>, timeUs: Long): PipSpec? {
    if (windows.isEmpty()) return null
    var lo = 0
    var hi = windows.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val w = windows[mid]
        when {
            timeUs < w.startUs -> hi = mid - 1
            timeUs >= w.endUs -> lo = mid + 1
            else -> return w.pip
        }
    }
    return windows[lo.coerceIn(0, windows.size - 1)].pip
}
