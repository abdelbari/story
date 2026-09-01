package com.kinetic.editor.core.model

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Probes duration/dimensions/fps once at import so the editor never touches the file again for metadata. */
object MediaProbe {

    /**
     * Never throws. A file the platform cannot open (revoked grant, corrupt
     * container, a document that is not media at all) comes back with
     * `durationMs == 0`, which every caller treats as "not importable".
     */
    suspend fun probe(context: Context, uri: Uri): MediaRef = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"

            // Store DISPLAY dimensions. A phone clip is usually stored landscape
            // with a 90° rotation flag, and everything downstream — the preview
            // surface, the export compositor — sees the rotated picture.
            if (rotation % 180 != 0) {
                val t = width
                width = height
                height = t
            }

            MediaRef(
                uri = uri.toString(),
                durationMs = durationMs,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                fps = if (hasVideo) extractFps(context, uri) else 0f,
                width = width,
                height = height,
            )
        } catch (_: Exception) {
            // setDataSource throws IllegalArgument/Security/RuntimeException by
            // turns; none of them is worth more than "could not read".
            MediaRef(uri = uri.toString(), durationMs = 0L, hasVideo = false, hasAudio = false, fps = 0f)
        } finally {
            retriever.release()
        }
    }

    /** METADATA_KEY_CAPTURE_FRAMERATE is unreliable; read the track's MediaFormat instead. */
    private fun extractFps(context: Context, uri: Uri): Float {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var fps = 30f
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    // Containers store the rate as either an int or a float.
                    fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .recoverCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
                        .getOrDefault(30f)
                    break
                }
            }
            if (fps > 0f) fps else 30f
        } catch (_: Exception) {
            30f
        } finally {
            extractor.release()
        }
    }
}
