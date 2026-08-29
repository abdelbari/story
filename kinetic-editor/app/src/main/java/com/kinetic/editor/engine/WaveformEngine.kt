package com.kinetic.editor.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Decodes each audio source ONCE into a normalized min/max peak array
 * (~50 buckets/second), which the timeline draws directly. Runs on a single
 * background lane so waveform extraction never competes with thumbnail decode
 * or playback for codec instances.
 */
class WaveformEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val revision = mutableIntStateOf(0)

    private val cache = ConcurrentHashMap<String, FloatArray>()
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    @Suppress("OPT_IN_USAGE")
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    fun peek(uri: String): FloatArray? = cache[uri]

    fun request(uri: String) {
        if (cache.containsKey(uri) || !inFlight.add(uri)) return
        scope.launch(dispatcher) {
            try {
                val peaks = extract(uri)
                if (peaks != null) {
                    cache[uri] = peaks
                    revision.value++
                }
            } finally {
                inFlight.remove(uri)
            }
        }
    }

    private fun extract(uri: String): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return null

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else return null
            if (durationUs <= 0) return null

            val bucketCount = (durationUs / 1_000_000.0 * 50).toInt().coerceIn(64, 20_000)
            val peaks = FloatArray(bucketCount)

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    // Non-16-bit PCM output (rare float decoders): bail gracefully,
                    // the timeline falls back to a plain bar.
                    val encoding = codec.outputFormat.let {
                        if (it.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            it.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT) return null

                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val shorts = buf.asShortBuffer()
                    val bucket = ((info.presentationTimeUs.toDouble() / durationUs) * bucketCount)
                        .toInt().coerceIn(0, bucketCount - 1)
                    // Stride so a buffer costs ~128 reads regardless of size.
                    val stride = (shorts.remaining() / 128).coerceAtLeast(1)
                    var i = 0
                    var peak = peaks[bucket]
                    while (i < shorts.remaining()) {
                        val v = abs(shorts.get(i).toInt()) / 32767f
                        if (v > peak) peak = v
                        i += stride
                    }
                    peaks[bucket] = peak
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            // Fill decode gaps and normalize against the loudest bucket.
            var max = 0f
            for (p in peaks) if (p > max) max = p
            if (max > 0f) {
                var last = 0f
                for (i in peaks.indices) {
                    if (peaks[i] == 0f) peaks[i] = last * 0.9f else last = peaks[i]
                    peaks[i] = peaks[i] / max
                }
            }
            return peaks
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }
}
