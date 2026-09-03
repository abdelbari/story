package com.kinetic.editor.ui

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinetic.editor.audio.VoiceRecorder
import com.kinetic.editor.core.model.MediaProbe
import com.kinetic.editor.core.model.MediaRef
import com.kinetic.editor.core.model.PipSpec
import com.kinetic.editor.core.model.ProjectCodec
import com.kinetic.editor.core.model.TimelineState
import com.kinetic.editor.core.model.StickerSpec
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.audioStructureHash
import com.kinetic.editor.core.model.overlayStructureHash
import com.kinetic.editor.core.model.videoStructureHash
import com.kinetic.editor.core.mvi.EditorIntent
import com.kinetic.editor.core.mvi.EditorStore
import com.kinetic.editor.engine.ExportSpec
import com.kinetic.editor.engine.ExportWorker
import com.kinetic.editor.engine.PreviewEngine
import com.kinetic.editor.engine.ThumbnailEngine
import com.kinetic.editor.engine.WaveformEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
@OptIn(FlowPreview::class)
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val thumbnails = ThumbnailEngine(app, viewModelScope)
    val waveforms = WaveformEngine(app, viewModelScope)
    val preview = PreviewEngine(app, viewModelScope)
    val recorder = VoiceRecorder(viewModelScope)

    val store = EditorStore(viewModelScope) { prev, next -> route(prev, next) }

    private val projectFile = File(app.filesDir, "project.json")

    /**
     * One transient line for the user: an import that could not be read, a
     * microphone that would not open. Nothing here is an error of the document,
     * so it lives beside the store rather than in it.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    init {
        // Restore the last session, then autosave every commit (debounced so a
        // slider drag writes once, not sixty times). The read is off the main
        // thread; the restore arrives as an ordinary intent when it completes.
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { ProjectCodec.load(projectFile) }
            if (restored != null) store.dispatch(EditorIntent.Replace(restored))
        }
        viewModelScope.launch {
            store.timeline
                .drop(1)
                .debounce(AUTOSAVE_DEBOUNCE_MS)
                .collect { state ->
                    withContext(Dispatchers.IO) { ProjectCodec.save(projectFile, state) }
                }
        }
    }

    private fun route(prev: TimelineState, next: TimelineState) {
        // Read the position BEFORE updateCosmetics: it swaps in segments built
        // from `next`, and mapping the old player position through new segments
        // would land the preserved playhead on the wrong frame.
        val keepTimelineMs = preview.timelinePositionMs()
        preview.updateCosmetics(next)
        if (prev.videoStructureHash() != next.videoStructureHash()) {
            preview.setTimeline(next, keepTimelineMs = keepTimelineMs)
        }
        if (prev.audioStructureHash() != next.audioStructureHash() ||
            prev.overlayStructureHash() != next.overlayStructureHash()
        ) {
            preview.rescheduleSlaves(next)
        }
    }

    /* ------------------------------- imports ------------------------------- */

    fun addMedia(uri: Uri) {
        viewModelScope.launch {
            persistReadAccess(uri)
            val ref = MediaProbe.probe(getApplication(), uri)
            val state = store.timeline.value
            when {
                ref.durationMs <= 0 -> _notice.value = "Couldn't read that file"
                ref.hasVideo -> store.dispatch(EditorIntent.AddClip(trackId = state.mainTrack.id, media = ref))
                ref.hasAudio -> addToAudioTrack(ref, atMs = 0L)
                else -> _notice.value = "That file has no video or audio"
            }
        }
    }

    /** Adds a video to the picture-in-picture lane at the playhead. */
    fun addPictureInPicture(uri: Uri, atMs: Long) {
        viewModelScope.launch {
            persistReadAccess(uri)
            val ref = MediaProbe.probe(getApplication(), uri)
            if (!ref.hasVideo || ref.durationMs <= 0) {
                _notice.value = "Picture-in-picture needs a readable video"
                return@launch
            }
            val track = store.timeline.value.tracks.first { it.type == TrackType.VIDEO_OVERLAY }
            store.dispatch(
                EditorIntent.AddClip(
                    trackId = track.id,
                    media = ref,
                    startMs = atMs,
                    pip = PipSpec(),
                ),
            )
        }
    }

    fun addMusic(uri: Uri, atMs: Long) {
        viewModelScope.launch {
            persistReadAccess(uri)
            val ref = MediaProbe.probe(getApplication(), uri)
            if (ref.hasAudio && ref.durationMs > 0) {
                addToAudioTrack(ref, atMs)
            } else {
                _notice.value = "That file has no audio"
            }
        }
    }

    /**
     * A picker grants read access only to this process instance. Because projects
     * are persisted and reopened after process death — and the export worker reads
     * the same URIs from a background process — that grant has to be made durable
     * or a restored project cannot open its own media.
     */
    private fun persistReadAccess(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun addToAudioTrack(ref: MediaRef, atMs: Long) {
        val audioTrack = store.timeline.value.tracks.first { it.type == TrackType.AUDIO }
        store.dispatch(EditorIntent.AddClip(trackId = audioTrack.id, media = ref, startMs = atMs))
    }

    /**
     * Text is a first-class clip on the TEXT track: a synthetic MediaRef gives it
     * a duration so the same trim/move/split machinery applies, while preview
     * draws it as a Compose layer and export renders it as an OverlayEffect.
     */
    fun addText(atMs: Long, text: String = "Your text here") {
        val track = store.timeline.value.tracks.first { it.type == TrackType.TEXT }
        store.dispatch(
            EditorIntent.AddClip(
                trackId = track.id,
                media = MediaRef(
                    uri = SYNTHETIC_TEXT_URI,
                    durationMs = DEFAULT_OVERLAY_DURATION_MS,
                    hasVideo = false,
                    hasAudio = false,
                    fps = 0f,
                ),
                startMs = atMs,
                trimOutMs = DEFAULT_OVERLAY_DURATION_MS,
                text = TextSpec(text = text),
            ),
        )
    }

    /**
     * Stickers are clips too, on their own lane — same synthetic-media trick as
     * text, so trimming and moving a sticker needs no special cases anywhere.
     */
    fun addSticker(atMs: Long, assetPath: String) {
        val track = store.timeline.value.tracks.first { it.type == TrackType.STICKER }
        store.dispatch(
            EditorIntent.AddClip(
                trackId = track.id,
                media = MediaRef(
                    uri = SYNTHETIC_STICKER_URI,
                    durationMs = DEFAULT_OVERLAY_DURATION_MS,
                    hasVideo = false,
                    hasAudio = false,
                    fps = 0f,
                ),
                startMs = atMs,
                trimOutMs = DEFAULT_OVERLAY_DURATION_MS,
                sticker = StickerSpec(assetPath = assetPath),
            ),
        )
    }

    /* ------------------------------ voiceover ------------------------------ */

    /**
     * Where on the timeline the take being recorded will land. It belongs here
     * rather than in the screen: the recording outlives any composable, and
     * backgrounding the app has to be able to seal the take without the UI
     * handing the position back.
     */
    private var voiceoverStartMs = 0L

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startVoiceover(atMs: Long) {
        preview.pause()
        voiceoverStartMs = atMs
        val file = File(
            getApplication<Application>().filesDir,
            "voiceover_${System.currentTimeMillis()}.wav",
        )
        if (!recorder.start(file)) _notice.value = "Microphone unavailable"
    }

    fun stopVoiceover() {
        viewModelScope.launch {
            val rec = recorder.stop()
            if (rec == null) {
                _notice.value = "Recording too short to keep"
                return@launch
            }
            addToAudioTrack(
                MediaRef(
                    uri = Uri.fromFile(rec.file).toString(),
                    durationMs = rec.durationMs,
                    hasVideo = false,
                    hasAudio = true,
                    fps = 0f,
                ),
                atMs = voiceoverStartMs,
            )
        }
    }

    /* -------------------------------- export ------------------------------- */

    fun startExport() {
        preview.pause()
        val state = store.timeline.value
        if (state.mainTrack.clips.isEmpty()) {
            // Say so now rather than reporting a failed render a moment later.
            _notice.value = "Add a video before exporting"
            return
        }
        // Enqueueing snapshots the document to disk first; not on the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val queued = ExportWorker.enqueue(
                getApplication(),
                state,
                ExportSpec(width = state.outputWidth, height = state.outputHeight),
            )
            if (!queued) _notice.value = "Couldn't start the export — no space to save the project"
        }
    }

    /* ------------------------------ lifecycle ------------------------------ */

    /**
     * The app is no longer on screen. Three things must happen here, none of
     * which the system does for us:
     *
     *  - playback stops. Otherwise the editor keeps decoding and playing audio
     *    over whatever the user switched to, and holds codecs other apps want.
     *  - an in-progress voiceover is sealed. Capturing from a backgrounded app
     *    has no foreground service behind it, so the platform may hand it
     *    silence; ending the take keeps what was actually recorded.
     *  - the project is written now. Autosave is debounced, and a backgrounded
     *    process can be killed without any further notice.
     */
    fun onEnterBackground() {
        preview.pause()
        if (recorder.isRecording.value) stopVoiceover()
        val state = store.timeline.value
        viewModelScope.launch(Dispatchers.IO) { ProjectCodec.save(projectFile, state) }
    }

    private companion object {
        /** Text clips carry no decodable media; the URI is a marker, never opened. */
        const val SYNTHETIC_TEXT_URI = "kinetic://text"
        const val SYNTHETIC_STICKER_URI = "kinetic://sticker"
        const val DEFAULT_OVERLAY_DURATION_MS = 3_000L
        const val AUTOSAVE_DEBOUNCE_MS = 700L
    }

    override fun onCleared() {
        preview.release()
        thumbnails.release()
    }
}
