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
 *    the per-instance lock, which eviction also takes so a retriever is never
 *    released under a decode in progress on another lane).
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

    /** Access-ordered: the entry at the head is the least recently used. */
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>(
        /* initialCapacity= */ 8, /* loadFactor= */ 0.75f, /* accessOrder= */ true,
    )

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
        val retriever = retrieverFor(key.uri) ?: return null
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
            // Includes a retriever evicted between lookup and use: the slot stays
            // empty and the canvas simply asks again on its next draw.
            null
        }
    }

    /**
     * Opens (or reuses) the retriever for [uri]; null when the source cannot be
     * opened — a deleted file or revoked grant must not take the decode lane down.
     */
    private fun retrieverFor(uri: String): MediaMetadataRetriever? {
        var evicted: MediaMetadataRetriever? = null
        val retriever = synchronized(retrievers) {
            retrievers[uri]?.let { return it }
            val created = MediaMetadataRetriever()
            try {
                created.setDataSource(context, Uri.parse(uri))
            } catch (_: Exception) {
                created.release()
                return null
            }
            retrievers[uri] = created
            if (retrievers.size > MAX_OPEN) {
                val eldest = retrievers.entries.first()
                retrievers.remove(eldest.key)
                evicted = eldest.value
            }
            created
        }
        // Outside the map lock, under the instance lock: waits for a decode that
        // may still be running on the evicted retriever instead of pulling it away.
        evicted?.let { synchronized(it) { it.release() } }
        return retriever
    }

    fun release() {
        val open = synchronized(retrievers) {
            retrievers.values.toList().also { retrievers.clear() }
        }
        for (r in open) synchronized(r) { r.release() }
        cache.evictAll()
    }

    private companion object {
        const val MAX_OPEN = 4
    }
}
