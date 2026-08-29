package com.kinetic.editor.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.kinetic.editor.core.model.VolumeKeyframe
import java.nio.ByteBuffer

/**
 * Sample-accurate volume envelope for the EXPORT pipeline (the preview
 * approximates the same envelope by setting player volume from a 10Hz tick).
 *
 * Placement in the chain matters: it runs AFTER SonicAudioProcessor(speed), so
 * sample position == clip TIMELINE time — the same domain the keyframes are
 * authored in. Position is tracked by frames-since-flush; Transformer flushes
 * processors at each item start, which resets the envelope exactly at clip start.
 */
class VolumeEnvelopeAudioProcessor(
    private val baseGain: Float,
    keyframes: List<VolumeKeyframe>,
) : BaseAudioProcessor() {

    // Struct-of-arrays: no boxing, no per-sample object reads.
    private val timesUs = LongArray(keyframes.size) { keyframes[it].atMs * 1000L }
    private val gains = FloatArray(keyframes.size) { keyframes[it].gain }

    private var cursor = 0
    private var framePosition = 0L
    private var sampleRate = 0
    private var channelCount = 0

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Identity envelope: report inactive so the pipeline skips us entirely.
        if (timesUs.isEmpty() && baseGain == 1f) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val bytesPerFrame = 2 * channelCount

        while (inputBuffer.remaining() >= bytesPerFrame) {
            val gain = baseGain * envelopeGainAtUs(framePosition * 1_000_000L / sampleRate)
            for (c in 0 until channelCount) {
                val sample = (inputBuffer.short * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(sample.toShort())
            }
            framePosition++
        }
        // Defensive: pass through any trailing partial frame untouched.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }

    override fun onFlush() {
        framePosition = 0
        cursor = 0
    }

    /** Monotonic cursor: playback time only moves forward, so lookup is O(1) amortized. */
    private fun envelopeGainAtUs(timeUs: Long): Float {
        if (timesUs.isEmpty()) return 1f
        if (timeUs <= timesUs[0]) return gains[0]
        if (timeUs >= timesUs[timesUs.size - 1]) return gains[gains.size - 1]
        while (cursor + 1 < timesUs.size && timeUs >= timesUs[cursor + 1]) cursor++
        val t0 = timesUs[cursor]
        val t1 = timesUs[cursor + 1]
        val f = (timeUs - t0).toFloat() / (t1 - t0).coerceAtLeast(1L)
        return gains[cursor] + (gains[cursor + 1] - gains[cursor]) * f
    }
}
