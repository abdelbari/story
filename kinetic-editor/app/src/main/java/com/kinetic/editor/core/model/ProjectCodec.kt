package com.kinetic.editor.core.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.io.File

/**
 * kotlinx.serialization has no built-in support for kotlinx-collections-immutable,
 * so persistent lists round-trip through the plain List serializer. Generic
 * serializer classes are resolved by the plugin from this constructor.
 */
class PersistentListSerializer<T>(
    elementSerializer: KSerializer<T>,
) : KSerializer<PersistentList<T>> {

    private val delegate: KSerializer<List<T>> = ListSerializer(elementSerializer)

    override val descriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: PersistentList<T>) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): PersistentList<T> =
        delegate.deserialize(decoder).toPersistentList()
}

/**
 * The project document as durable JSON.
 *
 * Two things depend on this, and both are correctness features rather than
 * conveniences:
 *  - the export worker reads the document from disk, so a render survives the
 *    editor process being killed (an export outlives the UI by design);
 *  - the editing session is restored after process death, not just after a
 *    configuration change.
 *
 * `ignoreUnknownKeys` + `encodeDefaults=false` keep old files loadable as the
 * schema grows: a field added later simply falls back to its default.
 */
object ProjectCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    fun encode(state: TimelineState): String = json.encodeToString(TimelineState.serializer(), state)

    /**
     * Returns null for absent/corrupt data rather than throwing into a UI path.
     * Also rejects structurally impossible documents: JSON that parses but has no
     * main video track would blow up later at [TimelineState.mainTrack], far from
     * the cause.
     */
    fun decode(text: String): TimelineState? = runCatching {
        json.decodeFromString(TimelineState.serializer(), text)
    }.getOrNull()?.takeIf { state ->
        state.tracks.count { it.type == TrackType.VIDEO_MAIN } == 1
    }

    /**
     * Atomic write: a crash mid-save must not leave a truncated project behind.
     *
     * Returns false instead of throwing. This is called from an autosave
     * collector, and an exception there (a full disk is the realistic one) would
     * cancel the collector and silently end autosaving for the whole session —
     * the one failure mode a save routine must not have.
     */
    fun save(file: File, state: TimelineState): Boolean = runCatching {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encode(state))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    fun load(file: File): TimelineState? =
        if (file.exists()) decode(runCatching { file.readText() }.getOrDefault("")) else null
}
