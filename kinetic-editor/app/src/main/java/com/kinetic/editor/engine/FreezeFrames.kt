package com.kinetic.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.TimelineState
import java.io.File
import java.io.FileOutputStream

/**
 * The held frame of every freeze on the main track, pulled out of its source
 * as a PNG in the cache, for [CompositionMapper] to build image items from.
 *
 * Runs on an IO thread before the export starts — a software decode of a 4K
 * frame is a few hundred milliseconds, which is not for the main thread the
 * Transformer is started on. Best effort: a clip whose frame cannot be read
 * is left out, and the mapper plays its video item very slowly instead.
 */
object FreezeFrames {

    fun extract(context: Context, state: TimelineState): Map<ClipId, Uri> {
        val freezes = state.mainTrack.clips.filter { it.freezeMs > 0L }
        if (freezes.isEmpty()) return emptyMap()
        val dir = File(context.cacheDir, "freeze")
        // Frames from an earlier export are worthless once it is done.
        dir.deleteRecursively()
        if (!dir.mkdirs()) return emptyMap()

        val out = HashMap<ClipId, Uri>()
        for (clip in freezes) {
            val bitmap = runCatching { frameAt(context, clip) }.getOrNull() ?: continue
            val file = File(dir, "${clip.id.value}.png")
            val written = runCatching {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.getOrDefault(false)
            bitmap.recycle()
            if (written) out[clip.id] = Uri.fromFile(file)
        }
        return out
    }

    private fun frameAt(context: Context, clip: ClipModel): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.media.uri))
            // The frame the decoder shows at the trim, not the keyframe before
            // it; returned in display orientation, as the decoder's frames are.
            return retriever.getFrameAtTime(
                clip.trimInMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            )
        } finally {
            retriever.release()
        }
    }
}
