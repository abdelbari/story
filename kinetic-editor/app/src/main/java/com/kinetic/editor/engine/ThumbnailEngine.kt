package com.kinetic.editor.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Filmstrip thumbnail provider tuned for a Canvas-drawn timeline:
 *
 *  - peek() is non-suspending and allocation-free — safe to call from the draw
 *    phase for every visible slot on every frame.
 *  - request() is fire-and-forget; completion bumps [revision], which the canvas
 *    reads in its draw lambda, so new thumbs trigger a REDRAW, not recomposition.
 *  - Cache is byte-budgeted to 1/6 of the heap; keys bucket source time to 1s so
 *    zoom changes re-use frames instead of re-decoding.
 *  - One MediaMetadataRetriever per source, kept open in a tiny LRU (seek-open
 *    cost dominates thumbnail extraction; retrievers are NOT thread-safe, hence
 *    the per-instance lock).
 */
class ThumbnailEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private data class Key(val uri: String, val bucketMs: Long)

    val revision = mutableIntStateOf(0)

    private val cache = object : LruCache<Key, ImageBitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 6).toInt().coerceAtLeast(8 * 1024),
    ) {
        override fun sizeOf(key: Key, value: ImageBitmap): Int =
            value.width * value.height * 4 / 1024
    }

    private val inFlight = Collections.synchronizedSet(HashSet<Key>())

    @Suppress("OPT_IN_USAGE")
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val retrievers = object : LinkedHashMap<String, MediaMetadataRetriever>(
        /* initialCapacity= */ 8, /* loadFactor= */ 0.75f, /* accessOrder= */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>): Boolean {
            if (size > 4) {
                eldest.value.release()
                return true
            }
            return false
        }
    }

    fun peek(uri: String, sourceMs: Long): ImageBitmap? =
        cache.get(Key(uri, bucket(sourceMs)))

    fun request(uri: String, sourceMs: Long) {
        val key = Key(uri, bucket(sourceMs))
        if (cache.get(key) != null || !inFlight.add(key)) return
        scope.launch(decodeDispatcher) {
            try {
                val bmp = decode(key)
                if (bmp != null) {
                    cache.put(key, bmp)
                    revision.value++ // snapshot write is thread-safe; canvas redraws
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun bucket(sourceMs: Long): Long = (sourceMs / 1000L) * 1000L

    private fun decode(key: Key): ImageBitmap? {
        val retriever = synchronized(retrievers) {
            retrievers.getOrPut(key.uri) {
                MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(key.uri)) }
            }
        }
        return try {
            synchronized(retriever) {
                retriever.getScaledFrameAtTime(
                    key.bucketMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, // sync frames only: 10x faster than exact
                    /* dstWidth= */ 256,
                    /* dstHeight= */ 160,
                )
            }?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    fun release() {
        synchronized(retrievers) {
            retrievers.values.forEach { it.release() }
            retrievers.clear()
        }
        cache.evictAll()
    }
}
