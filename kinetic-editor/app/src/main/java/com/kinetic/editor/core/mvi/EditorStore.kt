package com.kinetic.editor.core.mvi

import android.os.SystemClock
import com.kinetic.editor.core.model.ClipId
import com.kinetic.editor.core.model.TimelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single-threaded MVI store. Intents are queued into a Channel and reduced on the
 * main dispatcher one at a time — the document is only ever touched from one
 * thread, so the reducer needs no locks and engines observe a consistent stream
 * of (prev, next) commits.
 *
 * The store knows nothing about ExoPlayer, GL, or Compose. Engines subscribe via
 * [CommitListener]; UI reads [timeline]/[selection] and calls [dispatch].
 */
class EditorStore(
    private val scope: CoroutineScope,
    private val listener: CommitListener,
) {
    fun interface CommitListener {
        fun onCommitted(prev: TimelineState, next: TimelineState)
    }

    private val _timeline = MutableStateFlow(TimelineState.empty())
    val timeline: StateFlow<TimelineState> = _timeline.asStateFlow()

    /** Selection is session state, not document state: it is never part of undo history. */
    private val _selection = MutableStateFlow<ClipId?>(null)
    val selection: StateFlow<ClipId?> = _selection.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val intents = Channel<EditorIntent>(Channel.UNLIMITED)
    private val history = UndoStack(capacity = 100)

    init {
        scope.launch(Dispatchers.Main.immediate) {
            for (intent in intents) process(intent)
        }
    }

    fun dispatch(intent: EditorIntent) {
        intents.trySend(intent)
    }

    fun select(id: ClipId?) {
        // Selecting a deleted clip is a no-op guard for late gesture callbacks.
        _selection.value = if (id == null || _timeline.value.findClip(id) != null) id else null
    }

    private fun process(intent: EditorIntent) {
        val prev = _timeline.value
        val next: TimelineState = when (intent) {
            EditorIntent.Undo -> history.undo(prev) ?: return
            EditorIntent.Redo -> history.redo(prev) ?: return
            is EditorIntent.Replace -> {
                // A restored project is a new document, not an edit: undoing back
                // into the pre-restore session would be meaningless.
                history.clear()
                intent.state
            }
            else -> {
                val reduced = reduce(prev, intent)
                if (reduced === prev) return
                history.push(prev, intent.coalesceKey)
                reduced
            }
        }
        commit(prev, next)
    }

    private fun commit(prev: TimelineState, next: TimelineState) {
        val stamped = next.copy(revision = prev.revision + 1)
        _timeline.value = stamped
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        if (_selection.value != null && stamped.findClip(_selection.value!!) == null) {
            _selection.value = null
        }
        listener.onCommitted(prev, stamped)
    }
}

/**
 * Snapshot-based undo. Persistent collections make each entry a handful of
 * pointers, so 100 levels of history cost kilobytes, not megabytes.
 *
 * Coalescing: a burst of intents sharing a coalesceKey (a slider drag emitting
 * 60 SetGrade/s) collapses into one entry — the snapshot taken before the first
 * intent of the burst. A different key, a null key, or a 1.2s pause seals the burst.
 */
private class UndoStack(private val capacity: Int) {
    private val undo = ArrayDeque<TimelineState>()
    private val redo = ArrayDeque<TimelineState>()
    private var lastKey: String? = null
    private var lastPushUptimeMs: Long = 0L

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(beforeIntent: TimelineState, coalesceKey: String?) {
        redo.clear()
        val now = SystemClock.uptimeMillis()
        val coalesce = coalesceKey != null &&
            coalesceKey == lastKey &&
            now - lastPushUptimeMs < 1_200L
        lastKey = coalesceKey
        lastPushUptimeMs = now
        if (coalesce) return
        undo.addLast(beforeIntent)
        if (undo.size > capacity) undo.removeFirst()
    }

    fun clear() {
        undo.clear()
        redo.clear()
        lastKey = null
    }

    fun undo(current: TimelineState): TimelineState? {
        val snapshot = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastKey = null
        return snapshot
    }

    fun redo(current: TimelineState): TimelineState? {
        val snapshot = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastKey = null
        return snapshot
    }
}
