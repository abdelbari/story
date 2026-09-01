package app.morpho.converter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morpho.engine.layout.FidelityReport
import kotlin.math.roundToInt

/**
 * Review Mode (plan §4.1): what the Fidelity Report knows, shown plainly.
 * Every block is listed with where its content actually came from — read
 * exactly, read from PDF tags, reconstructed from glyph positions, or
 * recognized by OCR — so a person can check the parts that were guessed
 * instead of discovering the guesses after sharing the file.
 *
 * Colors are fixed rather than taken from the dynamic palette: a
 * confidence signal has to mean the same thing on every device, and it is
 * never the only carrier of meaning — every row also says its source in
 * words.
 */
@Composable
fun ReviewScreen(report: FidelityReport.Report, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    val flagged = report.reviewables
    var flaggedOnly by remember(report) { mutableStateOf(flagged.isNotEmpty()) }
    val shown = if (flaggedOnly) flagged else report.entries

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            items(shown, key = { it.index }) { entry -> EntryRow(entry) }
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

@Composable
private fun EntryRow(entry: FidelityReport.Entry) {
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
            Text(
                text = entry.excerpt.ifEmpty { stringResource(R.string.review_image) },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
