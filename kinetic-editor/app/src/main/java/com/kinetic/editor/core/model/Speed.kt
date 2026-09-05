package com.kinetic.editor.core.model

import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Speed, in one place. Everything that converts between a clip's SOURCE time
 * and its TIMELINE time — the reducer's split, the preview's seek mapping and
 * playback rate, the export's SpeedProvider, the timeline's thumbnails — goes
 * through the functions here, so a clip with a curve or a freeze plays the
 * same everywhere. A constant speed takes a one-line fast path through each.
 */

/** One run of constant speed, in clip-relative SOURCE milliseconds. */
data class SpeedRun(val sourceStartMs: Long, val sourceEndMs: Long, val speed: Float) {
    val sourceSpanMs: Long get() = sourceEndMs - sourceStartMs
    val timelineSpanMs: Double get() = sourceSpanMs / speed.toDouble()
}

/** Bounds on a run's rate: a curve times the clip's own speed can reach past either. */
const val MIN_RUN_SPEED = 0.05f
const val MAX_RUN_SPEED = 16f

/** Whether the clip plays at one rate for its whole span. */
val ClipModel.hasConstantSpeed: Boolean get() = freezeMs <= 0L && curve == null

/**
 * The runs a clip plays at, over its trimmed source span. A constant speed is
 * one run; a freeze is one run slow enough to hold its single frame for the
 * whole hold; a curve is [SpeedCurve.RUNS] equal steps of source time, each
 * played at the curve's rate at its midpoint.
 */
fun ClipModel.speedRuns(): List<SpeedRun> {
    val span = sourceSpanMs
    if (freezeMs > 0L) return listOf(SpeedRun(0L, span, span.toFloat() / freezeMs))
    val c = curve ?: return listOf(SpeedRun(0L, span, speed))
    val n = SpeedCurve.RUNS
    val out = ArrayList<SpeedRun>(n)
    for (i in 0 until n) {
        val a = span * i / n
        val b = if (i == n - 1) span else span * (i + 1) / n
        if (b <= a) continue
        val rate = (speed * c.speedAt((i + 0.5f) / n)).coerceIn(MIN_RUN_SPEED, MAX_RUN_SPEED)
        out.add(SpeedRun(a, b, rate))
    }
    return out
}

/** Clip-relative SOURCE ms -> clip-relative TIMELINE ms. */
fun ClipModel.sourceToTimelineMs(sourceRelMs: Long): Long {
    if (hasConstantSpeed) return (sourceRelMs / speed.toDouble()).roundToLong()
    var acc = 0.0
    for (r in speedRuns()) {
        if (sourceRelMs <= r.sourceStartMs) break
        acc += (min(sourceRelMs, r.sourceEndMs) - r.sourceStartMs) / r.speed.toDouble()
    }
    return acc.roundToLong()
}

/** The timeline span between two clip-relative SOURCE positions. */
fun ClipModel.timelineSpanMs(sourceFromMs: Long, sourceToMs: Long): Long =
    if (hasConstantSpeed) ((sourceToMs - sourceFromMs) / speed.toDouble()).roundToLong()
    else sourceToTimelineMs(sourceToMs) - sourceToTimelineMs(sourceFromMs)

/** Clip-relative TIMELINE ms -> clip-relative SOURCE ms, held within the span. */
fun ClipModel.timelineToSourceMs(timelineRelMs: Long): Long {
    if (hasConstantSpeed) {
        return (timelineRelMs * speed.toDouble()).roundToLong().coerceIn(0L, sourceSpanMs)
    }
    var acc = 0.0
    for (r in speedRuns()) {
        val runMs = r.timelineSpanMs
        if (timelineRelMs <= acc + runMs) {
            val into = (timelineRelMs - acc).coerceAtLeast(0.0)
            return (r.sourceStartMs + into * r.speed).roundToLong()
                .coerceIn(r.sourceStartMs, r.sourceEndMs)
        }
        acc += runMs
    }
    return sourceSpanMs
}

/** The rate the clip plays at, at a clip-relative SOURCE position. */
fun ClipModel.speedAtSourceMs(sourceRelMs: Long): Float {
    if (hasConstantSpeed) return speed
    val runs = speedRuns()
    for (r in runs) if (sourceRelMs < r.sourceEndMs) return r.speed
    return runs.last().speed
}

/**
 * The runs as media3's SpeedProvider sees them: item-local microseconds from
 * the item's first sample — which, for a head-trimmed sequence item, is
 * [headOffsetMs] into the clip. Pure, so the boundary rule can be tested: the
 * next change is always strictly after the time asked about, because media3
 * walks changes with `while (next <= now)`, and an equal answer never ends.
 */
class SpeedRunLookup(private val runs: List<SpeedRun>, private val headOffsetMs: Long = 0L) {

    val isConstant: Boolean get() = runs.size == 1

    val constantSpeed: Float get() = runs.first().speed

    fun speedAtUs(itemTimeUs: Long): Float {
        val sourceMs = itemTimeUs / 1_000L + headOffsetMs
        for (r in runs) if (sourceMs < r.sourceEndMs) return r.speed
        return runs.last().speed
    }

    /** Item-local µs of the next speed change after [itemTimeUs], or -1 for none. */
    fun nextChangeUs(itemTimeUs: Long): Long {
        for (i in 0 until runs.size - 1) {
            val boundaryUs = (runs[i].sourceEndMs - headOffsetMs) * 1_000L
            if (boundaryUs > itemTimeUs) return boundaryUs
        }
        return -1L
    }
}
