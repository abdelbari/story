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

    suspend fun probe(context: Context, uri: Uri): MediaRef = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val hasVideo = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"

            MediaRef(
                uri = uri.toString(),
                durationMs = durationMs,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                fps = if (hasVideo) extractFps(context, uri) else 0f,
                width = width,
                height = height,
            )
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
                    fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                    break
                }
            }
            fps
        } catch (_: Exception) {
            30f
        } finally {
            extractor.release()
        }
    }
}
