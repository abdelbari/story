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
import com.kinetic.editor.core.model.ClipMotion
import com.kinetic.editor.core.model.Track
import com.kinetic.editor.core.model.fadeKeyframes
import com.kinetic.editor.core.model.readFades
import com.kinetic.editor.core.model.ColorGradeSpec
import com.kinetic.editor.core.model.LutSpec
import com.kinetic.editor.core.model.TransformSpec
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.kinetic.editor.ui.theme.Chip
import com.kinetic.editor.ui.theme.ChipBox
import com.kinetic.editor.ui.theme.Dim
import com.kinetic.editor.ui.theme.IconAction
import com.kinetic.editor.ui.theme.Ink
import com.kinetic.editor.ui.theme.KineticIcons
import com.kinetic.editor.ui.theme.SectionLabel
import com.kinetic.editor.ui.theme.Type
import com.kinetic.editor.ui.theme.ValueSlider
import com.kinetic.editor.core.model.StickerSpec
import com.kinetic.editor.core.model.PipSpec
import com.kinetic.editor.core.model.ChromaKeySpec

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
            .background(Ink.window)
            // targetSdk 35 + enableEdgeToEdge draws behind the system bars: without
            // this the bottom tool row sits under the navigation bar.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        PreviewSurface(vm.preview, state, viewport, Modifier.fillMaxWidth().weight(1f))

        (playbackError ?: notice)?.let { message ->
            Text(
                message,
                style = Type.control.copy(color = Ink.danger),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ink.dangerFill)
                    .padding(horizontal = Dim.md, vertical = Dim.sm),
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
            Column(Modifier.fillMaxWidth().background(Ink.surface)) {
                // A 2dp rule rather than a Material progress bar: it belongs to
                // the chrome, not on top of it.
                Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.raised)) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(Ink.accent),
                    )
                }
                Text(
                    "Exporting ${(fraction * 100).toInt()}%",
                    style = Type.label,
                    modifier = Modifier.padding(horizontal = Dim.md, vertical = Dim.xs),
                )
            }
        }
        WorkInfo.State.SUCCEEDED -> {
            val name = info.outputData.getString(ExportWorker.KEY_NAME)
            val published = info.outputData.getBoolean(ExportWorker.KEY_PUBLISHED, false)
            if (name != null) {
                Text(
                    if (published) "Saved to Movies/Kinetic · $name"
                    else "Saved $name (app storage only)",
                    style = Type.control.copy(color = Ink.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ink.accentFill)
                        .padding(horizontal = Dim.md, vertical = Dim.sm),
                )
            }
        }
        WorkInfo.State.FAILED -> {
            Text(
                "Export failed: ${info.outputData.getString(ExportWorker.KEY_ERROR) ?: "unknown"}",
                style = Type.control.copy(color = Ink.danger),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ink.dangerFill)
                    .padding(horizontal = Dim.md, vertical = Dim.sm),
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
        Modifier
            .fillMaxWidth()
            .background(Ink.surface)
            .padding(horizontal = Dim.sm, vertical = Dim.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(KineticIcons.Undo, "Undo", canUndo, onUndo)
        TransportIcon(KineticIcons.Redo, "Redo", canRedo, onRedo)
        Spacer(Modifier.weight(1f))
        PlayheadReadout(viewport, durationMs)
        Spacer(Modifier.weight(1f))
        // The one filled control on the screen: play is the verb this app is for.
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Ink.accent)
                .clickable(onClickLabel = "Play or pause", onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) KineticIcons.Pause else KineticIcons.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Ink.window,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TransportIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Dim.touch)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Ink.textMuted else Ink.textFaint,
            modifier = Modifier.size(Dim.icon),
        )
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
    val nowStyle = remember { Type.timecode }
    val totalStyle = remember { Type.timecode.copy(color = Ink.textFaint) }
    val cache = remember { HashMap<String, TextLayoutResult>() }
    androidx.compose.foundation.Canvas(Modifier.width(170.dp).height(20.dp)) {
        // Two layouts, so the elapsed time can carry more weight than the total.
        val now = formatMs(viewport.playheadMs)
        val total = " / " + formatMs(durationMs)
        val nowLayout = cache.getOrPut("n$now") {
            if (cache.size > 256) cache.clear()
            measurer.measure(AnnotatedString(now), nowStyle)
        }
        val totalLayout = cache.getOrPut("t$total") {
            measurer.measure(AnnotatedString(total), totalStyle)
        }
        val width = nowLayout.size.width + totalLayout.size.width
        val left = (size.width - width) / 2f
        val top = (size.height - nowLayout.size.height) / 2f
        drawText(nowLayout, topLeft = Offset(left, top))
        drawText(totalLayout, topLeft = Offset(left + nowLayout.size.width, top))
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

    // The render's progress lives in a foreground-service notification, which
    // Android 13+ hides unless POST_NOTIFICATIONS was granted. The export starts
    // either way.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.startExport() }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dim.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(KineticIcons.Film, "Video") { videoPicker.launch(arrayOf("video/*")) }
        IconAction(KineticIcons.Music, "Music") { musicPicker.launch(arrayOf("audio/*")) }
        IconAction(KineticIcons.Pip, "PiP") { pipPicker.launch(arrayOf("video/*")) }
        IconAction(KineticIcons.TypeT, "Text") { vm.addText(viewport.playheadMs) }
        IconAction(KineticIcons.Sticker, "Sticker") {
            vm.addSticker(viewport.playheadMs, STICKER_ASSETS.first().second)
        }
        IconAction(
            icon = if (recording) KineticIcons.Stop else KineticIcons.Mic,
            label = if (recording) "Stop" else "Record",
            active = recording,
            tint = if (recording) Ink.danger else null,
        ) {
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

        ToolDivider()

        IconAction(KineticIcons.Split, "Split", enabled = hasSelection) {
            vm.store.selection.value?.let { id ->
                vm.store.dispatch(EditorIntent.SplitClip(id, viewport.playheadMs))
            }
        }
        IconAction(KineticIcons.Duplicate, "Copy", enabled = hasSelection) {
            vm.store.selection.value?.let { vm.store.dispatch(EditorIntent.DuplicateClip(it)) }
        }
        IconAction(KineticIcons.Detach, "Detach", enabled = canDetachAudio) {
            vm.store.selection.value?.let { vm.store.dispatch(EditorIntent.DetachAudio(it)) }
        }
        IconAction(
            icon = KineticIcons.Trash,
            label = "Delete",
            enabled = hasSelection,
            tint = if (hasSelection) Ink.danger else null,
        ) {
            vm.store.selection.value?.let { vm.store.dispatch(EditorIntent.RemoveClip(it)) }
        }

        ToolDivider()

        val canvas = state.outputWidth to state.outputHeight
        val preset = CANVAS_PRESETS.indexOfFirst { it.width == canvas.first && it.height == canvas.second }
        IconAction(
            icon = KineticIcons.Frame,
            label = CANVAS_PRESETS.getOrNull(preset)?.label ?: "Canvas",
        ) {
            val next = CANVAS_PRESETS[(preset + 1) % CANVAS_PRESETS.size]
            vm.store.dispatch(EditorIntent.SetCanvas(next.width, next.height))
        }
        IconAction(KineticIcons.Frame, state.canvasFit.label) {
            val order = CanvasFit.entries
            vm.store.dispatch(
                EditorIntent.SetCanvasFit(order[(order.indexOf(state.canvasFit) + 1) % order.size]),
            )
        }
        IconAction(KineticIcons.Export, "Export", tint = Ink.accent) {
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

/** Separates the tool rail into add / edit / output, without a heavy rule. */
@Composable
private fun ToolDivider() {
    Box(
        Modifier
            .padding(horizontal = Dim.xs)
            .width(Dim.hair)
            .height(28.dp)
            .background(Ink.hairline),
    )
}

private class CanvasPreset(val label: String, val width: Int, val height: Int)

/** The four frames short-form video is delivered in; cycled by the canvas button. */
private val CANVAS_PRESETS = listOf(
    CanvasPreset("9:16", 1080, 1920),
    CanvasPreset("16:9", 1920, 1080),
    CanvasPreset("1:1", 1080, 1080),
    CanvasPreset("4:5", 1080, 1350),
)

/**
 * Everything about the selected clip, grouped and bounded.
 *
 * Bounded because it shares the screen with the timeline: it scrolls inside a
 * fixed height rather than pushing the timeline off the bottom, which is what
 * an unbounded column of controls does the moment a clip has many of them.
 */
@Composable
private fun ClipInspector(
    clip: ClipModel,
    track: Track,
    dispatch: (EditorIntent) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink.surface)
            .heightIn(max = 232.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dim.md, vertical = Dim.sm),
        verticalArrangement = Arrangement.spacedBy(Dim.xs),
    ) {
        clip.text?.let { spec -> TextSection(clip, spec, dispatch) }
        clip.sticker?.let { spec -> StickerSection(clip, spec, dispatch) }
        clip.pip?.let { spec -> PipSection(clip, spec, dispatch) }

        // Picture controls only for clips that carry a picture; sound controls
        // only for clips that carry sound. A sticker has neither.
        val hasVideo = clip.media.hasVideo
        val hasAudio = clip.media.hasAudio
        if (hasVideo) LookSection(clip, dispatch)
        if (hasVideo) FrameSection(clip, dispatch)
        if (hasVideo) KeySection(clip, dispatch)
        if (hasAudio) SoundSection(clip, dispatch)
        if (hasVideo || hasAudio) ClipSection(clip, track, dispatch)
    }
}

/** A row of choices behind a label, scrolling sideways when it overflows. */
@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dim.xs),
    ) {
        SectionLabel(label, Modifier.width(54.dp))
        content()
    }
}

@Composable
private fun TextSection(clip: ClipModel, spec: TextSpec, dispatch: (EditorIntent) -> Unit) {
    fun edit(next: TextSpec) = dispatch(EditorIntent.SetText(clip.id, next))

    OutlinedTextField(
        value = spec.text,
        onValueChange = { edit(spec.copy(text = it)) },
        // Multi-line: a caption is often two or three lines, and both the
        // preview measurer and the export's layout wrap on \n already.
        singleLine = false,
        maxLines = 3,
        textStyle = Type.body,
        label = { Text("Text", style = Type.label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = Dim.xs),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Size", spec.textSizePx, 24f..160f, Modifier.weight(1f)) {
            edit(spec.copy(textSizePx = it))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("X", spec.anchorX, -1f..1f, Modifier.weight(1f)) { edit(spec.copy(anchorX = it)) }
        ValueSlider("Y", spec.anchorY, -1f..1f, Modifier.weight(1f)) { edit(spec.copy(anchorY = it)) }
    }
    // Each face is labelled in its own family, so the picker shows what it will
    // give you rather than naming it.
    ChipRow("Face") {
        for (face in TextFont.entries) {
            ChipBox(active = spec.font == face, onClick = { edit(spec.copy(font = face)) }) {
                Text(
                    face.label,
                    style = Type.control.copy(
                        fontFamily = face.composeFamily(),
                        color = if (spec.font == face) Ink.accent else Ink.textMuted,
                    ),
                )
            }
        }
        ChipBox(active = spec.bold, onClick = { edit(spec.copy(bold = !spec.bold)) }) {
            Text(
                "B",
                style = Type.control.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (spec.bold) Ink.accent else Ink.textMuted,
                ),
            )
        }
        ChipBox(active = spec.italic, onClick = { edit(spec.copy(italic = !spec.italic)) }) {
            Text(
                "I",
                style = Type.control.copy(
                    fontStyle = FontStyle.Italic,
                    color = if (spec.italic) Ink.accent else Ink.textMuted,
                ),
            )
        }
    }
    ChipRow("Animate") {
        for (motion in OverlayAnim.entries) {
            Chip(motion.label, spec.anim == motion) { edit(spec.copy(anim = motion)) }
        }
    }
    ChipRow("Colour") {
        for (argb in TEXT_COLORS) {
            val active = spec.argb == argb
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        width = if (active) 2.dp else Dim.hair,
                        color = if (active) Ink.accent else Ink.hairline,
                        shape = CircleShape,
                    )
                    .clickable { edit(spec.copy(argb = argb)) },
            )
        }
    }
}

@Composable
private fun StickerSection(clip: ClipModel, spec: StickerSpec, dispatch: (EditorIntent) -> Unit) {
    fun edit(next: StickerSpec) = dispatch(EditorIntent.SetSticker(clip.id, next))
    ChipRow("Sticker") {
        for ((label, asset) in STICKER_ASSETS) {
            Chip(label, spec.assetPath == asset) { edit(spec.copy(assetPath = asset)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Size", spec.scale, 0.05f..0.8f, Modifier.weight(1f)) { edit(spec.copy(scale = it)) }
        ValueSlider("Turn", spec.rotationDeg, -180f..180f, Modifier.weight(1f)) {
            edit(spec.copy(rotationDeg = it))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("X", spec.anchorX, -1f..1f, Modifier.weight(1f)) { edit(spec.copy(anchorX = it)) }
        ValueSlider("Y", spec.anchorY, -1f..1f, Modifier.weight(1f)) { edit(spec.copy(anchorY = it)) }
    }
    ChipRow("Animate") {
        // TYPE has nothing to reveal on a sticker and reads as a cut.
        for (motion in OverlayAnim.entries.filter { it != OverlayAnim.TYPE }) {
            Chip(motion.label, spec.anim == motion) { edit(spec.copy(anim = motion)) }
        }
    }
}

@Composable
private fun PipSection(clip: ClipModel, spec: PipSpec, dispatch: (EditorIntent) -> Unit) {
    fun edit(next: PipSpec) = dispatch(EditorIntent.SetPip(clip.id, next))
    SectionLabel("Picture in picture")
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Size", spec.scale, 0.1f..0.9f, Modifier.weight(1f)) { edit(spec.copy(scale = it)) }
        ValueSlider("Turn", spec.rotationDeg, -180f..180f, Modifier.weight(1f)) {
            edit(spec.copy(rotationDeg = it))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("X", spec.anchorX, -1f..1f, Modifier.weight(1f)) { edit(spec.copy(anchorX = it)) }
        ValueSlider("Y", spec.anchorY, -1f..1f, Modifier.weight(1f)) { edit(spec.copy(anchorY = it)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Opacity", spec.opacity, 0f..1f, Modifier.weight(1f)) { edit(spec.copy(opacity = it)) }
    }
}

/** Colour: a filter as a starting point, then the sliders that refine it. */
@Composable
private fun LookSection(clip: ClipModel, dispatch: (EditorIntent) -> Unit) {
    ChipRow("Look") {
        for (filter in FILTERS) {
            val active = clip.grade == filter.grade && clip.lut?.assetPath == filter.lut?.assetPath
            Chip(filter.label, active) {
                dispatch(EditorIntent.ApplyFilter(clip.id, filter.grade, filter.lut))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Bright", clip.grade.brightness, -0.5f..0.5f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(brightness = it)))
        }
        ValueSlider("Sat", clip.grade.saturation, 0f..2f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(saturation = it)))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Contrast", clip.grade.contrast, 0.25f..2f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(contrast = it)))
        }
        ValueSlider("Warmth", clip.grade.temperature, -1f..1f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(temperature = it)))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Grain", clip.grade.grain, 0f..1f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(grain = it)))
        }
        ValueSlider("Vignette", clip.grade.vignette, 0f..1f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetGrade(clip.id, clip.grade.copy(vignette = it)))
        }
    }
}

/** Geometry: where the picture sits in the frame, and how it moves. */
@Composable
private fun FrameSection(clip: ClipModel, dispatch: (EditorIntent) -> Unit) {
    val xf = clip.transform
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Zoom", xf.scale, 0.2f..4f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetTransform(clip.id, xf.copy(scale = it)))
        }
        ValueSlider("Turn", xf.rotationDeg, -180f..180f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetTransform(clip.id, xf.copy(rotationDeg = it)))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Pan X", xf.offsetX, -1f..1f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetTransform(clip.id, xf.copy(offsetX = it)))
        }
        ValueSlider("Pan Y", xf.offsetY, -1f..1f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetTransform(clip.id, xf.copy(offsetY = it)))
        }
    }
    ChipRow("Motion") {
        for (move in ClipMotion.entries) {
            Chip(move.label, clip.motion == move) { dispatch(EditorIntent.SetMotion(clip.id, move)) }
        }
        if (!xf.isIdentity) {
            Chip("Reset", false) { dispatch(EditorIntent.SetTransform(clip.id, TransformSpec.NONE)) }
        }
    }
}

/**
 * Chroma key. Only useful where something sits behind the clip, so the copy
 * says so rather than leaving the user to wonder why keying the main track
 * turns it black.
 */
@Composable
private fun KeySection(clip: ClipModel, dispatch: (EditorIntent) -> Unit) {
    val key = clip.chroma
    ChipRow("Key") {
        Chip("Off", key == null) { dispatch(EditorIntent.SetChroma(clip.id, null)) }
        for ((label, argb) in KEY_COLOURS) {
            Chip(label, key?.argb == argb) {
                dispatch(
                    EditorIntent.SetChroma(clip.id, (key ?: ChromaKeySpec()).copy(argb = argb)),
                )
            }
        }
    }
    if (key != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
            ValueSlider("Amount", key.tolerance, 0.01f..1f, Modifier.weight(1f)) {
                dispatch(EditorIntent.SetChroma(clip.id, key.copy(tolerance = it)))
            }
            ValueSlider("Edge", key.softness, 0f..0.5f, Modifier.weight(1f)) {
                dispatch(EditorIntent.SetChroma(clip.id, key.copy(softness = it)))
            }
        }
    }
}

/**
 * Sound. Fades are the 90% case for volume envelopes; the model underneath is a
 * general keyframe list and these two sliders author and read the common shape.
 */
@Composable
private fun SoundSection(clip: ClipModel, dispatch: (EditorIntent) -> Unit) {
    val fades = readFades(clip.volumeKeyframes, clip.durationMs)
    val maxFade = (clip.durationMs / 2).coerceAtLeast(1L).toFloat()
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Volume", clip.volume, 0f..2f, Modifier.weight(1f)) {
            dispatch(EditorIntent.SetVolume(clip.id, it))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dim.sm)) {
        ValueSlider("Fade in", fades.inMs.toFloat(), 0f..maxFade, Modifier.weight(1f)) { v ->
            dispatch(
                EditorIntent.SetVolumeKeyframes(
                    clip.id,
                    fadeKeyframes(clip.durationMs, fades.copy(inMs = v.toLong())).toPersistentList(),
                ),
            )
        }
        ValueSlider("Fade out", fades.outMs.toFloat(), 0f..maxFade, Modifier.weight(1f)) { v ->
            dispatch(
                EditorIntent.SetVolumeKeyframes(
                    clip.id,
                    fadeKeyframes(clip.durationMs, fades.copy(outMs = v.toLong())).toPersistentList(),
                ),
            )
        }
    }
}

/** The clip itself: how fast it plays, whether its track is heard, how it cuts. */
@Composable
private fun ClipSection(clip: ClipModel, track: Track, dispatch: (EditorIntent) -> Unit) {
    ChipRow("Speed") {
        for (speed in floatArrayOf(0.5f, 1f, 2f, 4f)) {
            Chip("${speed}x", clip.speed == speed) {
                dispatch(EditorIntent.SetSpeed(clip.id, speed))
            }
        }
        if (track.type == TrackType.AUDIO || track.type == TrackType.VIDEO_MAIN) {
            ChipBox(
                active = track.muted,
                onClick = { dispatch(EditorIntent.SetTrackMuted(track.id, !track.muted)) },
            ) {
                Text(
                    if (track.muted) "Muted" else "Mute",
                    style = Type.control.copy(color = if (track.muted) Ink.danger else Ink.textMuted),
                )
            }
        }
    }
    // A transition is a cut between neighbours on the main track; nothing else
    // has a cut to sit on.
    if (track.type == TrackType.VIDEO_MAIN) {
        val current = clip.transitionOut?.type ?: TransitionType.NONE
        ChipRow("Cut") {
            for (type in TransitionType.entries) {
                Chip(type.name.lowercase().replace('_', ' '), current == type) {
                    dispatch(
                        EditorIntent.SetTransition(
                            clip.id,
                            type.takeIf { it != TransitionType.NONE }?.let { TransitionSpec(it, 500L) },
                        ),
                    )
                }
            }
        }
    }
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
    Filter(
        "Fade",
        ColorGradeSpec(brightness = 0.08f, contrast = 0.78f, saturation = 0.82f, vignette = 0.18f),
    ),
    Filter("Mono", ColorGradeSpec(saturation = 0f)),
    Filter("Noir", ColorGradeSpec(contrast = 1.35f, saturation = 0f, vignette = 0.42f, grain = 0.22f)),
    // The two the app is really for: grain and falloff are what make footage
    // read as film rather than as a phone recording.
    Filter(
        "Super 8",
        ColorGradeSpec(
            brightness = 0.04f, contrast = 1.12f, saturation = 0.92f,
            temperature = 0.28f, grain = 0.45f, vignette = 0.38f,
        ),
    ),
    Filter(
        "Film",
        ColorGradeSpec(contrast = 1.08f, saturation = 0.95f, grain = 0.18f, vignette = 0.22f),
        LutSpec(FILM_LUT_ASSET, 0.85f),
    ),
)

/** What people actually shoot against. */
private val KEY_COLOURS = listOf(
    "Green" to 0xFF00D000,
    "Blue" to 0xFF0040D0,
)

/** Swatches for text: white and black first, then the app's own accents. */
private val TEXT_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFC145, 0xFFFF5C7A,
    0xFF35C4B5, 0xFF6C8CFF, 0xFF9B5CFF, 0xFF3BD16F,
)

private const val NOTICE_MS = 4_000L
private const val FILM_LUT_ASSET = "luts/teal_orange.png"

/** Bundled sticker assets; the "+ Sticker" button cycles through them. */
/** The sticker set, labelled for the inspector's picker. */
private val STICKER_ASSETS = listOf(
    "Star" to "stickers/star.png",
    "Heart" to "stickers/heart.png",
    "Arrow" to "stickers/arrow.png",
    "Sparkle" to "stickers/sparkle.png",
    "Ring" to "stickers/ring.png",
    "Bolt" to "stickers/bolt.png",
    "Bubble" to "stickers/bubble.png",
)

private fun formatMs(ms: Long): String {
    val tenths = (ms % 1000) / 100
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, tenths)
}
