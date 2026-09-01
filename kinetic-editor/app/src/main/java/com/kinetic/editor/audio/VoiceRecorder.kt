package com.kinetic.editor.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Voiceover capture: 44.1kHz mono PCM16 streamed straight to a WAV file (header
 * back-patched on stop), with a live peak-level flow for the record button's
 * meter. WAV because the result goes directly onto an AUDIO track — MediaCodec
 * AAC encoding here would only add latency to a file the export pipeline
 * re-encodes anyway.
 */
class VoiceRecorder(private val scope: CoroutineScope) {

    data class Recording(val file: File, val durationMs: Long)

    private val _peak = MutableStateFlow(0f)
    val peak: StateFlow<Float> = _peak.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var job: Job? = null
    private var outFile: File? = null
    private var bytesWritten = 0L

    /**
     * Starts capturing into [file]. Returns false, with nothing started, when
     * the microphone cannot be opened — no such device, or another app holds it.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(file: File): Boolean {
        if (_isRecording.value) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return false // ERROR / ERROR_BAD_VALUE: format unsupported here
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // best noise handling for voice
                SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 2,
            )
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        // A busy microphone does not throw: the record simply never starts.
        val started = runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            recorder.release()
            return false
        }

        outFile = file
        bytesWritten = 0
        _isRecording.value = true

        job = scope.launch(Dispatchers.IO) {
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(ByteArray(44)) // header placeholder, patched on stop
                    val buffer = ByteArray(minBuf)
                    try {
                        while (isActive) {
                            val n = recorder.read(buffer, 0, buffer.size)
                            if (n < 0) break // the stream died (mic taken away); keep what we have
                            if (n == 0) continue
                            raf.write(buffer, 0, n)
                            bytesWritten += n
                            var peak = 0
                            var i = 0
                            while (i + 1 < n) {
                                val s = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                val v = abs(s.toShort().toInt())
                                if (v > peak) peak = v
                                i += 2
                            }
                            _peak.value = peak / 32767f
                        }
                    } finally {
                        patchWavHeader(raf, bytesWritten)
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                _isRecording.value = false
            }
        }
        return true
    }

    /** Stops and returns the finished WAV, ready to add as an AUDIO clip; null if too short to keep. */
    suspend fun stop(): Recording? {
        val j = job ?: return null
        job = null
        j.cancel()
        j.join() // header patch happens in the reader's finally block
        _isRecording.value = false
        _peak.value = 0f
        val file = outFile ?: return null
        outFile = null
        val durationMs = bytesWritten * 1000L / (SAMPLE_RATE * 2L)
        return if (durationMs > 200) {
            Recording(file, durationMs)
        } else {
            file.delete()
            null
        }
    }

    private fun patchWavHeader(raf: RandomAccessFile, dataBytes: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * 2
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)               // PCM chunk size
        header.putShort(1)              // PCM format
        header.putShort(1)              // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(2)              // block align
        header.putShort(16)             // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
