package app.morpho.converter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.morpho.engine.layout.FidelityReport
import app.morpho.engine.layout.ParagraphKind
import kotlin.math.roundToInt

/**
 * Review Mode (plan §4.1): what the Fidelity Report knows, shown plainly,
 * and fixable on the spot. Every block is listed with where its content
 * actually came from — read exactly, read from PDF tags, reconstructed from
 * glyph positions, or recognized by OCR — so a person can check the parts
 * that were guessed instead of discovering the guesses after sharing the
 * file. A block the reader mislabelled can be corrected here and the file
 * written again, which is the difference between a report and a repair.
 *
 * Colors are fixed rather than taken from the dynamic palette: a
 * confidence signal has to mean the same thing on every device, and it is
 * never the only carrier of meaning — every row also says its source in
 * words.
 */
@Composable
fun ReviewScreen(
    state: ReviewState,
    onReclassify: (index: Int, kind: ParagraphKind) -> Unit,
    // Not the report's excerpt: see [EntryRow].
    textOf: (index: Int) -> String,
    onRetext: (index: Int, text: String) -> Unit,
    onRemove: (index: Int) -> Unit,
    onRestore: (index: Int) -> Unit,
    onJoinUp: (index: Int) -> Unit,
    onSplitLines: (index: Int) -> Unit,
    onUnsplitLines: (index: Int) -> Unit,
    onSaveCorrected: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val report = state.report
    val flagged = report.reviewables
    // No key: the filter is the reader's choice and must survive both a
    // correction (which replaces the report with a recomputed one) and a
    // rotation, hence saveable.
    var flaggedOnly by rememberSaveable { mutableStateOf(flagged.isNotEmpty()) }
    val shown = if (flaggedOnly) flagged else report.entries

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The activity is edge-to-edge, and this screen has no Scaffold
            // to inset it: without this the header sits under the status bar.
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.review_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onClose) { Text(stringResource(R.string.review_close)) }
        }

        Summary(report)

        if (state.fixes > 0) {
            Button(onClick = onSaveCorrected, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.review_save_corrected, state.fixes))
            }
        }

        if (flagged.isNotEmpty()) {
            TextButton(onClick = { flaggedOnly = !flaggedOnly }) {
                Text(
                    stringResource(
                        if (flaggedOnly) R.string.review_filter_all
                        else R.string.review_filter_flagged
                    )
                )
            }
        }

        LazyColumn(
            // weight, not fillMaxSize: the list takes the height left over
            // after the header, instead of the parent's full height.
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.index }) { entry ->
                EntryRow(
                    entry = entry,
                    edited = entry.index in state.edited,
                    dropped = entry.index in state.dropped,
                    restorable = entry.index in state.restorable,
                    canJoinUp = entry.index in state.joinable,
                    canSplit = entry.index in state.splittable,
                    split = entry.index in state.split,
                    textOf = { textOf(entry.index) },
                    onRetext = { text -> onRetext(entry.index, text) },
                    onReclassify = { kind -> onReclassify(entry.index, kind) },
                    onRemove = { onRemove(entry.index) },
                    onRestore = { onRestore(entry.index) },
                    onJoinUp = { onJoinUp(entry.index) },
                    onSplitLines = { onSplitLines(entry.index) },
                    onUnsplitLines = { onUnsplitLines(entry.index) },
                )
            }
        }
    }
}

@Composable
private fun Summary(report: FidelityReport.Report) {
    val percent = (report.overall * 100).roundToInt()
    val flagged = report.reviewables.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.review_overall, percent),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text =
                if (flagged == 0) stringResource(R.string.review_all_exact)
                else stringResource(R.string.review_some_flagged, flagged, report.entries.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BandBar(report)
    }
}

/** One bar, split by how many blocks landed in each confidence band. */
@Composable
private fun BandBar(report: FidelityReport.Report) {
    val bands = FidelityReport.Band.entries.map { band ->
        band to (report.counts[band] ?: 0)
    }
    if (bands.sumOf { it.second } == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        for ((band, count) in bands) {
            if (count == 0) continue
            Box(
                modifier = Modifier
                    .weight(count.toFloat())
                    .fillMaxHeight()
                    .background(bandColor(band)),
            )
        }
    }
}

/**
 * One block of the document, said plainly, and correctable two ways: what
 * it is, and what it says.
 *
 * The words shown are the report's excerpt — eighty code points, a label
 * for a list. What the editor opens on is the block's whole text, asked
 * for at the moment it opens, because an editor seeded from an excerpt
 * saves a paragraph back with its tail cut off and calls that a
 * correction.
 */
// FlowRow so the actions wrap rather than being clipped: three of them on
// a narrow phone is already close, and German and French say all three in
// about twice the letters English does. The opt-in is belt and braces —
// FlowRow is stable in this version of Compose and the marker costs a
// warning at worst.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(
    entry: FidelityReport.Entry,
    edited: Boolean,
    dropped: Boolean,
    restorable: Boolean,
    canJoinUp: Boolean,
    canSplit: Boolean,
    split: Boolean,
    textOf: () -> String,
    onRetext: (String) -> Unit,
    onReclassify: (ParagraphKind) -> Unit,
    onRemove: () -> Unit,
    onRestore: () -> Unit,
    onJoinUp: () -> Unit,
    onSplitLines: () -> Unit,
    onUnsplitLines: () -> Unit,
) {
    // Null is "not editing", so the draft and the state that it exists are
    // one thing and cannot disagree. Saveable because a reader retyping a
    // paragraph off a photograph must not lose it to a turn of the phone.
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    val typing = draft
    // Only text has words to fix. A table's cells and a picture are edited
    // where they can be edited honestly, which is not here.
    val hasWords = entry.kind == FidelityReport.Kind.PARAGRAPH ||
        entry.kind == FidelityReport.Kind.HEADING

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(bandColor(entry.band)),
                )
                Text(
                    text = stringResource(kindLabel(entry.kind)),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.review_confidence, (entry.confidence * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(sourceLabel(entry.source)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (typing == null) {
                Text(
                    text = entry.excerpt.ifEmpty { stringResource(R.string.review_image) },
                    style = MaterialTheme.typography.bodyMedium,
                    // Struck through and dimmed rather than hidden: what was
                    // taken out has to stay readable, or a reader cannot tell
                    // whether they took out the right thing.
                    textDecoration = if (dropped) TextDecoration.LineThrough else null,
                    color =
                        if (dropped) MaterialTheme.colorScheme.onSurfaceVariant
                        else Color.Unspecified,
                )
            } else {
                WordEditor(
                    text = typing,
                    onText = { draft = it },
                    onKeep = {
                        onRetext(typing)
                        draft = null
                    },
                    onLeave = { draft = null },
                )
            }

            if (dropped) {
                // Nothing else is offered on a block that is not in the
                // document: correcting the words of something on its way
                // out is work thrown away, and offering to remove it again
                // says the first removal did not take.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (restorable) R.string.review_removed else R.string.review_joined
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Offered only where it would do something. A join is
                    // given back from the outside in, so one whose words
                    // have since moved further up says where they went
                    // instead of offering a button that does nothing.
                    if (restorable) {
                        TextButton(onClick = onRestore) {
                            Text(stringResource(R.string.review_put_back))
                        }
                    }
                }
            } else {
                if (edited) {
                    Text(
                        text = stringResource(R.string.review_edited),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (typing == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (hasWords) {
                            TextButton(onClick = { draft = textOf() }) {
                                Text(stringResource(R.string.review_words_fix))
                            }
                        }
                        if (canJoinUp) {
                            TextButton(onClick = onJoinUp) {
                                Text(stringResource(R.string.review_join_up))
                            }
                        }
                        // Offered only on a block that holds a line break,
                        // since the lines are the reader's own: they typed
                        // them, or the document arrived with them.
                        if (canSplit) {
                            TextButton(onClick = onSplitLines) {
                                Text(stringResource(R.string.review_split_lines))
                            }
                        }
                        if (split) {
                            TextButton(onClick = onUnsplitLines) {
                                Text(stringResource(R.string.review_rejoin_lines))
                            }
                        }
                        // Offered on a picture and a table as well: a
                        // scanner's edge comes back as a picture of nothing,
                        // and a rule drawn across a page comes back as a
                        // table of one empty cell.
                        TextButton(onClick = onRemove) {
                            Text(stringResource(R.string.review_remove))
                        }
                    }
                }
            }

            // The corrections part company here on purpose. Any words can be
            // wrong, including a Word document's own — that is what editing a
            // document is — so fixing them is offered on every block that has
            // any. Relabelling is offered only where the label was guessed: a
            // heading level a DOCX states outright is not the app's to
            // second-guess.
            val relabel = entry.band != FidelityReport.Band.HIGH && hasWords
            if (relabel && typing == null && !dropped) {
                Text(
                    text = stringResource(R.string.review_correct_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KindButton(R.string.review_as_body, ParagraphKind.BODY, onReclassify)
                    KindButton(R.string.review_as_h1, ParagraphKind.HEADING_1, onReclassify)
                    KindButton(R.string.review_as_h2, ParagraphKind.HEADING_2, onReclassify)
                    KindButton(R.string.review_as_h3, ParagraphKind.HEADING_3, onReclassify)
                }
            }
        }
    }
}

/**
 * Where the words are put right.
 *
 * Capped rather than allowed to grow: a paragraph two pages ran together
 * is thousands of characters, and a field that tall pushes the rest of the
 * document off the screen and cannot be got back to. Capped, it scrolls
 * within itself and the list stays a list.
 */
@Composable
private fun WordEditor(
    text: String,
    onText: (String) -> Unit,
    onKeep: () -> Unit,
    onLeave: () -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 240.dp),
        label = { Text(stringResource(R.string.review_words_label)) },
        textStyle = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onKeep) { Text(stringResource(R.string.review_words_keep)) }
        TextButton(onClick = onLeave) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun KindButton(labelRes: Int, kind: ParagraphKind, onReclassify: (ParagraphKind) -> Unit) {
    TextButton(onClick = { onReclassify(kind) }) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun bandColor(band: FidelityReport.Band): Color {
    val dark = isSystemInDarkTheme()
    return when (band) {
        FidelityReport.Band.HIGH ->
            if (dark) Color(0xFF5CC98B) else Color(0xFF1E8E4F)
        FidelityReport.Band.MEDIUM ->
            if (dark) Color(0xFFE0B23C) else Color(0xFFB07A05)
        FidelityReport.Band.LOW ->
            if (dark) Color(0xFFF08A7C) else Color(0xFFC0392B)
    }
}

private fun kindLabel(kind: FidelityReport.Kind): Int = when (kind) {
    FidelityReport.Kind.HEADING -> R.string.kind_heading
    FidelityReport.Kind.PARAGRAPH -> R.string.kind_paragraph
    FidelityReport.Kind.TABLE -> R.string.kind_table
    FidelityReport.Kind.IMAGE -> R.string.kind_image
}

private fun sourceLabel(source: FidelityReport.Source): Int = when (source) {
    FidelityReport.Source.EXACT -> R.string.source_exact
    FidelityReport.Source.TAGGED -> R.string.source_tagged
    FidelityReport.Source.RECONSTRUCTED -> R.string.source_reconstructed
    FidelityReport.Source.RECOGNIZED -> R.string.source_recognized
}
