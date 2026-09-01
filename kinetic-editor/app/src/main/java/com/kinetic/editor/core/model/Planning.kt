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
 * One item in a serial audio sequence: what to play, from where, and how much
 * silence precedes it.
 *
 * A Media3 EditedMediaItemSequence is strictly serial, so overlapping clips on
 * one AUDIO track cannot be mixed within it. Rather than let a later clip slide
 * right (which desyncs the whole tail from the preview), the overlap is removed
 * from the HEAD of the later clip: the audio that does play stays exactly where
 * the timeline says it should be.
 */
data class AudioItemPlan(
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

fun planAudioSequence(placements: List<PlacedClip>): List<AudioItemPlan> {
    val out = ArrayList<AudioItemPlan>(placements.size)
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

        val plan = AudioItemPlan(
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
