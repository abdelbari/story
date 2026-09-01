package com.kinetic.editor.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kinetic.editor.core.model.ProjectCodec
import com.kinetic.editor.core.model.TimelineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hardware-accelerated MP4 render of the full composition. Decode -> GL effects
 * -> encode stays on hardware surfaces end to end: no frame ever becomes a Java
 * Bitmap, which is what makes 4K export OOM-proof by construction.
 */
class ExportEngine(private val context: Context) {

    sealed interface Event {
        data class Progress(val fraction: Float) : Event
        data class Completed(val outputPath: String, val durationMs: Long) : Event
        data class Failed(val error: Throwable) : Event
    }

    /**
     * Cold flow; collection starts the export, cancellation cancels it.
     * Runs on Main because Transformer requires a Looper thread — the pipeline
     * itself works on its own internal threads, the Looper only carries callbacks.
     */
    fun export(state: TimelineState, spec: ExportSpec, outputFile: File): Flow<Event> =
        callbackFlow {
            var finished = false
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(spec.videoMimeType)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(spec.videoBitrate)
                                .build(),
                        )
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true
                        trySend(Event.Completed(outputFile.absolutePath, exportResult.durationMs))
                        close()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        finished = true
                        trySend(Event.Failed(exportException))
                        close()
                    }
                })
                .build()

            val composition = CompositionMapper.build(context, state, spec)
            transformer.start(composition, outputFile.absolutePath)

            val poller = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        trySend(Event.Progress(holder.progress / 100f))
                    }
                    delay(250)
                }
            }

            awaitClose {
                poller.cancel()
                if (!finished) transformer.cancel()
            }
        }.flowOn(Dispatchers.Main)
}

/* ------------------------------ background job ----------------------------- */

/**
 * The worker is handed a FILE, not an object: WorkManager can run (or retry) it
 * after the editor process is gone, which a static field would not survive.
 */
class ExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val projectPath = inputData.getString(KEY_PROJECT) ?: return Result.failure()
        val state = ProjectCodec.load(File(projectPath))
            ?: return Result.failure(workDataOf(KEY_ERROR to "Project file missing or corrupt"))
        if (state.mainTrack.clips.isEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR to "Nothing to export"))
        }
        val spec = ExportSpec(
            width = inputData.getInt(KEY_WIDTH, state.outputWidth),
            height = inputData.getInt(KEY_HEIGHT, state.outputHeight),
        )
        setForeground(foregroundInfo(0))

        val dir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: applicationContext.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(dir, "kinetic_$stamp.mp4")

        var result: Result = Result.failure()
        // Collect on Main: Transformer's contract, see ExportEngine.export.
        withContext(Dispatchers.Main) {
            ExportEngine(applicationContext)
                .export(state, spec, outputFile)
                .catch { result = Result.failure(workDataOf(KEY_ERROR to it.message)) }
                .collect { event ->
                    when (event) {
                        is ExportEngine.Event.Progress -> {
                            setProgress(workDataOf(KEY_PROGRESS to event.fraction))
                            notifyProgress((event.fraction * 100).toInt())
                        }
                        is ExportEngine.Event.Completed -> {
                            // Publishing copies the whole file, so it runs on IO —
                            // and it consumes the sandbox copy, which is why the
                            // reported location is the published URI when it works.
                            val file = File(event.outputPath)
                            val name = file.name
                            val published = withContext(Dispatchers.IO) {
                                MediaStorePublisher.publish(applicationContext, file)
                            }
                            result = Result.success(
                                workDataOf(
                                    KEY_NAME to name,
                                    KEY_OUTPUT to (published?.toString() ?: event.outputPath),
                                    KEY_PUBLISHED to (published != null),
                                ),
                            )
                        }
                        is ExportEngine.Event.Failed ->
                            result = Result.failure(workDataOf(KEY_ERROR to event.error.message))
                    }
                }
        }
        return result
    }

    private fun notifyProgress(percent: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(percent))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; export continues silently.
        }
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val notification = buildNotification(percent)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(percent: Int): Notification {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Exports", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Exporting video")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val WORK_NAME = "kinetic-export"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT = "output"
        const val KEY_ERROR = "error"
        /** File name of the render, for display. */
        const val KEY_NAME = "name"
        /** True when the video reached the shared Movies collection. */
        const val KEY_PUBLISHED = "published"
        private const val KEY_PROJECT = "project"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 42

        /**
         * Snapshots the document to its own file first: the user may keep editing
         * (or leave) while the render runs, and the export must render what was
         * on screen when they pressed the button.
         */
        fun enqueue(context: Context, state: TimelineState, spec: ExportSpec) {
            val snapshot = File(context.filesDir, "export_project.json")
            ProjectCodec.save(snapshot, state)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ExportWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_PROJECT to snapshot.absolutePath,
                            KEY_WIDTH to spec.width,
                            KEY_HEIGHT to spec.height,
                        ),
                    )
                    .build(),
            )
        }
    }
}
