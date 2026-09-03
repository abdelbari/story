package com.kinetic.editor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kinetic.editor.core.model.CanvasFit
import com.kinetic.editor.core.model.ClipModel
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.fadeKeyframes
import com.kinetic.editor.core.model.readFades
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.LutSpec
import com.kinetic.editor.core.model.TransitionSpec
import com.kinetic.editor.core.model.TrackType
import com.kinetic.editor.core.model.OverlayAnim
import com.kinetic.editor.core.model.TextFont
import com.kinetic.editor.core.model.TextSpec
import com.kinetic.editor.core.model.TransitionType
import com.kinetic.editor.core.mvi.EditorIntent
import kotlinx.collections.immutable.toPersistentList
import com.kinetic.editor.engine.ExportWorker
import com.kinetic.editor.ui.preview.PreviewSurface
import com.kinetic.editor.ui.timeline.Timeline
import com.kinetic.editor.ui.timeline.TimelineGeometry
import com.kinetic.editor.ui.timeline.TimelineGestureCallbacks
import com.kinetic.editor.ui.timeline.TimelineViewportState
import com.kinetic.editor.ui.timeline.TrimGhost
import com.kinetic.editor.ui.timeline.DragGhost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Screen-level wiring. The two LaunchedEffects below are the ENTIRE
 * playhead-sync story, in both directions, with feedback loops broken by
 * ownership (isUserInteracting):
 *
 *   player -> viewport : per-frame while playing, skipped while the user drags
 *   viewport -> player : snapshotFlow of playheadMs while the user owns it,
 *                        conflated into frame-exact seeks by PreviewEngine
 */
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val state by vm.store.timeline.collectAsState()
    val selection by vm.store.selection.collectAsState()
    val isPlaying by vm.preview.isPlaying.collectAsState()
    val durationMs by vm.preview.timelineDurationMs.collectAsState()
    val canUndo by vm.store.canUndo.collectAsState()
    val canRedo by vm.store.canRedo.collectAsState()
    val recording by vm.recorder.isRecording.collectAsState()
    val playbackError by vm.preview.error.collectAsState()
    val notice by vm.notice.collectAsState()
    // A notice clears itself; a playback error stays until the pipeline is rebuilt.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            vm.clearNotice()
        }
    }

    val viewport = remember { TimelineViewportState() }
    val haptics = LocalHapticFeedback.current

    // The engine and document survive configuration changes but the viewport does
    // not; re-seed it from the player so a rotation keeps the playhead.
    LaunchedEffect(Unit) { viewport.scrollToMs(vm.preview.timelinePositionMs()) }

    // Player is the position master while playing.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            withFrameNanos { }
            viewport.syncFromPlayer(vm.preview.timelinePositionMs())
        }
    }

    // The finger (or its fling) is the position master while interacting.
    LaunchedEffect(Unit) {
        snapshotFlow { if (viewport.isUserInteracting) viewport.playheadMs else -1L }
            .distinctUntilChanged()
            .collect { if (it >= 0L) vm.preview.scrubTo(it) }
    }

    val callbacks = remember(vm, haptics) {
        object : TimelineGestureCallbacks {
            override fun onScrubStart() = vm.preview.setScrubbing(true)
            override fun onScrubEnd() = vm.preview.setScrubbing(false)

            override fun onEditStart() {
                vm.preview.pause()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }

            override fun onEditEnd() = Unit

            override fun onTap(hit: TimelineGeometry.Hit) {
                when (hit) {
                    is TimelineGeometry.Hit.Body -> vm.store.select(hit.clipId)
                    else -> vm.store.select(null)
                }
            }

            override fun onTrimCommit(ghost: TrimGhost) {
                vm.store.dispatch(
                    EditorIntent.TrimClip(ghost.clipId, ghost.trimInMs, ghost.trimOutMs, ghost.startMs),
                )
            }

            override fun onMoveCommit(ghost: DragGhost) {
                val tracks = vm.store.timeline.value.tracks
                val target = tracks.getOrNull(ghost.ghostTrackIndex) ?: return
                vm.store.dispatch(
                    EditorIntent.MoveClip(
                        clipId = ghost.clipId,
                        toTrackId = target.id,
                        toIndex = ghost.insertIndex.takeIf { it >= 0 },
                        toStartMs = ghost.ghostStartMs.takeIf { ghost.insertIndex < 0 },
                    ),
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F13))
            // targetSdk 35 + enableEdgeToEdge draws behind the system bars: without
            // this the bottom tool row sits under the navigation bar.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        PreviewSurface(vm.preview, state, viewport, Modifier.fillMaxWidth().weight(1f))

        (playbackError ?: notice)?.let { message ->
            Text(
                message,
                color = Color(0xFFFF5C7A),
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FF5C7A))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        ExportStatus()

        TransportBar(
            viewport = viewport,
            durationMs = durationMs,
            isPlaying = isPlaying,
            canUndo = canUndo,
            canRedo = canRedo,
            onTogglePlay = vm.preview::togglePlay,
            onUndo = { vm.store.dispatch(EditorIntent.Undo) },
            onRedo = { vm.store.dispatch(EditorIntent.Redo) },
        )

        Timeline(
            state = state,
            selection = selection,
            viewport = viewport,
            thumbnails = vm.thumbnails,
            waveforms = vm.waveforms,
            callbacks = callbacks,
        )

        val selected = selection?.let { id -> state.findClip(id) }
        if (selected != null) {
            ClipInspector(
                clip = selected.second,
                track = selected.first,
                dispatch = vm.store::dispatch,
            )
        }

        ToolBar(
            vm = vm,
            viewport = viewport,
            recording = recording,
            hasSelection = selected != null,
            canDetachAudio = selected?.let { (track, c) ->
                track.type != TrackType.AUDIO && c.media.hasAudio
            } == true,
        )
    }
}

/* --------------------------------- pieces --------------------------------- */

/**
 * Export status. A render can outlive the UI, so this reads WorkManager rather
 * than any in-memory state — reopening the app mid-export still shows progress,
 * and the finished path is reported instead of the bar simply vanishing.
 */
@Composable
private fun ExportStatus() {
    val context = LocalContext.current
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(ExportWorker.WORK_NAME)
        .collectAsState(initial = emptyList())
    val info = workInfos.firstOrNull() ?: return

    when (info.state) {
        WorkInfo.State.RUNNING -> {
            val fraction = info.progress.getFloat(ExportWorker.KEY_PROGRESS, 0f)
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Exporting ${(fraction * 100).toInt()}%",
                    color = Color(0xFF9A9AA5),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        WorkInfo.State.SUCCEEDED -> {
            val name = info.outputData.getString(ExportWorker.KEY_NAME)
            val published = info.outputData.getBoolean(ExportWorker.KEY_PUBLISHED, false)
            if (name != null) {
                Text(
                    if (published) "Saved to Movies/Kinetic — $name"
                    else "Saved $name (app storage only)",
                    color = Color(0xFF35C4B5),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        WorkInfo.State.FAILED -> {
            Text(
                "Export failed: ${info.outputData.getString(ExportWorker.KEY_ERROR) ?: "unknown"}",
                color = Color(0xFFFF5C7A),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        else -> Unit
    }
}

@Composable
private fun TransportBar(
    viewport: TimelineViewportState,
    durationMs: Long,
    isPlaying: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onTogglePlay: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onUndo, enabled = canUndo) { Text("↺") }
        TextButton(onClick = onRedo, enabled = canRedo) { Text("↻") }
        Spacer(Modifier.weight(1f))
        PlayheadReadout(viewport, durationMs)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onTogglePlay) {
            Text(if (isPlaying) "❚❚" else "▶", fontSize = 18.sp, color = Color.White)
        }
    }
}

/**
 * Position/duration counter rendered entirely in the DRAW phase: the hot
 * playhead value is read inside the Canvas lambda, so a 120Hz scrub or playback
 * repaints this node without ever invalidating a composition scope.
 */
@Composable
private fun PlayheadReadout(viewport: TimelineViewportState, durationMs: Long) {
    val measurer = rememberTextMeasurer()
    val style = remember {
        TextStyle(color = Color(0xFFEDEDF2), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
    val cache = remember { HashMap<String, TextLayoutResult>() }
    androidx.compose.foundation.Canvas(Modifier.width(160.dp).height(20.dp)) {
        val text = "${formatMs(viewport.playheadMs)} / ${formatMs(durationMs)}"
        val layout = cache.getOrPut(text) {
            if (cache.size > 256) cache.clear()
            measurer.measure(AnnotatedString(text), style)
        }
        drawText(
            layout,
            topLeft = Offset(
                (size.width - layout.size.width) / 2f,
                (size.height - layout.size.height) / 2f,
            ),
        )
    }
}

@Composable
private fun ToolBar(
    vm: EditorViewModel,
    viewport: TimelineViewportState,
    recording: Boolean,
    hasSelection: Boolean,
    canDetachAudio: Boolean,
) {
    val context = LocalContext.current
    val state by vm.store.timeline.collectAsState()

    // OpenDocument (not PickVisualMedia/GetContent): only its grants can be made
    // persistable, which a saved project needs to reopen its media later.
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::addMedia) }

    val musicPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.addMusic(it, viewport.playheadMs) } }

    val pipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.addPictureInPicture(it, viewport.playheadMs) } }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startVoiceover(viewport.playheadMs)
    }

    // The render runs as a foreground job whose progress lives in the shade;
    // from API 33 that shade needs permission. The export starts either way.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.startExport() }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton("+ Video") { videoPicker.launch(arrayOf("video/*")) }
        ToolButton("+ Music") { musicPicker.launch(arrayOf("audio/*")) }
        ToolButton("+ PiP") { pipPicker.launch(arrayOf("video/*")) }
        ToolButton("+ Text") { vm.addText(viewport.playheadMs) }
        var stickerIndex by remember { mutableIntStateOf(0) }
        ToolButton("+ Sticker") {
            vm.addSticker(viewport.playheadMs, STICKER_ASSETS[stickerIndex % STICKER_ASSETS.size])
            stickerIndex++
        }
        ToolButton(if (recording) "■ Stop" else "● Rec") {
            if (recording) {
                vm.stopVoiceover()
            } else if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                vm.startVoiceover(viewport.playheadMs)
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        ToolButton("Split", enabled = hasSelection) {
            vm.store.selection.value?.let { id ->
                vm.store.dispatch(EditorIntent.SplitClip(id, viewport.playheadMs))
            }
        }
        ToolButton("Detach", enabled = canDetachAudio) {
            vm.store.selection.value?.let { id ->
                vm.store.dispatch(EditorIntent.DetachAudio(id))
            }
        }
        ToolButton("Copy", enabled = hasSelection) {
            vm.store.selection.value?.let { id ->
                vm.store.dispatch(EditorIntent.DuplicateClip(id))
            }
        }
        ToolButton("Delete", enabled = hasSelection) {
            vm.store.selection.value?.let { id ->
                vm.store.dispatch(EditorIntent.RemoveClip(id))
            }
        }
        val canvas = state.outputWidth to state.outputHeight
        val preset = CANVAS_PRESETS.indexOfFirst { it.width == canvas.first && it.height == canvas.second }
        ToolButton("Canvas ${CANVAS_PRESETS.getOrNull(preset)?.label ?: "${canvas.first}×${canvas.second}"}") {
            val next = CANVAS_PRESETS[(preset + 1) % CANVAS_PRESETS.size]
            vm.store.dispatch(EditorIntent.SetCanvas(next.width, next.height))
        }
        ToolButton(state.canvasFit.label) {
            val order = CanvasFit.entries
            val next = order[(order.indexOf(state.canvasFit) + 1) % order.size]
            vm.store.dispatch(EditorIntent.SetCanvasFit(next))
        }
        ToolButton("Export") {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                vm.startExport()
            }
        }
    }
}

private class CanvasPreset(val label: String, val width: Int, val height: Int)

/** The four frames short-form video is delivered in; cycled by the Canvas button. */
private val CANVAS_PRESETS = listOf(
    CanvasPreset("9:16", 1080, 1920),
    CanvasPreset("16:9", 1920, 1080),
    CanvasPreset("1:1", 1080, 1080),
    CanvasPreset("4:5", 1080, 1350),
)

@Composable
private fun ToolButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, fontSize = 13.sp, color = if (enabled) Color(0xFFEDEDF2) else Color(0x55EDEDF2))
    }
}

/** Effect controls for the selected clip; every slider dispatches a coalesced intent. */
@Composable
private fun ClipInspector(
    clip: ClipModel,
    track: Track,
    dispatch: (EditorIntent) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // A text overlay whose words cannot be changed is not a text tool.
        clip.text?.let { spec ->
            fun edit(next: TextSpec) = dispatch(EditorIntent.SetText(clip.id, next))

            OutlinedTextField(
                value = spec.text,
                onValueChange = { edit(spec.copy(text = it)) },
                // Multi-line: a caption is often two or three lines, and both the
                // preview measurer and the export's layout wrap on \n already.
                singleLine = false,
                maxLines = 3,
                textStyle = TextStyle(color = Color(0xFFEDEDF2), fontSize = 14.sp),
                label = { Text("Text", fontSize = 11.sp, color = Color(0xFF9A9AA5)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("Size", spec.textSizePx, 24f..160f) {
                    edit(spec.copy(textSizePx = it))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("X", spec.anchorX, -1f..1f) { edit(spec.copy(anchorX = it)) }
                InspectorSlider("Y", spec.anchorY, -1f..1f) { edit(spec.copy(anchorY = it)) }
            }
            // Each face is labelled in its own family, so the picker shows what
            // it will give you rather than naming it.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (face in TextFont.entries) {
                    val active = spec.font == face
                    TextButton(onClick = { edit(spec.copy(font = face)) }) {
                        Text(
                            face.label,
                            fontFamily = face.composeFamily(),
                            fontSize = 14.sp,
                            color = if (active) Color.White else Color(0xFF9A9AA5),
                        )
                    }
                }
                TextButton(onClick = { edit(spec.copy(bold = !spec.bold)) }) {
                    Text(
                        "B",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (spec.bold) Color(0xFF35C4B5) else Color(0xFF9A9AA5),
                    )
                }
                TextButton(onClick = { edit(spec.copy(italic = !spec.italic)) }) {
                    Text(
                        "I",
                        fontStyle = FontStyle.Italic,
                        fontSize = 14.sp,
                        color = if (spec.italic) Color(0xFF35C4B5) else Color(0xFF9A9AA5),
                    )
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Animate", color = Color(0xFF9A9AA5), fontSize = 11.sp)
                for (motion in OverlayAnim.entries) {
                    val active = spec.anim == motion
                    TextButton(onClick = { edit(spec.copy(anim = motion)) }) {
                        Text(
                            motion.label,
                            fontSize = 12.sp,
                            color = if (active) Color(0xFF35C4B5) else Color(0xFF9A9AA5),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Colour", color = Color(0xFF9A9AA5), fontSize = 11.sp)
                for (argb in TEXT_COLORS) {
                    val active = spec.argb == argb
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(Color(argb), CircleShape)
                            .border(
                                width = if (active) 2.dp else 1.dp,
                                color = if (active) Color.White else Color(0x44FFFFFF),
                                shape = CircleShape,
                            )
                            .clickable { edit(spec.copy(argb = argb)) },
                    )
                }
            }
        }

        // Sticker placement: the same numbers the export overlay consumes.
        clip.sticker?.let { spec ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("Size", spec.scale, 0.05f..0.8f) {
                    dispatch(EditorIntent.SetSticker(clip.id, spec.copy(scale = it)))
                }
                InspectorSlider("Rot", spec.rotationDeg, -180f..180f) {
                    dispatch(EditorIntent.SetSticker(clip.id, spec.copy(rotationDeg = it)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("X", spec.anchorX, -1f..1f) {
                    dispatch(EditorIntent.SetSticker(clip.id, spec.copy(anchorX = it)))
                }
                InspectorSlider("Y", spec.anchorY, -1f..1f) {
                    dispatch(EditorIntent.SetSticker(clip.id, spec.copy(anchorY = it)))
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Animate", color = Color(0xFF9A9AA5), fontSize = 11.sp)
                // TYPE has nothing to reveal on a sticker and reads as a cut.
                for (motion in OverlayAnim.entries.filter { it != OverlayAnim.TYPE }) {
                    val active = spec.anim == motion
                    TextButton(onClick = {
                        dispatch(EditorIntent.SetSticker(clip.id, spec.copy(anim = motion)))
                    }) {
                        Text(
                            motion.label,
                            fontSize = 12.sp,
                            color = if (active) Color(0xFF35C4B5) else Color(0xFF9A9AA5),
                        )
                    }
                }
            }
        }

        // PiP placement: the same numbers the export compositor consumes.
        clip.pip?.let { spec ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("PiP size", spec.scale, 0.1f..0.9f) {
                    dispatch(EditorIntent.SetPip(clip.id, spec.copy(scale = it)))
                }
                InspectorSlider("PiP rot", spec.rotationDeg, -180f..180f) {
                    dispatch(EditorIntent.SetPip(clip.id, spec.copy(rotationDeg = it)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("PiP X", spec.anchorX, -1f..1f) {
                    dispatch(EditorIntent.SetPip(clip.id, spec.copy(anchorX = it)))
                }
                InspectorSlider("PiP Y", spec.anchorY, -1f..1f) {
                    dispatch(EditorIntent.SetPip(clip.id, spec.copy(anchorY = it)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("Opacity", spec.opacity, 0f..1f) {
                    dispatch(EditorIntent.SetPip(clip.id, spec.copy(opacity = it)))
                }
            }
        }

        // Picture controls only for clips that carry a picture; sound controls
        // only for clips that carry sound. A sticker has neither.
        val hasVideo = clip.media.hasVideo
        val hasAudio = clip.media.hasAudio
        if (hasVideo) {
            // A filter is a starting point, not a mode: it sets the same grade
            // and LUT fields the sliders below edit, so the two never disagree.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Filter", color = Color(0xFF9A9AA5), fontSize = 11.sp)
                for (filter in FILTERS) {
                    val active = clip.grade == filter.grade &&
                        clip.lut?.assetPath == filter.lut?.assetPath
                    TextButton(onClick = {
                        dispatch(EditorIntent.ApplyFilter(clip.id, filter.grade, filter.lut))
                    }) {
                        Text(
                            filter.label,
                            fontSize = 12.sp,
                            color = if (active) Color(0xFF35C4B5) else Color(0xFF9A9AA5),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("Bright", clip.grade.brightness, -0.5f..0.5f) {
                    dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(brightness = it)))
                }
                InspectorSlider("Sat", clip.grade.saturation, 0f..2f) {
                    dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(saturation = it)))
                }
            }
        }
        if (hasVideo || hasAudio) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasVideo) {
                    InspectorSlider("Contrast", clip.grade.contrast, 0.25f..2f) {
                        dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(contrast = it)))
                    }
                }
                if (hasAudio) {
                    InspectorSlider("Vol", clip.volume, 0f..2f) {
                        dispatch(EditorIntent.SetVolume(clip.id, it))
                    }
                }
            }
        }

        // Fades are the 90% case for volume envelopes; the underlying model is a
        // general keyframe list, and these two sliders author/read the common shape.
        if (clip.media.hasAudio) {
            val fades = readFades(clip.volumeKeyframes, clip.durationMs)
            val maxFade = (clip.durationMs / 2).coerceAtLeast(1L).toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorSlider("Fade in", fades.inMs.toFloat(), 0f..maxFade) { v ->
                    dispatch(
                        EditorIntent.SetVolumeKeyframes(
                            clip.id,
                            fadeKeyframes(clip.durationMs, fades.copy(inMs = v.toLong()))
                                .toPersistentList(),
                        ),
                    )
                }
                InspectorSlider("Fade out", fades.outMs.toFloat(), 0f..maxFade) { v ->
                    dispatch(
                        EditorIntent.SetVolumeKeyframes(
                            clip.id,
                            fadeKeyframes(clip.durationMs, fades.copy(outMs = v.toLong()))
                                .toPersistentList(),
                        ),
                    )
                }
            }
        }
        // Text and stickers carry no media to speed up, grade or cut between.
        if (!hasVideo && !hasAudio) return@Column

        // Scrollable: speed presets + effect toggles overflow a 360dp screen.
        // NOTE: no Modifier.weight children in here — weight inside a scrollable
        // row is measured with infinite constraints and crashes.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Speed", color = Color(0xFF9A9AA5), fontSize = 11.sp)
            for (speed in floatArrayOf(0.5f, 1f, 2f, 4f)) {
                val active = clip.speed == speed
                TextButton(onClick = { dispatch(EditorIntent.SetSpeed(clip.id, speed)) }) {
                    Text(
                        "${speed}x", fontSize = 12.sp,
                        color = if (active) Color.White else Color(0xFF9A9AA5),
                    )
                }
            }
            val nextTransition = when (clip.transitionOut?.type) {
                null, TransitionType.NONE -> TransitionType.DIP_TO_BLACK
                TransitionType.DIP_TO_BLACK -> TransitionType.WIPE_LEFT
                TransitionType.WIPE_LEFT -> TransitionType.ZOOM_PUNCH
                TransitionType.ZOOM_PUNCH -> TransitionType.NONE
            }
            if (track.type == TrackType.AUDIO || track.type == TrackType.VIDEO_MAIN) {
                TextButton(onClick = {
                    dispatch(EditorIntent.SetTrackMuted(track.id, !track.muted))
                }) {
                    Text(
                        if (track.muted) "Muted" else "Mute",
                        fontSize = 12.sp,
                        color = if (track.muted) Color(0xFFFF5C7A) else Color(0xFF9A9AA5),
                    )
                }
            }
            // A transition is a cut between neighbours on the main track; nothing
            // else has a cut to sit on.
            if (track.type == TrackType.VIDEO_MAIN) {
                TextButton(onClick = {
                    dispatch(
                        EditorIntent.SetTransition(
                            clip.id,
                            nextTransition.takeIf { it != TransitionType.NONE }
                                ?.let { TransitionSpec(it, 500L) },
                        ),
                    )
                }) {
                    Text(
                        "Trans: ${(clip.transitionOut?.type ?: TransitionType.NONE).name.lowercase()}",
                        fontSize = 12.sp, color = Color(0xFFFFC145),
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.InspectorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Text(label, color = Color(0xFF9A9AA5), fontSize = 11.sp, modifier = Modifier.width(52.dp))
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        modifier = Modifier.weight(1f).padding(end = 8.dp),
    )
}

/** Ships in app/src/main/assets — a 64-cube teal/orange film LUT. */
/**
 * The looks offered as one tap. Each is nothing but preset values of the grade
 * the sliders edit (and, for Film, the bundled LUT), so a filter can always be
 * adjusted afterwards rather than being an opaque mode.
 */
private class Filter(val label: String, val grade: ColorGradeSpec, val lut: LutSpec? = null)

private val FILTERS = listOf(
    Filter("None", ColorGradeSpec.NEUTRAL),
    Filter("Vivid", ColorGradeSpec(contrast = 1.20f, saturation = 1.45f)),
    Filter("Warm", ColorGradeSpec(temperature = 0.35f, saturation = 1.10f)),
    Filter("Cool", ColorGradeSpec(temperature = -0.35f, saturation = 1.05f)),
    Filter("Fade", ColorGradeSpec(brightness = 0.08f, contrast = 0.78f, saturation = 0.82f)),
    Filter("Mono", ColorGradeSpec(saturation = 0f)),
    Filter("Noir", ColorGradeSpec(contrast = 1.35f, saturation = 0f)),
    Filter("Film", ColorGradeSpec(contrast = 1.08f, saturation = 0.95f), LutSpec(FILM_LUT_ASSET, 0.85f)),
)

/** Swatches for text: white and black first, then the app's own accents. */
private val TEXT_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFC145, 0xFFFF5C7A,
    0xFF35C4B5, 0xFF6C8CFF, 0xFF9B5CFF, 0xFF3BD16F,
)

private const val NOTICE_MS = 4_000L
private const val FILM_LUT_ASSET = "luts/teal_orange.png"

/** Bundled sticker assets; the "+ Sticker" button cycles through them. */
private val STICKER_ASSETS = listOf(
    "stickers/star.png",
    "stickers/heart.png",
    "stickers/arrow.png",
)

private fun formatMs(ms: Long): String {
    val tenths = (ms % 1000) / 100
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, tenths)
}
