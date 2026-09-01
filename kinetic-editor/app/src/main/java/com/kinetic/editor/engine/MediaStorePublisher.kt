package com.kinetic.editor.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a finished render into the shared Movies collection.
 *
 * Transformer writes to a path, and the only path an app reliably owns is inside
 * its own sandbox — where the user's gallery and share sheet will never see it.
 * So the render lands in app-private storage first and is then copied into
 * MediaStore, which is what makes the export an actual deliverable rather than a
 * file the user cannot reach.
 *
 * On API 29+ this needs no permission (IS_PENDING marks it incomplete until the
 * copy finishes). Below 29 it requires WRITE_EXTERNAL_STORAGE; if that was not
 * granted the export is still on disk, so publishing fails soft.
 */
object MediaStorePublisher {

    fun publish(context: Context, source: File, displayName: String = source.name): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Kinetic")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            // The sandbox copy has served its purpose once the shared one exists.
            source.delete()
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
