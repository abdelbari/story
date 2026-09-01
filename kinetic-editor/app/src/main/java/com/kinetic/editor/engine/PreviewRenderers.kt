package com.kinetic.editor.engine

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * A video renderer whose frame timestamps, as the GL effects see them, are
 * positions in the preview WINDOW — the concatenated source timeline that
 * [PreviewFxTimeline][com.kinetic.editor.effects.PreviewFxTimeline] is built in.
 *
 * Stock ExoPlayer hands the effects pipeline `bufferTime - startOfFirstStream`,
 * with the start captured once per renderer lifetime. Sequential playback then
 * yields window positions, but a seek into another clip restarts the renderer
 * clock (ExoPlayerImplInternal resets the period's renderer offset), after which
 * the same formula yields that clip's *source* time instead. The grade, LUT and
 * transition lookups would then land on the wrong clip after any cross-clip
 * scrub. Each stream carries its own period id, and the timeline knows where
 * that period sits in the window, so the adjustment is derived per stream:
 *
 *     frameTime = bufferTime - streamOffset + period.positionInWindow
 *               = sampleTime + positionInWindow
 *               = window position, however playback got there.
 */
internal class WindowTimeVideoRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {

    private val period = Timeline.Period()

    /** Window position of each input stream the decoder has not reached yet, in order. */
    private val pendingPositionsUs = ArrayDeque<Long>()
    private var positionInWindowUs = 0L

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        val timeline = timeline
        val index = timeline.getIndexOfPeriod(mediaPeriodId.periodUid)
        val positionUs = if (index == C.INDEX_UNSET) 0L else timeline.getPeriod(index, period).positionInWindowUs
        // The superclass reports the change as processed synchronously for the
        // first stream and whenever the previous one is already drained, so the
        // entry must be queued before delegating.
        pendingPositionsUs.addLast(positionUs)
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onProcessedStreamChange() {
        pendingPositionsUs.removeFirstOrNull()?.let { positionInWindowUs = it }
        // The superclass reads getBufferTimestampAdjustmentUs() in here.
        super.onProcessedStreamChange()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        // Mirrors MediaCodecRenderer dropping its pending stream changes on a
        // position reset; the current output stream (and its offset) stays.
        pendingPositionsUs.clear()
    }

    override fun onDisabled() {
        pendingPositionsUs.clear()
        super.onDisabled()
    }

    override fun getBufferTimestampAdjustmentUs(): Long = positionInWindowUs - outputStreamOffsetUs
}

/** DefaultRenderersFactory with [WindowTimeVideoRenderer] as the video renderer. */
internal class PreviewRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            WindowTimeVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
            ),
        )
    }
}
