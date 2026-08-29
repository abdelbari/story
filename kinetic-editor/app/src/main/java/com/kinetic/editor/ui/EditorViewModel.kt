package com.kinetic.editor.ui

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinetic.editor.audio.VoiceRecorder
import com.kinetic.editor.core.model.MediaProbe
import com.kinetic.editor.core.model.MediaRef
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.audioStructureHash
import com.kinetic.editor.core.model.videoStructureHash
import com.kinetic.editor.core.mvi.EditorIntent
import com.kinetic.editor.core.mvi.EditorStore
import com.kinetic.editor.engine.ExportSpec
import com.kinetic.editor.engine.ExportWorker
import com.kinetic.editor.engine.PreviewEngine
import com.kinetic.editor.engine.ThumbnailEngine
import com.kinetic.editor.engine.WaveformEngine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Composition root for one editing session. Its only real logic is the commit
 * router below — the piece that keeps the UI thread flat: it grades every
 * document change by how much of the media pipeline actually has to move.
 *
 *   cosmetic change  -> volatile FX/segment snapshot swap   (~µs, every commit)
 *   audio structure  -> rebuild slave playlists             (cheap, rare)
 *   video structure  -> rebuild concatenated source         (costly, rare,
 *                        position-preserving)
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val thumbnails = ThumbnailEngine(app, viewModelScope)
    val waveforms = WaveformEngine(app, viewModelScope)
    val preview = PreviewEngine(app, viewModelScope)
    val recorder = VoiceRecorder(viewModelScope)

    val store = EditorStore(viewModelScope) { prev, next -> route(prev, next) }

    private fun route(prev: TimelineState, next: TimelineState) {
        preview.updateCosmetics(next)
        if (prev.videoStructureHash() != next.videoStructureHash()) {
            preview.setTimeline(next, keepTimelineMs = preview.timelinePositionMs())
        }
        if (prev.audioStructureHash() != next.audioStructureHash()) {
            preview.rescheduleAudio(next)
        }
    }

    /* ------------------------------- imports ------------------------------- */

    fun addMedia(uri: Uri) {
        viewModelScope.launch {
            val ref = MediaProbe.probe(getApplication(), uri)
            val state = store.timeline.value
            if (ref.hasVideo) {
                store.dispatch(EditorIntent.AddClip(trackId = state.mainTrack.id, media = ref))
            } else if (ref.hasAudio) {
                addToAudioTrack(ref, atMs = 0L)
            }
        }
    }

    fun addMusic(uri: Uri, atMs: Long) {
        viewModelScope.launch {
            val ref = MediaProbe.probe(getApplication(), uri)
            if (ref.hasAudio) addToAudioTrack(ref, atMs)
        }
    }

    private fun addToAudioTrack(ref: MediaRef, atMs: Long) {
        val audioTrack = store.timeline.value.tracks.first { it.type == TrackType.AUDIO }
        store.dispatch(EditorIntent.AddClip(trackId = audioTrack.id, media = ref, startMs = atMs))
    }

    /* ------------------------------ voiceover ------------------------------ */

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startVoiceover() {
        preview.pause()
        val file = File(
            getApplication<Application>().filesDir,
            "voiceover_${System.currentTimeMillis()}.wav",
        )
        recorder.start(file)
    }

    fun stopVoiceover(atMs: Long) {
        viewModelScope.launch {
            val rec = recorder.stop() ?: return@launch
            addToAudioTrack(
                MediaRef(
                    uri = Uri.fromFile(rec.file).toString(),
                    durationMs = rec.durationMs,
                    hasVideo = false,
                    hasAudio = true,
                    fps = 0f,
                ),
                atMs = atMs,
            )
        }
    }

    /* -------------------------------- export ------------------------------- */

    fun startExport() {
        preview.pause()
        val state = store.timeline.value
        ExportWorker.enqueue(
            getApplication(),
            state,
            ExportSpec(width = state.outputWidth, height = state.outputHeight),
        )
    }

    override fun onCleared() {
        preview.release()
        thumbnails.release()
    }
}
